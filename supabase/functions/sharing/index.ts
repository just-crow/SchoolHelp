import { createClient } from "npm:@supabase/supabase-js@2.117.3";

const url = Deno.env.get("SUPABASE_URL")!;
const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const reply = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json" } });
const uuid = (value: unknown): value is string => typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const check = (error: unknown) => { if(error) throw new Error((error as {message?:string}).message ?? "Server request failed"); };
function code() { const alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; return Array.from(crypto.getRandomValues(new Uint8Array(10)), n=>alphabet[n%32]).join(""); }
async function profile(owner: string) {
  let {data,error}=await admin.from("sharing_profiles").select("*").eq("owner",owner).maybeSingle(); check(error);
  if(!data) { const result=await admin.from("sharing_profiles").insert({owner,code:code()}).select().single();
    if(result.error?.code==="23505") return profile(owner); check(result.error); data=result.data; }
  return data!;
}
async function ownerView(owner: string) {
  const p=await profile(owner);
  const {data,error}=await admin.from("sharing_memberships").select("listener,joined_at").eq("owner",owner); check(error);
  return {code:p.code,enabled:p.enabled,listeners:data};
}
async function notify(owner: string) {
  const {data:members}=await admin.from("sharing_memberships").select("listener").eq("owner",owner);
  if(!members?.length) return;
  const {data:tokens}=await admin.from("sharing_tokens").select("token,platform").in("listener",members.map(m=>m.listener));
  if(!tokens?.length || !Deno.env.get("FCM_SERVICE_ACCOUNT")) return;
  const account=JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT")!);
  const enc=(v:unknown)=>btoa(JSON.stringify(v)).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_");
  const now=Math.floor(Date.now()/1000);
  const input=enc({alg:"RS256",typ:"JWT"})+"."+enc({iss:account.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3600});
  const pem=account.private_key.replace(/-----[^-]+-----/g,"").replace(/\s/g,"");
  const key=await crypto.subtle.importKey("pkcs8",Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
  const sig=new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,new TextEncoder().encode(input)));
  const jwt=input+"."+btoa(String.fromCharCode(...sig)).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_");
  const auth=await fetch("https://oauth2.googleapis.com/token",{method:"POST",body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:jwt})});
  if(!auth.ok) throw new Error("Push authorization failed");
  const {access_token}=await auth.json();
  await Promise.all(tokens.map(async ({token,platform})=>{
    const delivery=platform==="ios"
      ? {apns:{headers:{"apns-push-type":"alert","apns-priority":"10"},payload:{aps:{alert:{title:"SchoolHelp",body:"A shared answer is ready"},sound:"ready.wav","content-available":1}}}}
      : {android:{priority:"high",ttl:"86400s"}};
    const response=await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,{method:"POST",headers:{Authorization:`Bearer ${access_token}`,"Content-Type":"application/json"},body:JSON.stringify({message:{token,data:{type:"sharing-sync"},...delivery}})});
    if(!response.ok) { const result=await response.json(); if(result.error?.details?.some((d:{errorCode?:string})=>d.errorCode==="UNREGISTERED")) await admin.from("sharing_tokens").delete().eq("token",token); }
  }));
}
Deno.serve(async req=>{
  if(req.method==="OPTIONS") return new Response(null,{status:204,headers:cors});
  try {
    if(req.method!=="POST") return reply({error:"POST required"},405);
    const text=await req.text(); if(text.length>250000) return reply({error:"Request too large"},413);
    const body=JSON.parse(text);
    if(body.action==="cleanup") {
      const secret=Deno.env.get("CLEANUP_SECRET");
      const supplied=req.headers.get("x-cleanup-secret");
      let authorized=!!secret && supplied===secret;
      if(!authorized && supplied) {
        const result=await admin.rpc("sharing_cleanup_authorized",{p_token:supplied}); check(result.error);
        authorized=result.data===true;
      }
      if(!authorized) return reply({error:"Unauthorized"},401);
      const {data,error}=await admin.from("sharing_items").select("id,object_path").lt("expires_at",new Date().toISOString()); check(error);
      for(const item of data??[]) { const removed=await admin.storage.from("shared-audio").remove([item.object_path]); check(removed.error); const deleted=await admin.from("sharing_items").delete().eq("id",item.id); check(deleted.error); }
      const profiles=await admin.from("sharing_profiles").select("owner"); check(profiles.error);
      for(const {owner} of profiles.data??[]) {
        for(const prefix of [`${owner}/staging`,owner]) {
          const files=await admin.storage.from("shared-audio").list(prefix,{limit:1000}); check(files.error);
          for(const file of files.data??[]) {
            if(!file.id || !file.created_at || Date.parse(file.created_at)>Date.now()-3*60*60*1000) continue;
            const path=`${prefix}/${file.name}`;
            if(prefix===owner) {
              const used=await admin.from("sharing_items").select("id").eq("object_path",path).maybeSingle(); check(used.error);
              if(used.data) continue;
            }
            const removed=await admin.storage.from("shared-audio").remove([path]); check(removed.error);
          }
        }
      }
      return reply({removed:data?.length??0});
    }
    const token=req.headers.get("Authorization")?.replace(/^Bearer /,"")??"";
    const {data:auth,error:authError}=await admin.auth.getUser(token);
    if(authError || !auth.user) return reply({error:"Authentication required"},401);
    const owner=auth.user.id;
    switch(body.action) {
      case "profile": return reply(await ownerView(owner));
      case "toggle": case "rotate": case "remove": {
        await profile(owner);
        if(body.action==="remove") { if(!uuid(body.member)) throw new Error("Invalid listener"); const r=await admin.from("sharing_memberships").delete().eq("owner",owner).eq("listener",body.member); check(r.error); }
        else { const r=await admin.from("sharing_profiles").update(body.action==="rotate"?{code:code()}:{enabled:body.enabled===true}).eq("owner",owner); check(r.error); }
        return reply(await ownerView(owner));
      }
      case "join": {
        const allowed=await admin.rpc("sharing_allow_join",{p_listener:owner}); check(allowed.error);
        if(!allowed.data) return reply({error:"Too many code attempts. Try again in an hour."},429);
        if(typeof body.code!=="string" || !/^[A-Z2-9]{10}$/.test(body.code)) throw new Error("Enter the ten-character code");
        const result=await admin.rpc("sharing_join",{p_listener:owner,p_code:body.code}); check(result.error);
        return reply({owner:result.data});
      }
      case "leave": { const result=await admin.from("sharing_memberships").delete().eq("listener",owner); check(result.error); return reply({ok:true}); }
      case "token": {
        if(typeof body.token!=="string" || body.token.length>4096) throw new Error("Invalid push token");
        const platform=body.platform??"android";
        if(platform!=="android" && platform!=="ios") throw new Error("Invalid push platform");
        const r=await admin.from("sharing_tokens").upsert({listener:owner,token:body.token,platform}); check(r.error); return reply({ok:true});
      }
      case "prepare": {
        if(!uuid(body.id)) throw new Error("Invalid item ID");
        const p=await profile(owner); if(!p.enabled) throw new Error("Sharing disabled; enable it to publish queued answers");
        const prior=await admin.from("sharing_items").select("id,owner").eq("id",body.id).maybeSingle(); check(prior.error);
        if(prior.data) { if(prior.data.owner!==owner) throw new Error("Item ID unavailable"); return reply({published:true}); }
        const used=await admin.rpc("sharing_total_bytes"); check(used.error);
        if(Number(used.data)>800000000) throw new Error("Shared storage is full. Retry after older items expire.");
        const path=`${owner}/staging/${body.id}-${crypto.randomUUID()}.m4a`;
        const upload=await admin.storage.from("shared-audio").createSignedUploadUrl(path,{upsert:true}); check(upload.error);
        return reply({url:upload.data!.signedUrl,path});
      }
      case "publish": {
        if(!uuid(body.id) || !body.solution?.problems?.length || body.solution.problems.length>50 || typeof body.spokenText!=="string" || !["en","pl"].includes(body.language) || !Number.isFinite(body.speed) || body.speed<=0 || body.speed>4) throw new Error("Invalid transcription");
        const path=`${owner}/${body.id}.m4a`;
        const existing=await admin.from("sharing_items").select("owner,sequence").eq("id",body.id).maybeSingle(); check(existing.error);
        if(existing.data) { if(existing.data.owner!==owner) throw new Error("Item ID unavailable"); return reply({sequence:existing.data.sequence}); }
        if(typeof body.uploadPath!=="string" || !body.uploadPath.startsWith(`${owner}/staging/${body.id}-`) || !/^[-a-zA-Z0-9/]+\.m4a$/.test(body.uploadPath)) throw new Error("Invalid upload path");
        const download=await admin.storage.from("shared-audio").download(body.uploadPath); check(download.error);
        const bytes=new Uint8Array(await download.data!.arrayBuffer());
        if(bytes.length<1 || bytes.length>25000000) throw new Error("Audio size invalid");
        const hash=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",bytes)),n=>n.toString(16).padStart(2,"0")).join("");
        if(hash!==body.checksum) throw new Error("Audio checksum mismatch");
        const finalAudio=await admin.storage.from("shared-audio").upload(path,bytes,{contentType:"audio/mp4",upsert:false});
        if(finalAudio.error) {
          const saved=await admin.storage.from("shared-audio").download(path); check(saved.error);
          const savedHash=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",await saved.data!.arrayBuffer())),n=>n.toString(16).padStart(2,"0")).join("");
          if(savedHash!==hash) throw new Error("Published audio conflict");
        }
        const result=await admin.rpc("sharing_publish",{p_owner:owner,p_id:body.id,p_solution:body.solution,p_spoken:body.spokenText,p_language:body.language,p_speed:body.speed,p_path:path,p_checksum:hash,p_bytes:bytes.length}); check(result.error);
        await admin.storage.from("shared-audio").remove([body.uploadPath]);
        // Publication is durable even when push fails; clients reconcile on reconnect.
        try { await notify(owner); } catch { console.error("Push delivery failed; durable queue remains available"); }
        return reply({sequence:result.data});
      }
      case "sync": {
        if(!uuid(body.owner)) throw new Error("Invalid owner");
        const membership=await admin.from("sharing_memberships").select("start_sequence").eq("owner",body.owner).eq("listener",owner).maybeSingle(); check(membership.error);
        if(!membership.data) return reply({error:"Connection was removed. Disconnect to join another code."},403);
        const result=await admin.from("sharing_items").select("*").eq("owner",body.owner).gte("sequence",membership.data.start_sequence).gt("expires_at",new Date().toISOString()).order("sequence"); check(result.error);
        const items=await Promise.all((result.data??[]).map(async item=>{const signed=await admin.storage.from("shared-audio").createSignedUrl(item.object_path,300); check(signed.error); return {...item,url:signed.data!.signedUrl};}));
        return reply({items});
      }
      default: return reply({error:"Unknown action"},400);
    }
  } catch(error) { return reply({error:error instanceof Error?error.message:"Sharing failed"},400); }
});
