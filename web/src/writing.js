export function writingInstruction(s){return s.writingPauses?`Writing pauses are enabled at ${s.writingWpm||30} words per minute. Return speechSegments for every problem: [{"text":"spoken instructions and answer","writingText":"only what must be copied","pauseSeconds":null}]. Normally use one segment per short question. For essays, normally use one complete paragraph per segment, copying that full paragraph into writingText. Preserve every essay paragraph in order without shortening it or adding repeated Write instructions. User context may override grouping. Together the segment texts must cover spokenText exactly once, without page/question labels. Split into rows or steps when requested by context. The app calculates silence from writingText; leave it empty when nothing needs writing. Follow context timing/grouping requests: set pauseSeconds only for an explicit real-second override (zero means no pause); otherwise null. Maximum 600 seconds per pause, 100 segments total. All text must be plain spoken words in the selected output language. Never speak pause announcements. Preserve these fields when rewriting.`:''}
export function writingSeconds(text,wpm,override){
 if(Number.isFinite(override))return Math.max(0,Math.min(600,override));
 const words=(text.match(/[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu)||[]).length;
 return Math.min(600,Math.ceil(words*60/Math.max(1,Math.min(300,wpm))));
}
export function writingPlan(solution,s){
 const wpm=Number(s.writingWpm??30);if(!Number.isFinite(wpm)||wpm<1||wpm>300)throw Error('Enter a writing speed from 1 to 300 words per minute.');
 const parts=solution.problems.flatMap(p=>{
  const segments=p.speechSegments?.length?p.speechSegments:[{text:p.spokenText||p.answer+(s.explanations?' '+p.explanation:''),writingText:p.unreadable?'':p.answer}];
  const label=p.spokenLabel?.trim().replace(/[.\s]+$/,'')||p.number;
  return segments.map((segment,index)=>{if(!segment.text?.trim())throw Error('An empty speech segment was returned. Solve again.');return {text:(index===0?label+'. ':'')+segment.text,pauseSeconds:writingSeconds(segment.writingText||'',wpm,segment.pauseSeconds)}});
 });
 if(!parts.length||parts.length>100)throw Error('Too many speech parts. Ask for fewer pauses and solve again.');
 if(parts.reduce((n,p)=>n+p.pauseSeconds,0)>7200)throw Error('Writing pauses exceed two hours. Increase writing speed or request shorter pauses.');
 return parts;
}
