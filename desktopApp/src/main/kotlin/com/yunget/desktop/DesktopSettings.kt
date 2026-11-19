package com.yunget.desktop

import com.yunget.app.data.network.CoreDeviceIds
import com.yunget.app.data.network.CoreHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

internal enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

internal enum class ConflictPolicy(val label: String, val summary: String) {
    AUTO_RENAME("自动重命名", "目标文件已存在时自动生成新文件名（如 file (1).zip）"),
    KEEP_EXISTING("保留原文件并提示", "不覆盖已有文件，保留下载缓存等待手动处理");

    companion object {
        fun fromName(name: String?): ConflictPolicy =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO_RENAME
    }
}

internal data class DesktopConfig(
    val downloadDirectory: String = File(System.getProperty("user.home"), "Downloads").absolutePath,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val httpPolicy: Int = 0,
    val maxConcurrentTasks: Int = 3,
    val connectionsPerTask: Int = 16,
    val speedLimitMbPerSec: Double = 0.0,
    val proxyUrl: String = "",
    val dohUrl: String = "",
    val conflictPolicy: ConflictPolicy = ConflictPolicy.AUTO_RENAME,
    val githubMirrorPrefix: String = "",
    val pan123LoginUuid: String = CoreDeviceIds.randomHex32(),
    val windowWidthDp: Int = 1180,
    val windowHeightDp: Int = 780,
) {
    val speedLimitBytesPerSec: Long
        get() = if (speedLimitMbPerSec <= 0.0) 0L else (speedLimitMbPerSec * 1024.0 * 1024.0).toLong()
}

internal class DesktopSettingsStore(private val dataDirectory: File) {
    private val file = File(dataDirectory, "settings.json")
    private val legacyPrefs = runCatching { Preferences.userRoot().node("com/yunget/desktop") }.getOrNull()
    private val state = MutableStateFlow(load())
    val config = state.asStateFlow()

    @Volatile
    private var cachedProxyKey: String = ""

    @Volatile
    private var cachedHttpClient: OkHttpClient? = null

    init {
        CoreDeviceIds.installPan123LoginUuid { state.value.pan123LoginUuid }
        CoreHttp.install { apiHttpClient() }
    }

    fun update(transform: (DesktopConfig) -> DesktopConfig) {
        val next = transform(state.value).let { cfg ->
            cfg.copy(
                maxConcurrentTasks = cfg.maxConcurrentTasks.coerceIn(1, 8),
                connectionsPerTask = cfg.connectionsPerTask.coerceIn(1, 64),
                speedLimitMbPerSec = cfg.speedLimitMbPerSec.coerceIn(0.0, 2000.0),
                httpPolicy = cfg.httpPolicy.coerceIn(0, 3),
                proxyUrl = cfg.proxyUrl.trim(),
                dohUrl = cfg.dohUrl.trim(),
                githubMirrorPrefix = cfg.githubMirrorPrefix.trim(),
                windowWidthDp = cfg.windowWidthDp.coerceIn(720, 3840),
                windowHeightDp = cfg.windowHeightDp.coerceIn(520, 2160),
            )
        }
        state.value = next
        save(next)
    }

    fun apiHttpClient(): OkHttpClient {
        val proxyKey = state.value.proxyUrl
        val existing = cachedHttpClient
        if (existing != null && proxyKey == cachedProxyKey) return existing
        synchronized(this) {
            val again = cachedHttpClient
            if (again != null && proxyKey == cachedProxyKey) return again
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
            parseJavaProxy(proxyKey)?.let { builder.proxy(it) }
            return builder.build().also {
                cachedProxyKey = proxyKey
                cachedHttpClient = it
            }
        }
    }

    private fun load(): DesktopConfig {
        if (file.isFile) {
            runCatching {
                val obj = JSONObject(file.readText())
                val savedUuid = obj.optString("pan123LoginUuid")
                    .takeIf { Regex("^[0-9a-fA-F]{32}$").matches(it) }
                    ?: CoreDeviceIds.randomHex32()
                return DesktopConfig(
                    downloadDirectory = obj.optString("downloadDirectory")
                        .ifBlank { File(System.getProperty("user.home"), "Downloads").absolutePath },
                    themeMode = ThemeMode.fromName(obj.optString("themeMode")),
                    httpPolicy = obj.optInt("httpPolicy", 0).coerceIn(0, 3),
                    maxConcurrentTasks = obj.optInt("maxConcurrentTasks", 3).coerceIn(1, 8),
                    connectionsPerTask = obj.optInt("connectionsPerTask", 16).coerceIn(1, 64),
                    speedLimitMbPerSec = obj.optDouble("speedLimitMbPerSec", 0.0).coerceAtLeast(0.0),
                    proxyUrl = obj.optString("proxyUrl", ""),
                    dohUrl = obj.optString("dohUrl", ""),
                    conflictPolicy = ConflictPolicy.fromName(obj.optString("conflictPolicy")),
                    githubMirrorPrefix = obj.optString("githubMirrorPrefix", ""),
                    pan123LoginUuid = savedUuid,
                    windowWidthDp = obj.optInt("windowWidthDp", 1180).coerceIn(720, 3840),
                    windowHeightDp = obj.optInt("windowHeightDp", 780).coerceIn(520, 2160),
                )
            }
        }
        val defaultDir = legacyPrefs?.get(
            "directory",
            File(System.getProperty("user.home"), "Downloads").absolutePath,
        ) ?: File(System.getProperty("user.home"), "Downloads").absolutePath
        val legacyPolicy = legacyPrefs?.getInt("httpPolicy", 0) ?: 0
        return DesktopConfig(
            downloadDirectory = defaultDir,
            httpPolicy = legacyPolicy.coerceIn(0, 3),
        ).also { save(it) }
    }

    private fun save(cfg: DesktopConfig) {
        val obj = JSONObject()
            .put("downloadDirectory", cfg.downloadDirectory)
            .put("themeMode", cfg.themeMode.name)
            .put("httpPolicy", cfg.httpPolicy)
            .put("maxConcurrentTasks", cfg.maxConcurrentTasks)
            .put("connectionsPerTask", cfg.connectionsPerTask)
            .put("speedLimitMbPerSec", cfg.speedLimitMbPerSec)
            .put("proxyUrl", cfg.proxyUrl)
            .put("dohUrl", cfg.dohUrl)
            .put("conflictPolicy", cfg.conflictPolicy.name)
            .put("githubMirrorPrefix", cfg.githubMirrorPrefix)
            .put("pan123LoginUuid", cfg.pan123LoginUuid)
            .put("windowWidthDp", cfg.windowWidthDp)
            .put("windowHeightDp", cfg.windowHeightDp)
        Files.createDirectories(file.parentFile.toPath())
        val tmp = file.resolveSibling("settings.json.tmp")
        tmp.writeText(obj.toString(2))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun parseJavaProxy(raw: String): Proxy? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val normalized = if ("://" in trimmed) trimmed else "http://$trimmed"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = if (uri.port > 0) uri.port else when (uri.scheme?.lowercase()) {
        "socks", "socks5", "socks5h" -> 1080
        else -> 8080
    }
    val type = when (uri.scheme?.lowercase()) {
        "socks", "socks4", "socks5", "socks5h" -> Proxy.Type.SOCKS
        else -> Proxy.Type.HTTP
    }
    return Proxy(type, InetSocketAddress(host, port))
}
