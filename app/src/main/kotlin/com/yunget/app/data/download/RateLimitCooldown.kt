package com.yunget.app.data.download

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prevents repeated manual retries from turning a source-side throttle into an account ban. */
internal object RateLimitCooldown {
    private val retryDelaysMs = longArrayOf(30_000, 120_000, 300_000, 600_000)

    fun delayMs(failures: Int): Long = retryDelaysMs[(failures - 1).coerceIn(retryDelaysMs.indices)]

    fun connectionLimit(platform: String, url: String, configured: Int, failures: Int = 0): Int {
        if (sourceKey(platform, url) != "xunlei") return configured
        return (configured.coerceAtMost(8) shr failures.coerceIn(0, 3)).coerceAtLeast(1)
    }

    private val knownSources = setOf(
        "quark", "uc", "xunlei", "baidu", "pan123", "c139", "pan115",
        "guangya", "ilanzou", "lanzou",
    )

    fun applies(reason: String, status: Int?): Boolean {
        if (status == 429 || status == 503) return true
        val normalized = reason.lowercase(Locale.ROOT)
        return normalized.contains("http 429") || normalized.contains("http 503") ||
            normalized.contains("持续被限流") || normalized.contains("较长时间限流")
    }

    fun message(status: Int?, untilMs: Long): String {
        val code = status?.let { "（HTTP $it）" }.orEmpty()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(untilMs))
        return "源站持续限流$code，已暂停本轮重试。请在 $time 后再点继续，断点已保留"
    }

    fun sourceKey(platform: String, url: String): String? {
        val normalized = platform.trim().lowercase(Locale.ROOT)
        if (normalized in knownSources) return normalized
        val host = runCatching {
            java.net.URI(url).host?.lowercase(Locale.ROOT)
        }.getOrNull() ?: return null
        fun under(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            under("quark.cn") -> "quark"
            under("uc.cn") || under("ucweb.com") -> "uc"
            under("xunlei.com") || under("sandai.net") -> "xunlei"
            under("baidu.com") || under("baidupcs.com") -> "baidu"
            under("115.com") || under("115cdn.com") -> "pan115"
            else -> null
        }
    }
}
