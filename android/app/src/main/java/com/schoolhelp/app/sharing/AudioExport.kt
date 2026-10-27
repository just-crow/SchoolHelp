@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.schoolhelp.app.sharing

import android.content.Context
import androidx.media3.common.*
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AudioExport {
    suspend fun encode(context: Context, source: File, output: File) = withContext(Dispatchers.Main) {
        val partial=File(output.parentFile, output.name + ".part.m4a")
        partial.delete()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer=Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object: Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { if(continuation.isActive) continuation.resume(Unit) }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { if(continuation.isActive) continuation.resumeWithException(exception) }
                    }).build()
                continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel(); partial.delete() } }
                transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(source))).setRemoveVideo(true).build(), partial.absolutePath)
            }
            check(partial.renameTo(output)) { "Could not prepare audio for sharing." }
        } finally { partial.delete() }
    }
    suspend fun combine(context: Context, clips: List<Pair<File,Double>>, playbackSpeed: Float, output: File) = withContext(Dispatchers.Main) {
        val sequence = EditedMediaItemSequence.Builder().experimentalSetForceAudioTrack(true)
        clips.forEach { (file, pause) ->
            sequence.addItem(EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(file))).setRemoveVideo(true).build())
            val gapUs = (pause * playbackSpeed * 1_000_000).toLong()
            if (gapUs > 0) sequence.addGap(gapUs)
        }
        val partial = File(output.parentFile, output.name + ".part.m4a")
        partial.delete()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object: Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { if(continuation.isActive) continuation.resume(Unit) }
                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { if(continuation.isActive) continuation.resumeWithException(exception) }
                    }).build()
                continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel(); partial.delete() } }
                transformer.start(Composition.Builder(sequence.build()).build(),partial.absolutePath)
            }
            check(partial.renameTo(output)) { "Could not save writing pauses." }
        } finally { partial.delete() }
    }

}
