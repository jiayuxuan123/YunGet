/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 空闲连接回收（[HttpClients.evictIdleConnections]）的契约测试。
 *
 * ## 被钉住的真实崩溃（用户实测，2.6.18）
 *
 * ```
 * android.os.NetworkOnMainThreadException
 *   at HttpClients.evictIdleConnections(HttpClients.kt:75)
 *   at YunGetApp.onTrimMemory(YunGetApp.kt:47)
 * ```
 *
 * `onTrimMemory` 是**主线程**回调，而 `evictAll()` 会真的 close 每条空闲连接的 socket
 * （`RealConnectionPool.evictAll` → `Util.closeQuietly(socket)`）；关闭 TLS 连接要写
 * `close_notify`，是一次网络写，于是 Android 抛异常。OkHttp 的 `closeQuietly` 只吞
 * `IOException`，`NetworkOnMainThreadException` 是 `RuntimeException`，**原样穿透**把应用崩掉。
 *
 * ## ⚠️ 本测试能证明什么、不能证明什么
 *
 * `NetworkOnMainThreadException` 是 **Android 运行时专有**的检查：桌面 JVM 上在任何线程
 * 做阻塞 I/O 都不会抛它。所以本测试**无法复现**那条异常 —— 真正复现需要真机 instrumentation。
 *
 * 它能守住的是修复后的**行为契约**：
 *  1. 从「模拟主线程」（名为 simulated-main 的线程）调用不抛错、不挂住调用线程；
 *  2. **回收真的发生了**（两套客户端的空闲连接都被关掉）—— 上游那种"整体 try/catch"的写法
 *     在 Android 上会让回收**从未发生**（异常被吞、语句根本没执行完）；
 *  3. 反复调用不会把回收"闸住"（内部有在飞闸门，若它忘了复位，后续回收会被永久跳过）。
 *
 * 「回收不在调用线程执行」由实现结构保证（内部 `Thread(...)` 派发），不是本测试能证明的。
 */
class IdleConnectionEvictTest {

    /**
     * 极简本地 HTTP 服务器：每个请求回一个固定响应后**保持连接**（Connection: keep-alive），
     * 这样客户端用完会把连接还回连接池，成为可被回收的**空闲连接**。
     *
     * 刻意不用 `com.sun.net.httpserver`：Android 单测的类路径是 `android.jar`，没有那个包。
     * 只用 `java.net`，两边都能跑。本测试全程只连 127.0.0.1，不访问外网。
     */
    private class TinyServer {
        private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        private val workers = mutableListOf<Thread>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "tiny-http-accept") {
                while (running.get()) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    workers += thread(isDaemon = true, name = "tiny-http-conn") { serve(sock) }
                }
            }
        }

        /** 一个连接上可承载多次请求（keep-alive）；客户端关连接时退出。 */
        private fun serve(sock: Socket) {
            try {
                val input = sock.getInputStream().buffered()
                val output = sock.getOutputStream()
                while (running.get() && !sock.isClosed) {
                    // 读到请求头结束（空行）为止 —— 请求体我们不关心。
                    var line = input.readUTFLine() ?: return
                    while (line.isNotEmpty()) {
                        line = input.readUTFLine() ?: return
                    }
                    val body = ByteArray(64)
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: keep-alive\r\n" +
                                "\r\n"
                            ).toByteArray(Charsets.ISO_8859_1)
                    )
                    output.write(body)
                    output.flush()
                }
            } catch (_: Throwable) {
                // 连接被对端关闭（正是回收做的事）—— 正常路径。
            } finally {
                runCatching { sock.close() }
            }
        }

        /** 按 CRLF 读一行；流结束返回 null。 */
        private fun java.io.InputStream.readUTFLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        fun stop() {
            running.set(false)
            runCatching { server.close() }
        }
    }

    private lateinit var srv: TinyServer

    @Before
    fun setUp() {
        srv = TinyServer()
    }

    @After
    fun tearDown() {
        srv.stop()
    }

    /** 发一次请求并读完响应体 → 连接归还到连接池、成为**空闲**连接。 */
    private fun warmOneIdleConnection(client: OkHttpClient) {
        val req = Request.Builder().url("http://127.0.0.1:${srv.port}/f.bin").build()
        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body?.bytes()
        }
    }

    /** 在一条名为 simulated-main 的线程上调用回收（模拟 Android 的 Application 主线程回调）。 */
    private fun evictFromSimulatedMainThread() {
        var thrown: Throwable? = null
        val t = Thread({ runCatching { HttpClients.evictIdleConnections() }.onFailure { thrown = it } },
            "simulated-main")
        t.start()
        t.join(5_000)
        assertTrue("回收调用不应把调用线程挂住", !t.isAlive)
        if (thrown != null) throw AssertionError("回收不应向调用方抛错", thrown)
    }

    /** 轮询等待两套客户端的空闲连接都被清空（回收在后台线程上，需要等它跑完）。 */
    private fun awaitDrained(api: OkHttpClient, download: OkHttpClient, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (api.connectionPool.idleConnectionCount() == 0 &&
                download.connectionPool.idleConnectionCount() == 0
            ) return
            Thread.sleep(50)
        }
        assertEquals(
            "apiClient 的空闲连接应被回收",
            0, api.connectionPool.idleConnectionCount()
        )
        assertEquals(
            "downloadClient 的空闲连接应被回收",
            0, download.connectionPool.idleConnectionCount()
        )
    }

    @Test
    fun evictDrainsIdleConnectionsOfBothClients() {
        val api = HttpClients.apiClient()
        val download = HttpClients.downloadClient()

        // 先各自制造一条真实的空闲连接 —— 没有连接时这个测试等于没测。
        warmOneIdleConnection(api)
        warmOneIdleConnection(download)
        assertTrue("apiClient 应有空闲连接", api.connectionPool.idleConnectionCount() >= 1)
        assertTrue("downloadClient 应有空闲连接", download.connectionPool.idleConnectionCount() >= 1)

        evictFromSimulatedMainThread()

        // 关键断言：回收**真的发生了**（而不是被静默吞掉）。两套客户端都要清空 ——
        // 若第一句抛错导致第二句被跳过，downloadClient 的断言就会失败。
        awaitDrained(api, download)
    }

    @Test
    fun repeatedEvictIsSafeAndStillDrains() {
        val api = HttpClients.apiClient()
        val download = HttpClients.downloadClient()

        // 内存告急时 onTrimMemory 会被系统反复回调，闸门必须能复位；
        // 若它卡在"在飞"状态，后续回收会被永久跳过 —— 下面的第二次断言就会失败。
        repeat(50) { HttpClients.evictIdleConnections() }
        Thread.sleep(200)

        warmOneIdleConnection(api)
        warmOneIdleConnection(download)
        assertTrue("apiClient 应有空闲连接", api.connectionPool.idleConnectionCount() >= 1)

        evictFromSimulatedMainThread()
        awaitDrained(api, download)
    }

    /** 回收线程的名字（与实现里的 `Thread(..., "yunget-idle-evict")` 一致）。 */
    private val evictThreadName = "yunget-idle-evict"

    /** 当前活着的线程里是否有叫这个名字的（走 ThreadGroup 枚举，比 getAllStackTraces 便宜得多）。 */
    private fun evictWorkerAlive(): Boolean {
        var group: ThreadGroup? = Thread.currentThread().threadGroup
        while (group?.parent != null) group = group.parent
        val arr = arrayOfNulls<Thread>(group?.activeCount()?.plus(16) ?: 64)
        val n = group?.enumerate(arr, true) ?: 0
        return (0 until n).any { arr[it]?.name == evictThreadName }
    }

    /**
     * 【结构契约】回收必须发生在**它自己的线程**上，而不是调用方线程。
     *
     * 这是整条修复的要点：`onTrimMemory` 在主线程回调，只要 close socket 这件事还在调用方
     * 线程上做，真机就会抛 `NetworkOnMainThreadException`（桌面 JVM 不抛，所以别的测试守不住它）。
     *
     * 判据：触发回收后，进程里应能观察到名为 `yunget-idle-evict` 的线程。
     * **同步实现永远不会有这条线程** → 这条断言会失败（即：它能抓住"改回同步"的回退）。
     * 反过来说，只要实现保持"派发到具名后台线程"，这条断言就成立。
     *
     * 为了让 worker 活得够久、便于采样，每轮先制造若干真实空闲连接。
     */
    @Test
    fun evictionRunsOnItsOwnThreadNotTheCallers() {
        val download = HttpClients.downloadClient()
        // 多轮尝试：worker 很快（关闭本机回环 socket 是微秒级），单轮采样可能恰好错过。
        // 同步实现则**任何一轮都不可能观察到** —— 判据因此是可靠的。
        repeat(40) {
            repeat(6) { warmOneIdleConnection(download) }
            evictFromSimulatedMainThread()
            repeat(30) {
                if (evictWorkerAlive()) return
            }
            awaitDrained(HttpClients.apiClient(), download)
            Thread.sleep(2)
        }
        throw AssertionError(
            "从未观察到名为 '$evictThreadName' 的回收线程：回收很可能又回到调用方线程上执行了" +
                "（那正是 2.6.18 主线程 NetworkOnMainThreadException 的成因）"
        )
    }
}
