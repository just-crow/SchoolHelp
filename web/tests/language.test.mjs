import test from 'node:test';
import assert from 'node:assert/strict';
import { makePrompt } from '../src/prompt.js';

test('typed output language controls all speech instead of detecting the photo language', () => {
  for (const outputLanguage of ['French','Spanish','Japanese','Brazilian Portuguese']) {
    const prompt=makePrompt({language:'en',outputLanguage,explanations:true});
    assert.ok(prompt.includes(JSON.stringify(outputLanguage)));
    assert.ok(prompt.includes('Do not detect or choose an output language from the photos'));
    assert.ok(prompt.includes('every answer, explanation, spokenText and spokenLabel'));
    assert.ok(!prompt.includes('Detect the language separately'));
  }
  assert.throws(()=>makePrompt({outputLanguage:'   '}),/Enter an answer language/);
});

import {parseSolution,speechText} from '../src/core.js';
test('localized announcements stay with answers regardless of app language, including after saving',()=>{
 const solution=parseSolution(JSON.stringify({problems:[{number:'1',page:1,answer:'Komórka.',spokenText:'Komórka jest podstawową jednostką życia.',spokenLabel:'Strona pierwsza. Zadanie pierwsze.'}]}));
 const restored=parseSolution(JSON.stringify(solution));
 assert.equal(speechText(restored,{language:'en'}),'Strona pierwsza. Zadanie pierwsze. Komórka jest podstawową jednostką życia.');
 const spanish=parseSolution(JSON.stringify({problems:[{number:'2',answer:'Una célula.',spokenText:'Una célula.',spokenLabel:'Pregunta dos.'}]}));
 assert.equal(speechText(spanish,{language:'pl'}),'Pregunta dos. Una célula.');
});
