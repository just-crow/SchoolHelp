package com.schoolhelp.app.audio

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTranscriptionTest {
    @Test fun sendsEachSelectedLanguageAndReturnsOriginalText() = runBlocking {
        MockWebServer().use { server ->
            val client = OpenRouterTranscription(OkHttpClient(), server.url("/audio/transcriptions").toString())
            val samples = listOf(
                Triple(RecordingLanguage.ENGLISH, "en", "What is a cell?"),
                Triple(RecordingLanguage.GERMAN, "de", "Was ist eine Zelle?"),
                Triple(RecordingLanguage.BOSNIAN, "bs", "Šta je ćelija?"),
            )
            for ((language, code, transcript) in samples) {
                server.enqueue(MockResponse().setBody(JSONObject().put("text", transcript).toString()))
                assertEquals(transcript, client.transcribe(byteArrayOf(1, 2, 3), "m4a", language, "test-key"))
                val request = server.takeRequest()
                assertEquals("/audio/transcriptions", request.path)
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals("openai/whisper-large-v3", body.getString("model"))
                assertEquals(code, body.getString("language"))
                assertEquals("m4a", body.getJSONObject("input_audio").getString("format"))
                assertEquals("AQID", body.getJSONObject("input_audio").getString("data"))
            }
        }
    }

    @Test fun emptyTranscriptReportsNoSpeech() = runBlocking {
        MockWebServer().use { server ->
            val client = OpenRouterTranscription(OkHttpClient(), server.url("/audio/transcriptions").toString())
            server.enqueue(MockResponse().setBody("""{"text":"  "}"""))
            val result = runCatching { client.transcribe(byteArrayOf(1), "wav", RecordingLanguage.ENGLISH, "test-key") }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("No speech"))
        }
    }
}
