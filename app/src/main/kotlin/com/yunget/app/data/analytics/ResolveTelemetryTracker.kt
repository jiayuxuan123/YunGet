package com.yunget.app.data.analytics

/** Records transitions directly; Compose snapshot coalescing must not drop results. */
internal class ResolveTelemetryTracker(private val emit: (String, Map<String, Any>) -> Unit) {
    private var active = false
    private var kind = "unknown"
    private var source = "unknown"

    fun begin(kind: String, source: String) {
        this.kind = kind
        this.source = analyticsSource(source)
        active = true
        emit("resolve_start", mapOf("kind" to kind, "source" to this.source))
    }

    fun finish(success: Boolean, error: String = "", sourceOverride: String? = null) {
        if (!active) return
        active = false
        if (analyticsErrorCategory(error) == "cancelled") return
        emit(if (success) "resolve_success" else "resolve_fail", buildMap {
            put("kind", kind)
            put("source", sourceOverride?.let { analyticsSource(it) } ?: source)
            if (!success) {
                put("error", analyticsErrorCategory(error))
                analyticsHttpStatus(error)?.takeIf { it >= 400 }?.let { put("http_status", it) }
            }
        })
    }

    fun inputReady() { if (kind == "direct" || kind == "github") finish(true) }
    fun cancel() { active = false }
}
