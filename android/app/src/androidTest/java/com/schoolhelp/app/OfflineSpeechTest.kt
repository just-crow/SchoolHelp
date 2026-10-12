package com.schoolhelp.app

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.schoolhelp.app.audio.SpeechGenerator
import com.schoolhelp.app.data.ModelCatalog
import com.schoolhelp.app.data.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs when the appropriate voice package has been installed on the test device. */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechTest {
    @Test fun englishVoiceProducesSeekableAudio() = checkVoice("en", "Two plus two equals four. The answer is four.")
    @Test fun polishVoiceProducesSeekableAudio() = checkVoice("pl", "Dwa plus dwa to cztery. Odpowiedź to cztery.")

    private fun checkVoice(language: String, text: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val models = ModelStore(context)
        assumeTrue("Install the voice before running this hardware test.", models.installed(ModelCatalog.voice(language)))
        val file = File(context.cacheDir, "tts-test-$language.wav")
        SpeechGenerator(models).generate(text, language, 1f, file)
        assertTrue(file.length() > 1000)
        MediaMetadataRetriever().use {
            it.setDataSource(file.path)
            assertTrue(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 1000)
        }
        file.delete()
        Unit
    }
}
