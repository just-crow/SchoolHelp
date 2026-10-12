package com.schoolhelp.app.data

import android.content.Context
import android.util.AtomicFile
import com.schoolhelp.app.core.Solution
import com.schoolhelp.app.core.SolutionParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedSession(
    val photos: List<File> = emptyList(),
    val selectedPhoto: Int = 0,
    val solution: Solution? = null,
    val recording: File? = null,
    val recordingSpeed: Float = 1f,
    val positionMs: Long = 0,
    val listening: Boolean = false,
)

/** One private, durable session. Atomic metadata replacement survives interrupted writes. */
class SessionStore(context: Context) {
    val directory = File(context.filesDir, "session").apply { mkdirs() }
    private val database = AppDatabase.get(context)
    private val metadata = AtomicFile(File(directory, "state.json"))

    fun save(session: SavedSession) {
        if (session.photos.isEmpty() && session.solution == null && session.recording == null) {
            database.records().delete("session")
            metadata.delete()
            return
        }
        val json = JSONObject().put("version", 1)
            .put("photos", JSONArray(session.photos.map { it.name }))
            .put("selectedPhoto", session.selectedPhoto)
            .put("recording", session.recording?.name)
            .put("recordingSpeed", session.recordingSpeed)
            .put("positionMs", session.positionMs)
            .put("listening", session.listening)
        session.solution?.let { solution ->
            json.put("solution", JSONObject().put("problems", JSONArray(solution.problems.map { problem ->
                JSONObject().put("number", problem.number).put("question", problem.question)
                    .put("answer", problem.answer).put("explanation", problem.explanation)
                    .put("spokenText", problem.spokenText).put("unreadable", problem.unreadable).put("page", problem.page)
            })))
        }
        database.records().put(StoredRecord("session", "session", json.toString(), 0))
        val stream = metadata.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            metadata.finishWrite(stream)
        } catch (e: Exception) { metadata.failWrite(stream); throw e }
    }

    fun load(): SavedSession = runCatching {
        val persisted = database.records().get("session")?.payload ?: metadata.openRead().bufferedReader().use { it.readText() }.also {
            database.records().put(StoredRecord("session", "session", it, 0))
        }
        val json = JSONObject(persisted)
        fun existing(name: String, parent: File): File? {
            if (name.isBlank() || File(name).name != name) return null
            return File(parent, name).takeIf { it.isFile }
        }
        val names = json.getJSONArray("photos")
        val photos = (0 until names.length()).mapNotNull { existing(names.getString(it), File(directory, "images")) }
        SavedSession(
            photos = photos,
            selectedPhoto = json.optInt("selectedPhoto").coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
            solution = json.optJSONObject("solution")?.let { SolutionParser.parse(it.toString()) },
            recording = existing(json.optString("recording"), directory),
            recordingSpeed = json.optDouble("recordingSpeed", 1.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f,
            positionMs = json.optLong("positionMs").coerceAtLeast(0),
            listening = json.optBoolean("listening"),
        )
    }.getOrDefault(SavedSession())
}
