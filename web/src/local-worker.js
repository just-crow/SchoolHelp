let vision,processor,kokoro,polish;
const progress=(id,message)=>self.postMessage({id,progress:typeof message==='string'?message:`${message.file||'Model'} ${Math.round(message.progress||0)}%`});
self.onmessage=async({data})=>{
 const {id,type}=data;
 try{
  let result;
  if(type==='solve'||type==='vision-install'){
   if(!self.navigator.gpu)throw Error('Local photo solving requires WebGPU. Use a compatible browser or explicitly choose OpenRouter.');
   const {AutoProcessor,AutoModelForVision2Seq,RawImage}=await import('@huggingface/transformers');
   const modelId='HuggingFaceTB/SmolVLM-256M-Instruct',revision='7e3e67edbbed1bf9888184d9df282b700a323964';
   processor??=await AutoProcessor.from_pretrained(modelId,{revision,progress_callback:p=>progress(id,p)});
   vision??=await AutoModelForVision2Seq.from_pretrained(modelId,{revision,device:'webgpu',dtype:'q4',progress_callback:p=>progress(id,p)});
   if(type==='solve'){
    const images=await Promise.all(data.images.map(url=>RawImage.read(url)));
    const content=[...images.flatMap((_,i)=>[{type:'text',text:`Page ${i+1} of ${images.length}`},{type:'image'}]),{type:'text',text:data.prompt+'\nReturn the JSON object now. Do not describe the photograph.'}];
    const messages=[{role:'user',content}];
    const complete=async()=>{const text=processor.apply_chat_template(messages,{add_generation_prompt:true});const inputs=await processor(text,images);const outputs=await vision.generate({...inputs,max_new_tokens:2048,do_sample:false});return processor.batch_decode(outputs.slice(null,[inputs.input_ids.dims.at(-1),null]),{skip_special_tokens:true})[0]};
    result=await complete();
    if(!result.includes('"problems"')){progress(id,'Structuring the local answer…');messages.push({role:'assistant',content:result},{role:'user',content:'Convert your answer into JSON only: {"problems":[{"number":"1","page":1,"question":"question text","answer":"answer","explanation":"","spokenText":"plain spoken words and placement instructions","unreadable":false}]}. Use spoken words, not mathematical notation.'});result=await complete()}
   }else result=true;
  }else if(type==='speech'||type==='speech-install'){
   if(data.language==='pl'){
    const {TtsSession}=await import('@mintplex-labs/piper-tts-web');
    const assetBase=new URL('../piper/',import.meta.url).href;
    polish??=await TtsSession.create({voiceId:'pl_PL-gosia-medium',wasmPaths:{onnxWasm:assetBase,piperData:assetBase+'piper_phonemize.data',piperWasm:assetBase+'piper_phonemize.wasm'},progress:p=>progress(id,`Polish voice ${Math.round(100*p.loaded/(p.total||1))}%`)});
    if(type==='speech')result={blob:await polish.predict(data.text)};else{await polish.predict('Gotowe.');result=true}
   }else{
    const {KokoroTTS}=await import('kokoro-js');
    kokoro??=await KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX',{dtype:'q8',device:'wasm',progress_callback:p=>progress(id,p)});
    if(type==='speech'){
     const chunks=data.text.match(/.{1,280}(?:\s|$)|\S+/gs)||[data.text],parts=[];
     for(const [index,chunk]of chunks.entries()){progress(id,`Speaking section ${index+1} of ${chunks.length}…`);parts.push((await kokoro.generate(chunk.trim(),{voice:data.voice||'af_heart'})).audio)}
     const audio=new Float32Array(parts.reduce((n,a)=>n+a.length,0));let offset=0;for(const part of parts){audio.set(part,offset);offset+=part.length}result={audio,rate:24000};
    }else{await kokoro.generate('Ready.',{voice:data.voice||'af_heart'});result=true}
   }
  }else throw Error('Unknown local model operation.');
  self.postMessage({id,result});
 }catch(error){self.postMessage({id,error:error.message||'Local model failed. No paid provider was contacted.'})}
};
