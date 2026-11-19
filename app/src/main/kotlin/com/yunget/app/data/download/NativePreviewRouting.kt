package com.yunget.app.data.download

import dev.turbodl.core.DownloadBackend
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal data class NativeSftpConfig(
    val username: String = "",
    val password: String = "",
    val knownHosts: String = "",
    val publicKey: String = "",
    val privateKey: String = "",
)

internal data class NativeAltSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

internal interface NativePreviewRouting {
    val available: Boolean
    val backend: DownloadBackend
    fun diagnostics(taskId: Long): DownloadDiagnostics? = null
    fun takeDiagnostics(taskId: Long): DownloadDiagnostics? = null
    fun supportsMedia(kind: String): Boolean = false
    fun supportsTorrent(): Boolean = false
    fun supportsMetalink(): Boolean = false
    fun supportsSftp(): Boolean = false
    fun isRecording(taskId: Long): Boolean = false
    suspend fun finishRecording(taskId: Long): Boolean = false
    fun close() = Unit

    companion object {
        const val ENGINE_ID = "turbodl"
        const val TAG = "yunget.transferBackend"
        const val NATIVE = "native-http-v1"
        const val MEDIA_TAG = "yunget.nativeMedia"
        const val ALT_SOURCES_TAG = "yunget.nativeAltSources"
        const val MEDIA_HLS = "hls"
        const val MEDIA_DASH = "dash"
        const val TORRENT_FILE = "torrent"
        const val METALINK_FILE = "metalink"

        fun selected(policy: String): Boolean =
            runCatching { JSONObject(policy).optString(TAG) == NATIVE }.getOrDefault(false)

        fun pin(
            policy: String,
            useNative: Boolean,
            mediaKind: String? = null,
            altSourcesJson: String? = null,
        ): String {
            val json = runCatching { JSONObject(policy) }.getOrElse { JSONObject() }
            if (useNative) json.put(TAG, NATIVE) else json.remove(TAG)
            if (useNative && mediaKind != null) json.put(MEDIA_TAG, mediaKind) else json.remove(MEDIA_TAG)
            if (useNative && !altSourcesJson.isNullOrBlank() && altSourcesJson != "[]") {
                json.put(ALT_SOURCES_TAG, altSourcesJson)
            } else {
                json.remove(ALT_SOURCES_TAG)
            }
            return json.toString()
        }

        fun tags(policy: String): Map<String, String> {
            val json = runCatching { JSONObject(policy) }.getOrNull() ?: return emptyMap()
            if (json.optString(TAG) != NATIVE) return emptyMap()
            return buildMap {
                put(TAG, NATIVE)
                json.optString(MEDIA_TAG).takeIf { it.isNotEmpty() }?.let { put(MEDIA_TAG, it) }
                json.optString(ALT_SOURCES_TAG).takeIf { it.isNotEmpty() }?.let { put(ALT_SOURCES_TAG, it) }
            }
        }

        fun decodeAltSources(raw: String?): List<NativeAltSource> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").trim()
                    if (url.isEmpty()) continue
                    val hdrObj = obj.optJSONObject("headers")
                    val headers = if (hdrObj != null) {
                        hdrObj.keys().asSequence().associateWith { key -> hdrObj.optString(key) }
                    } else emptyMap()
                    add(NativeAltSource(url, headers))
                }
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

        fun isMetalinkFile(url: String, name: String = ""): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return false
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val validScheme = scheme == null || scheme in setOf("http", "https", "file")
            if (!validScheme) return false
            if (scheme in setOf("http", "https") && uri?.host == null) return false
            val path = uri?.path.orEmpty().ifBlank { trimmed }
            return path.endsWith(".metalink", true) || path.endsWith(".meta4", true) ||
                name.endsWith(".metalink", true) || name.endsWith(".meta4", true)
        }

        fun metalinkMetadataFile(output: File) = output.resolveSibling(output.name + ".metalink")
        fun partialMetalinkMetadataFile(output: File) = output.resolveSibling(output.name + ".metalink.part")

        fun isFtp(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("ftp", true) && uri.host != null && uri.userInfo == null
        }.getOrDefault(false)

        fun isSftp(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("sftp", true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)

        fun stripSftpUserInfo(url: String): String {
            if (!isSftp(url)) return url
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val authStart = schemeEnd + 3
            val authEnd = url.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authStart)
                .let { if (it < 0) url.length else it }
            val atIndex = url.indexOf('@', startIndex = authStart)
            if (atIndex !in authStart until authEnd) return url
            return url.substring(0, authStart) + url.substring(atIndex + 1)
        }

        fun supportsDirectLink(url: String, name: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) return false
            if (mediaKind(url, name) != null) return false
            val special = setOf("m3u8", "mpd", "torrent", "metalink", "meta4")
            return listOf(uri.path.orEmpty(), name).none { it.substringAfterLast('.', "").lowercase() in special }
        }
    }
}
