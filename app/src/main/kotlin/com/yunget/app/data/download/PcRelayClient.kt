package com.yunget.app.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 手机端推送任务到局域网 PC 云取（对应桌面端 `DesktopRelayServer`）。
 */
internal object PcRelayClient {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun push(
        endpoint: String,
        token: String,
        url: String,
        fileName: String = "",
        headers: Map<String, String> = emptyMap(),
        mode: String = "download",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = endpoint.trim().trimEnd('/').let {
                if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it"
            }
            val parsed = requireNotNull(base.toHttpUrlOrNull()) { "请输入有效的电脑端地址（如 http://192.168.1.10:17890）" }
            require(parsed.scheme == "http" || parsed.scheme == "https") { "仅支持 http 或 https 地址" }
            val targetUrl = "$base/enqueue"
            val payload = JSONObject()
                .put("url", url.trim())
                .put("fileName", fileName.trim())
                .put("mode", mode)
                .put("source", "android")
                .put("token", token.trim())
            if (headers.isNotEmpty()) {
                val headersObj = JSONObject()
                headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) headersObj.put(k, v) }
                if (headersObj.length() > 0) payload.put("headers", headersObj)
            }
            val req = Request.Builder()
                .url(targetUrl)
                .apply {
                    if (token.isNotBlank()) header("X-YunGet-Token", token.trim())
                }
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) error("配对令牌不匹配，请核对电脑端「设置 → 局域网推送」中的令牌")
                check(resp.isSuccessful) { "电脑端返回 HTTP ${resp.code}" }
            }
            "已推送到电脑端云取"
        }
    }
}
