package com.yunget.app.data.network.video

/** Single `Sec-CH-UA` brand/version entry derived from a User-Agent string. */
data class ClientHintBrandVersion(
    val brand: String,
    val majorVersion: String,
    val fullVersion: String,
)

/**
 * Pure-JVM User-Agent Client Hints specification derived from a User-Agent string.
 *
 * Shared by Android WebView (`WebSettingsCompat.setUserAgentMetadata`) and desktop CDP
 * (`Emulation.setUserAgentOverride`) so UA, `Sec-CH-UA*`, and `navigator.platform` stay consistent.
 */
data class ClientHintsSpec(
    val brands: List<ClientHintBrandVersion>,
    val fullVersion: String,
    val platform: String,
    val platformVersion: String,
    val architecture: String,
    val model: String,
    val mobile: Boolean,
    val wow64: Boolean = false,
    val navigatorPlatform: String? = null,
)

object ClientHints {

    private val chromeMajorRegex = Regex("Chrome/(\\d+)")

    /** Derive consistent client hints metadata for [userAgent]. */
    fun fromUserAgent(userAgent: String): ClientHintsSpec {
        val major = chromeMajorRegex.find(userAgent)?.groupValues?.getOrNull(1) ?: "131"
        val full = "$major.0.0.0"
        val brands = listOf(
            ClientHintBrandVersion("Google Chrome", major, full),
            ClientHintBrandVersion("Chromium", major, full),
            ClientHintBrandVersion("Not_A Brand", "24", "24.0.0.0"),
        )

        val isAndroid = userAgent.contains("Android")
        val isIos = userAgent.contains("iPhone") || userAgent.contains("iPad")
        val isMac = userAgent.contains("Mac OS X") && !isIos

        val platform = when {
            isAndroid -> "Android"
            isIos -> "iOS"
            isMac -> "macOS"
            else -> "Windows"
        }
        val platformVersion = when {
            isAndroid -> "14.0.0"
            isIos -> "17.0.0"
            isMac -> "14.0.0"
            else -> "10.0.0"
        }
        val navigatorPlatform = when {
            userAgent.contains("Windows") -> "Win32"
            isMac -> "MacIntel"
            else -> null
        }

        return ClientHintsSpec(
            brands = brands,
            fullVersion = full,
            platform = platform,
            platformVersion = platformVersion,
            architecture = if (isAndroid || isIos) "" else "x86",
            model = if (isAndroid) "Pixel 8" else "",
            mobile = isAndroid || isIos || userAgent.contains("Mobile"),
            wow64 = false,
            navigatorPlatform = navigatorPlatform,
        )
    }
}
