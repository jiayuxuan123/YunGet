package com.yunget.app.data.download

/**
 * 将解析后的直链与必需请求头导出为外部下载器可直接执行的命令（curl / aria2c）。
 * 解决部分网盘直链（如夸克、UC、百度、139）仅复制裸 URL 在外部工具里 403/412 的痛点。
 */
object DirectLinkExport {

    fun toCurlCommand(
        url: String,
        fileName: String = "",
        headers: Map<String, String> = emptyMap(),
    ): String = buildString {
        append("curl -L")
        if (fileName.isNotBlank()) {
            append(" -o ").append(quoteArg(sanitizeFileName(fileName)))
        }
        for ((name, value) in filteredHeaders(headers)) {
            append(" -H ").append(quoteArg("$name: $value"))
        }
        append(' ').append(quoteArg(url.trim()))
    }

    fun toAria2Command(
        url: String,
        fileName: String = "",
        headers: Map<String, String> = emptyMap(),
        connections: Int = 16,
    ): String = buildString {
        val c = connections.coerceIn(1, 16)
        append("aria2c -x ").append(c).append(" -s ").append(c)
        if (fileName.isNotBlank()) {
            append(" -o ").append(quoteArg(sanitizeFileName(fileName)))
        }
        for ((name, value) in filteredHeaders(headers)) {
            append(" --header=").append(quoteArg("$name: $value"))
        }
        append(' ').append(quoteArg(url.trim()))
    }

    fun hasImportantHeaders(headers: Map<String, String>): Boolean =
        filteredHeaders(headers).isNotEmpty()

    private fun filteredHeaders(headers: Map<String, String>): List<Pair<String, String>> =
        headers.entries
            .filter { (k, v) -> k.isNotBlank() && v.isNotBlank() && k.none { it == '\r' || it == '\n' } && v.none { it == '\r' || it == '\n' } }
            .sortedBy { it.key.lowercase() }
            .map { it.key.trim() to it.value.trim() }

    private fun sanitizeFileName(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").ifBlank { "download.bin" }

    private fun quoteArg(raw: String): String {
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
