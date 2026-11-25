package com.schoolhelp.app.audio

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.schoolhelp.app.core.ApiKey
import com.schoolhelp.app.data.openRouterCreditMessage
import com.schoolhelp.app.data.useResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class RecordingLanguage(val code: String, val label: String) {
    ENGLISH("en", "English"),
    GERMAN("de", "German"),
    BOSNIAN("bs", "Bosnian"),
}

class OpenRouterTranscription(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(150, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).build(),
    private val endpoint: String = "https://openrouter.ai/api/v1/audio/transcriptions",
) {
    suspend fun transcribe(resolver: ContentResolver, uri: Uri, language: RecordingLanguage, key: String): String =
        withContext(Dispatchers.IO) {
            val format = audioFormat(resolver, uri)
            val audio = resolver.openInputStream(uri)?.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_BYTES) { "Choose a recording under 10 MB." }
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            } ?: error("Could not open the recording. Choose it again.")
            transcribe(audio, format, language, key)
        }

    internal suspend fun transcribe(audio: ByteArray, format: String, language: RecordingLanguage, key: String): String {
        require(audio.isNotEmpty()) { "This recording is empty." }
        val body = JSONObject().put("model", "openai/whisper-large-v3")
            .put("language", language.code)
            .put("temperature", 0)
            .put("input_audio", JSONObject().put("format", format)
                .put("data", Base64.getEncoder().encodeToString(audio)))
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer ${ApiKey.normalize(key)}")
            .header("X-Title", "SchoolHelp")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).useResponse { response ->
            check(response.isSuccessful) {
                when (response.code) {
                    401, 403 -> "OpenRouter rejected the request. Check your saved API key and permissions."
                    402 -> response.openRouterCreditMessage()
                    413 -> "OpenRouter could not accept this recording size. Try a shorter clip."
                    429 -> "OpenRouter is busy or your rate limit was reached. Try again shortly."
                    else -> "OpenRouter could not transcribe the recording (${response.code}). Try a shorter WAV or M4A file."
                }
            }
            val payload = response.body?.string() ?: "{}"
            val json = try { JSONObject(payload) }
            catch (_: JSONException) { error("OpenRouter returned an unreadable transcription response.") }
            val text = (json.opt("text") as? String)?.trim().orEmpty()
            check(text.isNotBlank()) { "No speech was returned. Try a clearer recording and check its language." }
            check(text.length <= MAX_TRANSCRIPT) { "The transcript is too long. Try a shorter recording." }
            text
        }
    }

    private fun audioFormat(resolver: ContentResolver, uri: Uri): String {
        val extension = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        if (extension in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")) return requireNotNull(extension)
        return when (resolver.getType(uri)) {
            "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/webm" -> "webm"
            "audio/aac" -> "aac"
            else -> error("Choose a WAV, MP3, FLAC, M4A, Ogg, WebM, or AAC recording.")
        }
    }

    companion object {
        private const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_TRANSCRIPT = 32000
    }
}
