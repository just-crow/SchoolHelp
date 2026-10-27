import test from 'node:test';
import assert from 'node:assert/strict';
import { makePrompt } from '../src/prompt.js';

test('question language overrides app language for every question', () => {
  for (const language of ['en', 'pl']) {
    const prompt = makePrompt({ language, explanations: true });
    assert.ok(prompt.includes('same language as that question'));
    assert.ok(prompt.includes('mixed-language pages'));
    assert.ok(prompt.includes('overrides the app'));
    assert.ok(!prompt.includes('Answer in English.'));
    assert.ok(!prompt.includes('Answer in Polish.'));
  }
});
