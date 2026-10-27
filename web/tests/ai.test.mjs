import {test} from 'node:test';import assert from 'node:assert/strict';
import {solveRemote,speechRemote,remote} from '../src/ai.js';import {defaults} from '../src/core.js';
const answer=spokenText=>({problems:[{number:'1',page:1,question:'Continued on page two',answer:'one half',explanation:'',spokenText,unreadable:false}]});
class Reader{readAsDataURL(blob){blob.arrayBuffer().then(bytes=>{this.result=`data:${blob.type};base64,${Buffer.from(bytes).toString('base64')}`;this.onload()})}}
test('all ordered photos reach one request; notation is rewritten before speech',async()=>{
 const original=globalThis.fetch,reader=globalThis.FileReader,calls=[];globalThis.FileReader=Reader;
 globalThis.fetch=async(url,options)=>{calls.push(JSON.parse(options.body));return Response.json({choices:[{message:{content:JSON.stringify(answer(calls.length===1?'1/2':'Write one half. Put one above and two below the fraction bar.'))}}]})};
 try{const photos=[{blob:new Blob(['page1'],{type:'image/jpeg'})},{blob:new Blob(['page2'],{type:'image/jpeg'})}];const result=await solveRemote(photos,{...defaults,visionModel:'fixture/vision'},'sk-or-v1-fixture');assert.equal(calls.length,2);const content=calls[0].messages[1].content;assert.deepEqual(content.filter(p=>p.type==='text').slice(1).map(p=>p.text),['Page 1 of 2','Page 2 of 2']);assert.equal(content.filter(p=>p.type==='image_url').length,2);assert.equal(result.problems[0].page,1);assert.equal(calls[1].messages.at(-1).role,'user')}finally{globalThis.fetch=original;globalThis.FileReader=reader}
});
test('speech uses selected model and voice without a provider fallback',async()=>{const original=globalThis.fetch;let body;globalThis.fetch=async(url,options)=>{body=JSON.parse(options.body);return new Response(new Uint8Array([1,2]),{headers:{'Content-Type':'audio/mpeg'}})};try{const blob=await speechRemote('Write one half.',{speechModel:'fixture/speech',speechVoice:'fixture-voice'},'sk-or-v1-fixture');assert.equal(blob.type,'audio/mpeg');assert.equal(body.model,'fixture/speech');assert.equal(body.voice,'fixture-voice');assert.equal(body.input,'Write one half.')}finally{globalThis.fetch=original}});
test('Google PCM is wrapped only when the actual response is PCM',async()=>{const original=globalThis.fetch;try{globalThis.fetch=async()=>new Response(new Uint8Array([1,2,3,4]),{headers:{'Content-Type':'audio/pcm;rate=24000'}});const s={speechModel:'google/fixture',speechVoice:'fixture'};assert.equal((await speechRemote('hello',s,'fixture')).size,48);globalThis.fetch=async()=>new Response(new Uint8Array([1,2,3,4]),{headers:{'Content-Type':'audio/wav'}});assert.equal((await speechRemote('hello',s,'fixture')).size,4)}finally{globalThis.fetch=original}});
test('payment failures are explicit and requests are not repeated',async()=>{const original=globalThis.fetch;let count=0;globalThis.fetch=async()=>{count++;return Response.json({error:{message:'credits'}},{status:402})};try{await assert.rejects(remote('chat/completions','fixture',{}),/credits/);assert.equal(count,1)}finally{globalThis.fetch=original}});

test('Fish Audio omits voice for provider default and preserves custom reference IDs',async()=>{
 const original=globalThis.fetch,calls=[];
 globalThis.fetch=async(url,options)=>{calls.push(JSON.parse(options.body));return new Response(new Uint8Array([1,2]),{headers:{'Content-Type':'audio/mpeg'}})};
 try {
  await speechRemote('Hello',{speechModel:'fish-audio/s2.1-pro',speechVoice:''},'fixture');
  assert.equal(calls[0].response_format,'mp3');assert.ok(!('voice' in calls[0]));
  await speechRemote('Hello',{speechModel:'fish-audio/s2.1-pro',speechVoice:'reference-id'},'fixture');
  assert.equal(calls[1].voice,'reference-id');assert.equal(calls.length,2);
  await assert.rejects(speechRemote('Hello',{speechModel:'openai/tts-1',speechVoice:''},'fixture'),/voice/);
  assert.equal(calls.length,2);
 }finally{globalThis.fetch=original}
});

test('optional user context reaches the vision request without becoming speech input',async()=>{
 const original=globalThis.fetch,reader=globalThis.FileReader,calls=[];globalThis.FileReader=Reader;
 globalThis.fetch=async(url,options)=>{calls.push({url,body:JSON.parse(options.body)});return url.endsWith('audio/speech')?new Response(new Uint8Array([1,2]),{headers:{'Content-Type':'audio/mpeg'}}):Response.json({choices:[{message:{content:JSON.stringify(answer('Write one half.'))}}]})};
 try {
  const settings={...defaults,visionModel:'fixture/vision',context:'Use the definitions from chapter four.',speechModel:'fixture/tts',speechVoice:'fixture'};
  const solution=await solveRemote([{blob:new Blob(['paper'],{type:'image/jpeg'})}],settings,'fixture');
  const content=calls[0].body.messages[1].content;
  assert.ok(content.some(p=>p.text?.includes(settings.context)));
  assert.ok(!calls[0].body.messages[0].content.includes(settings.context));
  await speechRemote(solution.problems[0].spokenText,settings,'fixture');
  assert.equal(calls[1].body.input,'Write one half.');
  assert.ok(!JSON.stringify(calls[1].body).includes(settings.context));
 }finally{globalThis.fetch=original;globalThis.FileReader=reader}
});

test('context-only requests need no images and empty requests make no paid call',async()=>{
 const original=globalThis.fetch,calls=[];
 globalThis.fetch=async(url,options)=>{calls.push(JSON.parse(options.body));return Response.json({choices:[{message:{content:JSON.stringify(answer('Cells are the basic unit of life.'))}}]})};
 try {
  const s={...defaults,visionModel:'fixture/vision',context:'Explain what a cell is.'};
  const result=await solveRemote([],s,'fixture');
  assert.equal(calls.length,1);assert.ok(result.problems.length);
  const content=calls[0].messages[1].content;
  assert.equal(content.filter(p=>p.type==='image_url').length,0);
  assert.ok(content.some(p=>p.text?.includes(s.context)));
  assert.ok(calls[0].messages[0].content.includes('complete text-only task'));
  await assert.rejects(solveRemote([],{...s,context:'  '},'fixture'),/photo or enter context/);
  assert.equal(calls.length,1);
 }finally{globalThis.fetch=original}
});
