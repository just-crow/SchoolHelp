export class Playback{
 constructor(changed,save){this.audio=document.querySelector('#speech');this.changed=changed;this.save=save;this.url=null;this.wake=null;
  for(const event of ['timeupdate','loadedmetadata','play','pause','ended','error'])this.audio.addEventListener(event,()=>{changed();save()});
  if('mediaSession'in navigator){for(const [action,handler] of Object.entries({play:()=>this.play(),pause:()=>this.pause(),previoustrack:()=>this.seek(-5),nexttrack:()=>this.seek(5),seekbackward:()=>this.seek(-5),seekforward:()=>this.seek(5),seekto:e=>{this.audio.currentTime=e.seekTime}})){try{navigator.mediaSession.setActionHandler(action,handler)}catch{}}}
 }
 load(blob,position=0,speed=1){this.clear();this.url=URL.createObjectURL(blob);this.audio.src=this.url;this.audio.playbackRate=speed;this.audio.addEventListener('loadedmetadata',()=>{this.audio.currentTime=Math.min(position,this.duration||position)},{once:true});this.audio.load();if('mediaSession'in navigator)navigator.mediaSession.metadata=new MediaMetadata({title:'SchoolHelp answer',artist:'SchoolHelp'});this.changed()}
 get duration(){return Number.isFinite(this.audio.duration)?this.audio.duration:0}
 get position(){return this.audio.currentTime||0}
 get playing(){return !this.audio.paused&&!this.audio.ended}
 async play(){try{if(this.audio.ended)this.audio.currentTime=0;await this.audio.play()}catch{this.changed('Tap Play to allow audio in this browser.')}}
 pause(){this.audio.pause()}
 toggle(){this.playing?this.pause():this.play()}
 seek(delta){this.audio.currentTime=Math.max(0,Math.min(this.duration,this.position+delta))}
 restart(){this.audio.currentTime=0;this.play()}
 clear(){this.audio.pause();this.audio.removeAttribute('src');this.audio.load();if(this.url)URL.revokeObjectURL(this.url);this.url=null}
 async awake(active){if(!active){await this.wake?.release().catch(()=>{});this.wake=null;return}if(document.visibilityState==='visible'&&navigator.wakeLock){try{this.wake=await navigator.wakeLock.request('screen')}catch{}}}
}
