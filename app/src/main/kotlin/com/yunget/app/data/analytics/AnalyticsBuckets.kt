package com.yunget.app.data.analytics

internal fun analyticsSizeBucket(bytes: Long): String = when {
    bytes <= 0 -> "unknown"
    bytes < 1L * 1024 * 1024 -> "<1MiB"
    bytes < 10L * 1024 * 1024 -> "1-10MiB"
    bytes < 50L * 1024 * 1024 -> "10-50MiB"
    bytes < 100L * 1024 * 1024 -> "50-100MiB"
    bytes < 512L * 1024 * 1024 -> "100-512MiB"
    bytes < 1L * 1024 * 1024 * 1024 -> "512MiB-1GiB"
    else -> ">=1GiB"
}

internal fun analyticsDurationBucket(millis: Long): String = when {
    millis <= 0 -> "unknown"
    millis < 5_000 -> "<5s"
    millis < 30_000 -> "5-30s"
    millis < 120_000 -> "30-120s"
    millis < 600_000 -> "2-10m"
    millis < 3_600_000 -> "10-60m"
    else -> ">=1h"
}

internal fun analyticsErrorCategory(raw: String): String {
    val message = raw.lowercase()
    return when {
        message.isBlank() -> "unknown"
        "401" in message || "403" in message || "unauthorized" in message ||
            "forbidden" in message || "登录" in message || "授权" in message -> "auth"
        "404" in message || "410" in message || "not found" in message ||
            "不存在" in message || "失效" in message -> "gone"
        "412" in message || "429" in message || "503" in message ||
            "too many" in message || "限流" in message -> "rate_limited"
        "sha" in message || "checksum" in message || "校验" in message -> "checksum"
        "disk" in message || "space" in message || "存储" in message ||
            "enospc" in message -> "storage"
        "timeout" in message || "timed out" in message ||
            "超时" in message -> "timeout"
        "cancelled" in message || "canceled" in message ||
            "取消" in message -> "cancelled"
        "connect" in message || "network" in message || "dns" in message ||
            "socket" in message || "网络" in message -> "network"
        else -> "other"
    }
}

internal fun analyticsConsentAllowsSending(enabled: Boolean, noticeVersion: Int): Boolean =
    enabled && noticeVersion >= AnonymousAnalytics.DISCLOSURE_VERSION
