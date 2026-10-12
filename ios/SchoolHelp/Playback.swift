import AVFoundation
import MediaPlayer
import UIKit

@MainActor final class Playback: ObservableObject {
    @Published var duration:Double=0
    @Published var position:Double=0
    @Published var playing=false
    @Published var ready=false
    private var player:AVPlayer?
    private var cue:AVAudioPlayer?
    private var observer:Any?
    private var current:URL?
    private var preferredRate:Float=1
    var onNext:(()->Void)?
    init() {
        let command=MPRemoteCommandCenter.shared()
        command.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.play() };return .success }
        command.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() };return .success }
        command.togglePlayPauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.toggle() };return .success }
        command.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.forward() };return .success }
        command.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.back() };return .success }
        command.skipForwardCommand.preferredIntervals=[5]
        command.skipBackwardCommand.preferredIntervals=[5]
        command.skipForwardCommand.addTarget { [weak self] _ in Task { @MainActor in self?.forward() };return .success }
        command.skipBackwardCommand.addTarget { [weak self] _ in Task { @MainActor in self?.back() };return .success }
        try? AVAudioSession.sharedInstance().setCategory(.playback,mode:.default,options:[.allowBluetoothA2DP])
        try? AVAudioSession.sharedInstance().setActive(true)
        NotificationCenter.default.addObserver(forName:AVAudioSession.interruptionNotification,object:nil,queue:.main) { [weak self] _ in Task { @MainActor in self?.pause() } }
    }
    func load(_ url:URL,position:Double=0,speed:Float=1) {
        clear();current=url;preferredRate=speed
        let item=AVPlayerItem(url:url);player=AVPlayer(playerItem:item);player?.automaticallyWaitsToMinimizeStalling=true
        Task { [weak self] in
            let loadedDuration=try? await item.asset.load(.duration)
            if let length=loadedDuration?.seconds,length.isFinite,length>0,self?.current==url {
                self?.duration=length;self?.ready=true;self?.publishNowPlaying()
            }
        }
        player?.seek(to:CMTime(seconds:position,preferredTimescale:600));player?.rate=0
        observer=player?.addPeriodicTimeObserver(forInterval:CMTime(seconds:0.25,preferredTimescale:600),queue:.main) { [weak self] _ in
            Task { @MainActor in
                guard let self else{return}
                self.position=self.player?.currentTime().seconds ?? 0
                let length=self.player?.currentItem?.duration.seconds ?? 0
                if length.isFinite && length>0 {self.duration=length;self.ready=true}
                self.playing=(self.player?.rate ?? 0)>0
                self.publishNowPlaying()
            }
        }
        publishNowPlaying()
    }
    func play() {player?.play();player?.rate=preferredRate;playing=true;publishNowPlaying()}
    func pause() {player?.pause();playing=false;publishNowPlaying()}
    func toggle() {playing ? pause():play()}
    func seek(_ seconds:Double) {player?.seek(to:CMTime(seconds:max(0,min(duration,seconds)),preferredTimescale:600))}
    func back() {seek(position-5)}
    func forward() {seek(position+5)}
    func restart() {seek(0);play()}
    func clear() {
        if let observer,let player {player.removeTimeObserver(observer)}
        observer=nil;player?.pause();player=nil;current=nil;position=0;duration=0;ready=false;playing=false
        MPNowPlayingInfoCenter.default().nowPlayingInfo=nil
    }
    private func publishNowPlaying() {
        guard current != nil else{return}
        MPNowPlayingInfoCenter.default().nowPlayingInfo=[MPMediaItemPropertyTitle:"SchoolHelp answer",MPMediaItemPropertyPlaybackDuration:duration,MPNowPlayingInfoPropertyElapsedPlaybackTime:position,MPNowPlayingInfoPropertyPlaybackRate:playing ? 1:0]
    }
    func readyCue() {
        guard let sound=Bundle.main.url(forResource:"ready",withExtension:"wav") else{return}
        cue=try? AVAudioPlayer(contentsOf:sound);cue?.play()
    }
}
