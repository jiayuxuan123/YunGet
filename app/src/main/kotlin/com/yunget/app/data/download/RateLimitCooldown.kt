package com.yunget.app.data.download

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prevents repeated manual retries from turning a source-side throttle into an account ban. */
internal object RateLimitCooldown {
    const val DURATION_MS = 10 * 60 * 1000L

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
}
