package com.yunget.app.data.download

import dev.turbodl.core.DownloadBackend
import org.json.JSONObject
import java.io.File
import java.net.URI

internal interface NativePreviewRouting {
    val available: Boolean
    val backend: DownloadBackend
    fun diagnostics(taskId: Long): DownloadDiagnostics? = null
    fun takeDiagnostics(taskId: Long): DownloadDiagnostics? = null
    fun supportsMedia(kind: String): Boolean = false
    fun supportsTorrent(): Boolean = false
    fun isRecording(taskId: Long): Boolean = false
    suspend fun finishRecording(taskId: Long): Boolean = false
    fun close() = Unit

    companion object {
        const val ENGINE_ID = "turbodl"
        const val TAG = "yunget.transferBackend"
        const val NATIVE = "native-http-v1"
        const val MEDIA_TAG = "yunget.nativeMedia"
        const val MEDIA_HLS = "hls"
        const val MEDIA_DASH = "dash"
        const val TORRENT_FILE = "torrent"

        fun selected(policy: String): Boolean =
            runCatching { JSONObject(policy).optString(TAG) == NATIVE }.getOrDefault(false)

        fun pin(policy: String, useNative: Boolean, mediaKind: String? = null): String {
            val json = runCatching { JSONObject(policy) }.getOrElse { JSONObject() }
            if (useNative) json.put(TAG, NATIVE) else json.remove(TAG)
            if (useNative && mediaKind != null) json.put(MEDIA_TAG, mediaKind) else json.remove(MEDIA_TAG)
            return json.toString()
        }

        fun tags(policy: String): Map<String, String> {
            val json = runCatching { JSONObject(policy) }.getOrNull() ?: return emptyMap()
            if (json.optString(TAG) != NATIVE) return emptyMap()
            return buildMap {
                put(TAG, NATIVE)
                json.optString(MEDIA_TAG).takeIf { it.isNotEmpty() }?.let { put(MEDIA_TAG, it) }
            }
        }

        fun mediaKind(url: String, name: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) return null
            val extensions = sequenceOf(uri.path.orEmpty(), name)
                .map { it.substringAfterLast('.', "").lowercase() }
                .toSet()
            return when {
                extensions.any { it == "m3u8" || it == "m3u" } -> MEDIA_HLS
                extensions.any { it == "mpd" || it == "dash" } -> MEDIA_DASH
                else -> null
            }
        }

        fun isNativeProvider(id: String?): Boolean = id == ENGINE_ID

        fun isMagnet(url: String): Boolean = MagnetLink.isMagnet(url)

        fun isTorrentFile(url: String, name: String = ""): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
                (uri.path.orEmpty().endsWith(".torrent", true) || name.endsWith(".torrent", true))
        }

        fun torrentMetadataFile(output: File) = output.resolveSibling(output.name + ".torrent")
        fun partialTorrentMetadataFile(output: File) = output.resolveSibling(output.name + ".torrent.part")

        fun isFtp(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("ftp", true) && uri.host != null && uri.userInfo == null
        }.getOrDefault(false)

        fun supportsDirectLink(url: String, name: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) return false
            if (mediaKind(url, name) != null) return false
            val special = setOf("m3u8", "mpd", "torrent", "metalink", "meta4")
            return listOf(uri.path.orEmpty(), name).none { it.substringAfterLast('.', "").lowercase() in special }
        }
    }
}
