import {mkdir,copyFile} from 'node:fs/promises';
await mkdir('public/ffmpeg',{recursive:true});
for(const name of ['ffmpeg-core.js','ffmpeg-core.wasm']) await copyFile(`node_modules/@ffmpeg/core/dist/esm/${name}`,`public/ffmpeg/${name}`);
await mkdir('public/piper',{recursive:true});
for(const name of ['ort-wasm-simd-threaded.wasm','ort-wasm-simd-threaded.mjs']) await copyFile(`node_modules/onnxruntime-web/dist/${name}`,`public/piper/${name}`);
for(const name of ['piper_phonemize.data','piper_phonemize.wasm']) await copyFile(`node_modules/@diffusionstudio/piper-wasm/build/${name}`,`public/piper/${name}`);
