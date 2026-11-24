package com.yunget.app.data.backup

import com.yunget.app.data.db.BookmarkDao
import com.yunget.app.data.db.BookmarkEntity
import com.yunget.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 安卓端 WebDAV 同步快照导出与恢复（多账号 + 收藏夹 + 完整下载/引擎设置），
 * 与桌面端 [com.yunget.desktop.exportDesktopWebDavSnapshot] 保持同一份 JSON 协议。
 */
internal object AndroidWebDavSync {
    suspend fun exportSnapshot(
        backupManager: AuthBackupManager,
        settingsRepo: SettingsRepository,
        bookmarkDao: BookmarkDao,
    ): JSONObject = withContext(Dispatchers.IO) {
        val root = JSONObject(backupManager.exportJson())
        val bookmarks = runCatching { bookmarkDao.observeAll().first() }.getOrDefault(emptyList())
        if (bookmarks.isNotEmpty()) {
            val arr = JSONArray()
            bookmarks.forEach { b ->
                arr.put(
                    JSONObject()
                        .put("link", b.link)
                        .put("title", b.title)
                        .put("platform", b.platform)
                        .put("pwd", b.pwd)
                        .put("category", b.category)
                        .put("createdAt", b.createTime)
                )
            }
            root.put("bookmarks", arr)
        }
        root.put(
            "downloadSettings",
            JSONObject()
                .put("connectionsPerTask", settingsRepo.downloadThreads)
                .put("maxConcurrentTasks", settingsRepo.maxConcurrentDownloads)
                .put("speedLimitBytesPerSec", settingsRepo.downloadSpeedLimit)
                .put("httpPolicy", settingsRepo.engineHttpVersionPolicy)
                .put("providerSafeCapEnabled", settingsRepo.providerSafeCapEnabled)
                .put("providerThreadOverrides", com.yunget.app.data.download.ProviderDownloadPolicy.encodeOverrides(settingsRepo.providerThreadOverrides))
        )
        root
    }

    suspend fun restoreSnapshot(
        snapshot: JSONObject,
        backupManager: AuthBackupManager,
        settingsRepo: SettingsRepository,
        bookmarkDao: BookmarkDao,
    ): Int = withContext(Dispatchers.IO) {
        val count = backupManager.importJson(snapshot.toString())
        snapshot.optJSONObject("downloadSettings")?.let { settings ->
            settingsRepo.downloadThreads = settings.optInt("connectionsPerTask", settingsRepo.downloadThreads)
            settingsRepo.maxConcurrentDownloads = settings.optInt("maxConcurrentTasks", settingsRepo.maxConcurrentDownloads)
            if (settings.has("speedLimitBytesPerSec")) {
                settingsRepo.downloadSpeedLimit = settings.optLong("speedLimitBytesPerSec", settingsRepo.downloadSpeedLimit)
            }
            if (settings.has("httpPolicy")) {
                settingsRepo.engineHttpVersionPolicy = settings.optInt("httpPolicy", settingsRepo.engineHttpVersionPolicy)
            }
            if (settings.has("providerSafeCapEnabled")) {
                settingsRepo.providerSafeCapEnabled = settings.optBoolean("providerSafeCapEnabled", settingsRepo.providerSafeCapEnabled)
            }
            if (settings.has("providerThreadOverrides")) {
                settingsRepo.providerThreadOverrides = com.yunget.app.data.download.ProviderDownloadPolicy.decodeOverrides(
                    settings.optString("providerThreadOverrides")
                )
            }
        }
        snapshot.optJSONArray("bookmarks")?.let { arr ->
            val existingLinks = runCatching { bookmarkDao.observeAll().first() }
                .getOrDefault(emptyList())
                .mapTo(hashSetOf()) { it.link.trim() }
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val link = obj.optString("link").trim()
                if (link.isEmpty() || !existingLinks.add(link)) continue
                bookmarkDao.insert(
                    BookmarkEntity(
                        link = link,
                        title = obj.optString("title"),
                        platform = obj.optString("platform"),
                        pwd = obj.optString("pwd"),
                        category = obj.optString("category").ifBlank { "默认" },
                        createTime = obj.optLong("createdAt", System.currentTimeMillis()),
                    )
                )
            }
        }
        count
    }
}
