package com.schoolhelp.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Keep cancellation connected to the socket while consuming a streaming response body. */
suspend fun <T> Call.useResponse(block: suspend (Response) -> T): T = coroutineScope {
    val request = this@useResponse
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { request.cancel() }
    }
    try {
        request.await().use { block(it) }
    } catch (e: IOException) {
        ensureActive()
        throw e
    } finally {
        cancellation.cancel()
    }
}

private val openRouterGenerationLock = Mutex()

/** Share one gate across chat, speech and transcription, through the end of the response body. */
internal suspend fun <T> Call.useOpenRouterGenerationResponse(block: suspend (Response) -> T): T =
    openRouterGenerationLock.withLock { useResponse(block) }

/** Use only known error codes so upstream messages cannot expose request details. */
internal fun Response.openRouterCreditMessage(operation: String): String {
    val metadata = runCatching {
        org.json.JSONObject(peekBody(8192).string()).optJSONObject("error")?.optJSONObject("metadata")
    }.getOrNull()
    return when {
        metadata?.optString("limit_source") == "openrouter_key_limit" ->
            "The OpenRouter key reached its spending limit. Check this key's limit in OpenRouter."
        metadata?.optString("limit_source") == "openrouter_in_flight_budget" ||
            metadata?.optString("reason") == "in_flight_budget_exhausted" ->
            {
                val retryAfter = header("Retry-After")?.toLongOrNull()?.takeIf { it > 0 }
                val retry = retryAfter?.let { "Try again in $it seconds." } ?: "Try again shortly."
                "OpenRouter blocked $operation with a temporary spending limit (402). $retry"
            }
        else -> "OpenRouter rejected the request for budget reasons (402). Check the saved key's spending limit and your OpenRouter balance."
    }
}
