package com.yunget.app.data.analytics

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class AnalyticsUpload(
    private val queue: AnalyticsQueue,
    private val client: OkHttpClient,
    private val endpoint: String,
) {
    @Volatile private var call: Call? = null

    fun cancel() { call?.cancel() }

    /** False leaves the batch intact for an idempotent retry. */
    fun send(batch: AnalyticsQueue.Batch, installId: String): Boolean {
        if (!queue.isCurrent(batch)) return false
        val body = JSONObject().put("install_id", installId)
            .put("events", JSONArray(batch.events)).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val next = client.newCall(Request.Builder().url(endpoint)
            .header("X-YunGet-Analytics", "1").post(body).build())
        call = next
        try {
            if (!queue.isCurrent(batch)) return false
            return next.execute().use { response ->
                if (response.code != 202) false
                else { queue.acknowledge(batch); true }
            }
        } catch (_: java.io.IOException) {
            return false
        } finally { call = null }
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
}
