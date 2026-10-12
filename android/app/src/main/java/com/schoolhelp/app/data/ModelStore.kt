package com.schoolhelp.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ModelSpec(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val archive: Boolean = false,
)

object ModelCatalog {
    val vision = ModelSpec(
        "gemma-e2b", "Gemma 4 E2B", "Reads photos and solves problems · 2.01 GB download",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it-gpu.litertlm",
        2008432640L, "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
    )
    val english = ModelSpec(
        "kokoro-en", "Kokoro · English", "Natural offline speech · 103 MB download",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
        103248205L, "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd", true,
    )
    val polish = ModelSpec(
        "piper-pl", "Piper · Polish", "Polish offline speech · 21 MB download",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pl_PL-gosia-medium-int8.tar.bz2",
        21109262L, "72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb", true,
    )
    val all = listOf(vision, english, polish)
    fun voice(language: String) = if (language == "pl") polish else english
}

class ModelStore(context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun directory(spec: ModelSpec) = File(root, spec.id)
    fun installed(spec: ModelSpec) = File(directory(spec), ".verified").readTextOrNull() == spec.sha256
    fun installedIds() = ModelCatalog.all.filter(::installed).map { it.id }.toSet()
    fun visionFile() = File(directory(ModelCatalog.vision), "model.litertlm")
    fun remove(spec: ModelSpec) { directory(spec).deleteRecursively() }

    suspend fun install(spec: ModelSpec, progress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        // Never expose an incomplete model as installed. Retrying replaces only staging files.
        val staging = File(root, "${spec.id}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val download = File(root, "${spec.id}.part")
        try {
            require(root.usableSpace > spec.bytes * if (spec.archive) 4 else 2) {
                "Not enough free storage. Free up space and try again."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(spec.url).build()).useResponse { response ->
                check(response.isSuccessful) { "Download failed (${response.code}). Please try again." }
                val body = response.body ?: error("The download was empty.")
                body.byteStream().buffered().use { input ->
                    download.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        var lastUpdate = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            total += count
                            check(total <= spec.bytes) { "The download size does not match the expected model." }
                            if (total - lastUpdate > 1024 * 1024 || total == spec.bytes) {
                                progress(total.toFloat() / spec.bytes, "Downloading ${spec.title}")
                                lastUpdate = total
                            }
                        }
                        check(total == spec.bytes) { "The download was interrupted. Please try again." }
                    }
                }
            }
            progress(1f, "Verifying ${spec.title}")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == spec.sha256) { "Model verification failed. Please download it again." }
            currentCoroutineContext().ensureActive()
            if (spec.archive) {
                progress(1f, "Unpacking ${spec.title}")
                extract(download, staging)
            } else check(download.renameTo(File(staging, "model.litertlm"))) { "Could not store the model." }
            File(staging, ".verified").writeText(spec.sha256)
            directory(spec).deleteRecursively()
            check(staging.renameTo(directory(spec))) { "Could not finish installing the model." }
        } finally {
            download.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun extract(archive: File, destination: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var expanded = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                require(target.path.startsWith(destination.canonicalPath + File.separator)) { "Unsafe archive path." }
                require(!entry.isSymbolicLink && !entry.isLink) { "Links are not supported in model archives." }
                if (entry.isDirectory) target.mkdirs()
                else {
                    require(entry.isFile) { "Unsupported model archive entry." }
                    expanded += entry.size
                    require(expanded <= 2_000_000_000L) { "The model archive is too large." }
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }
}

private fun File.readTextOrNull(): String? = if (isFile) runCatching { readText() }.getOrNull() else null
