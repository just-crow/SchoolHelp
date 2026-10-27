import test from 'node:test';
import assert from 'node:assert/strict';
import { makePrompt } from '../src/prompt.js';

test('question language overrides app language for every question', () => {
  for (const language of ['en', 'pl']) {
    const prompt = makePrompt({ language, explanations: true });
    assert.ok(prompt.includes('same language as that question'));
    assert.ok(prompt.includes('mixed-language pages'));
    assert.ok(prompt.includes('overrides the app'));
    assert.ok(prompt.includes('Include spokenLabel for every problem'));
    assert.ok(!prompt.includes('Answer in English.'));
    assert.ok(!prompt.includes('Answer in Polish.'));
  }
});

import {parseSolution,speechText} from '../src/core.js';
test('localized announcements stay with answers regardless of app language, including after saving',()=>{
 const solution=parseSolution(JSON.stringify({problems:[{number:'1',page:1,answer:'Komórka.',spokenText:'Komórka jest podstawową jednostką życia.',spokenLabel:'Strona pierwsza. Zadanie pierwsze.'}]}));
 const restored=parseSolution(JSON.stringify(solution));
 assert.equal(speechText(restored,{language:'en'}),'Strona pierwsza. Zadanie pierwsze. Komórka jest podstawową jednostką życia.');
 const spanish=parseSolution(JSON.stringify({problems:[{number:'2',answer:'Una célula.',spokenText:'Una célula.',spokenLabel:'Pregunta dos.'}]}));
 assert.equal(speechText(spanish,{language:'pl'}),'Pregunta dos. Una célula.');
});
