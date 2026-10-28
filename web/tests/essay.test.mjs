import test from 'node:test';
import assert from 'node:assert/strict';
import {prompt,rewrite,parseSolution,speechText,defaults,needsRewrite} from '../src/core.js';
import {writingPlan} from '../src/writing.js';
const paragraphs=['A clear thesis introduces the argument.','A developed paragraph explains the evidence and its relevance.','The conclusion draws these arguments together.'];
test('essay rules override brevity for both reasoning settings and keep short questions concise',()=>{
 for(const explanations of [false,true]){
  const text=prompt({...defaults,explanations,writingPauses:true});
  assert.ok(text.includes('Distinguish the task genre'));
  assert.ok(text.includes('finished piece itself, not an outline'));
  assert.ok(text.includes('Preserve them even when optional explanations are disabled'));
  assert.ok(text.includes('word count and paragraph count'));
  assert.ok(text.includes('Short factual questions, calculations and multiple-choice tasks need concise'));
  assert.ok(text.includes('one complete paragraph per segment'));
  assert.ok(!text.includes('Give concise test answers. Leave explanation empty'));
 }
 assert.ok(rewrite.includes('never summarize or outline an essay'));
});
test('full essay paragraphs survive parsing, speech, saved state and writing-pause segmentation',()=>{
 const essay=paragraphs.join('\n\n');
 const solution=parseSolution(JSON.stringify({problems:[{number:'1',answer:essay,explanation:'',spokenText:essay,spokenLabel:'Essay.',speechSegments:paragraphs.map(text=>({text,writingText:text,pauseSeconds:null}))}]}));
 const restored=parseSolution(JSON.stringify(solution));
 assert.equal(restored.problems[0].answer,essay);
 assert.ok(speechText(restored,defaults).includes(essay));
 assert.equal(needsRewrite(restored),false);
 const plan=writingPlan(restored,{writingWpm:30});
 assert.equal(plan.length,3);assert.equal(plan[2].text,paragraphs[2]);
 assert.ok(plan.every(p=>p.pauseSeconds>0));
});
