import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit

struct ContentView:View {
    @ObservedObject var model:AppModel
    @ObservedObject private var sharing:Sharing
    @State private var picked:[PhotosPickerItem]=[]
    @State private var camera=false
    @State private var joinCode=""
    @State private var settings=false
    @State private var key=""
    @State private var visionModels:[RemoteModel]=[]
    @State private var speechModels:[RemoteModel]=[]
    @State private var folderPicker=false
    @State private var crop=false
    init(model:AppModel) {self.model=model;self.sharing=model.sharing}
    var body:some View {
        Group {
            if model.screen=="listen" {ListenView(model:model,sharing:sharing)}
            else if settings {settingsView}
            else {homeView}
        }
        .sheet(isPresented:$camera) {CameraView { image in model.add([image]) }}
        .fileImporter(isPresented:$folderPicker,allowedContentTypes:[.folder]) { result in
            if case .success(let url)=result {model.installVoice(from:url)}
        }
        .sheet(isPresented:$crop) {
            if model.photos.indices.contains(model.selected),let image=UIImage(contentsOfFile:model.photos[model.selected].path) {
                CropView(image:image) { rect in model.cropPhoto(rect);crop=false }
            }
        }
        .alert("SchoolHelp",isPresented:Binding(get:{!model.error.isEmpty},set:{if !$0{model.error=""}})) {Button("OK"){model.error=""}} message:{Text(model.error)}
        .onChange(of:picked){_,items in Task {
            var images:[UIImage]=[]
            for item in items.prefix(max(0,10-model.photos.count)) {if let data=try? await item.loadTransferable(type:Data.self),let image=UIImage(data:data){images.append(image)}}
            model.add(images);picked=[]
        }}
    }
    private var homeView:some View {
        NavigationStack {
            ScrollView {
                VStack(alignment:.leading,spacing:20) {
                    Text("SchoolHelp").font(.largeTitle.bold())
                    Text("Photograph your questions. Listen to the answers.").foregroundStyle(.secondary)
                    TextField("Answer and speech language",text:Binding(get:{model.settings.requestedLanguage},set:{model.settings.outputLanguage=String($0.prefix(60));model.saveSettings()}))
                        .textFieldStyle(.roundedBorder).disabled(!model.busy.isEmpty)
                    Text("All answers and spoken labels use this language. No automatic detection. Solve again to apply changes.").font(.caption).foregroundStyle(.secondary)
                    TextField("Context for the AI (optional)",text:Binding(get:{model.settings.context ?? ""},set:{model.settings.context=String($0.prefix(8000));model.saveSettings()}),axis:.vertical)
                        .lineLimit(3...8).textFieldStyle(.roundedBorder).disabled(!model.busy.isEmpty)
                    Text("Used with photos or by itself when solving. Saved until cleared; not read aloud as a separate note.").font(.caption).foregroundStyle(.secondary)
                    if !(model.settings.context ?? "").isEmpty {Button("Clear context"){model.settings.context="";model.saveSettings()}.disabled(!model.busy.isEmpty)}
                    if model.interrupted {Button("Continue speech from saved answer"){model.retrySpeech()};Button("Retry solving pages"){model.solve()}}
                    if !model.busy.isEmpty {ProgressView(model.busy);Button("Cancel"){model.cancelWork()}}
                    PhotosPicker(selection:$picked,maxSelectionCount:max(1,10-model.photos.count),matching:.images){Label("Add photos",systemImage:"photo.on.rectangle")}
                    Button {camera=true} label:{Label("Take a photo",systemImage:"camera")}
                    if !model.photos.isEmpty {
                        Text("Page \(model.selected+1) of \(model.photos.count)").font(.headline)
                        if model.photos.indices.contains(model.selected),let image=UIImage(contentsOfFile:model.photos[model.selected].path){Image(uiImage:image).resizable().scaledToFit().frame(maxHeight:360)}
                        HStack {Button("Previous"){model.selected=max(0,model.selected-1)}.disabled(model.selected==0);Button("Next"){model.selected=min(model.photos.count-1,model.selected+1)}.disabled(model.selected==model.photos.count-1)}
                        HStack {Button("Rotate"){model.transformPhoto(crop:false)};Button("Crop"){crop=true};Button("Remove"){model.removePhoto()}}
                        HStack {Button("Earlier"){model.movePhoto(-1)};Button("Later"){model.movePhoto(1)}}

                    }
                    Button("Solve and prepare speech"){model.solve()}.buttonStyle(.borderedProminent).disabled(!model.busy.isEmpty || (model.photos.isEmpty && (model.settings.context ?? "").trimmingCharacters(in:.whitespacesAndNewlines).isEmpty))
                    if model.ownAudio != nil {Button("Listen to my answer"){model.shared=false;model.screen="listen";UserDefaults.standard.set(true,forKey:"screen-listen");model.playback.load(model.ownAudio!,position:UserDefaults.standard.double(forKey:"own-position"))}}
                    if model.shared {Button("Open my own workspace"){model.ownWorkspace()}}
                    Divider()
                    Text("Shared listening").font(.title2.bold())
                    if sharing.code.isEmpty {Button("Get my code"){task{try await sharing.profile()}}}
                    else {Text("My code: \(sharing.code)").textSelection(.enabled);Button("Copy code"){UIPasteboard.general.string=sharing.code};Button("Rotate code"){task{try await sharing.rotate()}}}
                    Toggle("Share new answers",isOn:Binding(get:{sharing.enabled},set:{_ in task{try await sharing.toggle()}}))
                    Text("Connected listeners: \(sharing.listeners.count)/10")
                    ForEach(sharing.listeners,id:\.self){id in HStack {Text(String(id.prefix(8)));Button("Remove"){task{try await sharing.remove(id)}}}}
                    if sharing.following.isEmpty {
                        TextField("Someone else's code",text:$joinCode).textInputAutocapitalization(.characters).textFieldStyle(.roundedBorder)
                        Button("Connect"){task{try await sharing.join(joinCode);model.openShared()}}
                    } else {
                        Text("\(sharing.pending) queued, \(sharing.ready) downloaded")
                        Button("Listen to shared answers"){model.openShared()}
                        Button("Disconnect"){model.disconnect()}
                    }
                    Text(sharing.status).font(.footnote).foregroundStyle(.secondary)
                    Button("Sync now"){Task{await model.sync()}}
                }.padding()
            }
            .toolbar {Button("Settings"){settings=true}}
        }
    }
    private var settingsView:some View {
        NavigationStack {
            Form {
                Picker("Offline voice language",selection:$model.settings.language){Text("English").tag("en");Text("Polski").tag("pl")}
                Toggle("Include brief reasoning",isOn:$model.settings.explanations)
                Picker("Answer engine",selection:$model.settings.answerProvider){Text("On this iPhone").tag("local");Text("OpenRouter").tag("openrouter")}
                Picker("Speech engine",selection:$model.settings.speechProvider){Text("Offline voice").tag("local");Text("OpenRouter speech").tag("openrouter")}
                SecureField("OpenRouter API key",text:$key)
                Button("Save and test key"){task{try model.setKey(key);try await model.remote.test(key:key);model.modelProgress="Connection works"}}
                Button("Load vision and speech models"){Task {visionModels=(try? await model.remote.models(speech:false)) ?? [];speechModels=(try? await model.remote.models(speech:true)) ?? []}}
                Picker("Vision model",selection:$model.settings.visionModel){Text("Choose model").tag("");ForEach(visionModels){Text($0.name).tag($0.id)}}
                Picker("Speech model",selection:$model.settings.speechModel){Text("Choose model").tag("");ForEach(speechModels){Text($0.name).tag($0.id)}}
                    .onChange(of:model.settings.speechModel){_,id in model.settings.speechVoice=speechModels.first(where:{$0.id==id})?.voices.first ?? ""}
                let voices=speechModels.first(where:{$0.id==model.settings.speechModel})?.voices ?? []
                if model.settings.speechModel.hasPrefix("fish-audio/") {
                    Text("Fish Audio has no preset voice list. Leave the voice ID empty for the provider default.")
                    TextField("Fish Audio voice ID (optional)",text:$model.settings.speechVoice)
                    Button("Use provider default voice"){model.settings.speechVoice=""}
                } else if voices.isEmpty {TextField("Speech voice",text:$model.settings.speechVoice)}
                else {Picker("Speech voice",selection:$model.settings.speechVoice){Text("Choose voice").tag("");ForEach(voices,id:\.self){Text($0).tag($0)}}}
                HStack {Text("Playback speed \(String(format:"%.2f",model.settings.speed))×");Slider(value:Binding(get:{Double(model.settings.speed)},set:{model.settings.speed=Float($0)}),in:0.25...2,step:0.05)}
                Toggle("Ready sound",isOn:$model.settings.readySound)
                Button("Download offline vision model (2 GB)"){model.downloadVision()}
                Button("Import unpacked offline voice folder"){folderPicker=true}
                Text(model.modelProgress).font(.footnote)
            }.navigationTitle("Settings").toolbar {Button("Done"){model.saveSettings();settings=false}}
        }
    }
    private func task(_ work:@escaping () async throws->Void) {
        Task {do {try await work()}catch{model.error=error.localizedDescription}}
    }
}

struct ListenView:View {
    @ObservedObject var model:AppModel
    @ObservedObject var sharing:Sharing
    @ObservedObject private var playback:Playback
    init(model:AppModel,sharing:Sharing){self.model=model;self.sharing=sharing;self.playback=model.playback}
    private var waiting:Bool{model.shared && sharing.current()==nil}
    var body:some View {
        ZStack {
            Color(red:0.07,green:0.13,blue:0.12).ignoresSafeArea()
            TouchSurface(single:{playback.back()},double:{playback.forward()},triple:{playback.toggle()},restart:{playback.restart()},finish:{model.closeSession()}).ignoresSafeArea()
            VStack(spacing:18) {
                HStack {Spacer();Text("LISTENING MODE").tracking(2)}
                Spacer()
                Image(systemName:playback.playing ? "waveform":"play.fill").font(.system(size:60))
                Text(waiting ? "Waiting for an answer" : playback.playing ? "Just listen." : "Take your time.").font(.largeTitle)
                Text(waiting ? "New answers appear here when ready." : "Triple tap to play or pause")
                if waiting {Text(sharing.status)}
                else {
                    ProgressView(value:playback.duration>0 ? min(1,playback.position/playback.duration):0)
                    HStack {Text(format(playback.position));Spacer();Text(format(playback.duration))}
                }
                Spacer()
                Text(waiting ? "Use Back to return to sharing" : "1 tap: back 5 seconds   2 taps: forward 5 seconds   3 taps: play or pause")
                Text("Hold 1.5 seconds to restart, 3 seconds to finish").font(.footnote)
            }.foregroundStyle(.white).padding(28).allowsHitTesting(false)
            VStack {
                HStack {
                    Button("Back"){model.back()}.accessibilityIdentifier("listenBack")
                    Spacer()
                    if waiting {Button("Check"){Task{await model.sync()}}}
                }.foregroundStyle(.white).padding(28)
                Spacer()
            }
        }
        .onAppear {UIApplication.shared.isIdleTimerDisabled=true}
        .onDisappear {UIApplication.shared.isIdleTimerDisabled=false}
    }
    private func format(_ seconds:Double)->String {let value=seconds.isFinite ? max(0,Int(seconds)):0;return String(format:"%d:%02d",value/60,value%60)}
}

struct TouchSurface:UIViewRepresentable {
    let single:()->Void,double:()->Void,triple:()->Void,restart:()->Void,finish:()->Void
    func makeUIView(context:Context)->TouchView {let view=TouchView();view.handler=context.coordinator;return view}
    func updateUIView(_ view:TouchView,context:Context){context.coordinator.actions=(single,double,triple,restart,finish)}
    func makeCoordinator()->Coordinator {Coordinator(actions:(single,double,triple,restart,finish))}
    final class Coordinator {
        var actions:(()->Void,()->Void,()->Void,()->Void,()->Void)
        init(actions:(()->Void,()->Void,()->Void,()->Void,()->Void)){self.actions=actions}
    }
    final class TouchView:UIView {
        var handler:Coordinator?
        private var count=0
        private var longStarted=false
        private var ended=false
        private var singleWork:DispatchWorkItem?
        private var restartWork:DispatchWorkItem?
        private var finishWork:DispatchWorkItem?
        override func touchesBegan(_ touches:Set<UITouch>,with event:UIEvent?) {
            longStarted=false;ended=false
            let restart=DispatchWorkItem {[weak self] in guard let self else{return};self.longStarted=true;self.handler?.actions.3()}
            let finish=DispatchWorkItem {[weak self] in guard let self else{return};self.ended=true;self.handler?.actions.4()}
            restartWork=restart;finishWork=finish
            DispatchQueue.main.asyncAfter(deadline:.now()+1.5,execute:restart)
            DispatchQueue.main.asyncAfter(deadline:.now()+3,execute:finish)
        }
        override func touchesEnded(_ touches:Set<UITouch>,with event:UIEvent?) {
            restartWork?.cancel();finishWork?.cancel()
            guard !longStarted && !ended else{return}
            count+=1;singleWork?.cancel()
            if count>=3 {count=0;handler?.actions.2();return}
            let work=DispatchWorkItem {[weak self] in guard let self else{return};let taps=self.count;self.count=0;if taps==1{self.handler?.actions.0()}else if taps==2{self.handler?.actions.1()}}
            singleWork=work;DispatchQueue.main.asyncAfter(deadline:.now()+0.42,execute:work)
        }
        override func touchesCancelled(_ touches:Set<UITouch>,with event:UIEvent?){restartWork?.cancel();finishWork?.cancel();singleWork?.cancel();count=0}
    }
}

struct CameraView:UIViewControllerRepresentable {
    let captured:(UIImage)->Void
    @Environment(\.dismiss) var dismiss
    func makeUIViewController(context:Context)->UIImagePickerController{let picker=UIImagePickerController();picker.sourceType=.camera;picker.delegate=context.coordinator;return picker}
    func updateUIViewController(_ picker:UIImagePickerController,context:Context){}
    func makeCoordinator()->Coordinator{Coordinator(parent:self)}
    final class Coordinator:NSObject,UIImagePickerControllerDelegate,UINavigationControllerDelegate {
        let parent:CameraView;init(parent:CameraView){self.parent=parent}
        func imagePickerController(_ picker:UIImagePickerController,didFinishPickingMediaWithInfo info:[UIImagePickerController.InfoKey:Any]){if let image=info[.originalImage] as? UIImage{parent.captured(image)};parent.dismiss()}
        func imagePickerControllerDidCancel(_ picker:UIImagePickerController){parent.dismiss()}
    }
}

struct CropView:View {
    let image:UIImage
    let save:(CGRect)->Void
    @Environment(\.dismiss) private var dismiss
    @State private var left:Double=0.05
    @State private var top:Double=0.05
    @State private var right:Double=0.95
    @State private var bottom:Double=0.95
    var body:some View {
        NavigationStack {
            VStack {
                GeometryReader { geometry in
                    let scale=min(geometry.size.width/image.size.width,geometry.size.height/image.size.height)
                    let size=CGSize(width:image.size.width*scale,height:image.size.height*scale)
                    Image(uiImage:image).resizable().frame(width:size.width,height:size.height)
                        .overlay {Rectangle().stroke(.yellow,lineWidth:3)
                            .frame(width:size.width*(right-left),height:size.height*(bottom-top))
                            .offset(x:size.width*((left+right)/2-0.5),y:size.height*((top+bottom)/2-0.5))}
                        .frame(maxWidth:.infinity,maxHeight:.infinity)
                }
                Text("Adjust the four edges").font(.footnote)
                edge("Left",value:$left,range:0...min(right-0.1,0.9))
                edge("Top",value:$top,range:0...min(bottom-0.1,0.9))
                edge("Right",value:$right,range:max(left+0.1,0.1)...1)
                edge("Bottom",value:$bottom,range:max(top+0.1,0.1)...1)
            }.padding()
            .navigationTitle("Crop page")
            .toolbar {Button("Save"){save(CGRect(x:left,y:top,width:right-left,height:bottom-top));dismiss()}}
        }
    }
    private func edge(_ title:String,value:Binding<Double>,range:ClosedRange<Double>)->some View {HStack{Text(title).frame(width:55);Slider(value:value,in:range)}}
}
