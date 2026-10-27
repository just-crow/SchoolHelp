package com.schoolhelp.app.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import com.schoolhelp.app.data.AppSettings
import java.io.File

class OpenRouterSolverTest {
    private val server = MockWebServer()
    private lateinit var solver: OpenRouterSolver
    @Before fun setup() { server.start(); solver = OpenRouterSolver(OkHttpClient(), server.url("/").toString()) }
    @After fun teardown() { server.shutdown() }

    @Test fun contextIsIncludedAsUserTextWithThePhotos() = runBlocking {
        val answer = """{"problems":[{"number":"1","answer":"Answer.","spokenText":"Answer."}]}"""
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)))).toString()))
        val page = File.createTempFile("context-paper", ".jpg")
        try {
            page.writeText("paper")
            solver.solve(listOf(page), "test-secret", AppSettings(modelId = "fixture/vision", context = "Use definitions from chapter four."))
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertFalse(messages.getJSONObject(0).getString("content").contains("chapter four"))
            val content = messages.getJSONObject(1).getJSONArray("content")
            assertTrue(content.getJSONObject(1).getString("text").contains("Use definitions from chapter four."))
            assertEquals("Page 1 of 1", content.getJSONObject(2).getString("text"))
            assertEquals("image_url", content.getJSONObject(3).getString("type"))
        } finally { page.delete() }
    }

    @Test fun onlyOffersImageCapableModels() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[
            {"id":"text-only","name":"Text","architecture":{"input_modalities":["text"]}},
            {"id":"vision-b","name":"Bravo","architecture":{"input_modalities":["text","image"]}},
            {"id":"vision-a","name":"Alpha","architecture":{"input_modalities":["image"]}},
            {"id":"unknown","name":"Unknown"}]}"""))
        assertEquals(listOf("vision-a", "vision-b"), solver.models().map { it.id })
        assertEquals("/models", server.takeRequest().path)
    }

    @Test fun continuedQuestionSendsAllPagesTogetherInOrderAndKeepsStartingPage() = runBlocking {
        val problem = JSONObject().put("number", "6").put("page", 1)
            .put("question", "Explain photosynthesis and name its products.")
            .put("answer", "Plants use light to make sugar and release oxygen.")
            .put("spokenText", "Write that plants use light to make sugar and release oxygen.")
        val response = JSONObject().put("problems", JSONArray().put(problem)).toString()
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop").put("message", JSONObject().put("content", response)))).toString()))
        val pages = listOf(File.createTempFile("page-one", ".jpg"), File.createTempFile("page-two", ".jpg"))
        try {
            pages[0].writeText("Question six starts here")
            pages[1].writeText("Question six continues here")
            val solution = solver.solve(pages, "test-secret", AppSettings(modelId = "test/vision"))
            assertEquals(1, server.requestCount)
            assertEquals(1, solution.problems.single().page)
            assertTrue(solution.problems.single().question.contains("products"))
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertTrue(messages.getJSONObject(0).getString("content").contains("Join its parts across pages"))
            val content = messages.getJSONObject(1).getJSONArray("content")
            assertEquals("Page 1 of 2", content.getJSONObject(1).getString("text"))
            assertEquals("Page 2 of 2", content.getJSONObject(3).getString("text"))
            for (i in pages.indices) assertEquals("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(pages[i].readBytes()),
                content.getJSONObject(2 + i * 2).getJSONObject("image_url").getString("url"))
        } finally { pages.forEach { it.delete() } }
    }

    @Test fun testsKeyWithAuthorizationHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{}}"))
        solver.testKey("test-secret")
        val request = server.takeRequest()
        assertEquals("/key", request.path)
        assertEquals("Bearer test-secret", request.getHeader("Authorization"))
    }

    @Test fun clipboardPaddingIsRemovedBeforeBuildingAuthorizationHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{}}"))
        solver.testKey("\uFEFF \r\ntest-secret\u0000\u200B\n")
        assertEquals("Bearer test-secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun internalControlCharactersAreRejectedWithoutExposingKey() = runBlocking {
        for (badKey in listOf("test-\u0000secret", "test-\r\nsecret", "test secret", "test-\u200Bsecret", "test-\u00E9secret")) {
            val error = runCatching { solver.testKey(badKey) }.exceptionOrNull()!!
            assertTrue(error is IllegalArgumentException)
            assertTrue(error.message!!.contains("Copy the key again"))
            assertFalse(error.message!!.contains("test-"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun authenticationFailureDoesNotExposeKeyOrResponseBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Sensitive upstream detail"))
        val error = runCatching { solver.testKey("test-secret") }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("API key"))
        assertFalse(error.message!!.contains("test-secret"))
        assertFalse(error.message!!.contains("Sensitive"))
    }

    @Test fun emptyKeyFailsBeforeSendingAnyRequest() = runBlocking {
        assertTrue(runCatching { solver.testKey("") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    private fun answer(script: String): MockResponse {
        val problem = JSONObject().put("number", "1").put("answer", "One half.").put("spokenText", script)
        val content = JSONObject().put("problems", JSONArray().put(problem)).toString()
        return MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString())
    }

    @Test fun visualNotationGetsOneListeningOnlyRewrite() = runBlocking {
        server.enqueue(answer("\\frac{1}{2}"))
        server.enqueue(answer("Write one half. Put one above the fraction bar and two below it."))
        val image = File.createTempFile("worksheet", ".jpg")
        try {
            val result = solver.solve(image, "test-secret", AppSettings(modelId = "test/vision"))
            assertTrue(result.speech(false, "en").contains("above the fraction bar"))
            server.takeRequest()
            val retry = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(4, retry.length())
            assertEquals("assistant", retry.getJSONObject(2).getString("role"))
            assertEquals("user", retry.getJSONObject(3).getString("role"))
            assertEquals(2, server.requestCount)
        } finally { image.delete() }
    }

    @Test fun repeatedFormattingStopsAfterOneRewriteInsteadOfSendingItToSpeech() = runBlocking {
        server.enqueue(answer("1/2"))
        server.enqueue(answer("1/2"))
        val image = File.createTempFile("worksheet", ".jpg")
        try {
            val failure = runCatching { solver.solve(image, "test-secret", AppSettings(modelId = "test/vision")) }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("visual notation"))
            assertEquals(2, server.requestCount)
        } finally { image.delete() }
    }

    @Test fun plainSpeechDoesNotTriggerAnotherModelRequest() = runBlocking {
        server.enqueue(answer("Write one half."))
        val image = File.createTempFile("worksheet", ".jpg")
        try {
            solver.solve(image, "test-secret", AppSettings(modelId = "test/vision"))
            assertEquals(1, server.requestCount)
        } finally { image.delete() }
    }
}
