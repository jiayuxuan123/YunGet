package com.yunget.app.data.analytics

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** GA4 Measurement Protocol transport. The legacy self-hosted queue is no longer used. */
internal class GoogleAnalyticsUpload(
    private val queue: AnalyticsQueue,
    private val client: OkHttpClient,
    measurementId: String,
    apiSecret: String,
) {
    private val endpoint =
        "https://www.google-analytics.com/mp/collect?measurement_id=$measurementId&api_secret=$apiSecret"
    @Volatile private var call: Call? = null

    fun cancel() {
        call?.cancel()
    }

    fun send(batch: AnalyticsQueue.Batch, installId: String): Boolean {
        if (!queue.isCurrent(batch)) return false
        val source = JSONArray(batch.events)
        val events = JSONArray()
        for (index in 0 until source.length()) {
            val entry = source.optJSONObject(index) ?: continue
            val name = safeName(entry.optString("event")) ?: continue
            val params = JSONObject()
            entry.optString("app_version").takeIf { it.isNotBlank() }?.let { params.put("app_version", it) }
            entry.optString("platform").takeIf { it.isNotBlank() }?.let { params.put("platform", it) }
            entry.optJSONObject("properties")?.let { properties ->
                for (key in properties.keys()) {
                    val value = properties.opt(key)
                    if (value is String || value is Number || value is Boolean) {
                        safeName(key)?.let { params.put(it, value) }
                    }
                }
            }
            events.put(JSONObject().put("name", name).put("params", params))
        }
        if (events.length() == 0) {
            queue.acknowledge(batch)
            return true
        }
        val body = JSONObject()
            .put("client_id", installId)
            .put("events", events)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("User-Agent", "YunGet")
            .build()
        val next = client.newCall(request)
        call = next
        return try {
            if (!queue.isCurrent(batch)) return false
            next.execute().use { response ->
                if (!response.isSuccessful) false
                else {
                    queue.acknowledge(batch)
                    true
                }
            }
        } catch (_: java.io.IOException) {
            false
        } finally {
            call = null
        }
    }

    private fun safeName(raw: String): String? {
        val value = raw.trim().substringBefore(':').take(40)
            .replace(Regex("[^A-Za-z0-9_]"), "_")
        return value.takeIf { it.isNotBlank() }
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
