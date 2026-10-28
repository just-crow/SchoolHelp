@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.schoolhelp.app.sharing

import android.content.Context
import androidx.media3.common.*
import androidx.media3.transformer.*
import com.schoolhelp.app.CrashDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AudioExport {
    // Sharing uploads and writing-pause assembly can otherwise open codecs simultaneously.
    private val exportLock=Mutex()

    suspend fun encode(context:Context,source:File,output:File) {
        val item=EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(source))).setRemoveVideo(true).build()
        render(context,Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build(),output,"sharing audio export")
    }
    suspend fun combine(context:Context,clips:List<Pair<File,Double>>,playbackSpeed:Float,output:File) {
        require(clips.isNotEmpty()) { "No speech clips to combine." }
        val sequence=EditedMediaItemSequence.Builder().experimentalSetForceAudioTrack(true)
        clips.forEach { (file,pause) ->
            sequence.addItem(EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(file))).setRemoveVideo(true).build())
            val gapUs=(pause*playbackSpeed*1_000_000).toLong()
            if(gapUs>0)sequence.addGap(gapUs)
        }
        render(context,Composition.Builder(sequence.build()).build(),output,"writing audio assembly: ${clips.size} clips")
    }
    private suspend fun render(context:Context,composition:Composition,output:File,stage:String) = exportLock.withLock {
        withContext(Dispatchers.Main.immediate) {
            CrashDiagnostics.stage(stage)
            val partial=File(output.parentFile,output.name+".part.m4a")
            partial.delete()
            var transformer:Transformer?=null
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val active=Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object:Transformer.Listener {
                            override fun onCompleted(composition:Composition,result:ExportResult) {if(continuation.isActive)continuation.resume(Unit)}
                            override fun onError(composition:Composition,result:ExportResult,exception:ExportException) {if(continuation.isActive)continuation.resumeWithException(exception)}
                        }).build()
                    transformer=active
                    continuation.invokeOnCancellation {
                        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())runCatching {active.cancel()}
                        else android.os.Handler(android.os.Looper.getMainLooper()).post {runCatching {active.cancel()}}
                    }
                    active.start(composition,partial.absolutePath)
                }
                ensureActive()
                check(partial.renameTo(output)) { "Could not save the audio recording." }
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {CrashDiagnostics.record(context,"audio-export",e);throw e}
            finally {
                // Wait for cancellation/release before deleting source clips or granting the next export the lock.
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    runCatching {transformer?.cancel()}.onFailure {CrashDiagnostics.record(context,"audio-export-release",it)}
                    partial.delete()
                }
            }
        }
    }
}
