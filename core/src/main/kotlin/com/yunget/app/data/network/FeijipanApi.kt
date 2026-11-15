package com.yunget.app.data.network

import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class FeijipanAccount(
    val nickname: String,
    val userId: String,
    val usedBytes: Long,
    val totalBytes: Long,
)

data class FeijipanLogin(
    val token: String,
    val uuid: String,
    val account: FeijipanAccount,
)

/**
 * Feijipan protocol.
 *
 * Share browsing works anonymously. The same client also supports normal
 * account login and personal-file endpoints.
 */
class FeijipanApi(
    private val clientProvider: () -> OkHttpClient = { CoreHttp.client() },
) {
    private data class SessionState(
        val uuid: String,
        val code: String,
        val token: String = "",
        val userId: String = "",
    )
    private val sessions = ConcurrentHashMap<String, SessionState>()

    suspend fun open(
        shareId: String,
        code: String,
        credential: String = "",
    ): JSONObject = withContext(Dispatchers.IO) {
        val saved = parseCredential(credential)
        val uuid = saved?.optString("uuid").orEmpty().ifBlank { fetchUuid() }
        val state = SessionState(
            uuid = uuid,
            code = code,
            token = saved?.optString("token").orEmpty(),
            userId = saved?.optString("userId").orEmpty(),
        )
        sessions[shareId] = state
        page(shareId, code, uuid, state.token, parent = null, page = 1)
    }

    suspend fun list(shareId: String, code: String, parent: String?): List<ShareFile> =
        withContext(Dispatchers.IO) {
            val state = sessions[shareId] ?: SessionState(fetchUuid(), code).also {
                sessions[shareId] = it
            }
            val files = mutableListOf<ShareFile>()
            var pageNumber = 1
            do {
                val data = page(shareId, code, state.uuid, state.token, parent, pageNumber)
                val rows = data.optJSONArray("list") ?: throw IllegalStateException("小飞机分享列表格式异常")
                var count = 0
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val grouped = row.optJSONArray("fileList")
                    val entries = if (grouped != null) {
                        buildList { for (i in 0 until grouped.length()) grouped.optJSONObject(i)?.let(::add) }
                    } else listOf(row)
                    for (entry in entries) {
                        count++
                        files += parseFile(entry, parent ?: "root")
                    }
                }
                val totalPages = data.optInt("totalPage", 0)
                if (totalPages > 0 && pageNumber >= totalPages) break
                if (totalPages <= 0 && count < 60) break
                pageNumber++
            } while (pageNumber <= 1000)
            files
        }

    suspend fun download(shareId: String, code: String, fileId: String): DownloadLink =
        withContext(Dispatchers.IO) {
            val rawId = fileId.removePrefix("f:").removePrefix("d:")
            val state = sessions[shareId] ?: throw IllegalStateException("小飞机分享会话已失效，请重新解析")
            val stamp = System.currentTimeMillis()
            val extra = linkedMapOf(
                "shareId" to shareId,
                "code" to code,
                "enable" to "1",
                "downloadId" to encrypt("$rawId|${state.userId}"),
                "auth" to encrypt("$rawId|$stamp"),
            )
            val response = request("GET", url("/ws/file/download", state.uuid, extra, state.token, stamp))
            val root = checkResponse(response)
            val encrypted = root.optString("downloadUrl")
            if (encrypted.isBlank()) throw IllegalStateException("小飞机未返回下载地址")
            val address = decryptDownloadUrl(encrypted)
            DownloadLink(
                fid = fileId,
                filename = "",
                downloadUrl = address,
                size = 0,
            )
        }

    suspend fun login(username: String, password: String): FeijipanLogin =
        withContext(Dispatchers.IO) {
            val cleanUser = username.trim()
            require(cleanUser.isNotEmpty() && password.isNotEmpty()) { "请输入小飞机账号和密码" }
            require(cleanUser.length <= 254 && password.length <= 256) { "账号或密码过长" }
            val uuid = fetchUuid()
            val body = JSONObject()
                .put("loginName", cleanUser)
                .put("loginPwd", password)
                .toString()
            val root = checkResponse(
                request("POST", url("/ws/login", uuid, emptyMap()), body = body)
            )
            val token = root.optJSONObject("data")?.optString("appToken").orEmpty()
            require(token.matches(Regex("[A-Za-z0-9:_-]{8,512}"))) {
                "小飞机未返回有效登录凭据"
            }
            FeijipanLogin(token, uuid, account(token, uuid))
        }

    suspend fun account(token: String, uuid: String): FeijipanAccount =
        withContext(Dispatchers.IO) {
            require(token.isNotBlank() && uuid.isNotBlank()) { "小飞机登录信息不完整" }
            val accountRoot = checkResponse(
                request("GET", url("/app/user/account/map", uuid, emptyMap(), token))
            )
            val infoRoot = checkResponse(
                request("GET", url("/app/user/info/map", uuid, emptyMap(), token))
            )
            val account = accountRoot.optJSONObject("map")
                ?: accountRoot.optJSONObject("data")
                ?: throw IllegalStateException("小飞机账号信息格式异常")
            val info = infoRoot.optJSONObject("map")
                ?: infoRoot.optJSONObject("data")
                ?: JSONObject()
            val userId = account.optString("userId")
            require(userId.matches(Regex("\\d+"))) { "小飞机未返回有效的账号身份" }
            val infoId = info.optString("userId")
            require(infoId.isBlank() || infoId == userId) { "小飞机账号身份校验失败" }
            val nickname = info.optString("userName")
                .ifBlank { account.optString("nickName") }
                .ifBlank { account.optString("nickname") }
                .ifBlank { account.optString("account") }
                .ifBlank { "小飞机用户" }
            val used = account.optLong("usedSize").coerceAtLeast(0L) * 1024L
            val total = (
                account.optLong("totalSize") +
                    account.optLong("vipSize") +
                    account.optLong("contractSize") +
                    account.optLong("rewardSize")
                ).coerceAtLeast(0L) * 1024L
            FeijipanAccount(nickname, userId, used, total)
        }

    suspend fun personalFiles(
        token: String,
        uuid: String,
        parent: String?,
    ): List<ShareFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ShareFile>()
        val seen = mutableSetOf<String>()
        val folder = parent
            ?.removePrefix("d:")
            ?.takeUnless { it == "0" || it == "root" || it.isBlank() }
            ?: "0"
        var page = 1
        while (page <= 1000) {
            val extra = linkedMapOf(
                "offset" to page.toString(),
                "limit" to "60",
                "folderId" to folder,
                "type" to "0",
            )
            val root = checkResponse(
                request("GET", url("/app/record/file/list", uuid, extra, token))
            )
            val rows = root.optJSONArray("list")
                ?: throw IllegalStateException("小飞机文件列表格式异常")
            var added = 0
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val item = parseFile(row, parent ?: "root")
                if (!seen.add(item.fid)) continue
                result += item
                added++
            }
            val pages = root.optInt("totalPage", 0)
            if (pages > 0 && page >= pages) break
            if (pages <= 0 && rows.length() < 60) break
            require(added > 0) { "小飞机文件分页异常，请重新打开目录" }
            page++
        }
        result
    }

    suspend fun personalDownload(
        token: String,
        uuid: String,
        userId: String,
        fileId: String,
    ): DownloadLink = withContext(Dispatchers.IO) {
        val rawId = fileId.removePrefix("f:").removePrefix("d:")
        require(rawId.matches(Regex("\\d+"))) { "小飞机文件标识无效" }
        val stamp = System.currentTimeMillis()
        val extra = linkedMapOf(
            "enable" to "1",
            "downloadId" to encrypt("$rawId|$userId"),
            "auth" to encrypt("$rawId|$stamp"),
        )
        request(
            "GET",
            url("/ws/file/redirect", uuid, extra, token, stamp),
            followRedirects = false,
        ).use { response ->
            var location = response.header("Location").orEmpty()
            if (response.isSuccessful && location.isBlank()) {
                val root = checkResponse(response)
                location = root.optString("url").ifBlank {
                    root.optJSONObject("data")?.optString("url").orEmpty()
                }
            } else if (response.code !in 300..399) {
                checkResponse(response)
            }
            require(location.startsWith("https://") || location.startsWith("http://")) {
                "小飞机未返回下载地址"
            }
            DownloadLink(fileId, "", location, 0L)
        }
    }

    private fun page(
        shareId: String,
        code: String,
        uuid: String,
        token: String,
        parent: String?,
        page: Int,
    ): JSONObject {
        val root = parent == null || parent == "root" || parent == "0"
        val path = if (root) "/ws/recommend/list" else "/ws/share/list"
        val extra = linkedMapOf(
            "shareId" to shareId,
            "code" to code,
            "offset" to page.toString(),
            "limit" to "60",
        )
        if (root) extra["type"] = "0" else extra["folderId"] = parent!!.removePrefix("d:")
        val response = request("POST", url(path, uuid, extra, token), body = "{}")
        val data = checkResponse(response)
        val status = data.optInt("status", 0)
        if (status == 1 || status == 2) throw IllegalStateException("小飞机分享需要正确的提取码")
        if (status == -1) throw IllegalStateException("小飞机分享已失效或暂不可用")
        return data
    }

    private fun fetchUuid(): String {
        val response = request("GET", url("/ws/getUuid", "", emptyMap()))
        val value = checkResponse(response).optString("uuid")
        if (!value.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
            throw IllegalStateException("小飞机未返回有效设备标识")
        }
        return value
    }

    private fun parseCredential(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun parseFile(row: JSONObject, parent: String): ShareFile {
        val type = row.optInt("fileType")
        require(type == 1 || type == 2) { "小飞机返回了不支持的文件类型" }
        val directory = type == 2
        val raw = row.optString(if (directory) "folderId" else "fileId")
        val name = row.optString(if (directory) "folderName" else "fileName")
        require(raw.matches(Regex("\\d+")) && name.isNotBlank()) { "小飞机文件信息不完整" }
        val sizeKb = row.optString("fileSize").toDoubleOrNull() ?: 0.0
        return ShareFile(
            fid = "${if (directory) "d" else "f"}:$raw",
            fname = name,
            fsize = if (directory) 0 else (sizeKb * 1024).toLong(),
            isdir = directory,
            pdirFid = parent,
            fidToken = "",
        )
    }

    private fun checkResponse(response: okhttp3.Response): JSONObject {
        response.use {
            val data = JSONObject(it.body?.string().orEmpty())
            if (it.code == 401 || data.optInt("code") == -2) {
                throw IllegalStateException("小飞机登录已过期")
            }
            if (!it.isSuccessful || data.optInt("code") != 200) {
                val code = data.optString("code").ifBlank { it.code.toString() }
                throw IllegalStateException("小飞机请求失败（$code）")
            }
            return data
        }
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        followRedirects: Boolean = true,
    ): okhttp3.Response {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Origin", WEB).header("Referer", "$WEB/").header("Accept-Language", "zh-CN")
        if (method == "POST") builder.post((body ?: "").toRequestBody("application/json".toMediaType()))
        val client = clientProvider()
        val callClient = if (followRedirects) {
            client
        } else {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        return callClient.newCall(builder.build()).execute()
    }

    private fun url(
        path: String,
        uuid: String,
        extra: Map<String, String>,
        token: String = "",
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val values = linkedMapOf(
            "uuid" to uuid,
            "devType" to "6",
            "devCode" to uuid,
            "devModel" to "chrome",
            "devVersion" to "131",
            "appVersion" to "",
            "timestamp" to encrypt(timestamp.toString()),
        )
        if (token.isNotBlank()) values["appToken"] = token
        if (path != "/ws/file/redirect") values["extra"] = "2"
        values.putAll(extra)
        val query = values.entries.joinToString("&") { (key, value) ->
            val encoded = encode(value)
            "$key=${if (key == "appToken") encoded.replace("%3A", ":") else encoded}"
        }
        return "$API$path?$query"
    }

    private fun encrypt(value: String): String = aes(value, Cipher.ENCRYPT_MODE).joinToString("") { "%02x".format(it) }

    private fun decryptDownloadUrl(value: String): String {
        val bytes = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        val url = cipher.doFinal(bytes).toString(StandardCharsets.UTF_8)
        require(url.startsWith("https://") || url.startsWith("http://")) { "小飞机下载地址无效" }
        return url
    }

    private fun aes(value: String, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(AES_KEY, "AES"))
        return cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val API = "https://api.feijipan.com"
        const val WEB = "https://www.feijipan.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        val AES_KEY = "dingHao-disk-app".toByteArray(StandardCharsets.UTF_8)
    }
}
