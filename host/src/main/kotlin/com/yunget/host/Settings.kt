package com.yunget.host

import java.io.File
import org.json.JSONObject

/**
 * 桌面宿主的数据目录与设置。
 *
 * 目录选择：`%LOCALAPPDATA%\YunGet`（不存在则退回用户主目录下的 `.yunget`）。
 * 设置、任务表、分片缓存都放在这里 —— 分片必须在固定目录才能跨重启续传。
 */
object Paths {
    val dataDir: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        File(base, "YunGet").apply { mkdirs() }
    }

    val chunksDir: File by lazy { File(dataDir, "chunks").apply { mkdirs() } }
    val settingsFile: File get() = File(dataDir, "settings.json")
    val tasksFile: File get() = File(dataDir, "tasks.json")

    /** 默认下载目录：`%USERPROFILE%\Downloads\YunGet`。 */
    val defaultDownloadDir: File by lazy {
        File(System.getProperty("user.home"), "Downloads/YunGet").apply { mkdirs() }
    }
}

/** 用户设置。字段与 Android 端的同名设置一一对应，换端不必重新学。 */
data class Settings(
    val downloadDir: String = Paths.defaultDownloadDir.absolutePath,
    val connections: Int = 16,
    val concurrentTasks: Int = 3,
    val speedLimitBytesPerSec: Long = 0L,
    /** direct / system / manual */
    val proxy: String = "system",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val readTimeoutMs: Long = 30_000L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("downloadDir", downloadDir)
        .put("connections", connections)
        .put("concurrentTasks", concurrentTasks)
        .put("speedLimitBytesPerSec", speedLimitBytesPerSec)
        .put("proxy", proxy)
        .put("proxyHost", proxyHost)
        .put("proxyPort", proxyPort)
        .put("readTimeoutMs", readTimeoutMs)

    fun save() = AtomicJson.write(Paths.settingsFile, toJson())

    companion object {
        fun load(): Settings = runCatching {
            if (!Paths.settingsFile.isFile) return@runCatching Settings()
            val o = JSONObject(Paths.settingsFile.readText())
            Settings(
                downloadDir = o.optString("downloadDir").ifBlank { Paths.defaultDownloadDir.absolutePath },
                connections = o.optInt("connections", 16).coerceIn(1, 256),
                concurrentTasks = o.optInt("concurrentTasks", 3).coerceIn(1, 64),
                speedLimitBytesPerSec = o.optLong("speedLimitBytesPerSec", 0L).coerceAtLeast(0),
                proxy = o.optString("proxy", "system"),
                proxyHost = o.optString("proxyHost", ""),
                proxyPort = o.optInt("proxyPort", 0),
                readTimeoutMs = o.optLong("readTimeoutMs", 30_000L).coerceIn(5_000L, 600_000L),
            )
        }.getOrDefault(Settings())
    }
}

/** 原子写 JSON：先写临时文件再替换，避免进程被杀时留下半个文件。 */
object AtomicJson {
    fun write(target: File, json: JSONObject) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Windows 上目标存在时 renameTo 可能失败：删掉再重试一次。
            target.delete()
            tmp.renameTo(target)
        }
    }
}
