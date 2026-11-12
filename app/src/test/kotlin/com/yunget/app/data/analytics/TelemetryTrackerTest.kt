package com.yunget.app.data.analytics

import com.yunget.app.data.db.DownloadTaskEntity as Task
import org.junit.Assert.*
import org.junit.Test

class TelemetryTrackerTest {
    private fun task(status: Int, generation: Long = 1, stage: String = "NETWORK", error: String = "") = Task(
        id = 1, url = "https://drive-pc.quark.cn/file?token=synthetic", fileName = "private-name.zip",
        platform = "", status = status, attemptGeneration = generation, pipelineStage = stage, errorMsg = error,
    )

    @Test fun newActiveTasksAreCountedAndResumeDoesNotCountAsANewDownload() {
        val tracker = DownloadTelemetryTracker("turbodl")
        tracker.observe(emptyList())
        assertEquals("download_started", tracker.observe(listOf(task(Task.STATUS_DOWNLOADING))).single().name)
        assertTrue(tracker.observe(listOf(task(Task.STATUS_DOWNLOADING))).isEmpty())
        tracker.observe(listOf(task(Task.STATUS_PAUSED)))
        assertEquals("download_resumed", tracker.observe(listOf(task(Task.STATUS_DOWNLOADING, 2))).single().name)
        val complete = tracker.observe(listOf(task(Task.STATUS_COMPLETED, 2, "SAVED"))).single()
        assertEquals("download_completed", complete.name)
        assertEquals("quark", complete.properties["source"])
        assertFalse(complete.properties.toString().contains("synthetic"))
        assertFalse(complete.properties.toString().contains("private-name"))
    }

    @Test fun saveFailureAndCancellationAreNotNetworkFailuresOrDoubleCounted() {
        val tracker = DownloadTelemetryTracker("turbodl")
        tracker.observe(emptyList())
        tracker.observe(listOf(task(Task.STATUS_DOWNLOADING)))
        val saved = tracker.observe(listOf(task(Task.STATUS_FAILED, stage = "SAVE_FAILED", error = "保存失败")))
        assertEquals(listOf("save_failed"), saved.map { it.name })
        assertTrue(tracker.observe(listOf(task(Task.STATUS_FAILED, stage = "SAVE_FAILED"))).isEmpty())
        tracker.observe(listOf(task(Task.STATUS_DOWNLOADING, 2)))
        assertEquals("download_cancelled", tracker.observe(listOf(task(Task.STATUS_FAILED, 2, error = "已取消"))).single().name)
    }

    @Test fun historicalCompletionsAreNotReplayedAndPausedTimeIsExcluded() {
        var now = 100L
        val tracker = DownloadTelemetryTracker("turbodl", { now })
        assertTrue(tracker.observe(listOf(task(Task.STATUS_COMPLETED))).isEmpty())
        tracker.observe(listOf(task(Task.STATUS_DOWNLOADING, 2)))
        now += 1_000
        tracker.observe(listOf(task(Task.STATUS_PAUSED, 2)))
        now += 3_600_000
        tracker.observe(listOf(task(Task.STATUS_DOWNLOADING, 2)))
        now += 1_000
        val complete = tracker.observe(listOf(task(Task.STATUS_COMPLETED, 2))).single()
        assertEquals("<5s", complete.properties["duration_bucket"])
    }

    @Test fun immediateRepeatedResolveFailuresRetainTheirOwnSource() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = ResolveTelemetryTracker { name, properties -> events += AnalyticsEvent(name, properties) }
        repeat(2) { tracker.begin("share", "uc"); tracker.finish(false, "HTTP 401") }
        assertEquals(2, events.count { it.name == "resolve_fail" })
        assertTrue(events.all { it.properties["source"] == "uc" })
        tracker.begin("direct", "direct")
        tracker.inputReady()
        tracker.finish(false, "stale error")
        assertEquals("resolve_success", events.last().name)
    }

    @Test fun terminalSdkDiagnosticsDoNotDependOnTheUiProgressStillExisting() {
        val tracker = DownloadTelemetryTracker("turbodl", diagnostics = {
            com.yunget.app.data.download.DownloadDiagnostics(null, 10, 20, 503, "probing")
        })
        tracker.observe(emptyList())
        tracker.observe(listOf(task(Task.STATUS_DOWNLOADING)))
        val failed = tracker.observe(listOf(task(Task.STATUS_FAILED, error = "来源暂时不可用"))).single()
        assertEquals(503, failed.properties["http_status"])
        assertEquals("probing", failed.properties["stage"])
        assertEquals("other", failed.properties["error"])
    }
}
