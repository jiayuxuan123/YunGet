package com.yunget.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "未知"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return if (value >= 100.0) String.format(Locale.US, "%.0f %s", value, units[idx])
    else String.format(Locale.US, "%.2f %s", value, units[idx])
}

internal fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    return "${formatBytes(bytesPerSec)}/s"
}

internal fun formatEta(seconds: Long): String {
    if (seconds < 0L) return "--"
    if (seconds < 60L) return "${seconds}秒"
    val mins = seconds / 60L
    val secs = seconds % 60L
    if (mins < 60L) return "${mins}分${secs}秒"
    val hours = mins / 60L
    val remMins = mins % 60L
    return "${hours}小时${remMins}分"
}

internal fun formatProtocols(bits: Int): List<String> =
    listOf(1 to "HTTP/1.1", 2 to "HTTP/2", 4 to "HTTP/3")
        .filter { bits and it.first != 0 }
        .map { it.second }

internal fun formatLatencyMs(micros: Long): String {
    if (micros <= 0L) return "--"
    val ms = micros / 1000.0
    return if (ms >= 1000.0) String.format(Locale.US, "%.2f s", ms / 1000.0)
    else String.format(Locale.US, "%.1f ms", ms)
}

internal fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

internal fun readClipboardText(): String =
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
        } else ""
    }.getOrDefault("")

internal fun revealInExplorer(file: File) {
    val isWindows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
    if (isWindows) {
        val target = if (file.exists()) file else file.parentFile
        if (target != null && target.exists()) {
            val args = if (file.isFile) listOf("explorer.exe", "/select,", file.absolutePath)
            else listOf("explorer.exe", target.absolutePath)
            runCatching { ProcessBuilder(args).start() }
            return
        }
    }
    val dir = if (file.isDirectory) file else file.parentFile
    if (dir != null && dir.exists()) {
        runCatching { Desktop.getDesktop().open(dir) }
    }
}

internal fun openLocalFile(file: File) {
    if (!file.exists()) return
    runCatching { Desktop.getDesktop().open(file) }
}

internal fun sanitizeFileName(raw: String, fallback: String = "download.bin"): String {
    val cleaned = raw.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
    return cleaned.ifBlank { fallback }
}
