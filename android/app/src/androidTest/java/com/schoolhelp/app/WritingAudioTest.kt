package com.schoolhelp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schoolhelp.app.sharing.AudioExport
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class WritingAudioTest {
    @Test fun generatedAudioContainsWritingTimeIncludingFinalPause() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val tone=File(context.cacheDir,"writing-test.wav")
        val samples=12000
        val data=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(24000).putInt(48000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples){data.putShort((sin(it*2*Math.PI*440/24000)*10000).toInt().toShort())}
        tone.writeBytes(data.array())
        val output=File(context.filesDir,"writing-fixture.m4a");output.delete()
        try {
            AudioExport.combine(context,listOf(tone to 2.0,tone to 2.0),.5f,output)
            val metadata=MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.path)
                val duration=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Expected approximately 3 seconds including two speed-compensated gaps, got $duration",duration in 2850..3250)
            } finally {metadata.release()}
        } finally {tone.delete()}
    }
}
