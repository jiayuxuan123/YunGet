package com.yunget.app.data.network.video

/**
 * Pure-JVM abstraction for headless browser page rendering and JS extraction.
 *
 * Platform hosts install an implementation at startup:
 * - Android (`:app`): `HeadlessWebView` (1x1 transparent WebView attached to foreground Activity)
 * - Desktop (`:desktopApp`): Edge/Chromium CDP page runner (or unavailable)
 *
 * When no host implementation is installed, [BrowserPageExtractorRegistry.current] returns
 * a safe unavailable no-op extractor whose [loadAndExtract] always returns `null`.
 */
interface BrowserPageExtractor {

    /** Whether a headless browser runtime is currently available to load pages. */
    val isAvailable: Boolean
        get() = true

    /**
     * Load [url] with [userAgent], poll [readyCheckJs] every [pollIntervalMs] until it
     * evaluates to `"true"` or [timeoutMs] expires, then evaluate [extractJs] and return
     * its decoded JSON/string value.
     *
     * @param extraDocumentStartJs scripts injected before any page script runs.
     * @return extracted payload text (typically JSON), or `null` if unavailable, timed out, or failed.
     */
    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 25_000L,
        pollIntervalMs: Long = 350L,
        extraDocumentStartJs: List<String> = emptyList(),
    ): String?
}

/**
 * Process-wide registry for [BrowserPageExtractor].
 *
 * Defaults to a safe no-op extractor so `:core` remains pure-JVM and tests/CLI hosts
 * never fail due to missing platform bindings.
 */
object BrowserPageExtractorRegistry {

    private val noop: BrowserPageExtractor = object : BrowserPageExtractor {
        override val isAvailable: Boolean = false

        override suspend fun loadAndExtract(
            url: String,
            userAgent: String,
            readyCheckJs: String,
            extractJs: String,
            timeoutMs: Long,
            pollIntervalMs: Long,
            extraDocumentStartJs: List<String>,
        ): String? = null
    }

    @Volatile
    private var active: BrowserPageExtractor = noop

    /** Currently active extractor, or the default unavailable no-op. */
    val current: BrowserPageExtractor
        get() = active

    /** Install or replace the active extractor (called once by host startup). */
    fun install(extractor: BrowserPageExtractor) {
        active = extractor
    }

    /** Restore the default unavailable no-op extractor (useful in tests). */
    fun reset() {
        active = noop
    }
}
