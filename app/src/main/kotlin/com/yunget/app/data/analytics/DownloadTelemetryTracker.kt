package com.yunget.app.data.analytics

import com.yunget.app.data.db.DownloadTaskEntity

internal data class AnalyticsEvent(val name: String, val properties: Map<String, Any>)

/** Tracks observed attempts, never progress bytes or filenames. */
internal class DownloadTelemetryTracker(
    private val engine: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val diagnostics: (Long) -> com.yunget.app.data.download.DownloadDiagnostics? = { null },
) {
    private data class Observed(val status: Int, val generation: Long, var activeSince: Long?, var elapsed: Long)
    private val previous = mutableMapOf<Long, Observed>()
    private var primed = false

    fun observe(tasks: List<DownloadTaskEntity>): List<AnalyticsEvent> {
        val events = mutableListOf<AnalyticsEvent>()
        val now = clock()
        for (task in tasks) {
            val old = previous[task.id]
            val active = task.status == DownloadTaskEntity.STATUS_DOWNLOADING
            if (!primed) {
                previous[task.id] = Observed(task.status, task.attemptGeneration, if (active) now else null, 0)
                continue
            }
            if (old?.status == task.status && old.generation == task.attemptGeneration) continue
            val sameAttempt = old?.generation == task.attemptGeneration
            val elapsed = if (sameAttempt) old!!.elapsed + (old.activeSince?.let { (now - it).coerceAtLeast(0) } ?: 0) else 0
            val props = buildMap<String, Any> {
                put("engine", engine.takeIf { it in setOf("turbodl", "legacy", "aria2", "gopeed") } ?: "other")
                put("source", analyticsSource(task.platform, task.url))
                put("size_bucket", analyticsSizeBucket(task.totalSize))
                put("stage", task.pipelineStage.lowercase().takeIf { it in setOf("network", "processing", "saving", "save_failed", "saved") } ?: "unknown")
                if (elapsed > 0) put("duration_bucket", analyticsDurationBucket(elapsed))
            }
            when (task.status) {
                DownloadTaskEntity.STATUS_DOWNLOADING -> events += AnalyticsEvent(
                    if (old?.status == DownloadTaskEntity.STATUS_PAUSED || old?.status == DownloadTaskEntity.STATUS_FAILED) "download_resumed" else "download_started", props)
                DownloadTaskEntity.STATUS_COMPLETED -> events += AnalyticsEvent("download_completed", props)
                DownloadTaskEntity.STATUS_FAILED -> {
                    val error = analyticsErrorCategory(task.errorMsg)
                    val name = when {
                        error == "cancelled" -> "download_cancelled"
                        task.pipelineStage == "SAVE_FAILED" || task.pipelineStage == "SAVING" -> "save_failed"
                        task.pipelineStage == "PROCESSING" -> "processing_failed"
                        else -> "download_failed"
                    }
                    events += AnalyticsEvent(name, props + buildMap {
                        put("error", error)
                        val terminal = diagnostics(task.id)
                        (analyticsHttpStatus(task.errorMsg) ?: terminal?.lastHttpStatus)
                            ?.takeIf { it >= 400 }?.let { put("http_status", it) }
                        terminal?.failureStage?.takeIf { it in setOf("queued", "probing", "transferring", "verifying", "finalizing") }
                            ?.let { put("stage", it) }
                    })
                }
            }
            previous[task.id] = Observed(task.status, task.attemptGeneration, if (active) now else null, elapsed)
        }
        primed = true
        previous.keys.retainAll(tasks.mapTo(HashSet()) { it.id })
        return events
    }
}
