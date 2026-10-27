import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareVisionInputs} from '../src/vision-input.js';
test('text-only inference bypasses the image processor; photos retain the multimodal path',()=>{
 const calls=[];
 const processor=(text,images)=>{calls.push('images');return {text,images}};
 processor.tokenizer=text=>{calls.push('text');return {input_ids:text}};
 assert.deepEqual(prepareVisionInputs(processor,'text task',[]),{input_ids:'text task'});
 const images=[{image:'fixture'}];
 assert.deepEqual(prepareVisionInputs(processor,'photo task',images),{text:'photo task',images});
 assert.deepEqual(calls,['text','images']);
});
