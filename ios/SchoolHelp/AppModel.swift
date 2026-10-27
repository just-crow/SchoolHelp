import SwiftUI
import UIKit
import PhotosUI
import FirebaseCore
import FirebaseMessaging
import UserNotifications
import CryptoKit

@MainActor final class AppModel:ObservableObject {
    static let shared=AppModel()
    let sharing=Sharing()
    let playback=Playback()
    let remote=OpenRouter()
    @Published var settings=AISettings()
    @Published var photos:[URL]=[]
    @Published var selected=0
    @Published var ownSolution:Solution?
    @Published var ownAudio:URL?
    @Published var shared=false
    @Published var screen="home"
    @Published var busy=""
    @Published var error=""
    @Published var modelProgress=""
    @Published var interrupted=false
    private var timer:Task<Void,Never>?
    private var generation:Task<Void,Never>?
    private var sharedID:String?
    private let defaults=UserDefaults.standard
    private init() {
        if let data=sharing.store.get("settings"),let value=try? JSONDecoder().decode(AISettings.self,from:data){settings=value}
        if let data=sharing.store.get("own-solution"),let value=try? JSONDecoder().decode(Solution.self,from:data){ownSolution=value}
        if let data=sharing.store.get("photos"),let names=try? JSONDecoder().decode([String].self,from:data){photos=names.map{sharing.store.root.appendingPathComponent($0)}.filter{FileManager.default.fileExists(atPath:$0.path)}}
        let saved=sharing.store.root.appendingPathComponent("own.m4a")
        if FileManager.default.fileExists(atPath:saved.path){ownAudio=saved}
        interrupted=sharing.store.get("generation-job") != nil
        if defaults.bool(forKey:"shared-mode") {
            shared=true
            if defaults.bool(forKey:"screen-listen") {openShared()}
        } else if defaults.bool(forKey:"own-listening"),let ownAudio,defaults.bool(forKey:"screen-listen") {
            playback.load(ownAudio,position:defaults.double(forKey:"own-position"));screen="listen"
        }
        startPolling()
    }
    func startPolling() {
        timer?.cancel();timer=Task { while !Task.isCancelled {
            if !sharing.following.isEmpty {await sync()}
            try? await Task.sleep(for:.seconds(15))
        }}
    }
    func sync() async {
        do {try await sharing.sync();if shared && screen=="listen" && sharedID==nil {openShared()}}
        catch {sharing.status=error.localizedDescription}
    }
    func saveSettings() {if let data=try? JSONEncoder().encode(settings){sharing.store.put("settings","config",0,data)}}
    func add(_ images:[UIImage]) {
        for image in images.prefix(max(0,10-photos.count)) {
            guard let bytes=image.jpegData(compressionQuality:0.84) else{continue}
            let url=sharing.store.root.appendingPathComponent("photo-\(UUID().uuidString).jpg")
            do {try bytes.write(to:url,options:.atomic);photos.append(url)} catch{self.error=error.localizedDescription}
        }
        persistPhotos()
    }
    func persistPhotos() {if let data=try? JSONEncoder().encode(photos.map(\.lastPathComponent)){sharing.store.put("photos","config",0,data)}}
    func removePhoto() {guard photos.indices.contains(selected) else{return};try? FileManager.default.removeItem(at:photos[selected]);photos.remove(at:selected);selected=max(0,min(selected,photos.count-1));persistPhotos()}
    func movePhoto(_ delta:Int) {let target=selected+delta;guard photos.indices.contains(selected),photos.indices.contains(target) else{return};photos.swapAt(selected,target);selected=target;persistPhotos()}
    func transformPhoto(crop:Bool) {
        guard photos.indices.contains(selected),let image=UIImage(contentsOfFile:photos[selected].path),let cg=image.cgImage else{return}
        let result:UIImage
        if crop {
            let side=min(cg.width,cg.height);guard let cut=cg.cropping(to:CGRect(x:(cg.width-side)/2,y:(cg.height-side)/2,width:side,height:side)) else{return};result=UIImage(cgImage:cut)
        } else {result=UIImage(cgImage:cg,scale:1,orientation:.right)}
        if let bytes=result.jpegData(compressionQuality:0.84){try? bytes.write(to:photos[selected],options:.atomic)}
    }
    func cropPhoto(_ normalized:CGRect) {
        guard photos.indices.contains(selected),let image=UIImage(contentsOfFile:photos[selected].path),let cg=image.cgImage else{return}
        let rect=CGRect(x:normalized.minX*CGFloat(cg.width),y:normalized.minY*CGFloat(cg.height),width:normalized.width*CGFloat(cg.width),height:normalized.height*CGFloat(cg.height)).integral.intersection(CGRect(x:0,y:0,width:cg.width,height:cg.height))
        guard rect.width>10,rect.height>10,let cut=cg.cropping(to:rect),let bytes=UIImage(cgImage:cut).jpegData(compressionQuality:0.84) else{return}
        try? bytes.write(to:photos[selected],options:.atomic)
    }
    func setKey(_ value:String) throws {
        let clean=value.trimmingCharacters(in:.whitespacesAndNewlines)
        guard !clean.unicodeScalars.contains(where:{$0.value<32 || $0.value==127}) else{throw AppError.message("Remove hidden characters from the API key")}
        Keychain.set("openrouter-key",clean)
    }
    var hasKey:Bool { !(Keychain.get("openrouter-key") ?? "").isEmpty }
    func solve() {
        guard !settings.requestedLanguage.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else {error="Enter an answer language before solving.";return}
        guard !photos.isEmpty || !(settings.context ?? "").trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{error="Add a photo or enter context first";return}
        generation?.cancel();busy=photos.isEmpty ? "Working on your text request":"Reading all pages";error=""
        sharing.store.put("generation-job","config",0,Data("solving".utf8));interrupted=true
        generation=Task {
            do {
                let solution:Solution
                if settings.answerProvider=="local" {
                    let model=sharing.store.root.appendingPathComponent("models/gemma-e2b/model.litertlm")
                    guard FileManager.default.fileExists(atPath:model.path) else{throw AppError.message("Download the local vision model in Settings, or choose OpenRouter")}
                    solution=try await LocalAI.solve(images:photos,model:model,settings:settings)
                } else {
                    guard let key=Keychain.get("openrouter-key") else{throw AppError.message("Add your OpenRouter key in Settings")}
                    solution=try await remote.solve(images:photos,key:key,settings:settings)
                }
                ownSolution=solution;sharing.store.put("own-solution","config",0,try JSONEncoder().encode(solution))
                sharing.store.put("generation-job","config",0,Data("speech".utf8))
                try await makeSpeech(solution)
            } catch is CancellationError {error="Processing cancelled"}
            catch {self.error=error.localizedDescription}
            busy=""
            if ownAudio != nil {sharing.store.delete("generation-job");interrupted=false}
        }
    }
    func cancelWork() {generation?.cancel();busy=""}
    func retrySpeech() {guard let solution=ownSolution else{solve();return};generation=Task{busy="Preparing speech";do{try await makeSpeech(solution);sharing.store.delete("generation-job");interrupted=false}catch{self.error=error.localizedDescription};busy=""}}
    private func makeSpeech(_ solution:Solution) async throws {
        let text=solution.speech(language:settings.language,explanations:settings.explanations)
        let target=sharing.store.root.appendingPathComponent("own.m4a")
        if settings.speechProvider=="local" {
            let name=settings.language == "pl" ? "piper-pl":"kokoro-en"
            let imported=sharing.store.root.appendingPathComponent("models/\(name)")
            let bundled=Bundle.main.resourceURL?.appendingPathComponent("Models/\(name)")
            let folder=FileManager.default.fileExists(atPath:imported.path) ? imported : (bundled ?? imported)
            _=try await LocalSpeech.generate(text:text,language:settings.language,modelFolder:folder,output:target)
        } else {
            guard let key=Keychain.get("openrouter-key") else{throw AppError.message("Add your OpenRouter key")}
            _=try await remote.generateSpeech(text:text,key:key,settings:settings,output:target)
        }
        ownAudio=target;playback.load(target,speed:settings.speed);shared=false;screen="listen";defaults.set(true,forKey:"own-listening");defaults.set(true,forKey:"screen-listen")
        if settings.readySound {playback.readyCue()}
        if sharing.enabled {
            let id=UUID().uuidString
            try sharing.enqueue(OutgoingItem(id:id,solution:solution,spokenText:text,speed:settings.speed,language:settings.language,source:id+"-source.m4a"),audio:target)
            Task {await sync()}
        }
    }
    func openShared() {
        shared=true;defaults.set(true,forKey:"shared-mode");defaults.set(true,forKey:"screen-listen");screen="listen"
        if let item=sharing.current() {
            if sharedID != item.id {playback.load(sharing.audio(item.id),position:defaults.double(forKey:"shared-position"),speed:item.speed);sharedID=item.id;if settings.readySound{playback.readyCue()}}
        } else {sharedID=nil;playback.clear()}
    }
    func next() {
        guard shared else{endOwn();return}
        if let sharedID {sharing.consume(sharedID)}
        sharedID=nil;defaults.set(0,forKey:"shared-position");openShared()
    }
    func back() {
        playback.pause();screen="home";defaults.set(false,forKey:"screen-listen")
        savePlaybackPosition()
    }
    func savePlaybackPosition() {defaults.set(playback.position,forKey:shared ? "shared-position":"own-position")}
    func ownWorkspace() {back();shared=false;sharedID=nil;defaults.set(false,forKey:"shared-mode");playback.clear()}
    func endOwn() {
        generation?.cancel();playback.clear();for url in photos {try? FileManager.default.removeItem(at:url)};photos=[];persistPhotos()
        if let ownAudio {try? FileManager.default.removeItem(at:ownAudio)}
        ownAudio=nil;ownSolution=nil;sharing.store.delete("own-solution");sharing.store.delete("generation-job")
        interrupted=false;defaults.set(false,forKey:"own-listening");defaults.set(false,forKey:"screen-listen");screen="home"
    }
    func closeSession() {shared ? next():endOwn()}
    func disconnect() {Task {do {try await sharing.leave();ownWorkspace()} catch{self.error=error.localizedDescription}}}
    func downloadVision() {
        generation=Task{do {
            modelProgress="Downloading vision model"
            let (temp,_)=try await URLSession.shared.download(from:URL(string:LocalAI.modelURL)!)
            let handle=try FileHandle(forReadingFrom:temp);defer{try? handle.close()}
            var hash=SHA256()
            while let chunk=try handle.read(upToCount:1_048_576),!chunk.isEmpty {hash.update(data:chunk)}
            guard hash.finalize().map({String(format:"%02x",$0)}).joined()==LocalAI.modelHash else{throw AppError.message("Model download did not pass verification")}
            let folder=sharing.store.root.appendingPathComponent("models/gemma-e2b")
            try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
            let destination=folder.appendingPathComponent("model.litertlm")
            try? FileManager.default.removeItem(at:destination)
            try FileManager.default.moveItem(at:temp,to:destination)
            modelProgress="Vision model ready"
        }catch{modelProgress=error.localizedDescription}}
    }
    func installVoice(from folder:URL) {
        guard folder.startAccessingSecurityScopedResource() else{error="Cannot access selected voice folder";return}
        defer{folder.stopAccessingSecurityScopedResource()}
        let destination=sharing.store.root.appendingPathComponent("models/\(settings.language == "pl" ? "piper-pl":"kokoro-en")")
        do {try? FileManager.default.removeItem(at:destination);try FileManager.default.copyItem(at:folder,to:destination);modelProgress="Voice installed"}
        catch{self.error=error.localizedDescription}
    }
}
