package com.yunget.app.data.network

/**
 * Shared diagnostic log bridge for pure-JVM `:core` modules.
 * Hosts (Android or Desktop) can install a sink at startup.
 */
object CoreLog {
    const val NETWORK = "network"

    @Volatile
    private var eventSink: (String, String, String) -> Unit = { _, _, _ -> }

    @Volatile
    private var warnSink: (String, String, String) -> Unit = { _, _, _ -> }

    @Volatile
    private var errorSink: (String, String, String, Throwable?) -> Unit = { _, _, _, _ -> }

    fun install(
        onEvent: (category: String, tag: String, summary: String) -> Unit,
        onWarn: (category: String, tag: String, summary: String) -> Unit,
        onError: (category: String, tag: String, summary: String, throwable: Throwable?) -> Unit,
    ) {
        eventSink = onEvent
        warnSink = onWarn
        errorSink = onError
    }

    fun event(category: String, tag: String, summary: String = "") {
        eventSink(category, tag, summary)
    }

    fun warn(category: String, tag: String, summary: String = "") {
        warnSink(category, tag, summary)
    }

    fun error(category: String, tag: String, summary: String = "", throwable: Throwable? = null) {
        errorSink(category, tag, summary, throwable)
    }
}
