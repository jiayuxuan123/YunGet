package com.yunget.app.data.network

import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Public single-file shares for Ctfile. Captcha-protected shares are rejected without attempting to solve them. */
class CtfileApi(
    private val clientProvider: () -> OkHttpClient = { CoreHttp.client() },
) {
    private data class Cached(val sourceUrl: String, val passcode: String, val info: JSONObject)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun open(
        shareId: String,
        sourceUrl: String,
        passcode: String,
        cookie: String = "",
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val info = fileInfo(shareId, sourceUrl, passcode, cookie)
            cache[shareId] = Cached(sourceUrl, passcode, info)
            info
        }

    fun cachedFile(shareId: String): ShareFile? = cache[shareId]?.let { parseFile(it.info) }

    suspend fun download(
        shareId: String,
        fileId: String,
        cookie: String = "",
    ): DownloadLink = withContext(Dispatchers.IO) {
        val cached = cache[shareId] ?: throw IllegalStateException("城通分享会话已失效，请重新解析")
        val fresh = fileInfo(shareId, cached.sourceUrl, cached.passcode, cookie)
        cache[shareId] = cached.copy(info = fresh)
        val file = parseFile(fresh)
        require(file.fid == fileId) { "城通返回的文件与所选文件不一致，请重新解析" }
        val wait = fresh.optInt("wait_seconds", 0)
        if (wait > 30) throw IllegalStateException("城通要求等待 $wait 秒，请稍后重新点击下载")
        if (wait > 0) delay(wait * 1_000L)
        val uid = fresh.optString("userid")
        val check = fresh.optString("file_chk")
        require(uid.isNotBlank() && check.isNotBlank()) { "城通未返回下载凭据" }
        val rawFid = fileId.removePrefix("f")
        val data = getJson(
            addQuery(
                "$WEB/get_down_url.php",
                linkedMapOf(
                    "uid" to uid,
                    "fid" to rawFid,
                    "file_chk" to check,
                    "start_time" to fresh.optLong("start_time").toString(),
                    "wait_seconds" to wait.toString(),
                ),
            ),
            cookie,
        )
        val address = data.optString("download_url").ifBlank { data.optString("downurl") }
        require(address.startsWith("https://") || address.startsWith("http://")) {
            "城通未返回有效下载地址"
        }
        DownloadLink(
            fid = fileId,
            filename = file.fname,
            downloadUrl = address,
            size = data.optLong("file_size", file.fsize),
        )
    }

    private fun fileInfo(
        shareId: String,
        sourceUrl: String,
        passcode: String,
        cookie: String,
    ): JSONObject {
        val parts = shareId.split('/')
        require(parts.size == 2 && parts[0] == "f") { "当前仅支持城通单文件分享 /f/ 链接" }
        val root = getJson(
            addQuery(
                "$WEB/getfile.php",
                linkedMapOf(
                    "path" to "f",
                    "f" to parts[1],
                    "passcode" to passcode,
                    "url" to sourceUrl,
                ),
            ),
            cookie,
        )
        checkCtfile(root)
        return root.optJSONObject("file") ?: throw IllegalStateException("城通分享响应不完整")
    }

    private fun checkCtfile(root: JSONObject) {
        val code = root.optInt("code", -1)
        val file = root.optJSONObject("file")
        if (code == 423 || file?.optString("error_type")?.startsWith("captcha_") == true) {
            throw IllegalStateException("此城通分享需要网页安全验证，暂时无法在应用内解析")
        }
        if (code != 200 && code != 0) {
            val message = file?.optString("message").orEmpty().ifBlank {
                root.optString("message").ifBlank { "城通分享请求失败（$code）" }
            }
            throw IllegalStateException(message)
        }
    }

    private fun parseFile(info: JSONObject): ShareFile {
        val id = info.optString("file_id").ifBlank { info.optString("fid") }.removePrefix("f")
        val name = info.optString("file_name").ifBlank { info.optString("name") }
        require(id.matches(Regex("\\d+")) && name.isNotBlank()) { "城通文件信息不完整" }
        val size = info.optLong("file_size_bytes", info.optLong("file_size", info.optLong("size")))
        return ShareFile(
            fid = "f$id",
            fname = name,
            fsize = size,
            isdir = false,
            pdirFid = "0",
            fidToken = "",
        )
    }

    private fun getJson(url: String, cookie: String = ""): JSONObject {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.ctfile.com/")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        val request = builder.build()
        clientProvider().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("城通请求失败（HTTP ${response.code}）")
            return JSONObject(body)
        }
    }

    private fun addQuery(base: String, values: Map<String, String>): String {
        val url = base.toHttpUrl().newBuilder()
        values.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return url.build().toString()
    }

    private companion object {
        const val WEB = "https://webapi.ctfile.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
    }
}
