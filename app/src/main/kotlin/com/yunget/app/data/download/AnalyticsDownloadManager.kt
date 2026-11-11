package com.yunget.app.data.download

import com.yunget.app.data.analytics.AnonymousAnalytics
import com.yunget.app.data.analytics.analyticsDurationBucket
import com.yunget.app.data.analytics.analyticsErrorCategory
import com.yunget.app.data.analytics.analyticsSizeBucket
import com.yunget.app.data.db.DownloadTaskEntity
import java.util.concurrent.ConcurrentHashMap
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
    private val lastStatus = ConcurrentHashMap<Long, Int>()
    private val lastStage = ConcurrentHashMap<Long, String>()
    private var primed = false

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
                put("engine", engine)
                if (platform.isNotBlank()) put("source", platform.lowercase().take(24))
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
        if (!primed) {
            tasks.forEach { task ->
                lastStatus[task.id] = task.status
                lastStage[task.id] = task.pipelineStage
            }
            primed = true
            return
        }
        tasks.forEach { task ->
            val oldStatus = lastStatus[task.id]
            val oldStage = lastStage[task.id]
            if (oldStatus != null && oldStatus != task.status) {
                when (task.status) {
                    DownloadTaskEntity.STATUS_DOWNLOADING -> trackStarted(task)
                    DownloadTaskEntity.STATUS_COMPLETED -> trackCompleted(task)
                    DownloadTaskEntity.STATUS_FAILED -> trackFailed(task, "download_failed")
                }
            }
            if (task.pipelineStage == "SAVE_FAILED" && oldStage != "SAVE_FAILED") {
                trackFailed(task, "save_failed")
            }
            lastStatus[task.id] = task.status
            lastStage[task.id] = task.pipelineStage
        }
        val existing = tasks.mapTo(HashSet()) { it.id }
        lastStatus.keys.removeIf { it !in existing }
        lastStage.keys.removeIf { it !in existing }
    }

    private fun trackStarted(task: DownloadTaskEntity) {
        AnonymousAnalytics.track(
            "download_started",
            buildMap {
                put("engine", engine)
                if (task.platform.isNotBlank()) put("source", task.platform.lowercase().take(24))
                put("size_bucket", analyticsSizeBucket(task.totalSize))
            },
        )
    }

    private fun trackCompleted(task: DownloadTaskEntity) {
        AnonymousAnalytics.track(
            "download_completed",
            buildMap {
                put("engine", engine)
                if (task.platform.isNotBlank()) put("source", task.platform.lowercase().take(24))
                put("size_bucket", analyticsSizeBucket(task.totalSize))
                put("duration_bucket", analyticsDurationBucket(System.currentTimeMillis() - task.createTime))
            },
        )
    }

    private fun trackFailed(task: DownloadTaskEntity, event: String) {
        AnonymousAnalytics.track(
            event,
            buildMap {
                put("engine", engine)
                if (task.platform.isNotBlank()) put("source", task.platform.lowercase().take(24))
                put("error", analyticsErrorCategory(task.errorMsg))
                put("size_bucket", analyticsSizeBucket(task.totalSize))
            },
        )
    }
}
