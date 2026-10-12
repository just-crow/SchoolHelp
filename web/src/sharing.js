import {createClient} from '@supabase/supabase-js';
import config from './backend.json';
import {get,put,del,update} from './store.js';
import {checksum,orderedQueue} from './core.js';
const client=createClient(config.SUPABASE_URL,config.SUPABASE_ANON_KEY);
export class Sharing {
 constructor(changed){this.changed=changed;this.code='';this.enabled=false;this.following='';this.listeners=[];this.items=[];this.status='';this.syncing=false;this.outgoing=0}
 async init(){Object.assign(this,await get('sharing')||{});this.items=await get('incoming')||[];this.outgoing=(await get('outbox')||[]).length}
 async save(){await put('sharing',{code:this.code,enabled:this.enabled,following:this.following,listeners:this.listeners});this.changed()}
 async request(action,values={}){
  let {data:{session}}=await client.auth.getSession();
  if(!session){const result=await client.auth.signInAnonymously();if(result.error)throw result.error;session=result.data.session}
  const response=await fetch(config.SUPABASE_URL+'/functions/v1/sharing',{method:'POST',headers:{apikey:config.SUPABASE_ANON_KEY,Authorization:`Bearer ${session.access_token}`,'Content-Type':'application/json'},body:JSON.stringify({action,...values})});
  const data=await response.json();if(!response.ok)throw Error(data.error||'Sharing request failed.');return data;
 }
 async profile(){const data=await this.request('profile');Object.assign(this,{code:data.code,enabled:data.enabled,listeners:data.listeners||[]});await this.save()}
 async action(action,values={}){const data=await this.request(action,values);Object.assign(this,{code:data.code,enabled:data.enabled,listeners:data.listeners||[]});await this.save()}
 async join(code){if(this.following)throw Error('Disconnect first.');if(await get('pending-leave')){await this.request('leave');await del('pending-leave')}const data=await this.request('join',{code:code.toUpperCase().replace(/[^A-Z0-9]/g,'')});this.following=data.owner;await this.save();await this.sync()}
 async leave(){try{await this.request('leave')}catch{await put('pending-leave',true)}this.following='';this.items=[];await put('incoming',[]);await put('consumed',[]);await del('shared-position');await this.save()}
 async enqueue(solution,spokenText,audio,settings){await update('outbox',list=>[...(list||[]),{id:crypto.randomUUID(),solution,spokenText,audio,language:settings.language,speed:settings.speed}]);this.outgoing=(await get('outbox')).length;this.changed()}
 async consume(id){await update('consumed',list=>[...new Set([...(list||[]),id])]);this.items=this.items.filter(i=>i.id!==id);await put('incoming',this.items);await del('shared-position');this.changed()}
 current(){return this.items[0]?.audio?this.items[0]:null}
 async sync(){
  if(this.syncing)return;this.syncing=true;
  try{
   if(await get('pending-leave')){await this.request('leave');await del('pending-leave')}
   await this.profile();let outgoingError;
   for(const item of await get('outbox')||[]){try{
    if(item.audio.size>25000000)throw Error('Shared recordings must be smaller than 25 MB.');
    const prep=await this.request('prepare',{id:item.id});
    if(!prep.published){const upload=await fetch(prep.url,{method:'PUT',headers:{'Content-Type':'audio/mp4'},body:item.audio});if(!upload.ok)throw Error('Recording upload failed.');await this.request('publish',{id:item.id,solution:item.solution,spokenText:item.spokenText,language:item.language,speed:item.speed,uploadPath:prep.path,checksum:await checksum(item.audio)})}
    await update('outbox',list=>(list||[]).filter(i=>i.id!==item.id));
   }catch(error){outgoingError=error;break}}
   if(this.following){
    const data=await this.request('sync',{owner:this.following}),consumed=await get('consumed')||[];
    const saved=new Map(this.items.map(i=>[i.id,i]));
    const merged=data.items.map(i=>({...i,audio:saved.get(i.id)?.audio}));
    for(const i of this.items)if(i.audio&&!merged.some(j=>j.id===i.id))merged.push(i);
    this.items=orderedQueue(merged,consumed);
    for(const item of this.items.slice(0,3)){if(item.audio)continue;const response=await fetch(item.url);if(!response.ok)throw Error('Download failed. Sync again to refresh the link.');const blob=await response.blob();if(blob.size>25000000||await checksum(blob)!==item.checksum)throw Error('Recording integrity check failed.');item.audio=blob}
    await put('incoming',this.items);
   }
   this.outgoing=(await get('outbox')||[]).length;this.status=outgoingError?outgoingError.message:this.items.length?`${this.items.length} answers queued`:'Waiting for the next answer';
  }catch(error){this.status=error.message}finally{this.syncing=false;this.changed()}
 }
}
