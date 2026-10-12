package com.schoolhelp.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
