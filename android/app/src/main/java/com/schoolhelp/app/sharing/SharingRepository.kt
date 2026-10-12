package com.schoolhelp.app.sharing

import android.content.Context
import com.schoolhelp.app.BuildConfig
import com.schoolhelp.app.core.Solution
import com.schoolhelp.app.core.SolutionParser
import com.schoolhelp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.*
import java.io.File
import java.util.UUID
import java.security.MessageDigest

data class SharedItem(val id: String, val file: File, val solution: Solution, val speed: Float, val position: Long)
data class SharingState(val configured: Boolean = false, val code: String = "", val enabled: Boolean = false,
    val following: String = "", val pending: Int = 0, val outgoing: Int = 0, val ready: Int = 0, val listeners: List<Pair<String,String>> = emptyList(),
    val status: String = "", val busy: Boolean = false)

class SharingRepository(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.records()
    private val prefs = context.getSharedPreferences("sharing-identity", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "sharing").apply { mkdirs() }
    private val http = OkHttpClient.Builder().callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val configured = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    private val mutable = MutableStateFlow(SharingState(configured = configured))
    val state = mutable.asStateFlow()
    init { refresh() }
    fun wasSharedListening() = config().optBoolean("sharedMode")
    fun sharedMode(enabled: Boolean) { updateConfig("sharedMode" to enabled) }
    private fun config() = dao.get("sharing-config")?.payload?.let(::JSONObject) ?: JSONObject()
    private fun updateConfig(vararg values: Pair<String,Any>) {
        db.runInTransaction {
            val json=config()
            values.forEach { (key,value) -> json.put(key,value) }
            dao.put(StoredRecord("sharing-config","config",json.toString(),0))
        }
        refresh()
    }
    private fun refresh() {
        val config = config()
        val incoming = dao.list("incoming")
        mutable.value = mutable.value.copy(code=config.optString("code"), enabled=config.optBoolean("enabled"), following=config.optString("following"),
            pending=incoming.size, outgoing=dao.list("outbox").size, ready=incoming.count { File(folder, it.id + ".m4a").isFile })
    }
    private suspend fun identity(): String {
        require(configured) { "Sharing is not configured in this build. Follow docs/SHARING_SETUP.md." }
        val expires = prefs.getLong("expires", 0)
        if (expires > System.currentTimeMillis() + 60_000) return prefs.getString("access", "")!!
        val refresh = prefs.getString("refresh", null)
        val url = BuildConfig.SUPABASE_URL + if (refresh == null) "/auth/v1/signup" else "/auth/v1/token?grant_type=refresh_token"
        val body = if (refresh == null) JSONObject() else JSONObject().put("refresh_token", refresh)
        val request = Request.Builder().url(url).header("apikey", BuildConfig.SUPABASE_ANON_KEY).post(body.toString().toRequestBody(JSON)).build()
        val json = http.newCall(request).useResponse { response ->
            check(response.isSuccessful) { "Sharing sign-in failed (${response.code}). Check anonymous authentication configuration." }
            JSONObject(response.body!!.string())
        }
        prefs.edit().putString("access", json.getString("access_token")).putString("refresh", json.getString("refresh_token"))
            .putLong("expires", System.currentTimeMillis() + json.getLong("expires_in") * 1000).apply()
        return json.getString("access_token")
    }
    private suspend fun api(action: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val token = identity()
        val request = Request.Builder().url(BuildConfig.SUPABASE_URL + "/functions/v1/sharing")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token")
            .post(body.put("action", action).toString().toRequestBody(JSON)).build()
        return@withContext http.newCall(request).useResponse { response ->
            val json = JSONObject(response.body!!.string())
            check(response.isSuccessful) { json.optString("error", "Sharing request failed (${response.code}).") }
            json
        }
    }
    suspend fun ownerAction(action: String, enabled: Boolean = false, member: String = "") = mutex.withLock {
        mutable.update { it.copy(busy=true) }
        try {
            val response = api(action, JSONObject().put("enabled", enabled).put("member", member))
            updateConfig("code" to response.getString("code"), "enabled" to response.getBoolean("enabled"))
            val listeners = response.optJSONArray("listeners") ?: JSONArray()
            mutable.update { it.copy(listeners=(0 until listeners.length()).map { i -> val row=listeners.getJSONObject(i); row.getString("listener") to row.getString("joined_at") },status="Sharing settings updated.") }
        } finally { mutable.update { it.copy(busy=false) } }
    }
    suspend fun join(code: String) = mutex.withLock {
        check(config().optString("following").isBlank()) { "Disconnect from the current owner first." }
        if(config().optBoolean("pendingLeave")) {
            api("leave"); updateConfig("pendingLeave" to false)
        }
        val response = api("join", JSONObject().put("code", code.uppercase().replace("-", "").replace(" ", "")))
        updateConfig("following" to response.getString("owner"), "joinedCode" to code)
        mutable.update { it.copy(status="Connected. Waiting for answers.") }
        SharingSyncWorker.enqueue(context)
    }
    suspend fun leave() = mutex.withLock {
        val owner = config().optString("following")
        var pendingLeave=false
        if (owner.isNotBlank()) {
            try { api("leave", JSONObject().put("owner",owner)) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { pendingLeave=true }
        }
        db.runInTransaction {
            dao.list("incoming").forEach { File(folder, it.id + ".m4a").delete() }
            dao.clear("incoming"); dao.clear("consumed"); dao.delete("shared-current")
            updateConfig("following" to "", "sharedMode" to false, "pendingLeave" to pendingLeave)
        }
    }
    suspend fun snapshot(solution: Solution, audio: File, speed: Float, language: String, spokenText: String) = withContext(Dispatchers.IO) {
        if (!config().optBoolean("enabled")) return@withContext
        val id = UUID.randomUUID().toString()
        val copy = File(folder, "$id-source.${audio.extension}")
        audio.copyTo(copy, overwrite=true)
        val json = JSONObject().put("id", id).put("source", copy.name).put("speed", speed).put("language", language)
            .put("spokenText", spokenText).put("solution", solutionJson(solution))
        dao.put(StoredRecord(id, "outbox", json.toString(), System.currentTimeMillis()))
        refresh()
        SharingSyncWorker.enqueue(context)
    }
    suspend fun sync() = withContext(Dispatchers.IO) { syncLocked() }
    private suspend fun syncLocked() = mutex.withLock {
        if (!configured) return@withLock
        try {
            if(config().optBoolean("pendingLeave")) {
                api("leave"); updateConfig("pendingLeave" to false)
            }
            val profile=api("profile")
            updateConfig("code" to profile.getString("code"), "enabled" to profile.getBoolean("enabled"))
            val listeners=profile.optJSONArray("listeners") ?: JSONArray()
            mutable.update { it.copy(listeners=(0 until listeners.length()).map { i -> val row=listeners.getJSONObject(i); row.getString("listener") to row.getString("joined_at") }) }
            var publicationError: Exception? = null
            for (record in dao.list("outbox")) {
                try {
                val data = JSONObject(record.payload)
                val encoded = File(folder, record.id + "-upload.m4a")
                if (!encoded.isFile) AudioExport.encode(context, File(folder, data.getString("source")), encoded)
                check(encoded.length() in 1..25_000_000) { "Shared recording exceeds the 25 MB limit." }
                val prepared = api("prepare", JSONObject().put("id", record.id))
                if (!prepared.optBoolean("published")) {
                    val upload = Request.Builder().url(prepared.getString("url")).put(encoded.asRequestBody("audio/mp4".toMediaType())).build()
                    http.newCall(upload).useResponse { check(it.isSuccessful) { "Audio upload failed (${it.code}). Will retry." } }
                    api("publish", data.put("checksum", checksum(encoded)).put("uploadPath",prepared.getString("path")))
                }
                dao.delete(record.id)
                File(folder, data.getString("source")).delete(); encoded.delete()
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { publicationError=e; break } // Preserve owner order without blocking received answers.
            }
            val following = config().optString("following")
            if (following.isNotBlank()) {
                val response = api("sync", JSONObject().put("owner", following))
                val items = response.getJSONArray("items")
                val available = mutableSetOf<String>()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i); val id = item.getString("id"); available += id
                    db.runInTransaction { if (dao.get(id)?.kind != "consumed") dao.put(StoredRecord(id, "incoming", item.toString(), item.getLong("sequence"))) }
                }
                val current = dao.get("shared-current")?.payload?.let(::JSONObject)?.optString("id")
                for (record in dao.list("incoming")) {
                    if (record.id !in available && !File(folder, record.id + ".m4a").isFile && record.id != current) dao.delete(record.id)
                }
                for (record in dao.list("incoming").take(3)) {
                    val target = File(folder, record.id + ".m4a")
                    if (target.isFile) continue
                    val item=JSONObject(record.payload)
                    val temp=File(folder, record.id + ".part")
                    try {
                        http.newCall(Request.Builder().url(item.getString("url")).build()).useResponse { response ->
                            check(response.isSuccessful) { "Audio download failed. Will retry." }
                            response.body!!.byteStream().use { input -> temp.outputStream().use { output ->
                                val buffer=ByteArray(8192); var total=0L
                                while (true) { val n=input.read(buffer); if(n<0) break; total+=n; check(total<=25_000_000) { "Shared audio is too large." }; output.write(buffer,0,n) }
                            } }
                        }
                        check(checksum(temp) == item.getString("checksum")) { "Audio verification failed. Will retry." }
                        db.runInTransaction { if(dao.get(record.id)?.kind=="incoming") check(temp.renameTo(target)) { "Could not save shared recording." } }
                    } finally { temp.delete() }
                }
            }
            prefs.getString("pushToken", null)?.let { api("token", JSONObject().put("token",it)) }
            refresh()
            if(publicationError!=null) throw publicationError
            mutable.update { it.copy(status=if(it.pending>0) "${it.pending} transcription(s) queued" else if(it.following.isNotBlank()) "Waiting for the next transcription" else "Ready to share") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(status=e.message ?: "Sharing will retry.") }; throw e }
    }
    fun current(): SharedItem? {
        val pointer = dao.get("shared-current")?.payload?.let(::JSONObject)
        val record = pointer?.optString("id")?.let { dao.get(it) }?.takeIf { it.kind == "incoming" }
            ?: dao.list("incoming").firstOrNull() ?: return null
        val file = File(folder, record.id + ".m4a")
        if (!file.isFile) return null // Preserve FIFO order even while the first audio is downloading.
        if (pointer?.optString("id") != record.id) dao.put(StoredRecord("shared-current", "config", JSONObject().put("id",record.id).put("position",0).toString(),0))
        val json=JSONObject(record.payload)
        return SharedItem(record.id,file,SolutionParser.parse(json.getJSONObject("solution").toString()),json.optDouble("speed",1.0).toFloat(),
            if(pointer?.optString("id")==record.id) pointer.optLong("position") else 0)
    }
    fun savePosition(position: Long) {
        val record=dao.get("shared-current") ?: return
        val json=JSONObject(record.payload).put("position",position)
        dao.put(StoredRecord(record.id,record.kind,json.toString(),0))
    }
    fun advance(expectedId: String?): SharedItem? {
        db.runInTransaction {
            val pointer=dao.get("shared-current")?.payload?.let(::JSONObject)
            val id=pointer?.optString("id")
            if (id != null && id == expectedId) {
                dao.get(id)?.let { dao.put(StoredRecord(id,"consumed",it.payload,it.sequence)) }
                dao.delete("shared-current"); File(folder,"$id.m4a").delete()
            }
        }
        refresh(); SharingSyncWorker.enqueue(context)
        return current()
    }
    fun storePushToken(token: String) { prefs.edit().putString("pushToken",token).apply(); SharingSyncWorker.enqueue(context) }
    companion object {
        private val JSON = "application/json".toMediaType()
        fun solutionJson(solution: Solution) = JSONObject().put("problems",JSONArray(solution.problems.map {
            JSONObject().put("number",it.number).put("question",it.question).put("answer",it.answer).put("explanation",it.explanation)
                .put("spokenText",it.spokenText).put("unreadable",it.unreadable).put("page",it.page)
        }))
        fun checksum(file: File): String { val digest=MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer=ByteArray(8192); while(true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
    }
}
