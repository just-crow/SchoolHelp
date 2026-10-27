package com.schoolhelp.app.ai

import java.util.Base64
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.schoolhelp.app.core.Solution
import com.schoolhelp.app.core.ApiKey
import com.schoolhelp.app.core.SolutionParser
import com.schoolhelp.app.data.AppSettings
import com.schoolhelp.app.data.useResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalProblemSolver {
    // Keep GPU creation, inference, and destruction on one thread.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    suspend fun solve(model: File, images: List<File>, cache: File, settings: AppSettings): Solution = withContext(dispatcher) {
        require(images.isNotEmpty())
        val engine = Engine(EngineConfig(
            modelPath = model.absolutePath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            cacheDir = cache.absolutePath,
            maxNumTokens = 32768,
            maxNumImages = images.size,
        ))
        try {
            engine.initialize()
            currentCoroutineContext().ensureActive()
            engine.createConversation(ConversationConfig()).use { conversation ->
                val result = StringBuilder()
                val content = mutableListOf<Content>(Content.Text(SolutionParser.prompt(settings.outputLanguage, settings.explanations)))
                if (settings.context.isNotBlank()) content += Content.Text("Additional context from the user (use for solving, do not read this note aloud):\n${settings.context.trim().take(8000)}")
                images.forEachIndexed { index, image ->
                    content += Content.Text("Page ${index + 1} of ${images.size}")
                    content += Content.ImageFile(image.absolutePath)
                }
                conversation.sendMessageAsync(Contents.of(*content.toTypedArray()), extraContext = mapOf("enable_thinking" to false)).collect { result.append(it.toString()) }
                var solution = SolutionParser.parse(result.toString())
                if (SolutionParser.needsSpokenRewrite(solution)) {
                    result.clear()
                    conversation.sendMessageAsync(Contents.of(Content.Text(SolutionParser.rewritePrompt(settings.outputLanguage, settings.explanations))),
                        extraContext = mapOf("enable_thinking" to false)).collect { result.append(it.toString()) }
                    solution = SolutionParser.parse(result.toString())
                }
                SolutionParser.requireListeningText(solution)
            }
        } finally {
            if (engine.isInitialized()) engine.close()
        }
    }

    fun close() { dispatcher.close() }
}

data class RemoteModel(val id: String, val name: String)

class OpenRouterSolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://openrouter.ai/api/v1/",
) {
    suspend fun models(): List<RemoteModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl}models").build()
        client.newCall(request).useResponse { response ->
            check(response.isSuccessful) { "Could not load OpenRouter models (${response.code})." }
            val data = JSONObject(response.body?.string() ?: error("Empty model list.")).getJSONArray("data")
            (0 until data.length()).mapNotNull { i ->
                val item = data.getJSONObject(i)
                val modalities = item.optJSONObject("architecture")?.optJSONArray("input_modalities")
                if (modalities != null && (0 until modalities.length()).any { modalities.optString(it) == "image" })
                    RemoteModel(item.getString("id"), item.optString("name", item.getString("id")))
                else null
            }.sortedBy { it.name.lowercase() }
        }
    }

    suspend fun testKey(key: String) = withContext(Dispatchers.IO) {
        val cleanKey = ApiKey.normalize(key)
        client.newCall(Request.Builder().url("${baseUrl}key").header("Authorization", "Bearer $cleanKey").build())
            .useResponse { response -> checkStatus(response.code) }
    }

    suspend fun solve(image: File, key: String, settings: AppSettings): Solution = solve(listOf(image), key, settings)

    suspend fun solve(images: List<File>, key: String, settings: AppSettings): Solution = withContext(Dispatchers.IO) {
        require(images.isNotEmpty())
        val cleanKey = ApiKey.normalize(key)
        require(settings.modelId.isNotBlank()) { "Select an OpenRouter vision model in settings." }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Read all attached pages together in the supplied order. Solve each complete question once, joining any continuation on later pages. Follow the listening-only instructions."))
        if (settings.context.isNotBlank()) content.put(JSONObject().put("type", "text").put("text",
            "Additional context from the user (use for solving, do not read this note aloud):\n${settings.context.trim().take(8000)}"))
        images.forEachIndexed { index, image ->
            currentCoroutineContext().ensureActive()
            content.put(JSONObject().put("type", "text").put("text", "Page ${index + 1} of ${images.size}"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url",
                    "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image.readBytes())}")))
        }
        val body = JSONObject().put("model", settings.modelId)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SolutionParser.prompt(settings.outputLanguage, settings.explanations)))
                .put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", 0.2)
        suspend fun complete(): String {
            val request = Request.Builder().url("${baseUrl}chat/completions")
                .header("Authorization", "Bearer $cleanKey").header("X-Title", "SchoolHelp")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return client.newCall(request).useResponse { response ->
                checkStatus(response.code)
                val json = JSONObject(response.body?.string() ?: error("The model returned no answer."))
                val choice = json.getJSONArray("choices").getJSONObject(0)
                choice.getJSONObject("message").getString("content")
            }
        }
        val raw = complete()
        var solution = SolutionParser.parse(raw)
        if (SolutionParser.needsSpokenRewrite(solution)) {
            body.getJSONArray("messages").put(JSONObject().put("role", "assistant").put("content", raw))
                .put(JSONObject().put("role", "user").put("content", SolutionParser.rewritePrompt(settings.outputLanguage, settings.explanations)))
            solution = SolutionParser.parse(complete())
        }
        SolutionParser.requireListeningText(solution)
    }

    private fun checkStatus(code: Int) {
        check(code in 200..299) {
            when (code) {
                401, 403 -> "OpenRouter rejected the API key. Check it in settings."
                402 -> "Your OpenRouter account needs credits."
                429 -> "OpenRouter is busy or your rate limit was reached. Try again shortly."
                else -> "OpenRouter request failed ($code). Try again or choose another model."
            }
        }
    }
}
