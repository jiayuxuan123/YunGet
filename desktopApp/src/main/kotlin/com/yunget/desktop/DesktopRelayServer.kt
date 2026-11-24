package com.yunget.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

internal data class DesktopRelayRequest(
    val url: String,
    val passcode: String = "",
    val fileName: String = "",
    val headers: Map<String, String> = emptyMap(),
    val mode: String = "resolve", // "resolve" | "download"
    val source: String = "", // "extension" | "android"
)

/**
 * 桌面端本地接管与局域网推送接收服务（内置 JDK HttpServer，无额外依赖）：
 * - 默认仅监听 `127.0.0.1`，供本机「云取链接助手」浏览器扩展一键发送链接；
 * - 开启 `allowLan` 后监听 `0.0.0.0`，手机端云取携带配对令牌即可局域网推送任务到电脑满速下载。
 */
internal class DesktopRelayServer(
    val port: Int,
    val allowLan: Boolean,
    private val token: String,
    private val onRequest: (DesktopRelayRequest) -> Unit,
) : Closeable {
    private val bindAddress = if (allowLan) InetSocketAddress("0.0.0.0", port) else InetSocketAddress("127.0.0.1", port)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "yunget-relay-server").apply { isDaemon = true }
    }
    private val server: HttpServer = HttpServer.create(bindAddress, 16).apply {
        executor = this@DesktopRelayServer.executor
        createContext("/ping", ::handlePing)
        createContext("/enqueue", ::handleEnqueue)
    }

    val boundPort: Int get() = server.address.port

    fun start() {
        server.start()
        DesktopLogStore.info("relay", "started", "bind=${bindAddress.hostString}:$boundPort allowLan=$allowLan")
    }

    override fun close() {
        runCatching { server.stop(0) }
        runCatching { executor.shutdownNow() }
    }

    private fun handlePing(exchange: HttpExchange) {
        addCorsHeaders(exchange)
        if (exchange.requestMethod.equals("OPTIONS", true)) {
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            return
        }
        val body = JSONObject()
            .put("ok", true)
            .put("app", "yunget-desktop")
            .put("allowLan", allowLan)
            .put("port", boundPort)
        sendJson(exchange, 200, body)
    }

    private fun handleEnqueue(exchange: HttpExchange) {
        addCorsHeaders(exchange)
        if (exchange.requestMethod.equals("OPTIONS", true)) {
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            return
        }
        if (!exchange.requestMethod.equals("POST", true)) {
            sendJson(exchange, 405, JSONObject().put("ok", false).put("error", "method_not_allowed"))
            return
        }
        val remoteAddr = exchange.remoteAddress?.address
        val isLoopback = remoteAddr != null && remoteAddr.isLoopbackAddress
        val rawBody = runCatching {
            val bytes = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
            if (bytes.size > MAX_BODY_BYTES) {
                sendJson(exchange, 413, JSONObject().put("ok", false).put("error", "payload_too_large"))
                return
            }
            String(bytes, StandardCharsets.UTF_8)
        }.getOrElse {
            sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "invalid_body"))
            return
        }
        val json = runCatching { JSONObject(rawBody) }.getOrElse {
            sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "invalid_json"))
            return
        }
        val providedToken = exchange.requestHeaders.getFirst("X-YunGet-Token")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: json.optString("token").trim()
        if (!isLoopback) {
            if (!allowLan || token.isBlank() || !constantTimeEquals(token, providedToken)) {
                sendJson(exchange, 401, JSONObject().put("ok", false).put("error", "unauthorized"))
                return
            }
        }
        val url = json.optString("url").trim()
        if (url.isEmpty() || url.length > 8192 || !isAllowedUrl(url)) {
            sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "invalid_url"))
            return
        }
        val headersObj = json.optJSONObject("headers")
        val headers = buildMap<String, String> {
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim()
                    if (size >= 24) break
                    val value = headersObj.optString(key).trim()
                    if (key.isNotEmpty() && value.isNotEmpty() &&
                        key.none { it == '\r' || it == '\n' } &&
                        value.none { it == '\r' || it == '\n' }
                    ) {
                        put(key, value)
                    }
                }
            }
        }
        val mode = when (json.optString("mode").trim().lowercase()) {
            "download" -> "download"
            else -> "resolve"
        }
        val request = DesktopRelayRequest(
            url = url,
            passcode = json.optString("passcode").trim().take(32),
            fileName = json.optString("fileName").trim().take(255),
            headers = headers,
            mode = mode,
            source = json.optString("source").trim().take(32),
        )
        runCatching { onRequest(request) }
            .onSuccess {
                sendJson(exchange, 200, JSONObject().put("ok", true).put("mode", mode))
            }
            .onFailure { err ->
                sendJson(exchange, 500, JSONObject().put("ok", false).put("error", err.message ?: "enqueue_failed"))
            }
    }

    private fun addCorsHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, X-YunGet-Token")
    }

    private fun sendJson(exchange: HttpExchange, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024

        private fun isAllowedUrl(url: String): Boolean {
            if (url.any { it.isISOControl() }) return false
            val lower = url.lowercase()
            return lower.startsWith("https://") ||
                lower.startsWith("http://") ||
                lower.startsWith("magnet:?") ||
                lower.startsWith("ftp://") ||
                lower.startsWith("sftp://")
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

        fun localLanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map(InetAddress::getHostAddress)
                .distinct()
        }.getOrDefault(emptyList())
    }
}
