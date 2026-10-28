package com.schoolhelp.app

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schoolhelp.app.sharing.AudioExport
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ExportStabilityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun tone():File {
        val samples=2400
        val data=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(24000).putInt(48000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples){data.putShort((sin(it*2*Math.PI*440/24000)*10000).toInt().toShort())}
        return File(context.cacheDir,"stability-${java.util.UUID.randomUUID()}.wav").apply {writeBytes(data.array())}
    }
    private fun valid(file:File) {
        val metadata=MediaMetadataRetriever()
        try {metadata.setDataSource(file.path);assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)}
        finally {metadata.release()}
    }
    @Test fun largeAssemblyAndSharingExportsCanOverlapWithoutSharingCodecs() = runBlocking {
        withTimeout(120000) {
            val tone=tone()
            val mp3=File(context.cacheDir,"stability-cloud.mp3")
            InstrumentationRegistry.getInstrumentation().context.assets.open("stability-tone.mp3").use {input->mp3.outputStream().use {input.copyTo(it)}}
            val files=(0..2).map {File(context.cacheDir,"overlap-$it.m4a").apply {delete()}}
            try {
                coroutineScope {
                    listOf(
                        async {AudioExport.combine(context,List(100){index->(if(index%2==0)mp3 else tone) to .1},.5f,files[0],cueSounds=true)},
                        async {AudioExport.encode(context,tone,files[1])},
                        async {AudioExport.combine(context,List(10){tone to .2},.25f,files[2])}
                    ).awaitAll()
                }
                files.forEach {valid(it)}
            } finally {tone.delete();mp3.delete();files.forEach {it.delete()}}
        }
    }
    @Test fun repeatedCancellationReleasesExporterBeforeNextAttempt() = runBlocking {
        withTimeout(120000) {
            val tone=tone();val output=File(context.cacheDir,"cancel-stability.m4a")
            try {
                repeat(4) {
                    output.delete()
                    val task=launch {AudioExport.combine(context,List(80){tone to .3},1f,output)}
                    delay(40);task.cancelAndJoin()
                    assertFalse(File(output.parentFile,output.name+".part.m4a").exists())
                }
                output.delete();AudioExport.combine(context,listOf(tone to 1.0),.5f,output);valid(output)
            } finally {tone.delete();output.delete()}
        }
    }
}
