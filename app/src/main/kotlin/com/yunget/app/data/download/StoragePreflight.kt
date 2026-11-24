package com.yunget.app.data.download

import java.io.File
import java.util.Locale

/**
 * 下载前剩余存储空间预检：
 * 提前拦截明显放不下的大文件，避免消耗大量流量下到 99% 落盘时才失败。
 */
internal object StoragePreflight {
    const val SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L

    fun checkDirectory(directory: File, totalSize: Long, alreadyDownloaded: Long = 0L): String? {
        if (totalSize <= 0L) return null
        val usable = runCatching {
            directory.mkdirs()
            directory.usableSpace
        }.getOrDefault(0L)
        if (usable <= 0L) return null
        return check(totalSize = totalSize, alreadyDownloaded = alreadyDownloaded, usableBytes = usable)
    }

    fun check(
        totalSize: Long,
        alreadyDownloaded: Long = 0L,
        usableBytes: Long,
        marginBytes: Long = SAFETY_MARGIN_BYTES,
    ): String? {
        if (totalSize <= 0L || usableBytes <= 0L) return null
        val remaining = (totalSize - alreadyDownloaded.coerceAtLeast(0L)).coerceAtLeast(0L)
        if (remaining == 0L) return null
        if (remaining + marginBytes <= usableBytes) return null
        return "存储空间不足：还需约 ${formatSize(remaining)}，当前可用 ${formatSize(usableBytes)}，请清理空间后重试"
    }

    internal fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }
}
