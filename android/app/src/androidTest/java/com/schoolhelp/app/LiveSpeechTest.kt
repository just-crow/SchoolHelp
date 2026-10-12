package com.schoolhelp.app

import android.app.Application
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.schoolhelp.app.data.SettingsStore
import com.schoolhelp.app.audio.OpenRouterSpeech
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

/** Explicit opt-in only: sends one short diagnostic request using the saved key. */
class LiveSpeechTest {
    @Test fun diagnoseSelectedSpeechModel() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSpeechProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = SettingsStore(context)
        val settings = store.load()
        val key = store.apiKey()
        val result = OpenRouterSpeech().generate("This is a short biology speech test.", key, settings, File(context.cacheDir, "live-speech-test.mp3"))
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(result.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            assertTrue("Generated speech should contain playable audio.", duration > 500)
            println("Speech diagnostic success: ${result.extension}, $duration ms")
        } finally { metadata.release() }
    }
}
