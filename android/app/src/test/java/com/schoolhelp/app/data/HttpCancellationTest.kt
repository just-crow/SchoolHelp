package com.schoolhelp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpCancellationTest {
    @Test fun cancellationClosesAStalledResponseBodyPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow model download").setBodyDelay(3, TimeUnit.SECONDS))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val start = System.nanoTime()
            val failure = runCatching {
                withTimeout(500) {
                    withContext(Dispatchers.IO) { call.useResponse { it.body!!.string() } }
                }
            }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertTrue(call.isCanceled())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
        }
    }
}
