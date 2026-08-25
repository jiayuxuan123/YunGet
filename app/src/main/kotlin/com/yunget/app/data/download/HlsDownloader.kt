package com.yunget.app.data.download

import android.util.Log
import com.yunget.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 轻量 HLS 下载器（UC play 转码流，绕过非会员视频被换成宣传片的问题）：
 * - 支持 master playlist（自动选第一个 #EXT-X-STREAM-INF 子流）与 media playlist；
 * - .ts 分片直接拼接；fMP4 按 #EXT-X-MAP 的 init 段 + 分片顺序拼接；
 * - 不支持 AES 加密（#EXT-X-KEY）与 #EXT-X-BYTERANGE（返回 false，由上层回退 OSS 直链）。
 */
object HlsDownloader {

    private const val TAG = "YunGet-HLS"

    private val client get() = HttpClients.apiClient()

    /**
     * 下载 m3u8 转码流并合并为单个文件（mp4/ts）。
     * @param onBytes 每下载一段分片回调新增字节数（suspend，可做进度上报）
     * @return 是否成功
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        destFile: File,
        onBytes: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val playlist = fetchText(url, headers) ?: return@runCatching false
            val mediaUrl = resolveMediaPlaylist(url, playlist) ?: return@runCatching false
            val mediaText = if (mediaUrl == url) playlist else fetchText(mediaUrl, headers) ?: return@runCatching false
            // AES 加密不支持
            if (mediaText.contains("#EXT-X-KEY")) {
                Log.w(TAG, "HLS 含 AES 加密，暂不支持")
                return@runCatching false
            }
            val base = mediaUrl.substringBeforeLast('/', "") + "/"
            val initUri = parseMapUri(mediaText)?.let { resolveUri(base, it) }
            val segments = parseSegments(mediaText).mapNotNull { resolveUri(base, it) }
            if (segments.isEmpty()) {
                Log.w(TAG, "HLS 分片列表为空")
                return@runCatching false
            }
            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { out ->
                // 先写 init 段（fMP4 需要）
                if (initUri != null) {
                    val initBytes = fetchBytes(initUri, headers) ?: return@runCatching false
                    out.write(initBytes)
                }
                var total = 0L
                segments.forEachIndexed { idx, seg ->
                    val bytes = fetchBytes(seg, headers) ?: return@runCatching false
                    out.write(bytes)
                    total += bytes.size
                    onBytes(bytes.size.toLong())
                    if (idx % 10 == 0) Log.d(TAG, "HLS 分片 ${idx + 1}/${segments.size}")
                }
                Log.d(TAG, "HLS 下载完成 segments=${segments.size} size=$total")
            }
            true
        }.getOrElse {
            Log.e(TAG, "HLS 下载失败: ${it.message}")
            false
        }
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        executeCancellable(req) { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    }.getOrNull()

    private suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? = runCatching {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        executeCancellable(req) { resp -> if (resp.isSuccessful) resp.body?.bytes() else null }
    }.getOrNull()

    private suspend fun <T> executeCancellable(request: Request, block: (okhttp3.Response) -> T): T {
        val call = client.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use(block)
        } finally {
            cancelHandle?.dispose()
        }
    }

    /** 若为 master playlist（含 #EXT-X-STREAM-INF），返回第一个子流 URL；否则原样返回 */
    private fun resolveMediaPlaylist(playlistUrl: String, text: String): String? {
        val base = playlistUrl.substringBeforeLast('/', "") + "/"
        val lines = text.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(i + 1)?.trim() ?: continue
                if (next.isNotBlank() && !next.startsWith("#")) return resolveUri(base, next)
            }
        }
        return playlistUrl
    }

    /** 解析 media playlist 的分片 URI（非 # 开头的行，过滤 #EXTINF 等标签） */
    private fun parseSegments(text: String): List<String> = buildList {
        val lines = text.lines()
        for (line in lines) {
            val t = line.trim()
            if (t.isNotBlank() && !t.startsWith("#")) add(t)
        }
    }

    /** 解析 #EXT-X-MAP:URI="init.mp4" 的 init 段地址（fMP4） */
    private fun parseMapUri(text: String): String? {
        val line = text.lines().firstOrNull { it.startsWith("#EXT-X-MAP") } ?: return null
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return null
        return m.groupValues[1]
    }

    /** 相对地址解析（相对于 m3u8 所在目录） */
    private fun resolveUri(base: String, uri: String): String =
        if (uri.startsWith("http")) uri else base + uri
}
