package com.schoolhelp.app.audio

import com.schoolhelp.app.data.AppSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class OpenRouterSpeechTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var speech: OpenRouterSpeech
    private val settings = AppSettings(speechModelId = "provider/tts", speechVoice = "voice-one", rate = 1.2f)
    @Before fun setup() { server.start(); speech = OpenRouterSpeech(OkHttpClient(), server.url("/").toString()) }
    @After fun teardown() { server.shutdown() }
    private fun output() = File(folder.root, "speech.mp3")
    private fun assertNoRecording(file: File) {
        assertFalse(file.exists())
        assertFalse(File(file.parentFile, "${file.name}.partial").exists())
    }

    @Test fun discoversOnlySpeechModelsAndTheirExactVoiceIds() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[
            {"id":"chat","architecture":{"output_modalities":["text","audio"]}},
            {"id":"tts","name":"Speech","architecture":{"output_modalities":["speech"]},"supported_voices":["en_paul_neutral","pl_voice"]},
            {"id":"tts-no-voices","architecture":{"output_modalities":["speech"]}}]}"""))
        val models = speech.models()
        assertEquals(listOf("tts", "tts-no-voices"), models.map { it.id })
        assertEquals(listOf("en_paul_neutral", "pl_voice"), models[0].voices)
        assertTrue(models[1].voices.isEmpty())
        assertEquals("/models?output_modalities=speech", server.takeRequest().path)
    }

    @Test fun requestsChosenModelVoiceSpeedAndSavesRawMp3() = runBlocking {
        val audio = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(audio)))
        val file = output()
        speech.generate("An answer in Polish: komórka.", "secret\u0000", settings, file)
        assertArrayEquals(audio, file.readBytes())
        assertFalse(File(file.parentFile, "${file.name}.partial").exists())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/audio/speech", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("provider/tts", body.getString("model"))
        assertEquals("voice-one", body.getString("voice"))
        assertEquals("mp3", body.getString("response_format"))
        assertEquals("An answer in Polish: komórka.", body.getString("input"))
        assertFalse(body.has("speed"))
    }

    @Test fun rejectsProviderErrorWithoutSavingOrExposingResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret upstream details"))
        val file = output()
        val failure = runCatching { speech.generate("Answer", "secret", settings, file) }.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("API key"))
        assertFalse(failure.message!!.contains("secret"))
        assertNoRecording(file)
    }

    @Test fun rejectsJsonSuccessResponseAndEmptyAudio() = runBlocking {
        for (response in listOf(
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}"),
            MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(""),
        )) {
            server.enqueue(response)
            val file = output()
            assertNotNull(runCatching { speech.generate("Answer", "secret", settings, file) }.exceptionOrNull())
            assertNoRecording(file)
        }
    }

    @Test fun missingSelectionFailsWithoutNetworkRequest() = runBlocking {
        assertNotNull(runCatching { speech.generate("Answer", "secret", AppSettings(), output()) }.exceptionOrNull())
        assertNotNull(runCatching { speech.generate("Answer", "secret", settings.copy(speechVoice = ""), output()) }.exceptionOrNull())
        assertEquals(0, server.requestCount)
        assertNoRecording(output())
    }

    @Test fun cancellationRemovesPartialAudio() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
            .setBody("delayed audio").setBodyDelay(3, TimeUnit.SECONDS))
        val file = output()
        val failure = runCatching { withTimeout(500) { speech.generate("Answer", "secret", settings, file) } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertNoRecording(file)
    }

    @Test fun geminiRequestsPcmAndProducesASeekableWaveContainer() = runBlocking {
        val pcm = byteArrayOf(0, 0, 1, 0, -1, 0, 0, 0)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(pcm)))
        val result = speech.generate("Answer", "secret", settings.copy(speechModelId = "google/gemini-3.8-flash-lite-tts"), output())
        assertEquals("wav", result.extension)
        val bytes = result.readBytes()
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
        val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(24000, header.getInt(24))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("pcm", body.getString("response_format"))
        assertFalse(body.has("speed"))
    }

    @Test fun structuredProviderErrorIsShownWithSensitiveValuesRedacted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"PCM is required. key=secret input=Answer sk-or-123"}}"""))
        val failure = runCatching { speech.generate("Answer", "secret", settings, output()) }.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("PCM is required"))
        assertFalse(failure.message!!.contains("secret"))
        assertFalse(failure.message!!.contains("Answer"))
        assertFalse(failure.message!!.contains("sk-or-123"))
    }
}
