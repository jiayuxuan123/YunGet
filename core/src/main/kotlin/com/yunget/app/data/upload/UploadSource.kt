package com.yunget.app.data.upload

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection

/**
 * Platform-independent upload file source providing metadata and ranged streams.
 * Android creates instances from SAF URIs; Desktop creates instances from local [File]s.
 */
class UploadSource(
    val name: String,
    val size: Long,
    val mimeType: String,
    private val rangeOpener: (start: Long, end: Long) -> InputStream
) {
    fun open(start: Long = 0L, end: Long = size): InputStream {
        if (start < 0 || end < start || end > size) {
            throw IllegalArgumentException("上传读取范围无效")
        }
        return rangeOpener(start, end)
    }

    companion object {
        private val MIME_PATTERN = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")

        fun sanitizeMime(raw: String?): String =
            raw?.trim()?.takeIf { MIME_PATTERN.matches(it) } ?: "application/octet-stream"

        fun fromFile(file: File): UploadSource {
            if (!file.isFile) throw IOException("文件不存在：${file.name}")
            val length = file.length()
            val guessed = runCatching { URLConnection.guessContentTypeFromName(file.name) }.getOrNull()
            return UploadSource(
                name = file.name.trim().ifBlank { "未命名文件" },
                size = length,
                mimeType = sanitizeMime(guessed),
            ) { start, end ->
                val raw = FileInputStream(file)
                try {
                    if (start > 0) raw.channel.position(start)
                    BoundedUploadInputStream(raw, end - start)
                } catch (t: Throwable) {
                    runCatching { raw.close() }
                    throw t
                }
            }
        }
    }
}

/** Wraps an [InputStream] to expose at most [remaining] bytes for fixed-length upload parts. */
class BoundedUploadInputStream(
    source: InputStream,
    private var remaining: Long
) : FilterInputStream(source) {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    override fun skip(count: Long): Long {
        val skipped = super.skip(minOf(count, remaining))
        if (skipped > 0) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
}
