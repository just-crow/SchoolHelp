import test from 'node:test';
import assert from 'node:assert/strict';
import {writingSeconds,writingPlan} from '../src/writing.js';
import {parseSolution,prompt,defaults} from '../src/core.js';
test('writing time counts copied words rather than spoken instructions',()=>{
 assert.equal(writingSeconds('one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen',30,null),30);
 assert.equal(writingSeconds('',30,null),0);
 assert.equal(writingSeconds('words',30,12.5),12.5);
 assert.equal(writingSeconds('words',30,0),0);
 assert.equal(writingSeconds('words',30,900),600);
});
test('context-defined speech segments preserve grouping, overrides and labels after saving',()=>{
 const solution=parseSolution(JSON.stringify({problems:[{number:'1',answer:'A B',spokenText:'Write A then B.',spokenLabel:'Question one.',speechSegments:[{text:'Write A.',writingText:'A',pauseSeconds:null},{text:'Write B.',writingText:'B',pauseSeconds:9}]}]}));
 const plan=writingPlan(parseSolution(JSON.stringify(solution)),{writingWpm:30});
 assert.deepEqual(plan,[{text:'Question one. Write A.',pauseSeconds:2},{text:'Write B.',pauseSeconds:9}]);
 assert.ok(prompt({...defaults,writingPauses:true,writingWpm:40}).includes('40 words per minute'));
 assert.ok(!prompt(defaults).includes('Writing pauses are enabled'));
});
test('older answers receive one calculated pause each; invalid speeds fail before speech requests',()=>{
 const solution=parseSolution('{"problems":[{"number":"1","answer":"one two three","spokenText":"Write one two three."}]}');
 assert.equal(writingPlan(solution,{writingWpm:30})[0].pauseSeconds,6);
 assert.throws(()=>writingPlan(solution,{writingWpm:-1}),/writing speed/);
});
