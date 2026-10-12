package com.schoolhelp.app.audio

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.schoolhelp.app.data.ModelCatalog
import com.schoolhelp.app.data.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class SpeechGenerator(private val models: ModelStore) {
    suspend fun generate(text: String, language: String, speed: Float, output: File): File = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "There is no text to read." }
        val spec = ModelCatalog.voice(language)
        check(models.installed(spec)) { "Download the ${spec.title} voice in settings first." }
        val directory = models.directory(spec)
        val onnx = directory.walkTopDown().firstOrNull { it.isFile && it.extension == "onnx" }
            ?: error("Voice model is missing. Delete it in settings and download it again.")
        val base = onnx.parentFile!!
        val tokens = File(base, "tokens.txt")
        val data = File(base, "espeak-ng-data")
        check(tokens.isFile && data.isDirectory) { "The voice package is incomplete. Download it again." }
        val config = if (language == "pl") OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = tokens.path, dataDir = data.path), numThreads = 2,
        ) else OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = onnx.path, voices = File(base, "voices.bin").path,
                tokens = tokens.path, dataDir = data.path,
            ), numThreads = 2,
        )
        val tts = OfflineTts(config = OfflineTtsConfig(model = config, maxNumSentences = 1))
        try {
            val job = currentCoroutineContext()
            job.ensureActive()
            val audio = tts.generateWithCallback(text = text, sid = 0, speed = speed,
                callback = SpeechProgress(job[Job]!!))
            job.ensureActive()
            check(audio.samples.isNotEmpty() && audio.save(output.path)) { "Could not generate the speech recording." }
            output
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            tts.release()
        }
    }
}

// sherpa's JNI looks up invoke(float[]): Integer, which an invokedynamic lambda does not expose.
// A concrete Function1 implementation preserves the specialized bridge required by the native API.
private class SpeechProgress(private val job: Job) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (job.isActive) 1 else 0
}
