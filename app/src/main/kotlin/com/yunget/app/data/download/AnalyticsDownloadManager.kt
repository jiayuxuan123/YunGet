package com.yunget.app.data.download

import com.yunget.app.data.analytics.AnonymousAnalytics
import com.yunget.app.data.analytics.analyticsSizeBucket
import com.yunget.app.data.analytics.DownloadTelemetryTracker
import com.yunget.app.data.analytics.analyticsSource
import com.yunget.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Transparent download-manager decorator for anonymous outcome telemetry.
 *
 * It observes the same Room flow used by the UI and reports only status changes,
 * engine name, source family, error category and coarse size/time buckets.
 */
internal class AnalyticsDownloadManager(
    private val delegate: DownloadManager,
    private val engine: String,
) : DownloadManager by delegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = DownloadTelemetryTracker(engine, diagnostics = delegate::terminalDiagnostics)

    init {
        scope.launch {
            delegate.tasks.collect { tasks -> observe(tasks) }
        }
    }

    override suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String>,
        size: Long,
        platform: String,
        fallbackUrl: String,
        altSources: List<DownloadSource>,
        mergeAudioUrl: String,
        mergeAudioHeaders: Map<String, String>,
        motionUrl: String,
        audioOnly: Boolean,
        submission: DownloadSubmissionContext,
        onComplete: suspend () -> Unit,
    ): Long {
        val id = delegate.enqueue(
            url = url,
            fileName = fileName,
            headers = headers,
            size = size,
            platform = platform,
            fallbackUrl = fallbackUrl,
            altSources = altSources,
            mergeAudioUrl = mergeAudioUrl,
            mergeAudioHeaders = mergeAudioHeaders,
            motionUrl = motionUrl,
            audioOnly = audioOnly,
            submission = submission,
            onComplete = onComplete,
        )
        AnonymousAnalytics.track(
            "download_queued",
            buildMap {
                put("engine", engine.takeIf { it in setOf("turbodl", "legacy", "aria2", "gopeed") } ?: "other")
                put("source", analyticsSource(platform, url))
                put("size_bucket", analyticsSizeBucket(size))
            },
        )
        return id
    }

    override fun shutdown() {
        scope.cancel()
        delegate.shutdown()
    }

    private fun observe(tasks: List<DownloadTaskEntity>) {
        tracker.observe(tasks).forEach { event -> AnonymousAnalytics.track(event.name, event.properties) }
    }
}
