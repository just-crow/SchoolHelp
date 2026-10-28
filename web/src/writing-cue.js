// Time is scaled before export, so the cue repeats every five real playback seconds.
export function cueExpression(speed){
 const phase=`mod(t/${speed}-0.15,5)`;
 return `if(lt(${phase},0.09)*gte(t/${speed},0.15),0.035*sin(2*PI*660*t)*pow(sin(PI*${phase}/0.09),2),0)`;
}
export function writingFilters(clips,speed,cues){
 const filters=[];
 clips.forEach((clip,index)=>{
  const seconds=clip.pauseSeconds*speed;
  const normalize=`[${index}:a]aresample=24000,aformat=sample_fmts=fltp:channel_layouts=mono`;
  if(cues&&seconds>0){
   filters.push(`${normalize}[s${index}]`);
   filters.push(`aevalsrc='${cueExpression(speed)}':s=24000:d=${seconds.toFixed(6)},aformat=sample_fmts=fltp:channel_layouts=mono[p${index}]`);
   filters.push(`[s${index}][p${index}]concat=n=2:v=0:a=1[a${index}]`);
  }else filters.push(`${normalize},apad=pad_dur=${seconds.toFixed(6)}[a${index}]`);
 });
 filters.push(clips.map((_,i)=>`[a${i}]`).join('')+`concat=n=${clips.length}:v=0:a=1[out]`);
 return filters.join(';');
}
