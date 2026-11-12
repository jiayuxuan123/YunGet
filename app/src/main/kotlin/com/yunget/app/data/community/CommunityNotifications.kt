package com.yunget.app.data.community

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object CommunityNotificationData {
    fun sessionCookie(raw: String?): String? = raw?.split(';')?.map(String::trim)?.firstOrNull {
        it.matches(Regex("^" + "__Host-" + "yunget-community=" + "[a-f0-9]{64}$"))
    }

    fun unread(json: String): Int = runCatching {
        if (json.length > 65_536) return 0
        val value = JSONObject(json).opt("unread") as? Number ?: return 0
        val count = value.toDouble()
        if (!count.isFinite() || count < 0 || count % 1.0 != 0.0) 0 else count.coerceAtMost(999.0).toInt()
    }.getOrDefault(0)
}

internal class CommunityNotificationClient(private val endpoint: HttpUrl) {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun unread(cookie: String?): Int {
        val session = CommunityNotificationData.sessionCookie(cookie) ?: return 0
        val request = Request.Builder().url(endpoint).header("Accept", "application/json")
            .header("Cookie", session).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume(0)
                }
                override fun onResponse(call: Call, response: Response) {
                    val count = response.use {
                        if (!it.isSuccessful || it.body?.contentType()?.subtype != "json") return@use 0
                        val source = it.body?.source() ?: return@use 0
                        runCatching {
                            source.request(65_537)
                            if (source.buffer.size > 65_536) 0 else CommunityNotificationData.unread(source.readUtf8())
                        }.getOrDefault(0)
                    }
                    if (continuation.isActive) continuation.resume(count)
                }
            })
        }
    }

    internal fun close() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
