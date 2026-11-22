package com.yunget.app.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Uses an exact WebDAV file URL. The server receives only the encrypted backup. */
class WebDavBackup(url: String, private val user: String, private val serverPassword: String) {
    private val endpoint = requireNotNull(url.trim().toHttpUrlOrNull()) { "请输入完整的 HTTPS 备份文件地址" }

    init {
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null && endpoint.pathSegments.last().isNotEmpty()
        ) { "请输入完整的 HTTPS 备份文件地址，账号和密码请单独填写" }
    }

    suspend fun upload(snapshot: JSONObject, password: String) = withContext(Dispatchers.IO) {
        validate(snapshot)
        val encrypted = AuthCrypto.encrypt(snapshot.toString(), password)
        require(encrypted.length <= MAX_BYTES) { "备份超过大小限制" }
        val current = read()
        val request = request().put(encrypted.toRequestBody("application/octet-stream".toMediaType()))
        if (current == null) {
            request.header("If-None-Match", "*")
        } else {
            validate(JSONObject(AuthCrypto.decrypt(current.content, password)))
            val etag = current.etag
            require(etag != null && etag.startsWith('"') && etag.endsWith('"')) { "服务器缺少可用于安全更新的 ETag" }
            request.header("If-Match", etag)
        }
        client.newCall(request.build()).execute().use { response ->
            if (response.code == 412) error("远端备份已变化，请重新读取后再上传")
            check(response.isSuccessful) { "上传失败（HTTP ${response.code}）" }
        }
    }

    suspend fun download(password: String): JSONObject = withContext(Dispatchers.IO) {
        val current = read() ?: error("该地址还没有备份文件")
        JSONObject(AuthCrypto.decrypt(current.content, password)).also(::validate)
    }

    private data class FileVersion(val content: String, val etag: String?)

    private fun request() = Request.Builder().url(endpoint).apply {
        if (user.isNotBlank()) header("Authorization", Credentials.basic(user, serverPassword, Charsets.UTF_8))
    }

    private fun read(): FileVersion? = client.newCall(request().get().build()).execute().use { response ->
        if (response.code == 404) return null
        check(response.isSuccessful) { "读取失败（HTTP ${response.code}）" }
        val body = checkNotNull(response.body) { "备份文件为空" }
        val source = body.source()
        require(!source.request(MAX_BYTES + 1L)) { "备份超过大小限制" }
        FileVersion(source.readUtf8(), response.header("ETag"))
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(25, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname.equals("localhost", true) || hostname.endsWith(".localhost", true) ||
                        hostname.endsWith(".local", true)
                    ) throw UnknownHostException("备份地址必须使用公网主机")
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.any { !publicAddress(it) }) throw UnknownHostException("备份地址必须使用公网主机")
                    return addresses
                }
            })
            .build()

        fun validate(snapshot: JSONObject) {
            require(snapshot.optString("app") == "yunget_auth_backup" && snapshot.optInt("version") == 1 &&
                snapshot.optJSONArray("accounts") != null
            ) { "无法识别该云取备份" }
        }

        fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val bytes = address.address.map { it.toInt() and 255 }
            if (bytes.size == 4) {
                val (a, b, c) = bytes
                return a in 1..223 && !(a == 100 && b in 64..127) &&
                    !(a == 192 && b == 0 && c in setOf(0, 2)) && !(a == 198 && b in setOf(18, 19)) &&
                    !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113)
            }
            return (bytes[0] and 0xe0) == 0x20 &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
        }
    }
}
