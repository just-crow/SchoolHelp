import {prompt,rewrite,parseSolution,needsRewrite,speechText} from './core.js';
const base='https://openrouter.ai/api/v1/';
export const contextNote=s=>s.context?.trim()?`Additional context from the user (use for solving, do not read this note aloud):\n${s.context.trim().slice(0,8000)}`:'';
export async function remote(path,key,body,signal){
 const timeout=AbortSignal.timeout(240000);
 const response=await fetch(base+path,{method:body?'POST':'GET',headers:{...(key?{Authorization:`Bearer ${key}`}:{ }),'X-Title':'SchoolHelp',...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:signal?AbortSignal.any([signal,timeout]):timeout});
 if(!response.ok){let data;try{data=await response.json()}catch{}const detail=String(data?.error?.message||`OpenRouter request failed (${response.status}).`).replace(/sk-[a-zA-Z0-9_-]+/g,'[redacted]').slice(0,300);throw Error(response.status===402?'OpenRouter credits are required.':detail)}return response;
}
export async function catalog(){const data=await(await remote('models')).json();return data.data||[]}
export async function speechCatalog(){const data=await(await remote('models?output_modalities=speech')).json();return data.data||[]}
export async function dataURL(blob){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(reader.error);reader.readAsDataURL(blob)})}
export async function solveRemote(photos,s,key,signal){
 if(!photos.length&&!s.context?.trim())throw Error('Add a photo or enter context first.');
 if(!key||!s.visionModel)throw Error('Open Settings and save your API key and vision model.');
 const content=[{type:'text',text:'Read all pages together, in order. Join questions continued across pages and answer each once.'}];
 if(contextNote(s))content.push({type:'text',text:contextNote(s)});
 for(const [index,photo] of photos.entries()){content.push({type:'text',text:`Page ${index+1} of ${photos.length}`},{type:'image_url',image_url:{url:await dataURL(photo.blob)}})}
 const messages=[{role:'system',content:prompt(s)},{role:'user',content}];
 const complete=async()=>{const data=await(await remote('chat/completions',key,{model:s.visionModel,messages,temperature:0.2},signal)).json();const result=data.choices?.[0]?.message?.content;if(typeof result!=='string')throw Error('The model returned no answer.');return result};
 const first=await complete();let solution=parseSolution(first);
 if(needsRewrite(solution)){messages.push({role:'assistant',content:first},{role:'user',content:rewrite});solution=parseSolution(await complete())}
 if(needsRewrite(solution))throw Error('The model kept returning visual notation. Choose another vision model.');return solution;
}
export async function speechRemote(text,s,key,signal){
 if(!key||!s.speechModel||(!s.speechVoice&&!s.speechModel.startsWith("fish-audio/")))throw Error('Choose an OpenRouter speech model and voice in Settings.');
 const pcm=(s.speechModel.startsWith('google/')||s.speechModel.startsWith('heygen/'));
 const response=await remote('audio/speech',key,{model:s.speechModel,input:text,...(s.speechVoice?.trim()?{voice:s.speechVoice.trim()}:{}),response_format:pcm?'pcm':'mp3'},signal);
 const blob=await response.blob();if(!blob.size||!blob.type.startsWith('audio/'))throw Error('OpenRouter returned no supported speech audio.');
 if(/audio\/(?:pcm|l16)/i.test(blob.type)){const rate=Number(blob.type.match(/rate=(\d+)/)?.[1]||24000);if(rate<8000||rate>192000||blob.size%2)throw Error('OpenRouter returned incomplete PCM audio. Retry speech.');return pcmWave(new Int16Array(await blob.arrayBuffer()),rate)}return blob;
}
export function pcmWave(samples,rate){const bytes=new ArrayBuffer(44+samples.length*2),view=new DataView(bytes);let o=0;const text=s=>{for(const c of s)view.setUint8(o++,c.charCodeAt(0))};const word=(v,n)=>{n===4?view.setUint32(o,v,true):view.setUint16(o,v,true);o+=n};text('RIFF');word(bytes.byteLength-8,4);text('WAVEfmt ');word(16,4);word(1,2);word(1,2);word(rate,4);word(rate*2,4);word(2,2);word(16,2);text('data');word(samples.length*2,4);for(const n of samples)word(n,2);return new Blob([bytes],{type:'audio/wav'})}
let encoder;
async function encoderReady(){
 const {FFmpeg}=await import('@ffmpeg/ffmpeg');
 if(!encoder){encoder=new FFmpeg();await encoder.load({coreURL:new URL('./ffmpeg/ffmpeg-core.js',document.baseURI).href,wasmURL:new URL('./ffmpeg/ffmpeg-core.wasm',document.baseURI).href}).catch(error=>{encoder=null;throw error})}
 return encoder;
}
export async function m4a(blob){
 if(blob.type.includes('mp4')||blob.type.includes('m4a'))return blob;
 await encoderReady();
 const input='input.'+(blob.type.includes('wav')?'wav':blob.type.includes('ogg')?'ogg':'mp3');
 try{await encoder.writeFile(input,new Uint8Array(await blob.arrayBuffer()));const status=await encoder.exec(['-i',input,'-vn','-c:a','aac','-b:a','64k','-movflags','+faststart','output.m4a']);if(status!==0)throw Error('AAC recording conversion failed.');const bytes=await encoder.readFile('output.m4a');return new Blob([bytes],{type:'audio/mp4'})}finally{await encoder.deleteFile(input).catch(()=>{});await encoder.deleteFile('output.m4a').catch(()=>{})}
}
let worker,sequence=0;const requests=new Map();
export function cancelLocal(){worker?.terminate();worker=null;for(const item of requests.values())item.reject(new DOMException('Cancelled','AbortError'));requests.clear()}
export function local(type,values,progress){
 if(!worker){worker=new Worker(new URL('./local-worker.js',import.meta.url),{type:'module'});worker.onmessage=({data})=>{const item=requests.get(data.id);if(!item)return;if(data.progress)item.progress(data.progress);else{requests.delete(data.id);data.error?item.reject(Error(data.error)):item.resolve(data.result)}};worker.onerror=event=>{for(const item of requests.values())item.reject(Error(event.message||'Local model failed.'));requests.clear();worker?.terminate();worker=null}}
 const id=++sequence;return new Promise((resolve,reject)=>{requests.set(id,{resolve,reject,progress});worker.postMessage({id,type,...values})});
}
export async function solveLocal(photos,s,progress){const text=await local('solve',{images:await Promise.all(photos.map(p=>dataURL(p.blob))),prompt:[prompt(s),contextNote(s)].filter(Boolean).join('\n\n')},progress);let solution;try{solution=parseSolution(text)}catch{throw Error('The small local vision model could not structure these questions. Try a closer photo or explicitly select an OpenRouter vision model.')}if(needsRewrite(solution))throw Error('Local model returned visual notation. Retry or explicitly select OpenRouter.');return solution}
export async function speechLocal(solution,s,progress){return speechLocalText(speechText(solution,s),s,progress)}
export async function speechLocalText(text,s,progress){const result=await local('speech',{text,language:s.language,voice:s.localVoice},progress);if(result.blob)return result.blob;const pcm=Int16Array.from(result.audio,v=>Math.max(-32768,Math.min(32767,Math.round(v*32767))));return pcmWave(pcm,result.rate)}

export async function combineWritingAudio(clips,speed,signal){
 const active=await encoderReady();
 const cancel=()=>{active.terminate();if(encoder===active)encoder=null};
 signal?.addEventListener('abort',cancel,{once:true});
 const files=[];
 try{
  for(const [index,clip] of clips.entries()){
   signal?.throwIfAborted();const name=`writing-${index}.audio`;files.push(name);
   await active.writeFile(name,new Uint8Array(await clip.blob.arrayBuffer()));
  }
  const args=files.flatMap(name=>['-i',name]);
  const filters=clips.map((clip,index)=>`[${index}:a]aresample=24000,aformat=sample_fmts=fltp:channel_layouts=mono,apad=pad_dur=${(clip.pauseSeconds*speed).toFixed(6)}[a${index}]`);
  filters.push(clips.map((_,i)=>`[a${i}]`).join('')+`concat=n=${clips.length}:v=0:a=1[out]`);
  const status=await active.exec([...args,'-filter_complex',filters.join(';'),'-map','[out]','-c:a','aac','-b:a','64k','-movflags','+faststart','writing.m4a']);
  signal?.throwIfAborted();if(status!==0)throw Error('Could not assemble writing pauses. Retry speech.');
  return new Blob([await active.readFile('writing.m4a')],{type:'audio/mp4'});
 }finally{signal?.removeEventListener('abort',cancel);for(const name of [...files,'writing.m4a'])await active.deleteFile(name).catch(()=>{})}
}
