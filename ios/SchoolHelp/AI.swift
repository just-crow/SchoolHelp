import Foundation
import UIKit
import AVFoundation
import LiteRTLM
import SherpaOnnx

struct RemoteModel: Identifiable {let id:String;let name:String;let voices:[String]}

struct AISettings: Codable {
    var language="en"
    var outputLanguage:String? = nil
    var requestedLanguage:String {outputLanguage ?? (language == "pl" ? "Polish":"English")}
    var explanations=false
    var answerProvider="local"
    var speechProvider="local"
    var visionModel=""
    var speechModel=""
    var speechVoice=""
    var speed:Float=1
    var readySound=true
}

final class OpenRouter {
    private let base="https://openrouter.ai/api/v1/"
    private func call(_ path:String,key:String?=nil,body:[String:Any]?=nil) async throws ->(Data,HTTPURLResponse) {
        var request=URLRequest(url:URL(string:base+path)!)
        request.timeoutInterval=240
        if let key {request.setValue("Bearer \(key)",forHTTPHeaderField:"Authorization")}
        request.setValue("SchoolHelp",forHTTPHeaderField:"X-Title")
        if let body {request.httpMethod="POST";request.setValue("application/json",forHTTPHeaderField:"Content-Type");request.httpBody=try JSONSerialization.data(withJSONObject:body)}
        let (data,response)=try await URLSession.shared.data(for:request)
        guard let http=response as? HTTPURLResponse else{throw AppError.message("No server response")}
        guard (200...299).contains(http.statusCode) else{
            if http.statusCode==402 {throw AppError.message("OpenRouter credits are required")}
            if http.statusCode==401 || http.statusCode==403 {throw AppError.message("OpenRouter rejected the API key")}
            let detail=((try? JSONSerialization.jsonObject(with:data)) as? [String:Any])?["error"] as? [String:Any]
            throw AppError.message((detail?["message"] as? String)?.prefix(240).description ?? "OpenRouter request failed (\(http.statusCode))")
        }
        return(data,http)
    }
    func test(key:String) async throws {_ = try await call("key",key:key)}
    func models(speech:Bool) async throws->[RemoteModel] {
        let (data,_)=try await call(speech ? "models?output_modalities=speech":"models")
        let items=((try JSONSerialization.jsonObject(with:data)) as? [String:Any])?["data"] as? [[String:Any]] ?? []
        return items.compactMap { item in
            let arch=item["architecture"] as? [String:Any] ?? [:]
            let modalities=arch[speech ? "output_modalities":"input_modalities"] as? [String] ?? []
            guard modalities.contains(speech ? "speech":"image"),let id=item["id"] as? String else{return nil}
            return RemoteModel(id:id,name:item["name"] as? String ?? id,voices:item["supported_voices"] as? [String] ?? [])
        }.sorted{$0.name<$1.name}
    }
    func solve(images:[URL],key:String,settings:AISettings) async throws->Solution {
        guard !settings.visionModel.isEmpty else{throw AppError.message("Choose a vision model")}
        var content:[[String:Any]]=[["type":"text","text":"Read all pages in order. Join questions continued on later pages and answer each once."]]
        for (index,url) in images.enumerated() {
            let data=try Data(contentsOf:url)
            content.append(["type":"text","text":"Page \(index+1) of \(images.count)"])
            content.append(["type":"image_url","image_url":["url":"data:image/jpeg;base64,\(data.base64EncodedString())"]])
        }
        var messages:[[String:Any]]=[["role":"system","content":ListeningText.prompt(language:settings.requestedLanguage,explanations:settings.explanations)], ["role":"user","content":content]]
        func complete(_ messages:[[String:Any]]) async throws->String {
            let (data,_)=try await call("chat/completions",key:key,body:["model":settings.visionModel,"messages":messages,"temperature":0.2])
            let obj=try JSONSerialization.jsonObject(with:data) as? [String:Any] ?? [:]
            let choices=obj["choices"] as? [[String:Any]] ?? []
            guard let text=(choices.first?["message"] as? [String:Any])?["content"] as? String else{throw AppError.message("The model returned no answer")}
            return text
        }
        let first=try await complete(messages)
        var solution=try ListeningText.parse(first)
        if ListeningText.needsRewrite(solution) {
            messages.append(["role":"assistant","content":first]);messages.append(["role":"user","content":ListeningText.rewrite])
            solution=try ListeningText.parse(try await complete(messages))
        }
        guard !ListeningText.needsRewrite(solution) else{throw AppError.message("The selected model kept returning visual notation. Choose another model.")}
        return solution
    }
    func generateSpeech(text:String,key:String,settings:AISettings,output:URL) async throws->URL {
        guard !settings.speechModel.isEmpty && (!settings.speechVoice.isEmpty || settings.speechModel.hasPrefix("fish-audio/")) else{throw AppError.message("Choose a speech model and voice")}
        let pcm=settings.speechModel.hasPrefix("google/") || settings.speechModel.hasPrefix("heygen/")
        var body:[String:Any]=["model":settings.speechModel,"input":text,"response_format":pcm ? "pcm":"mp3"]
        if !settings.speechVoice.isEmpty {body["voice"]=settings.speechVoice}
        let (data,response)=try await call("audio/speech",key:key,body:body)
        guard !data.isEmpty else{throw AppError.message("OpenRouter returned no audio")}
        let type=response.value(forHTTPHeaderField:"Content-Type") ?? ""
        guard type.hasPrefix("audio/") else{throw AppError.message("OpenRouter returned an unsupported audio format")}
        let raw=output.deletingPathExtension().appendingPathExtension(pcm ? "wav":"mp3")
        if pcm {
            let rate=Int(type.components(separatedBy:"rate=").last ?? "") ?? 24000
            try WAV.write(samples:data,rate:rate,to:raw)
        } else {try data.write(to:raw,options:.atomic)}
        defer {try? FileManager.default.removeItem(at:raw)}
        try await AudioEncoding.toM4A(raw,output)
        return output
    }
}

enum WAV {
    static func write(samples:Data,rate:Int,to url:URL) throws {
        var data=Data()
        func word(_ n:Int,_ bytes:Int) {for i in 0..<bytes {data.append(UInt8((n>>(i*8))&255))}}
        data.append(Data("RIFF".utf8));word(36+samples.count,4);data.append(Data("WAVEfmt ".utf8))
        word(16,4);word(1,2);word(1,2);word(rate,4);word(rate*2,4);word(2,2);word(16,2)
        data.append(Data("data".utf8));word(samples.count,4);data.append(samples)
        try data.write(to:url,options:.atomic)
    }
}

enum AudioEncoding {
    static func toM4A(_ input:URL,_ output:URL) async throws {
        try? FileManager.default.removeItem(at:output)
        let session=AVAssetExportSession(asset:AVURLAsset(url:input),presetName:AVAssetExportPresetAppleM4A)
        guard let session else{throw AppError.message("This recording cannot be shared as M4A")}
        session.outputURL=output;session.outputFileType=.m4a
        await withCheckedContinuation { continuation in session.exportAsynchronously { continuation.resume() } }
        guard session.status == .completed else{throw session.error ?? AppError.message("Audio conversion failed")}
    }
}

enum LocalAI {
    static let modelURL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it-gpu.litertlm"
    static let modelHash="a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"
    static func solve(images:[URL],model:URL,settings:AISettings) async throws->Solution {
        let config=try EngineConfig(modelPath:model.path,backend:.gpu,visionBackend:.gpu,maxNumTokens:32768)
        let engine=Engine(engineConfig:config)
        try await engine.initialize()
        let conversation=try await engine.createConversation()
        var contents:[Content]=[.text(ListeningText.prompt(language:settings.requestedLanguage,explanations:settings.explanations))]
        for (index,url) in images.enumerated(){contents.append(.text("Page \(index+1) of \(images.count)"));contents.append(.imageFile(url.path))}
        let first=try await conversation.sendMessage(Message(contents:contents),extraContext:["enable_thinking":false]).toString
        var solution=try ListeningText.parse(first)
        if ListeningText.needsRewrite(solution) {
            solution=try ListeningText.parse(try await conversation.sendMessage(Message(ListeningText.rewrite),extraContext:["enable_thinking":false]).toString)
        }
        guard !ListeningText.needsRewrite(solution) else{throw AppError.message("The local model kept returning visual notation")}
        return solution
    }
}

enum LocalSpeech {
    static func generate(text:String,language:String,modelFolder:URL,output:URL) async throws->URL {
        let files=(try FileManager.default.enumerator(at:modelFolder,includingPropertiesForKeys:nil)?.allObjects as? [URL] ?? [])
        guard let onnx=files.first(where:{$0.pathExtension=="onnx"}),let tokens=files.first(where:{$0.lastPathComponent=="tokens.txt"}) else{throw AppError.message("Install the selected offline voice first")}
        let data=files.first(where:{$0.lastPathComponent=="espeak-ng-data"})?.path ?? ""
        let voice:SherpaOnnxOfflineTtsModelConfig
        if language=="pl" {
            voice=sherpaOnnxOfflineTtsModelConfig(vits:sherpaOnnxOfflineTtsVitsModelConfig(model:onnx.path,tokens:tokens.path,dataDir:data),numThreads:2)
        } else {
            guard let voices=files.first(where:{$0.lastPathComponent=="voices.bin"}) else{throw AppError.message("English voice file missing")}
            voice=sherpaOnnxOfflineTtsModelConfig(kokoro:sherpaOnnxOfflineTtsKokoroModelConfig(model:onnx.path,voices:voices.path,tokens:tokens.path,dataDir:data),numThreads:2)
        }
        var config=sherpaOnnxOfflineTtsConfig(model:voice)
        let tts=SherpaOnnxOfflineTtsWrapper(config:&config)
        let generated=tts.generate(text:text)
        guard generated.n>0 else{throw AppError.message("Offline speech produced no audio")}
        let raw=output.deletingPathExtension().appendingPathExtension("wav")
        guard generated.save(filename:raw.path)==1 else{throw AppError.message("Could not save offline speech")}
        defer {try? FileManager.default.removeItem(at:raw)}
        try await AudioEncoding.toM4A(raw,output)
        return output
    }
}
