package com.yunget.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory

class DesktopDownloadsTest {
    @Test
    fun nativeQueuePausesAcrossRestartAndRetriesSavingWithoutReplacingExistingFile() = runBlocking {
        assumeTrue(System.getProperty("yunget.native.bin") != null)
        loadDesktopNative()
        val root = createTempDirectory("yunget-desktop-test-").toFile()
        val data = ByteArray(768 * 1024) { index -> (index * 31 + (index ushr 5)).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/fixture.bin") { exchange -> serve(exchange, data) }
        server.start()
        try {
            val state = File(root, "state")
            val output = File(root, "fixture.bin")
            val source = "http://127.0.0.1:${server.address.port}/fixture.bin"
            val id = DesktopDownloads(state, allowLoopbackFixtures = true).use { client ->
                val id = client.submit(source, root, output.name, TransferKind.HTTP, 1, sha)
                withTimeout(10_000) { client.tasks.first { rows -> rows.single().downloaded in 1L until data.size.toLong() } }
                client.pause(id)
                withTimeout(10_000) { client.tasks.first { rows -> rows.single().status == "PAUSED" } }
                id
            }
            val occupied = "existing-file".toByteArray()
            output.writeBytes(occupied)
            DesktopDownloads(state, allowLoopbackFixtures = true).use { client ->
                client.resume(id, 1)
                val saved = withTimeout(15_000) {
                    client.tasks.first { rows -> rows.single().status == "READY_TO_SAVE" && rows.single().message.isNotBlank() }
                }.single()
                assertTrue(saved.partial.isFile)
                assertArrayEquals(occupied, output.readBytes())
                assertTrue(output.delete())
                client.resume(id, 1)
                withTimeout(3_000) { client.tasks.first { rows -> rows.single().status == "DONE" } }
                assertArrayEquals(data, output.readBytes())
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun serve(exchange: HttpExchange, data: ByteArray) {
        exchange.use {
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val begin = range?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(data.lastIndex) ?: data.lastIndex
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("ETag", "\"desktop-test\"")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $begin-$end/${data.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, (end - begin + 1).toLong())
            try {
                for (offset in begin..end step 4096) {
                    exchange.responseBody.write(data, offset, minOf(4096, end - offset + 1))
                    Thread.sleep(8)
                }
            } catch (_: IOException) {
                // The paused client closes the active response.
            }
        }
    }
}
