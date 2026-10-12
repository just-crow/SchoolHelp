import Foundation
import SQLite3

final class RecordStore {
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    private var db: OpaquePointer?
    let root: URL
    init() {
        root = FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("SchoolHelp")
        try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true,attributes:[.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication])
        sqlite3_open(root.appendingPathComponent("state.sqlite").path,&db)
        sqlite3_exec(db,"CREATE TABLE IF NOT EXISTS records (id TEXT PRIMARY KEY,kind TEXT NOT NULL,sequence INTEGER NOT NULL,payload BLOB NOT NULL)",nil,nil,nil)
    }
    deinit { sqlite3_close(db) }
    func put(_ id:String,_ kind:String,_ sequence:Int64,_ data:Data) {
        var stmt:OpaquePointer?
        sqlite3_prepare_v2(db,"INSERT OR REPLACE INTO records VALUES (?,?,?,?)",-1,&stmt,nil)
        id.withCString {sqlite3_bind_text(stmt,1,$0,-1,transient)}
        kind.withCString {sqlite3_bind_text(stmt,2,$0,-1,transient)}
        sqlite3_bind_int64(stmt,3,sequence)
        _=data.withUnsafeBytes { sqlite3_bind_blob(stmt,4,$0.baseAddress,Int32(data.count),unsafeBitCast(-1,to:sqlite3_destructor_type.self)) }
        sqlite3_step(stmt); sqlite3_finalize(stmt)
    }
    func get(_ id:String)->Data? {
        var stmt:OpaquePointer?; sqlite3_prepare_v2(db,"SELECT payload FROM records WHERE id=?",-1,&stmt,nil)
        id.withCString {sqlite3_bind_text(stmt,1,$0,-1,transient)}
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt)==SQLITE_ROW, let bytes=sqlite3_column_blob(stmt,0) else {return nil}
        return Data(bytes:bytes,count:Int(sqlite3_column_bytes(stmt,0)))
    }
    func rows(_ kind:String)->[(String,Int64,Data)] {
        var stmt:OpaquePointer?; sqlite3_prepare_v2(db,"SELECT id,sequence,payload FROM records WHERE kind=? ORDER BY sequence",-1,&stmt,nil)
        kind.withCString {sqlite3_bind_text(stmt,1,$0,-1,transient)}
        defer { sqlite3_finalize(stmt) }
        var out:[(String,Int64,Data)]=[]
        while sqlite3_step(stmt)==SQLITE_ROW {
            guard let id=sqlite3_column_text(stmt,0),let bytes=sqlite3_column_blob(stmt,2) else {continue}
            out.append((String(cString:UnsafeRawPointer(id).assumingMemoryBound(to:CChar.self)),sqlite3_column_int64(stmt,1),Data(bytes:bytes,count:Int(sqlite3_column_bytes(stmt,2)))))
        }
        return out
    }
    func delete(_ id:String) {
        var stmt:OpaquePointer?; sqlite3_prepare_v2(db,"DELETE FROM records WHERE id=?",-1,&stmt,nil)
        id.withCString {sqlite3_bind_text(stmt,1,$0,-1,transient)};sqlite3_step(stmt);sqlite3_finalize(stmt)
    }
    func clear(_ kind:String) { for (id,_,_) in rows(kind) {delete(id)} }
}

struct ReceivedItem: Codable, Identifiable {
    let id:String
    let sequence:Int64
    let solution:Solution
    let spoken_text:String
    let checksum:String
    let url:String
    let expires_at:String
    let speed:Float
}

struct OutgoingItem: Codable, Identifiable {
    let id:String
    let solution:Solution
    let spokenText:String
    let speed:Float
    let language:String
    let source:String
}

@MainActor final class Sharing: ObservableObject {
    @Published var code=""
    @Published var enabled=false
    @Published var following=""
    @Published var status=""
    @Published var listeners:[String]=[]
    @Published var pending=0
    @Published var ready=0
    let store=RecordStore()
    private var access=Keychain.get("supabase-access") ?? ""
    private var refresh=Keychain.get("supabase-refresh") ?? ""
    private var expires=UserDefaults.standard.double(forKey:"auth-expiry")
    private var syncing=false
    init() { restore(); recount() }
    private func restore() {
        if let data=store.get("sharing-config"),let obj=try? JSONSerialization.jsonObject(with:data) as? [String:Any] {
            code=obj["code"] as? String ?? ""; enabled=obj["enabled"] as? Bool ?? false; following=obj["following"] as? String ?? ""
        }
    }
    private func save() {
        let body:[String:Any]=["code":code,"enabled":enabled,"following":following]
        if let data=try? JSONSerialization.data(withJSONObject:body) {store.put("sharing-config","config",0,data)}
    }
    func recount() {
        pending=store.rows("incoming").count
        ready=store.rows("incoming").filter { audio($0.0).isFileURL && FileManager.default.fileExists(atPath:audio($0.0).path) }.count
    }
    func audio(_ id:String)->URL {store.root.appendingPathComponent(id+".m4a")}
    private func auth() async throws ->String {
        if expires>Date().timeIntervalSince1970+60 && !access.isEmpty {return access}
        let base=BackendConfig.current
        let signup=refresh.isEmpty
        let endpoint=signup ? "/auth/v1/signup" : "/auth/v1/token?grant_type=refresh_token"
        var request=URLRequest(url:URL(string:base.SUPABASE_URL+endpoint)!)
        request.httpMethod="POST";request.setValue(base.SUPABASE_ANON_KEY,forHTTPHeaderField:"apikey")
        request.setValue("application/json",forHTTPHeaderField:"Content-Type")
        request.httpBody=try JSONSerialization.data(withJSONObject:signup ? [:] : ["refresh_token":refresh])
        let (data,response)=try await URLSession.shared.data(for:request)
        guard (200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 500),let obj=try JSONSerialization.jsonObject(with:data) as? [String:Any],let token=obj["access_token"] as? String else {throw AppError.message("Sharing sign-in failed. Check your connection.")}
        access=token;refresh=obj["refresh_token"] as? String ?? ""
        expires=Date().timeIntervalSince1970+Double(obj["expires_in"] as? Int ?? 3600)
        Keychain.set("supabase-access",access);Keychain.set("supabase-refresh",refresh)
        UserDefaults.standard.set(expires,forKey:"auth-expiry")
        return token
    }
    func request(_ action:String,_ values:[String:Any]=[:]) async throws->[String:Any] {
        let base=BackendConfig.current
        var request=URLRequest(url:URL(string:base.SUPABASE_URL+"/functions/v1/sharing")!)
        request.httpMethod="POST";request.setValue(base.SUPABASE_ANON_KEY,forHTTPHeaderField:"apikey")
        request.setValue("Bearer \(try await auth())",forHTTPHeaderField:"Authorization")
        request.setValue("application/json",forHTTPHeaderField:"Content-Type")
        request.httpBody=try JSONSerialization.data(withJSONObject:values.merging(["action":action]){$1})
        let (data,response)=try await URLSession.shared.data(for:request)
        let obj=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any] ?? [:]
        guard ((response as? HTTPURLResponse)?.statusCode ?? 500) < 300 else {throw AppError.message(obj["error"] as? String ?? "Sharing failed")}
        return obj
    }
    func profile() async throws {
        let obj=try await request("profile")
        code=obj["code"] as? String ?? ""; enabled=obj["enabled"] as? Bool ?? false
        listeners=(obj["listeners"] as? [[String:Any]] ?? []).compactMap{$0["listener"] as? String}
        save()
    }
    func toggle() async throws {let obj=try await request("toggle",["enabled":!enabled]);enabled=obj["enabled"] as? Bool ?? false;save()}
    func rotate() async throws {let obj=try await request("rotate");code=obj["code"] as? String ?? "";save()}
    func remove(_ id:String) async throws { _=try await request("remove",["member":id]);try await profile() }
    func join(_ entered:String) async throws {
        guard following.isEmpty else {throw AppError.message("Disconnect first")}
        if UserDefaults.standard.bool(forKey:"pendingLeave") {_=try await request("leave");UserDefaults.standard.set(false,forKey:"pendingLeave")}
        let normalized=entered.uppercased().filter { $0.isLetter || $0.isNumber }
        let obj=try await request("join",["code":normalized]);following=obj["owner"] as? String ?? "";save();try await sync()
    }
    func leave() async throws {
        do {_=try await request("leave")} catch {UserDefaults.standard.set(true,forKey:"pendingLeave")}
        following="";save()
        for (id,_,_) in store.rows("incoming") {try? FileManager.default.removeItem(at:audio(id));store.delete(id)}
        store.clear("consumed");store.delete("shared-current");recount()
    }
    func current()->ReceivedItem? {
        guard let first=store.rows("incoming").first,FileManager.default.fileExists(atPath:audio(first.0).path) else{return nil}
        return try? JSONDecoder().decode(ReceivedItem.self,from:first.2)
    }
    func consume(_ id:String) {
        guard let data=store.get(id) else{return}
        let sequence=store.rows("incoming").first(where:{$0.0==id})?.1 ?? 0
        store.put(id,"consumed",sequence,data);try? FileManager.default.removeItem(at:audio(id));store.delete("shared-current");recount()
    }
    func enqueue(_ item:OutgoingItem, audio:URL) throws {
        let source=store.root.appendingPathComponent(item.id+"-source.m4a")
        try FileManager.default.copyItem(at:audio,to:source)
        store.put(item.id,"outbox",Int64(Date().timeIntervalSince1970*1000),try JSONEncoder().encode(item))
    }
    func sync() async throws {
        guard !syncing else{return};syncing=true;defer{syncing=false;recount()}
        if UserDefaults.standard.bool(forKey:"pendingLeave") {_=try await request("leave");UserDefaults.standard.set(false,forKey:"pendingLeave")}
        try await profile()
        var publicationError:Error?
        for (id,_,data) in store.rows("outbox") {
            do {
            let item=try JSONDecoder().decode(OutgoingItem.self,from:data)
            let source=store.root.appendingPathComponent(item.source)
            guard FileManager.default.fileExists(atPath:source.path) else{throw AppError.message("Queued audio is missing")}
            let bytes=try Data(contentsOf:source)
            guard bytes.count>0 && bytes.count<=25_000_000 else{throw AppError.message("Shared audio exceeds 25 MB")}
            let prep=try await request("prepare",["id":id])
            if prep["published"] as? Bool != true {
                guard let target=URL(string:prep["url"] as? String ?? "") else{throw AppError.message("Upload URL missing")}
                var upload=URLRequest(url:target);upload.httpMethod="PUT";upload.setValue("audio/mp4",forHTTPHeaderField:"Content-Type")
                let (_,response)=try await URLSession.shared.upload(for:upload,from:bytes)
                guard ((response as? HTTPURLResponse)?.statusCode ?? 500) < 300 else{throw AppError.message("Audio upload failed")}
                let solution=try JSONSerialization.jsonObject(with:JSONEncoder().encode(item.solution))
                _=try await request("publish",["id":id,"solution":solution,"spokenText":item.spokenText,"language":item.language,"speed":item.speed,"uploadPath":prep["path"] ?? "","checksum":bytes.sha256])
            }
            store.delete(id);try? FileManager.default.removeItem(at:source)
            } catch {publicationError=error;break}
        }
        if !following.isEmpty {
            let result=try await request("sync",["owner":following])
            let items=result["items"] as? [[String:Any]] ?? []
            let available=Set(items.compactMap{$0["id"] as? String})
            for (id,_,_) in store.rows("incoming") where !available.contains(id) && !FileManager.default.fileExists(atPath:audio(id).path) {store.delete(id)}
            for obj in items {
                let data=try JSONSerialization.data(withJSONObject:obj)
                guard let item=try? JSONDecoder().decode(ReceivedItem.self,from:data) else{continue}
                if !store.rows("consumed").contains(where:{$0.0==item.id}) {store.put(item.id,"incoming",item.sequence,data)}
            }
            for (id,_,data) in store.rows("incoming").prefix(3) {
                guard !FileManager.default.fileExists(atPath:audio(id).path),let item=try? JSONDecoder().decode(ReceivedItem.self,from:data),let url=URL(string:item.url) else{continue}
                let (bytes,response)=try await URLSession.shared.data(from:url)
                guard (response as? HTTPURLResponse)?.statusCode == 200,bytes.count<=25_000_000,bytes.sha256==item.checksum else{throw AppError.message("Shared audio verification failed")}
                try bytes.write(to:audio(id),options:.atomic)
            }
        }
        if let token=Keychain.get("fcm-token") {_ = try await request("token",["token":token,"platform":"ios"])}
        recount()
        status=pending>0 ? "\(pending) answers queued" : "Waiting for the next answer"
        if let publicationError {throw publicationError}
    }
}
