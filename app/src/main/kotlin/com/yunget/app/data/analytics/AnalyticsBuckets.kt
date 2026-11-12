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
    val status = analyticsHttpStatus(raw)
    return when {
        message.isBlank() -> "unknown"
        "cancelled" in message || "canceled" in message || "取消" in message -> "cancelled"
        "存储权限" in message || "permission denied" in message || "securityexception" in message -> "permission"
        status == 401 || status == 403 || "unauthorized" in message ||
            "forbidden" in message || "登录" in message || "授权" in message -> "auth"
        status == 404 || status == 410 || "not found" in message ||
            "不存在" in message || "失效" in message -> "gone"
        status == 412 || status == 429 || status == 503 ||
            message.contains("429/503") ||
            "too many" in message || "限流" in message -> "rate_limited"
        "checksum" in message || "sha-256" in message || "sha256" in message || "校验" in message -> "checksum"
        "handshake" in message || "ssl" in message || "tls" in message || "证书" in message -> "network"
        "content-range" in message || "range ignored" in message || "区间" in message -> "range"
        "短读" in message || "short read" in message || "不完整" in message -> "short_read"
        "html" in message || "网页" in message -> "html"
        "disk" in message || "space" in message || "存储" in message || "空间" in message ||
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

private val httpPattern = Regex("""(?i)(?:\bhttp(?:/\d(?:\.\d)?)?\s*[:=：]?\s*|\bstatus(?:\s+code)?\s*[:=：]?\s*|状态码\s*[:=：]?\s*)([1-6]\d{2})(?!\d)""")

internal fun analyticsHttpStatus(raw: String): Int? {
    // A combined '429/503' label describes a class, not one observed response.
    if (raw.contains("429/503")) return null
    return httpPattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()
}

private val analyticsSources = setOf("quark", "uc", "xunlei", "baidu", "pan123", "c139", "pan115",
    "guangya", "ilanzou", "lanzou", "github", "douyin", "weibo", "xiaohongshu", "bilibili",
    "kuaishou", "acfun", "pixiv", "direct", "other", "unknown")

internal fun analyticsSource(platform: String, url: String = ""): String {
    val normalized = platform.lowercase(java.util.Locale.ROOT)
    if (normalized in analyticsSources) return normalized
    val host = runCatching { java.net.URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
        ?: return "unknown"
    fun under(domain: String) = host == domain || host.endsWith(".$domain")
    return when {
        under("quark.cn") -> "quark"
        under("uc.cn") || under("ucweb.com") -> "uc"
        under("xunlei.com") || under("sandai.net") -> "xunlei"
        under("baidu.com") || under("baidupcs.com") -> "baidu"
        under("github.com") || under("githubusercontent.com") -> "github"
        else -> "direct"
    }
}

internal fun analyticsConsentAllowsSending(enabled: Boolean, noticeVersion: Int): Boolean =
    enabled && noticeVersion >= AnonymousAnalytics.DISCLOSURE_VERSION
