package com.yunget.desktop

import com.yunget.app.data.network.CoreDeviceIds
import com.yunget.app.data.network.CoreHttp
import com.yunget.app.data.network.C139DeviceInfo
import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.app.data.network.XunleiDeviceFingerprintStore
import com.yunget.app.data.network.XunleiDeviceIdentity
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

internal enum class PostDownloadAction(val label: String, val summary: String) {
    NONE("无操作", "所有任务完成后保持当前状态"),
    OPEN_FOLDER("打开下载目录", "全部任务完成后自动打开默认下载目录"),
    SHUTDOWN_PC("60 秒后自动关机", "全部任务完成后弹出倒计时并在 60 秒后关机（可随时取消）");

    companion object {
        fun fromName(name: String?): PostDownloadAction =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

internal const val MAX_DESKTOP_HTTP_CONNECTIONS = 512

internal data class DesktopConfig(
    val downloadDirectory: String = File(System.getProperty("user.home"), "Downloads").absolutePath,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val httpPolicy: Int = 0,
    val maxConcurrentTasks: Int = 3,
    val connectionsPerTask: Int = 16,
    val speedLimitMbPerSec: Double = 0.0,
    val proxyUrl: String = "",
    val dohUrl: String = "",
    val sftpUsername: String = "",
    val sftpPassword: String = "",
    val sftpKnownHosts: String = "",
    val sftpPublicKey: String = "",
    val sftpPrivateKey: String = "",
    val conflictPolicy: ConflictPolicy = ConflictPolicy.AUTO_RENAME,
    val githubMirrorPrefix: String = "",
    val pan123LoginUuid: String = CoreDeviceIds.randomHex32(),
    val c139DeviceId: String = CoreDeviceIds.randomHex32(),
    val windowWidthDp: Int = 1180,
    val windowHeightDp: Int = 780,
    val minimizeToTray: Boolean = true,
    val systemNotifications: Boolean = true,
    val clipboardMonitor: Boolean = false,
    val preventSleepWhileDownloading: Boolean = true,
    val postDownloadAction: PostDownloadAction = PostDownloadAction.NONE,
    val relayEnabled: Boolean = true,
    val relayPort: Int = 17890,
    val relayAllowLan: Boolean = false,
    val relayToken: String = CoreDeviceIds.randomHex32().take(16),
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val providerSafeCapEnabled: Boolean = true,
    val providerThreadOverrides: Map<String, Int> = emptyMap(),
    val providerConcurrencyNoticeAckVersion: Int = 0,
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
        C139DeviceInfo.installProvider { C139DeviceInfo.defaultJvm(state.value.c139DeviceId) }
        XunleiDeviceFingerprint.init(DesktopXunleiDeviceFingerprintStore(dataDirectory))
        CoreHttp.install { apiHttpClient() }
    }

    fun update(transform: (DesktopConfig) -> DesktopConfig) {
        val next = transform(state.value).let { cfg ->
            cfg.copy(
                maxConcurrentTasks = cfg.maxConcurrentTasks.coerceIn(1, 8),
                connectionsPerTask = cfg.connectionsPerTask.coerceIn(1, MAX_DESKTOP_HTTP_CONNECTIONS),
                speedLimitMbPerSec = cfg.speedLimitMbPerSec.coerceIn(0.0, 2000.0),
                httpPolicy = cfg.httpPolicy.coerceIn(0, 3),
                proxyUrl = cfg.proxyUrl.trim(),
                dohUrl = cfg.dohUrl.trim(),
                sftpUsername = cfg.sftpUsername.trim(),
                sftpKnownHosts = cfg.sftpKnownHosts.trim(),
                sftpPublicKey = cfg.sftpPublicKey.trim(),
                sftpPrivateKey = cfg.sftpPrivateKey.trim(),
                githubMirrorPrefix = cfg.githubMirrorPrefix.trim(),
                windowWidthDp = cfg.windowWidthDp.coerceIn(720, 3840),
                windowHeightDp = cfg.windowHeightDp.coerceIn(520, 2160),
                relayPort = cfg.relayPort.coerceIn(1024, 65535),
                relayToken = cfg.relayToken.trim().ifBlank { CoreDeviceIds.randomHex32().take(16) },
                webdavUrl = cfg.webdavUrl.trim(),
                webdavUser = cfg.webdavUser.trim(),
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
                val savedC139Id = obj.optString("c139DeviceId")
                    .takeIf { Regex("^[0-9a-fA-F]{32}$").matches(it) }
                    ?: CoreDeviceIds.randomHex32()
                return DesktopConfig(
                    downloadDirectory = obj.optString("downloadDirectory")
                        .ifBlank { File(System.getProperty("user.home"), "Downloads").absolutePath },
                    themeMode = ThemeMode.fromName(obj.optString("themeMode")),
                    httpPolicy = obj.optInt("httpPolicy", 0).coerceIn(0, 3),
                    maxConcurrentTasks = obj.optInt("maxConcurrentTasks", 3).coerceIn(1, 8),
                    connectionsPerTask = obj.optInt("connectionsPerTask", 16).coerceIn(1, MAX_DESKTOP_HTTP_CONNECTIONS),
                    speedLimitMbPerSec = obj.optDouble("speedLimitMbPerSec", 0.0).coerceAtLeast(0.0),
                    proxyUrl = obj.optString("proxyUrl", ""),
                    dohUrl = obj.optString("dohUrl", ""),
                    sftpUsername = obj.optString("sftpUsername", ""),
                    sftpPassword = obj.optString("sftpPassword", ""),
                    sftpKnownHosts = obj.optString("sftpKnownHosts", ""),
                    sftpPublicKey = obj.optString("sftpPublicKey", ""),
                    sftpPrivateKey = obj.optString("sftpPrivateKey", ""),
                    conflictPolicy = ConflictPolicy.fromName(obj.optString("conflictPolicy")),
                    githubMirrorPrefix = obj.optString("githubMirrorPrefix", ""),
                    pan123LoginUuid = savedUuid,
                    c139DeviceId = savedC139Id,
                    windowWidthDp = obj.optInt("windowWidthDp", 1180).coerceIn(720, 3840),
                    windowHeightDp = obj.optInt("windowHeightDp", 780).coerceIn(520, 2160),
                    minimizeToTray = obj.optBoolean("minimizeToTray", true),
                    systemNotifications = obj.optBoolean("systemNotifications", true),
                    clipboardMonitor = obj.optBoolean("clipboardMonitor", false),
                    preventSleepWhileDownloading = obj.optBoolean("preventSleepWhileDownloading", true),
                    postDownloadAction = PostDownloadAction.fromName(obj.optString("postDownloadAction")),
                    relayEnabled = obj.optBoolean("relayEnabled", true),
                    relayPort = obj.optInt("relayPort", 17890).coerceIn(1024, 65535),
                    relayAllowLan = obj.optBoolean("relayAllowLan", false),
                    relayToken = obj.optString("relayToken").trim().ifBlank { CoreDeviceIds.randomHex32().take(16) },
                    webdavUrl = obj.optString("webdavUrl", ""),
                    webdavUser = obj.optString("webdavUser", ""),
                    providerSafeCapEnabled = obj.optBoolean("providerSafeCapEnabled", true),
                    providerThreadOverrides = com.yunget.app.data.download.ProviderDownloadPolicy.decodeOverrides(
                        obj.optString("providerThreadOverrides", "")
                    ),
                    providerConcurrencyNoticeAckVersion = obj.optInt("providerConcurrencyNoticeAckVersion", 0),
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
            .put("sftpUsername", cfg.sftpUsername)
            .put("sftpPassword", cfg.sftpPassword)
            .put("sftpKnownHosts", cfg.sftpKnownHosts)
            .put("sftpPublicKey", cfg.sftpPublicKey)
            .put("sftpPrivateKey", cfg.sftpPrivateKey)
            .put("conflictPolicy", cfg.conflictPolicy.name)
            .put("githubMirrorPrefix", cfg.githubMirrorPrefix)
            .put("pan123LoginUuid", cfg.pan123LoginUuid)
            .put("c139DeviceId", cfg.c139DeviceId)
            .put("windowWidthDp", cfg.windowWidthDp)
            .put("windowHeightDp", cfg.windowHeightDp)
            .put("minimizeToTray", cfg.minimizeToTray)
            .put("systemNotifications", cfg.systemNotifications)
            .put("clipboardMonitor", cfg.clipboardMonitor)
            .put("preventSleepWhileDownloading", cfg.preventSleepWhileDownloading)
            .put("postDownloadAction", cfg.postDownloadAction.name)
            .put("relayEnabled", cfg.relayEnabled)
            .put("relayPort", cfg.relayPort)
            .put("relayAllowLan", cfg.relayAllowLan)
            .put("relayToken", cfg.relayToken)
            .put("webdavUrl", cfg.webdavUrl)
            .put("webdavUser", cfg.webdavUser)
            .put("providerSafeCapEnabled", cfg.providerSafeCapEnabled)
            .put("providerThreadOverrides", com.yunget.app.data.download.ProviderDownloadPolicy.encodeOverrides(cfg.providerThreadOverrides))
            .put("providerConcurrencyNoticeAckVersion", cfg.providerConcurrencyNoticeAckVersion)
        Files.createDirectories(file.parentFile.toPath())
        val tmp = file.resolveSibling("settings.json.tmp")
        tmp.writeText(obj.toString(2))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun exportConfigJson(includeSftpSecrets: Boolean = false): String {
        val cfg = state.value
        val obj = JSONObject()
            .put("schema", "yunget-desktop-config-v1")
            .put("downloadDirectory", cfg.downloadDirectory)
            .put("themeMode", cfg.themeMode.name)
            .put("httpPolicy", cfg.httpPolicy)
            .put("maxConcurrentTasks", cfg.maxConcurrentTasks)
            .put("connectionsPerTask", cfg.connectionsPerTask)
            .put("speedLimitMbPerSec", cfg.speedLimitMbPerSec)
            .put("proxyUrl", cfg.proxyUrl)
            .put("dohUrl", cfg.dohUrl)
            .put("sftpUsername", cfg.sftpUsername)
            .put("sftpKnownHosts", cfg.sftpKnownHosts)
            .put("sftpPublicKey", cfg.sftpPublicKey)
            .put("conflictPolicy", cfg.conflictPolicy.name)
            .put("githubMirrorPrefix", cfg.githubMirrorPrefix)
            .put("minimizeToTray", cfg.minimizeToTray)
            .put("systemNotifications", cfg.systemNotifications)
            .put("clipboardMonitor", cfg.clipboardMonitor)
            .put("preventSleepWhileDownloading", cfg.preventSleepWhileDownloading)
            .put("postDownloadAction", cfg.postDownloadAction.name)
            .put("relayEnabled", cfg.relayEnabled)
            .put("relayPort", cfg.relayPort)
            .put("relayAllowLan", cfg.relayAllowLan)
            .put("webdavUrl", cfg.webdavUrl)
            .put("webdavUser", cfg.webdavUser)
            .put("providerSafeCapEnabled", cfg.providerSafeCapEnabled)
            .put("providerThreadOverrides", com.yunget.app.data.download.ProviderDownloadPolicy.encodeOverrides(cfg.providerThreadOverrides))
        if (includeSftpSecrets) {
            obj.put("sftpPassword", cfg.sftpPassword)
            obj.put("sftpPrivateKey", cfg.sftpPrivateKey)
        }
        return obj.toString(2)
    }

    fun importConfigJson(rawJson: String): DesktopConfig {
        val obj = JSONObject(rawJson)
        var imported: DesktopConfig = state.value
        update { cur ->
            cur.copy(
                downloadDirectory = obj.optString("downloadDirectory", cur.downloadDirectory).ifBlank { cur.downloadDirectory },
                themeMode = if (obj.has("themeMode")) ThemeMode.fromName(obj.optString("themeMode")) else cur.themeMode,
                httpPolicy = obj.optInt("httpPolicy", cur.httpPolicy),
                maxConcurrentTasks = obj.optInt("maxConcurrentTasks", cur.maxConcurrentTasks),
                connectionsPerTask = obj.optInt("connectionsPerTask", cur.connectionsPerTask),
                speedLimitMbPerSec = obj.optDouble("speedLimitMbPerSec", cur.speedLimitMbPerSec),
                proxyUrl = obj.optString("proxyUrl", cur.proxyUrl),
                dohUrl = obj.optString("dohUrl", cur.dohUrl),
                sftpUsername = obj.optString("sftpUsername", cur.sftpUsername),
                sftpPassword = if (obj.has("sftpPassword")) obj.optString("sftpPassword", cur.sftpPassword) else cur.sftpPassword,
                sftpKnownHosts = obj.optString("sftpKnownHosts", cur.sftpKnownHosts),
                sftpPublicKey = obj.optString("sftpPublicKey", cur.sftpPublicKey),
                sftpPrivateKey = if (obj.has("sftpPrivateKey")) obj.optString("sftpPrivateKey", cur.sftpPrivateKey) else cur.sftpPrivateKey,
                conflictPolicy = if (obj.has("conflictPolicy")) ConflictPolicy.fromName(obj.optString("conflictPolicy")) else cur.conflictPolicy,
                githubMirrorPrefix = obj.optString("githubMirrorPrefix", cur.githubMirrorPrefix),
                minimizeToTray = obj.optBoolean("minimizeToTray", cur.minimizeToTray),
                systemNotifications = obj.optBoolean("systemNotifications", cur.systemNotifications),
                clipboardMonitor = obj.optBoolean("clipboardMonitor", cur.clipboardMonitor),
                preventSleepWhileDownloading = obj.optBoolean("preventSleepWhileDownloading", cur.preventSleepWhileDownloading),
                postDownloadAction = if (obj.has("postDownloadAction")) PostDownloadAction.fromName(obj.optString("postDownloadAction")) else cur.postDownloadAction,
                relayEnabled = obj.optBoolean("relayEnabled", cur.relayEnabled),
                relayPort = obj.optInt("relayPort", cur.relayPort),
                relayAllowLan = obj.optBoolean("relayAllowLan", cur.relayAllowLan),
                webdavUrl = obj.optString("webdavUrl", cur.webdavUrl),
                webdavUser = obj.optString("webdavUser", cur.webdavUser),
                providerSafeCapEnabled = obj.optBoolean("providerSafeCapEnabled", cur.providerSafeCapEnabled),
                providerThreadOverrides = if (obj.has("providerThreadOverrides")) {
                    com.yunget.app.data.download.ProviderDownloadPolicy.decodeOverrides(obj.optString("providerThreadOverrides"))
                } else cur.providerThreadOverrides,
            ).also { imported = it }
        }
        return state.value
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

internal class DesktopXunleiDeviceFingerprintStore(
    private val dataDirectory: File,
) : XunleiDeviceFingerprintStore {
    private val file = File(dataDirectory, "xunlei-device.json")

    override fun load(): XunleiDeviceIdentity? {
        if (!file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val deviceId = obj.optString("deviceId").trim()
            val peerId = obj.optString("peerId").trim()
            val deviceSign = obj.optString("deviceSign").trim()
            if (deviceId.isBlank() || peerId.isBlank() || deviceSign.isBlank()) null
            else XunleiDeviceIdentity(deviceId = deviceId, peerId = peerId, deviceSign = deviceSign)
        }.getOrNull()
    }

    override fun save(identity: XunleiDeviceIdentity) {
        runCatching {
            Files.createDirectories(file.parentFile.toPath())
            val obj = JSONObject()
                .put("deviceId", identity.deviceId)
                .put("peerId", identity.peerId)
                .put("deviceSign", identity.deviceSign)
            val tmp = file.resolveSibling("xunlei-device.json.tmp")
            tmp.writeText(obj.toString(2))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
