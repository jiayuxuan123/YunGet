package com.yunget.app.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class MediaTrackCancellationTest {
    @Test fun cancelInterruptsAnActualBlockedResponse() = runBlocking {
        val server = MockWebServer()
        val root = Files.createTempDirectory("media-track-cancel-").toFile()
        server.enqueue(MockResponse().setBody("x".repeat(1024)).setBodyDelay(5, TimeUnit.SECONDS))
        server.start()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        try {
            val job = async(Dispatchers.IO) {
                DownloadPostProcessor.fetch(server.url("/track").toString(),
                    emptyMap(), File(root, "audio.part"), client)
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertFalse(job.isCompleted)
            withTimeout(1_500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            root.deleteRecursively()
        }
    }
}
