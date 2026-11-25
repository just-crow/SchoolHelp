package com.schoolhelp.app.audio

import com.schoolhelp.app.data.AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class OpenRouterRequestAuditTest {
    @get:Rule val files = TemporaryFolder()
    private val settings = AppSettings(speechModelId = "fixture/tts", speechVoice = "voice")

    @Test fun segmentedSpeechWaitsThenRetriesOnlyTheRejectedSegment() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(402).setHeader("Retry-After", "1")
                    .setBody("""{"error":{"metadata":{"limit_source":"openrouter_in_flight_budget"}}}"""))
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("test audio"))
            val service = OpenRouterSpeech(OkHttpClient(), server.url("/").toString())
            val start = System.nanoTime()
            val clip = service.generate("Segment three.", "fixture", settings, files.root.resolve("clip.mp3"),
                retryTemporaryBudget = true)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 2000L)
            assertEquals("test audio", clip.readText())
            assertEquals(3, server.requestCount)
            val original = server.takeRequest().body.readUtf8()
            repeat(2) { assertEquals(original, server.takeRequest().body.readUtf8()) }
        }
    }

    @Test fun speechAndTranscriptionCannotOverlapWhileAudioIsDownloading() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            val speech = OpenRouterSpeech(client, server.url("/").toString())
            val transcription = OpenRouterTranscription(client, server.url("/audio/transcriptions").toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
                .setBody("test audio").setBodyDelay(1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("""{"text":"Test transcript"}"""))
            val first = async { speech.generate("Test speech.", "fixture", settings, files.root.resolve("clip.mp3")) }
            yield()
            assertEquals("/audio/speech", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val second = async { transcription.transcribe(byteArrayOf(1), "wav", RecordingLanguage.ENGLISH, "fixture") }
            yield()
            assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertTrue(first.await().isFile)
            assertEquals("Test transcript", second.await())
            assertEquals("/audio/transcriptions", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        }
    }

    @Test fun freshFirstRequestCanReturnBudgetErrorWithoutALocalOverlap() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(402).setHeader("Retry-After", "12")
                .setBody("""{"error":{"metadata":{"limit_source":"openrouter_in_flight_budget"}}}"""))
            val service = OpenRouterSpeech(OkHttpClient(), server.url("/").toString())
            val clip = files.root.resolve("rejected.mp3")
            val failure = runCatching { service.generate("Test speech.", "fixture", settings, clip) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("speech generation"))
            assertTrue(failure?.message.orEmpty().contains("12 seconds"))
            assertEquals(1, server.requestCount)
            assertFalse(clip.exists())
        }
    }

    @Test fun droppedAudioResponseLeavesNoPartialFileOrAutomaticSecondRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg")
                .setBody("test audio".repeat(2000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val service = OpenRouterSpeech(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/").toString())
            val clip = files.root.resolve("dropped.mp3")
            val failure = runCatching { service.generate("Test speech.", "fixture", settings, clip) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(1, server.requestCount)
            assertFalse(clip.exists())
            assertFalse(files.root.resolve("dropped.mp3.partial").exists())
        }
    }
}
