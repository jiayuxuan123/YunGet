package com.yunget.app.data.upload

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

private const val ANDROID_SKIP_BUFFER = 64 * 1024

/**
 * Android SAF extension for resolving a content [Uri] into a shared [UploadSource].
 */
fun UploadSource.Companion.resolve(resolver: ContentResolver, uri: Uri): UploadSource? {
    var name = uri.lastPathSegment.orEmpty()
    var size = -1L
    runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    if (size < 0) {
        size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }
    if (size < 0) {
        size = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(ANDROID_SKIP_BUFFER)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            } ?: -1L
        }.getOrDefault(-1L)
    }
    if (size < 0) return null
    val rawType = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
    val type = UploadSource.sanitizeMime(rawType.substringBefore(';'))
    return UploadSource(
        name = name.trim().ifBlank { "未命名文件" },
        size = size,
        mimeType = type,
    ) { start, end -> openSafRange(resolver, uri, start, end) }
}

private fun openSafRange(resolver: ContentResolver, uri: Uri, start: Long, end: Long): InputStream {
    val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
    if (descriptor != null) {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        val positioned = runCatching { stream.channel.position(start) }.isSuccess
        if (positioned) return BoundedUploadInputStream(stream, end - start)
        runCatching { stream.close() }
    }
    val raw = resolver.openInputStream(uri) ?: throw IOException("无法打开所选文件")
    if (start > 0) {
        val buffer = ByteArray(ANDROID_SKIP_BUFFER)
        var remaining = start
        while (remaining > 0) {
            val read = raw.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) {
                runCatching { raw.close() }
                throw IOException("读取本地文件不完整，请重新选择")
            }
            remaining -= read
        }
    }
    return BoundedUploadInputStream(raw, end - start)
}
