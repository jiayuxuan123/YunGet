package com.yunget.app.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Network/file portion of provisioning; Android verifies the APK signer before publishing it. */
internal class Aria2KernelDownload(private val client: OkHttpClient) {
    data class Asset(val url: String, val size: Long, val sha256: String)

    companion object {
        const val NAME = "YunGet-aria2-kernel-arm64-v8a.apk"
        private const val PREFIX = "https://github.com/jiayuxuan123/YunGet/releases/download/"
        private const val MAX_BYTES = 64L * 1024 * 1024
        val metadataUrls = listOf(
            "https://yunget.yunl.us.ci/assets/releases.json",
            "https://raw.githubusercontent.com/jiayuxuan123/YunGet/main/website/data/releases.json",
            "https://api.github.com/repos/jiayuxuan123/YunGet/releases/latest",
        )

        fun parseAsset(text: String): Asset {
            val root = JSONObject(text)
            val release = root.optJSONObject("latest") ?: root
            val assets = release.optJSONArray("assets") ?: throw IOException("发布中没有内核附件")
            val item = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == NAME } ?: throw IOException("发布中没有 aria2 可选内核")
            val url = item.optString("browser_download_url").ifBlank { item.optString("url") }
            val parsed = url.toHttpUrlOrNull()
            if (!url.startsWith(PREFIX) || parsed == null || parsed.username.isNotEmpty() ||
                parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null ||
                parsed.pathSegments.size != 6 || parsed.pathSegments.last() != NAME) {
                throw IOException("内核来源无效")
            }
            val size = item.optLong("size")
            if (size !in 1..MAX_BYTES) throw IOException("内核包体积异常")
            val digest = item.optString("digest").removePrefix("sha256:")
                .ifBlank { item.optString("sha256") }.lowercase()
            if (!digest.matches(Regex("[0-9a-f]{64}"))) throw IOException("内核缺少 SHA-256 摘要")
            return Asset(url, size, digest)
        }

        fun downloadUrls(asset: Asset, mirrorPrefix: String): List<Pair<String, String>> = buildList {
            add("官方加速" to "https://dl.yunl.us.ci/yunget/releases/download/${asset.url.removePrefix(PREFIX)}")
            val mirror = mirrorPrefix.trim().trimEnd('/')
            val parsed = mirror.toHttpUrlOrNull()
            if (parsed?.isHttps == true && parsed.username.isEmpty() && parsed.password.isEmpty() &&
                parsed.query == null && parsed.fragment == null) {
                add("下载镜像" to "$mirror/${asset.url}")
            }
            add("GitHub 直连" to asset.url)
        }.distinctBy { it.second }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun download(
        directory: File,
        mirrorPrefix: String,
        bundledMetadata: String,
        onStatus: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
        validate: (File) -> Unit,
    ): File {
        onStatus("正在查询 aria2 内核版本…")
        var asset: Asset? = null
        for (url in metadataUrls) {
            try {
                asset = request(url) { response, checkActive ->
                    val source = response.body?.source() ?: throw IOException("版本响应为空")
                    // Limit before allocating, including unknown/chunked response lengths.
                    if (source.request(512L * 1024 + 1)) throw IOException("版本响应过大")
                    checkActive()
                    parseAsset(source.readUtf8())
                }
                break
            } catch (e: CancellationException) { throw e }
            catch (_: IOException) { /* Try independent release metadata, then the bundled snapshot. */ }
            catch (_: org.json.JSONException) { }
        }
        val selected = asset ?: parseAsset(bundledMetadata)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建内核缓存目录")
        val target = File(directory, NAME)
        if (target.isFile && target.length() == selected.size && sha256(target) == selected.sha256) {
            validate(target)
            return target
        }
        val errors = mutableListOf<String>()
        for ((label, url) in downloadUrls(selected, mirrorPrefix)) {
            onStatus("正在通过${label}下载 aria2 内核…")
            val partial = File.createTempFile("aria2-", ".part.apk", directory)
            try {
                request(url, onFailureCleanup = { partial.delete() }) { response, checkActive ->
                    val body = response.body ?: throw IOException("内核响应为空")
                    if (body.contentLength() > selected.size) throw IOException("内核包超过预期大小")
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        onProgress(0L, selected.size)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > selected.size) throw IOException("内核包超过预期大小")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            onProgress(copied, selected.size)
                        }
                        if (copied != selected.size) throw IOException("内核包下载不完整")
                    } }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hash != selected.sha256) throw IOException("内核 SHA-256 校验失败")
                    checkActive()
                }
                validate(partial)
                if (target.exists() && !target.delete()) throw IOException("无法更新内核安装包")
                if (!partial.renameTo(target)) throw IOException("无法保存内核安装包")
                return target
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "$label：${e.message ?: e.javaClass.simpleName}" }
            finally { partial.delete() }
        }
        throw IOException("aria2 内核下载失败\n${errors.joinToString("\n")}\n可重试或从仓库手动下载安装。")
    }

    private suspend fun <T> request(
        url: String,
        onFailureCleanup: () -> Unit = {},
        consume: (Response, () -> Unit) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val metadata = url in metadataUrls
            val requestClient = if (metadata) client.newBuilder()
                .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build() else client
            val call = requestClient.newCall(Request.Builder().url(url).header("User-Agent", "YunGet")
                .header("Accept", if (metadata) "application/json" else "application/octet-stream").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onFailureCleanup()
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            consume(it) { continuation.context.ensureActive() }
                        }
                        continuation.resume(value)
                    } catch (e: Exception) {
                        onFailureCleanup()
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}
