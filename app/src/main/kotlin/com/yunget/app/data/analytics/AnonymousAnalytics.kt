package com.yunget.app.data.analytics

import android.content.Context
import android.os.Build
import com.yunget.app.data.community.CommunityApi
import com.yunget.app.data.prefs.SettingsRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Privacy-first, self-hosted anonymous telemetry.
 *
 * Events never contain URLs, file names, account IDs, cookies or QR payloads. The
 * server stores only an HMAC of a random installation UUID, so the dashboard can
 * count distinct installs without retaining the raw identifier.
 */
object AnonymousAnalytics {
    const val DISCLOSURE_VERSION = 1
    private val endpoint get() = CommunityApi.analyticsUrl
    private const val PREFS = "anonymous_analytics"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_PENDING = "pending"
    private const val MAX_PENDING = 100
    private const val MAX_BATCH = 8
    private const val FLUSH_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val flushing = AtomicBoolean(false)

    @Volatile private var context: Context? = null
    @Volatile private var enabled = true
    @Volatile private var appVersion = ""
    @Volatile private var lastFlushAt = 0L

    fun initialize(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        val settings = SettingsRepository(app)
        enabled = analyticsConsentAllowsSending(
            settings.anonymousAnalyticsEnabled,
            settings.anonymousAnalyticsNoticeVersion,
        )
        appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        if (enabled) scope.launch { flush() }
    }

    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        enabled = value
        if (value) {
            scope.launch { flush() }
            return
        }
        prefs()?.edit()?.clear()?.apply()
    }

    fun needsDisclosure(context: Context): Boolean =
        SettingsRepository(context).anonymousAnalyticsNoticeVersion < DISCLOSURE_VERSION

    fun completeDisclosure(context: Context, allowed: Boolean) {
        val settings = SettingsRepository(context)
        settings.anonymousAnalyticsNoticeVersion = DISCLOSURE_VERSION
        settings.anonymousAnalyticsEnabled = allowed
        setEnabled(context, allowed)
        if (allowed) track("app_open")
    }

    fun track(event: String, properties: Map<String, Any> = emptyMap()) {
        if (!enabled || context == null) return
        scope.launch {
            enqueue(event, properties)
            val pending = pendingCount()
            val now = System.currentTimeMillis()
            if (pending >= 8 || now - lastFlushAt >= FLUSH_INTERVAL_MS) flush()
        }
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installId(): String {
        val preferences = prefs() ?: return UUID.randomUUID().toString()
        preferences.getString(KEY_INSTALL_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    @Synchronized
    private fun enqueue(event: String, properties: Map<String, Any>) {
        val preferences = prefs() ?: return
        val queue = readQueue(preferences.getString(KEY_PENDING, null))
        if (queue.length() >= MAX_PENDING) queue.remove(0)
        val payload = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("event", event)
            .put("product", "yunget")
            .put("app_version", appVersion)
            .put("platform", "android")
            .put(
                "properties",
                JSONObject().apply {
                    put("api", Build.VERSION.SDK_INT)
                    put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    properties.forEach { (key, value) ->
                        when (value) {
                            is String, is Number, is Boolean -> put(key, value)
                        }
                    }
                },
            )
        queue.put(payload)
        preferences.edit().putString(KEY_PENDING, queue.toString()).apply()
    }

    @Synchronized
    private fun pendingCount(): Int = readQueue(prefs()?.getString(KEY_PENDING, null)).length()

    @Synchronized
    private fun takeBatch(): Pair<JSONArray, List<String>> {
        val preferences = prefs() ?: return JSONArray() to emptyList()
        val queue = readQueue(preferences.getString(KEY_PENDING, null))
        val batch = JSONArray()
        val ids = mutableListOf<String>()
        val remaining = JSONArray()
        for (index in 0 until queue.length()) {
            val event = queue.optJSONObject(index) ?: continue
            if (batch.length() < MAX_BATCH) {
                batch.put(event)
                event.optString("event_id").takeIf { it.isNotBlank() }?.let(ids::add)
            } else {
                remaining.put(event)
            }
        }
        preferences.edit().putString(KEY_PENDING, remaining.toString()).apply()
        return batch to ids
    }

    @Synchronized
    private fun restoreFailedBatch(batch: JSONArray) {
        val preferences = prefs() ?: return
        val current = readQueue(preferences.getString(KEY_PENDING, null))
        val restored = JSONArray()
        for (index in 0 until batch.length()) batch.optJSONObject(index)?.let(restored::put)
        for (index in 0 until current.length()) current.optJSONObject(index)?.let(restored::put)
        while (restored.length() > MAX_PENDING) restored.remove(restored.length() - 1)
        preferences.edit().putString(KEY_PENDING, restored.toString()).apply()
    }

    private fun flush() {
        if (!enabled || context == null || !flushing.compareAndSet(false, true)) return
        var batch = JSONArray()
        try {
            val taken = takeBatch()
            batch = taken.first
            if (batch.length() == 0) return
            val body = JSONObject()
                .put("install_id", installId())
                .put("events", batch)
                .toString()
                .toRequestBody(mediaType)
            val request = Request.Builder()
                .url(endpoint)
                .header("X-YunGet-Analytics", "1")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) restoreFailedBatch(batch)
            }
            lastFlushAt = System.currentTimeMillis()
        } catch (_: Throwable) {
            if (batch.length() > 0) restoreFailedBatch(batch)
        } finally {
            flushing.set(false)
        }
    }

    private fun readQueue(raw: String?): JSONArray = runCatching {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    }.getOrElse { JSONArray() }
}
