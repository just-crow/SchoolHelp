package com.schoolhelp.app.audio

import com.schoolhelp.app.core.ApiKey
import com.schoolhelp.app.data.AppSettings
import com.schoolhelp.app.data.useResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class SpeechModel(val id: String, val name: String, val voices: List<String>)

class OpenRouterSpeech(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://openrouter.ai/api/v1/",
) {
    suspend fun models(): List<SpeechModel> = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("${baseUrl}models?output_modalities=speech").build()).useResponse { response ->
            check(response.isSuccessful) { "Could not load OpenRouter speech models (${response.code})." }
            val data = JSONObject(response.body?.string() ?: error("Empty speech model list.")).getJSONArray("data")
            (0 until data.length()).mapNotNull { i ->
                val item = data.getJSONObject(i)
                val outputs = item.optJSONObject("architecture")?.optJSONArray("output_modalities")
                if (outputs == null || (0 until outputs.length()).none { outputs.optString(it) == "speech" }) null
                else {
                    val voices = item.optJSONArray("supported_voices")
                    SpeechModel(item.getString("id"), item.optString("name", item.getString("id")),
                        if (voices == null) emptyList() else (0 until voices.length()).map { voices.getString(it) })
                }
            }.sortedBy { it.name.lowercase() }
        }
    }

    suspend fun generate(text: String, key: String, settings: AppSettings, output: File) = withContext(Dispatchers.IO) {
        val cleanKey = ApiKey.normalize(key)
        require(settings.speechModelId.isNotBlank()) { "Choose an OpenRouter speech model in settings." }
        require(settings.speechVoice.isNotBlank()) { "Choose a speech voice in settings." }
        require(text.isNotBlank()) { "There is no text to read." }
        val format = if (settings.speechModelId.startsWith("google/")) "pcm" else "mp3"
        val body = JSONObject().put("model", settings.speechModelId).put("input", text)
            .put("voice", settings.speechVoice).put("response_format", format)
        val request = Request.Builder().url("${baseUrl}audio/speech").header("Authorization", "Bearer $cleanKey")
            .header("X-Title", "SchoolHelp").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val partial = File(output.parentFile, "${output.name}.partial")
        try {
            var recording = output
            client.newCall(request).useResponse { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "OpenRouter rejected the API key. Check it in settings."
                        402 -> "Your OpenRouter account needs credits."
                        400, 404, 422 -> {
                            val detail = runCatching {
                                JSONObject(response.peekBody(8192).string()).optJSONObject("error")?.optString("message")
                                    ?.replace(cleanKey, "[redacted]")?.replace(text, "[answer text]")
                                    ?.replace(Regex("sk-[A-Za-z0-9_-]+"), "[redacted]")
                                    ?.replace(Regex("[\\p{Cntrl}]+"), " ")?.take(300)?.trim()
                            }.getOrNull().orEmpty()
                            "OpenRouter speech failed (${response.code}). " + detail.ifBlank { "Check the selected model and voice and try again." }
                        }
                        429 -> "OpenRouter speech rate limit reached. Try again shortly."
                        else -> "OpenRouter speech request failed (${response.code}). Try again."
                    }
                }
                val audio = response.body ?: error("OpenRouter returned no speech.")
                val type = audio.contentType()?.let { "${it.type}/${it.subtype}" }
                val pcm = type == "audio/pcm"
                check(type in listOf("audio/mpeg", "audio/mp3", "audio/pcm", "audio/wav", "audio/x-wav")) { "OpenRouter returned an unsupported audio format. Try another speech model." }
                recording = File(output.parentFile, "${output.nameWithoutExtension}.${if (pcm || type == "audio/wav" || type == "audio/x-wav") "wav" else "mp3"}")
                audio.byteStream().use { input -> partial.outputStream().use { out ->
                    if (pcm) out.write(ByteArray(44))
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        out.write(buffer, 0, count)
                    }
                    check(total > 0) { "OpenRouter returned an empty speech recording." }
                    if (pcm) check(total % 2 == 0L) { "OpenRouter returned incomplete PCM audio. Try again." }
                } }
                if (pcm) {
                    val size = (partial.length() - 44).toInt()
                    val rate = audio.contentType()?.parameter("rate")?.toIntOrNull() ?: 24000
                    check(rate in 8000..192000) { "OpenRouter returned an unsupported sample rate." }
                    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                        .put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
                        .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                        .put("data".toByteArray()).putInt(size).array()
                    RandomAccessFile(partial, "rw").use { it.write(header) }
                }
            }
            coroutineContext.ensureActive()
            check(partial.renameTo(recording)) { "Could not save the speech recording." }
            recording
        } finally { partial.delete() }
    }
}
