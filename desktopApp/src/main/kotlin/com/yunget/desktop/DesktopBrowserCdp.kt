package com.yunget.desktop

import com.yunget.app.data.network.BaiduConstants
import com.yunget.app.data.network.C139Constants
import com.yunget.app.data.network.Pan115Constants
import com.yunget.app.data.network.Pan123Constants
import com.yunget.app.data.network.QuarkConstants
import com.yunget.app.data.network.UCConstants
import com.yunget.app.data.network.XunleiWebCredential
import com.yunget.app.data.network.video.BrowserPageExtractor
import com.yunget.app.data.network.video.VideoPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class DesktopBrowserExecutable(
    val file: File,
    val displayName: String,
)

internal object DesktopBrowserLocator {
    fun findBrowser(): DesktopBrowserExecutable? {
        val override = System.getProperty("yunget.browser.exe")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("YUNGET_BROWSER_EXE")?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            val f = File(override)
            if (f.isFile) {
                val label = if (f.name.contains("edge", ignoreCase = true)) "Microsoft Edge" else "Chromium 浏览器"
                return DesktopBrowserExecutable(f, label)
            }
        }

        val programFiles = System.getenv("ProgramFiles").orEmpty()
        val programFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()

        val candidates = buildList {
            if (programFilesX86.isNotBlank()) {
                add(File(programFilesX86, "Microsoft\\Edge\\Application\\msedge.exe") to "Microsoft Edge")
            }
            if (programFiles.isNotBlank()) {
                add(File(programFiles, "Microsoft\\Edge\\Application\\msedge.exe") to "Microsoft Edge")
            }
            if (localAppData.isNotBlank()) {
                add(File(localAppData, "Microsoft\\Edge\\Application\\msedge.exe") to "Microsoft Edge")
            }
            if (programFiles.isNotBlank()) {
                add(File(programFiles, "Google\\Chrome\\Application\\chrome.exe") to "Google Chrome")
            }
            if (programFilesX86.isNotBlank()) {
                add(File(programFilesX86, "Google\\Chrome\\Application\\chrome.exe") to "Google Chrome")
            }
            if (localAppData.isNotBlank()) {
                add(File(localAppData, "Google\\Chrome\\Application\\chrome.exe") to "Google Chrome")
            }
        }
        return candidates.firstOrNull { it.first.isFile }?.let { (file, name) ->
            DesktopBrowserExecutable(file, name)
        }
    }

    fun allocateLocalPort(): Int =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    fun cleanupStaleTempProfiles(dataDirectory: File) {
        val tempRoot = File(dataDirectory, "browser-temp")
        if (!tempRoot.isDirectory) return
        tempRoot.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
    }
}

/**
 * Dedicated loopback OkHttp client bypassing system/user proxies for local CDP HTTP & WebSocket calls.
 */
internal object LocalCdpHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

internal data class CdpTargetInfo(
    val id: String,
    val type: String,
    val url: String,
    val webSocketDebuggerUrl: String,
)

internal data class CdpCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
)

/**
 * Minimal Chrome DevTools Protocol client built on OkHttp WebSocket.
 */
internal class CdpClient private constructor(
    private val webSocket: WebSocket,
    private val pending: ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>,
) : Closeable {
    private val nextId = AtomicInteger(1)
    @Volatile
    private var closed = false

    suspend fun send(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 10_000L,
    ): JSONObject {
        check(!closed) { "CDP 会话已关闭" }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val payload = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        if (!webSocket.send(payload)) {
            pending.remove(id)
            error("CDP 命令发送失败: $method")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    suspend fun evaluateRaw(
        expression: String,
        awaitPromise: Boolean = false,
        timeoutMs: Long = 10_000L,
    ): Any? {
        val params = JSONObject()
            .put("expression", expression)
            .put("returnByValue", true)
            .put("awaitPromise", awaitPromise)
        val response = send("Runtime.evaluate", params, timeoutMs)
        if (response.has("exceptionDetails")) return null
        val resultObj = response.optJSONObject("result") ?: return null
        if (resultObj.optString("type") == "undefined") return null
        val value = resultObj.opt("value")
        return if (value === JSONObject.NULL) null else value
    }

    suspend fun getAllCookies(timeoutMs: Long = 10_000L): List<CdpCookie> {
        val response = runCatching { send("Network.getAllCookies", JSONObject(), timeoutMs) }
            .recoverCatching { send("Storage.getCookies", JSONObject(), timeoutMs) }
            .getOrThrow()
        val arr = response.optJSONArray("cookies") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                add(
                    CdpCookie(
                        name = name,
                        value = obj.optString("value"),
                        domain = obj.optString("domain"),
                        path = obj.optString("path", "/"),
                    )
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val err = IllegalStateException("CDP 会话已关闭")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        runCatching { webSocket.close(1000, "done") }
        runCatching { webSocket.cancel() }
    }

    companion object {
        private fun isInternalBrowserPage(url: String): Boolean {
            val lower = url.trim().lowercase()
            return lower.startsWith("edge://") ||
                lower.startsWith("chrome://") ||
                lower.startsWith("chrome-extension://") ||
                lower.startsWith("devtools://")
        }

        fun isWebPageTarget(target: CdpTargetInfo): Boolean =
            target.type == "page" && target.webSocketDebuggerUrl.isNotBlank() && !isInternalBrowserPage(target.url)

        suspend fun fetchBrowserWebSocketUrl(port: Int): String? = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("http://127.0.0.1:$port/json/version")
                .get()
                .build()
            runCatching {
                LocalCdpHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    obj.optString("webSocketDebuggerUrl").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }

        suspend fun closeBrowser(port: Int) {
            val wsUrl = fetchBrowserWebSocketUrl(port) ?: return
            var client: CdpClient? = null
            try {
                client = connect(wsUrl, timeoutMs = 2_000L)
                runCatching { client.send("Browser.close", JSONObject(), timeoutMs = 2_000L) }
            } catch (_: Throwable) {
                // Best-effort graceful browser shutdown via CDP.
            } finally {
                runCatching { client?.close() }
            }
        }

        suspend fun listTargets(port: Int): List<CdpTargetInfo> = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("http://127.0.0.1:$port/json/list")
                .get()
                .build()
            LocalCdpHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val wsUrl = obj.optString("webSocketDebuggerUrl")
                        if (wsUrl.isBlank()) continue
                        add(
                            CdpTargetInfo(
                                id = obj.optString("id"),
                                type = obj.optString("type"),
                                url = obj.optString("url"),
                                webSocketDebuggerUrl = wsUrl,
                            )
                        )
                    }
                }
            }
        }

        suspend fun waitForPageTarget(
            port: Int,
            process: Process,
            timeoutMs: Long = 12_000L,
        ): CdpTargetInfo? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive && runCatching { process.exitValue() }.getOrDefault(0) != 0) {
                    return null
                }
                val targets = runCatching { listTargets(port) }.getOrDefault(emptyList())
                val page = targets.firstOrNull(::isWebPageTarget)
                if (page != null) return page
                delay(150)
            }
            return null
        }

        suspend fun connect(wsUrl: String, timeoutMs: Long = 8_000L): CdpClient {
            val openDeferred = CompletableDeferred<Unit>()
            val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
            val req = Request.Builder().url(wsUrl).build()
            val ws = LocalCdpHttp.wsClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openDeferred.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (!json.has("id")) return
                    val id = json.optInt("id", -1)
                    val deferred = pending.remove(id) ?: return
                    if (json.has("error")) {
                        val errObj = json.optJSONObject("error")
                        val msg = errObj?.optString("message").orEmpty().ifBlank { "CDP error" }
                        deferred.completeExceptionally(IllegalStateException(msg))
                    } else {
                        deferred.complete(json.optJSONObject("result") ?: JSONObject())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!openDeferred.isCompleted) openDeferred.completeExceptionally(t)
                    pending.values.forEach { it.completeExceptionally(t) }
                    pending.clear()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val err = IllegalStateException("CDP WebSocket closed: $code $reason")
                    if (!openDeferred.isCompleted) openDeferred.completeExceptionally(err)
                    pending.values.forEach { it.completeExceptionally(err) }
                    pending.clear()
                }
            })
            try {
                withTimeout(timeoutMs) { openDeferred.await() }
            } catch (t: Throwable) {
                runCatching { ws.cancel() }
                throw t
            }
            return CdpClient(ws, pending)
        }
    }
}

internal fun terminateBrowserProcess(process: Process?, profileDir: File?) {
    if (process != null) {
        runCatching {
            process.toHandle().descendants().forEach { child ->
                runCatching { child.destroy() }
            }
        }
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach { child ->
                    runCatching { child.destroyForcibly() }
                }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }
    if (profileDir != null && profileDir.exists()) {
        repeat(5) { attempt ->
            if (!profileDir.exists()) return
            val deleted = runCatching { profileDir.deleteRecursively() }.getOrDefault(false)
            if (deleted || !profileDir.exists()) return
            if (attempt < 4) runCatching { Thread.sleep(200) }
        }
    }
}

/**
 * Headless Edge/Chrome CDP implementation of [BrowserPageExtractor] for Douyin / Kuaishou / XHS.
 */
internal class DesktopCdpBrowserPageExtractor(
    private val dataDirectory: File,
    private val browserProvider: () -> DesktopBrowserExecutable? = { DesktopBrowserLocator.findBrowser() },
) : BrowserPageExtractor {

    private val gate = Semaphore(2)

    override val isAvailable: Boolean
        get() = browserProvider() != null

    override suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long,
        pollIntervalMs: Long,
        extraDocumentStartJs: List<String>,
    ): String? = gate.withPermit {
        val browser = browserProvider() ?: return@withPermit null
        withContext(Dispatchers.IO) {
            val port = DesktopBrowserLocator.allocateLocalPort()
            val profileDir = File(dataDirectory, "browser-temp/headless-${UUID.randomUUID()}")
            profileDir.mkdirs()
            val cmd = listOf(
                browser.file.absolutePath,
                "--headless=new",
                "--remote-debugging-port=$port",
                "--remote-allow-origins=*",
                "--user-data-dir=${profileDir.absolutePath}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-gpu",
                "--disable-extensions",
                "--disable-sync",
                "--disable-background-networking",
                "--disable-blink-features=AutomationControlled",
                "--window-size=1440,900",
                "--user-agent=$userAgent",
                "about:blank",
            )
            var process: Process? = null
            var cdp: CdpClient? = null
            try {
                process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()

                val target = CdpClient.waitForPageTarget(port, process, timeoutMs = 12_000L)
                    ?: return@withContext null
                cdp = CdpClient.connect(target.webSocketDebuggerUrl)
                cdp.send("Page.enable")
                cdp.send("Network.enable")
                cdp.send(
                    "Network.setUserAgentOverride",
                    JSONObject()
                        .put("userAgent", userAgent)
                        .put("platform", "Win32"),
                )
                // Hide webdriver flag and inject caller document-start hooks before navigation.
                val stealthJs = """
                    try {
                      Object.defineProperty(navigator, 'webdriver', { get: function(){ return undefined; } });
                    } catch (e) {}
                """.trimIndent()
                cdp.send("Page.addScriptToEvaluateOnNewDocument", JSONObject().put("source", stealthJs))
                extraDocumentStartJs.forEach { script ->
                    if (script.isNotBlank()) {
                        cdp.send("Page.addScriptToEvaluateOnNewDocument", JSONObject().put("source", script))
                    }
                }

                cdp.send("Page.navigate", JSONObject().put("url", url))

                val effectiveTimeout = timeoutMs.coerceAtLeast(3_000L)
                val effectivePoll = pollIntervalMs.coerceIn(100L, 2_000L)
                val ready = withTimeoutOrNull(effectiveTimeout) {
                    while (true) {
                        val check = runCatching { cdp.evaluateRaw(readyCheckJs, timeoutMs = 4_000L) }.getOrNull()
                        if (check == true || check?.toString().equals("true", ignoreCase = true)) {
                            return@withTimeoutOrNull true
                        }
                        delay(effectivePoll)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } ?: false

                if (!ready) return@withContext null
                val extracted = runCatching {
                    cdp.evaluateRaw(extractJs, awaitPromise = true, timeoutMs = 8_000L)
                }.getOrNull() ?: return@withContext null
                formatExtractedValue(extracted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { cdp?.close() }
                runCatching { CdpClient.closeBrowser(port) }
                terminateBrowserProcess(process, profileDir)
            }
        }
    }

    companion object {
        internal fun formatExtractedValue(value: Any?): String? {
            if (value == null || value === JSONObject.NULL) return null
            return when (value) {
                is JSONObject -> value.toString()
                is JSONArray -> value.toString()
                is String -> value.takeIf { it != "null" && it != "undefined" }
                else -> value.toString()
            }
        }
    }
}

internal enum class WebLoginCaptureMode {
    COOKIES,
    LOCAL_STORAGE_KEY,
    XUNLEI_WEB_STORAGE,
}

internal data class DesktopWebLoginSpec(
    val kind: DriveAccountKind,
    val loginUrl: String,
    val cookieDomains: List<String>,
    val captureMode: WebLoginCaptureMode = WebLoginCaptureMode.COOKIES,
    val localStorageKey: String = "",
    val pageHostHints: List<String> = emptyList(),
    val userAgent: String? = null,
    val hint: String,
    val formatCookies: (List<CdpCookie>) -> String = { cookies -> formatCookieHeader(cookies) },
    val isPlausible: (String) -> Boolean,
)

internal object DesktopWebLoginRegistry {
    private val specs: Map<DriveAccountKind, DesktopWebLoginSpec> = listOf(
        DesktopWebLoginSpec(
            kind = DriveAccountKind.QUARK,
            loginUrl = QuarkConstants.LOGIN_URL,
            cookieDomains = listOf("pan.quark.cn", "quark.cn"),
            hint = "在弹出的浏览器窗口中扫码或登录后，YunGet 会自动捕获夸克 Cookie 并完成绑定。",
            isPlausible = { QuarkConstants.isValidCookie(it) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.BAIDU,
            loginUrl = BaiduConstants.LOGIN_URL,
            cookieDomains = listOf("pan.baidu.com", "baidu.com"),
            hint = "在弹出的浏览器窗口中登录百度网盘（需进入网盘主页获取 BDUSS/STOKEN），会自动捕获完成绑定。",
            isPlausible = { BaiduConstants.isValidCookie(it) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.UC,
            loginUrl = UCConstants.LOGIN_URL,
            cookieDomains = listOf("drive.uc.cn", "uc.cn"),
            hint = "在弹出的浏览器窗口中扫码或登录 UC 网盘，会自动捕获 Cookie 并完成绑定。",
            isPlausible = { UCConstants.isValidCookie(it) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.XUNLEI,
            loginUrl = XunleiWebCredential.LOGIN_URL,
            cookieDomains = listOf("pan.xunlei.com", "xunlei.com"),
            captureMode = WebLoginCaptureMode.XUNLEI_WEB_STORAGE,
            pageHostHints = listOf("pan.xunlei.com", "xunlei.com"),
            userAgent = XunleiWebCredential.DESKTOP_UA,
            hint = "在弹出的窗口中扫码或登录迅雷云盘，进入云盘主界面后会自动提取 localStorage 网页凭据。",
            isPlausible = { XunleiWebCredential.parse(it) != null },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.C139,
            loginUrl = C139Constants.LOGIN_URL,
            cookieDomains = listOf("mail.10086.cn", "yun.139.com", "139.com"),
            hint = "在弹出的窗口中登录中国移动云盘（139），登录完成后会自动提取 Cookie（含 authorization / Os_SSo_Sid）。",
            formatCookies = { cookies ->
                val extracted = C139Constants.extractCookies { domainUrl ->
                    val host = domainUrl.substringAfter("://").substringBefore('/')
                    formatCookieHeader(filterCookiesForDomains(cookies, listOf(host)))
                }
                extracted.ifBlank { formatCookieHeader(cookies) }
            },
            isPlausible = { C139Constants.isValidCookie(it) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.PAN123,
            loginUrl = Pan123Constants.WEB_LOGIN_URL,
            cookieDomains = listOf("yun.123pan.cn", "123pan.cn", "123pan.com"),
            captureMode = WebLoginCaptureMode.LOCAL_STORAGE_KEY,
            localStorageKey = Pan123Constants.LOCAL_STORAGE_TOKEN_KEY,
            pageHostHints = listOf("123pan.cn", "123pan.com", "123865.com"),
            hint = "在弹出的窗口中完成 123 云盘登录，进入个人盘后会自动提取 localStorage 中的 authorToken。",
            isPlausible = { it.trim().length >= 24 },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.PAN115,
            loginUrl = Pan115Constants.WEB_LOGIN_URL,
            cookieDomains = listOf("115.com", "webapi.115.com"),
            hint = "在弹出的窗口中扫码或登录 115 网盘，会自动提取 UID / CID / SEID / KID 等核心 Cookie。",
            formatCookies = { cookies ->
                val extracted = Pan115Constants.extractCookies { domainUrl ->
                    val host = domainUrl.substringAfter("://").substringBefore('/')
                    formatCookieHeader(filterCookiesForDomains(cookies, listOf(host)))
                }
                extracted.ifBlank { formatCookieHeader(cookies) }
            },
            isPlausible = { Pan115Constants.isValidCookie(it) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.LANZOU,
            loginUrl = "https://pc.woozooo.com/account.php?action=login",
            cookieDomains = listOf("pc.woozooo.com", "up.woozooo.com", "woozooo.com"),
            hint = "在弹出的窗口中完成蓝奏云登录，会自动提取 phpdisk_info 与 ylogin Cookie。",
            isPlausible = { it.contains("phpdisk_info=", ignoreCase = true) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.BILIBILI,
            loginUrl = VideoPlatform.BILIBILI.loginUrl,
            cookieDomains = listOf("bilibili.com", "www.bilibili.com", "api.bilibili.com"),
            hint = "在弹出的窗口中扫码或登录 B 站，会自动捕获 SESSDATA 等完整 Cookie。",
            isPlausible = { it.contains("SESSDATA=", ignoreCase = true) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.XHS,
            loginUrl = VideoPlatform.XHS.loginUrl,
            cookieDomains = listOf("xiaohongshu.com", "www.xiaohongshu.com"),
            hint = "在弹出的窗口中扫码或登录小红书；游客态会被自动过滤，真正登录成功后即自动保存。",
            isPlausible = { it.contains("web_session=", ignoreCase = true) },
        ),
        DesktopWebLoginSpec(
            kind = DriveAccountKind.PIXIV,
            loginUrl = "https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2F",
            cookieDomains = listOf("pixiv.net", "www.pixiv.net"),
            hint = "在弹出的窗口中登录 Pixiv 并回到主站，游客 PHPSESSID 会被自动忽略，登录态有效后自动保存。",
            isPlausible = { it.contains("PHPSESSID=", ignoreCase = true) },
        ),
    ).associateBy { it.kind }

    fun specFor(kind: DriveAccountKind): DesktopWebLoginSpec? = specs[kind]

    fun supportsWebLogin(kind: DriveAccountKind): Boolean = kind in specs

    fun allSupportedKinds(): Set<DriveAccountKind> = specs.keys
}

internal fun domainMatches(cookieDomain: String, targetDomain: String): Boolean {
    val c = cookieDomain.trim().trimStart('.').lowercase()
    val t = targetDomain.trim().trimStart('.').lowercase()
    if (c.isEmpty() || t.isEmpty()) return false
    return c == t || t.endsWith(".$c") || c.endsWith(".$t")
}

internal fun filterCookiesForDomains(cookies: List<CdpCookie>, domains: List<String>): List<CdpCookie> =
    cookies.filter { cookie -> domains.any { target -> domainMatches(cookie.domain, target) } }

internal fun formatCookieHeader(cookies: List<CdpCookie>): String {
    if (cookies.isEmpty()) return ""
    val dedup = LinkedHashMap<String, String>()
    for (c in cookies) {
        if (c.name.isBlank()) continue
        dedup[c.name] = c.value
    }
    return dedup.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * Interactive isolated Edge/Chrome app window login session controlled via local CDP.
 */
internal class DesktopWebLoginSession private constructor(
    val spec: DesktopWebLoginSpec,
    val browserName: String,
    private val port: Int,
    private val process: Process,
    private val profileDir: File,
) : Closeable {

    @Volatile
    private var closed = false

    suspend fun checkAlive(): Boolean {
        if (closed) return false
        val targets = runCatching { CdpClient.listTargets(port) }.getOrNull()
        if (targets != null) {
            return targets.any(CdpClient::isWebPageTarget)
        }
        return process.isAlive
    }

    suspend fun sampleCredential(): String? = withContext(Dispatchers.IO) {
        if (closed) return@withContext null
        val targets = runCatching { CdpClient.listTargets(port) }.getOrDefault(emptyList())
        val pageTargets = targets.filter(CdpClient::isWebPageTarget)
        if (pageTargets.isEmpty()) return@withContext null

        val preferredTarget = if (spec.pageHostHints.isNotEmpty()) {
            pageTargets.firstOrNull { t ->
                spec.pageHostHints.any { hint -> t.url.contains(hint, ignoreCase = true) }
            } ?: pageTargets.first()
        } else {
            pageTargets.first()
        }

        var client: CdpClient? = null
        try {
            client = CdpClient.connect(preferredTarget.webSocketDebuggerUrl, timeoutMs = 4_000L)
            when (spec.captureMode) {
                WebLoginCaptureMode.COOKIES -> {
                    val all = client.getAllCookies(timeoutMs = 5_000L)
                    val filtered = filterCookiesForDomains(all, spec.cookieDomains)
                    if (filtered.isEmpty()) null else spec.formatCookies(filtered).takeIf { it.isNotBlank() }
                }
                WebLoginCaptureMode.LOCAL_STORAGE_KEY -> {
                    val key = spec.localStorageKey
                    val js = "(function(){try{return localStorage.getItem('$key')||'';}catch(e){return '';}})()"
                    for (target in orderedTargets(pageTargets, preferredTarget)) {
                        val value = evalOnTarget(target, client, preferredTarget, js)?.toString()?.trim().orEmpty()
                        val unquoted = value.trim('"')
                        if (unquoted.isNotBlank()) return@withContext unquoted
                    }
                    null
                }
                WebLoginCaptureMode.XUNLEI_WEB_STORAGE -> {
                    for (target in orderedTargets(pageTargets, preferredTarget)) {
                        val raw = evalOnTarget(target, client, preferredTarget, XunleiWebCredential.READ_SCRIPT)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (raw.isBlank()) continue
                        val decoded = runCatching {
                            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
                        }.getOrDefault(raw)
                        if (XunleiWebCredential.parse(decoded) != null) return@withContext decoded
                    }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { client?.close() }
        }
    }

    private fun orderedTargets(all: List<CdpTargetInfo>, preferred: CdpTargetInfo): List<CdpTargetInfo> =
        buildList {
            add(preferred)
            all.forEach { if (it.id != preferred.id) add(it) }
        }

    private suspend fun evalOnTarget(
        target: CdpTargetInfo,
        existingClient: CdpClient,
        existingTarget: CdpTargetInfo,
        js: String,
    ): Any? {
        if (target.id == existingTarget.id) {
            return runCatching { existingClient.evaluateRaw(js, timeoutMs = 4_000L) }.getOrNull()
        }
        var extra: CdpClient? = null
        return try {
            extra = CdpClient.connect(target.webSocketDebuggerUrl, timeoutMs = 3_000L)
            extra.evaluateRaw(js, timeoutMs = 4_000L)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extra?.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        kotlinx.coroutines.runBlocking {
            runCatching { CdpClient.closeBrowser(port) }
        }
        terminateBrowserProcess(process, profileDir)
    }

    companion object {
        suspend fun launch(
            kind: DriveAccountKind,
            dataDirectory: File,
        ): DesktopWebLoginSession = withContext(Dispatchers.IO) {
            val spec = DesktopWebLoginRegistry.specFor(kind)
                ?: error("${kind.displayName}暂不支持网页弹窗登录")
            val browser = DesktopBrowserLocator.findBrowser()
                ?: error("未检测到 Microsoft Edge 或 Google Chrome，请改用手动粘贴凭证")
            val port = DesktopBrowserLocator.allocateLocalPort()
            val profileDir = File(dataDirectory, "browser-temp/login-${kind.name.lowercase()}-${UUID.randomUUID()}")
            profileDir.mkdirs()

            val cmd = buildList {
                add(browser.file.absolutePath)
                add("--remote-debugging-port=$port")
                add("--remote-allow-origins=*")
                add("--user-data-dir=${profileDir.absolutePath}")
                add("--app=${spec.loginUrl}")
                add("--window-size=1080,780")
                add("--no-first-run")
                add("--no-default-browser-check")
                add("--disable-extensions")
                add("--disable-sync")
                spec.userAgent?.let { add("--user-agent=$it") }
            }

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

            val target = CdpClient.waitForPageTarget(port, process, timeoutMs = 15_000L)
            if (target == null) {
                terminateBrowserProcess(process, profileDir)
                error("未能连接到 ${browser.displayName} 登录窗口，请重试或改用手动粘贴凭证")
            }
            DesktopWebLoginSession(
                spec = spec,
                browserName = browser.displayName,
                port = port,
                process = process,
                profileDir = profileDir,
            )
        }
    }
}
