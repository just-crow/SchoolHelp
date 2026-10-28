import test from 'node:test';
import assert from 'node:assert/strict';
import {writingFilters,cueExpression} from '../src/writing-cue.js';
test('cue source uses real-time tick spacing and does not extend writing duration',()=>{
 const clips=[{pauseSeconds:6},{pauseSeconds:0}];
 const graph=writingFilters(clips,.5,true);
 assert.ok(graph.includes('d=3.000000'));
 assert.ok(graph.includes('mod(t/0.5-0.15,5)'));
 assert.ok(graph.includes('0.035*sin'));
 assert.ok(graph.includes('[s0][p0]concat'));
 assert.ok(!graph.includes('[p1]'));
 const quiet=writingFilters(clips,.5,false);
 assert.ok(quiet.includes('apad=pad_dur=3.000000'));
 assert.ok(!quiet.includes('aevalsrc'));
});
