package com.yunget.app.data.analytics

import android.content.Context
import android.os.Build
import com.yunget.app.data.community.CommunityApi
import com.yunget.app.data.prefs.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private const val PREFS = "anonymous_analytics"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_PENDING = "pending"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        // Telemetry failure must never interrupt parsing or downloading.
    })
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var queue: AnalyticsQueue? = null
    private var uploader: AnalyticsUpload? = null
    private var appContext: Context? = null
    private var appVersion = ""

    @Synchronized fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = AnalyticsQueue(object : AnalyticsQueueStore {
            override fun read() = preferences.getString(KEY_PENDING, null)
            override fun write(value: String) { preferences.edit().putString(KEY_PENDING, value).commit() }
            override fun clear() { preferences.edit().clear().commit() }
        })
        appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        queue = pending
        appContext = app
        val settings = SettingsRepository(app)
        changeEnabled(analyticsConsentAllowsSending(settings.anonymousAnalyticsEnabled, settings.anonymousAnalyticsNoticeVersion))
    }

    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        val settings = SettingsRepository(context)
        changeEnabled(analyticsConsentAllowsSending(value, settings.anonymousAnalyticsNoticeVersion))
    }

    private fun changeEnabled(enabled: Boolean) {
        worker?.cancel()
        uploader?.cancel()
        queue?.setEnabled(enabled)
        if (!enabled) { worker = null; return }
        worker = scope.launch {
            for (signal in wake) {
                delay(500)
                while (isActive) {
                    val batch = queue?.peek() ?: break
                    val sender = synchronized(this@AnonymousAnalytics) {
                        uploader ?: AnalyticsUpload(checkNotNull(queue), AnalyticsUpload.client(), CommunityApi.analyticsUrl)
                            .also { uploader = it }
                    }
                    val identity = synchronized(this@AnonymousAnalytics) {
                        if (queue?.isCurrent(batch) == true) installId() else null
                    } ?: break
                    if (!sender.send(batch, identity)) delay(60_000)
                }
            }
        }
        wake.trySend(Unit)
    }

    fun needsDisclosure(context: Context) = SettingsRepository(context).anonymousAnalyticsNoticeVersion < DISCLOSURE_VERSION

    fun completeDisclosure(context: Context, allowed: Boolean) {
        val settings = SettingsRepository(context)
        settings.anonymousAnalyticsNoticeVersion = DISCLOSURE_VERSION
        settings.anonymousAnalyticsEnabled = allowed
        setEnabled(context, allowed)
        if (allowed) track("app_open")
    }

    fun track(event: String, properties: Map<String, Any> = emptyMap()) {
        val pending = queue ?: return
        val ticket = pending.ticket() ?: return
        scope.launch {
            val payload = JSONObject().put("event_id", UUID.randomUUID().toString())
                .put("event", event).put("product", "yunget").put("app_version", appVersion)
                .put("platform", "android").put("properties", JSONObject().apply {
                    put("schema_version", 2)
                    put("api", Build.VERSION.SDK_INT)
                    put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    properties.forEach { (key, value) -> if (value is String || value is Number || value is Boolean) put(key, value) }
                })
            if (pending.enqueue(ticket, payload)) wake.trySend(Unit)
        }
    }

    @Synchronized private fun installId(): String {
        val prefs = checkNotNull(appContext).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALL_ID, it).commit() }
    }
}
