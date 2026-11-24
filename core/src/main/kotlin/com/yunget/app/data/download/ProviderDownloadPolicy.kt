package com.yunget.app.data.download

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * 跨端共享的网盘安全并发与时效判定策略（Android 与 Windows 桌面端共用）。
 *
 * 目标：将用户的全局高并发档位（最高 512，适合直链/GitHub）与易触发风控的网盘来源解耦。
 * 各网盘安全上限仅作为**默认值**，用户可随时按单个网盘自定义调高/调低、设为跟随全局，或一键关闭兜底。
 */
object ProviderDownloadPolicy {
    /** 说明弹窗版本号：升级策略说明时递增即可在更新后向老用户提示一次。 */
    const val CONCURRENCY_NOTICE_VERSION = 1

    /** 覆盖值 0 表示该网盘明确「跟随全局线程数」，不套用默认安全封顶。 */
    const val FOLLOW_GLOBAL = 0

    data class ProviderProfile(
        val key: String,
        val displayName: String,
        val defaultSafeCap: Int,
        val strictThrottle: Boolean = false,
    )

    val managedProviders: List<ProviderProfile> = listOf(
        ProviderProfile("xunlei", "迅雷网盘", 8, strictThrottle = true),
        ProviderProfile("baidu", "百度网盘", 16, strictThrottle = true),
        ProviderProfile("uc", "UC 网盘", 16, strictThrottle = true),
        ProviderProfile("pan115", "115 网盘", 16, strictThrottle = true),
        ProviderProfile("quark", "夸克网盘", 64),
        ProviderProfile("pan123", "123 云盘", 32),
        ProviderProfile("c139", "139 移动云盘", 32),
        ProviderProfile("guangya", "光鸭云盘", 32),
        ProviderProfile("ilanzou", "蓝奏优享", 32),
        ProviderProfile("lanzou", "蓝奏云", 32),
        ProviderProfile("feijipan", "小飞机网盘", 32),
        ProviderProfile("ctfile", "城通网盘", 32),
    )

    private val profileByKey = managedProviders.associateBy { it.key }
    private val knownSources = profileByKey.keys

    /**
     * 各网盘来源的默认单任务安全并发上限。
     * 返回 null 表示通用直链/GitHub 等来源，不额外收窄用户配置上限。
     */
    fun safeConnectionCap(sourceKey: String?): Int? =
        sourceKey?.let { profileByKey[it]?.defaultSafeCap }

    /**
     * 计算任务实际使用的连接数上限：
     * 1. 若用户为该网盘设置了显式覆盖值（[providerOverrides]）：
     *    - `0`（[FOLLOW_GLOBAL]）= 直接使用全局配置 [configured]；
     *    - `> 0` = 直接使用该网盘自定义上限（可自由高于默认安全值，最高受 [maxAllowed] 约束）；
     * 2. 否则若开启了 [enforceSafeCap]，按 [safeConnectionCap] 默认安全上限兜底封顶；
     * 3. 强风控来源在连续触发 429/503（`failures > 0`）时逐轮减半退让（最低 1）。
     */
    fun effectiveConnections(
        platform: String,
        url: String,
        configured: Int,
        failures: Int = 0,
        enforceSafeCap: Boolean = true,
        providerOverrides: Map<String, Int> = emptyMap(),
        maxAllowed: Int = 512,
    ): Int {
        val ceiling = maxAllowed.coerceAtLeast(1)
        val base = configured.coerceIn(1, ceiling)
        val source = sourceKey(platform, url)
        val override = source?.let { providerOverrides[it] }
        val resolved = when {
            override != null && override == FOLLOW_GLOBAL -> base
            override != null && override > 0 -> override.coerceIn(1, ceiling)
            enforceSafeCap -> safeConnectionCap(source)?.let { minOf(base, it) } ?: base
            else -> base
        }
        val strict = source != null && profileByKey[source]?.strictThrottle == true
        if (!strict || failures <= 0) return resolved
        return (resolved shr failures.coerceIn(0, 3)).coerceAtLeast(1)
    }

    fun encodeOverrides(overrides: Map<String, Int>): String {
        val obj = JSONObject()
        overrides.forEach { (key, value) ->
            val normalized = key.trim().lowercase(Locale.ROOT)
            if (normalized in knownSources && (value == FOLLOW_GLOBAL || value in 1..512)) {
                obj.put(normalized, value)
            }
        }
        return obj.toString()
    }

    fun decodeOverrides(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim().lowercase(Locale.ROOT)
                    if (key !in knownSources) continue
                    val value = obj.optInt(key, -1)
                    if (value == FOLLOW_GLOBAL || value in 1..512) {
                        put(key, value)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** 判断一次失败是否属于直链签名/授权过期（适合触发自动换链并断点续传）。 */
    fun isExpiredSourceFailure(httpStatus: Int?, reason: String): Boolean {
        if (httpStatus in setOf(401, 403, 410)) return true
        if (httpStatus != null) return false
        val lower = reason.lowercase(Locale.ROOT)
        return reason.contains("授权/时效") ||
            lower.contains("http 401") ||
            lower.contains("http 403") ||
            lower.contains("http 410") ||
            lower.contains("403 forbidden") ||
            lower.contains("410 gone")
    }

    fun sourceKey(platform: String, url: String, sourceLabel: String = ""): String? {
        val normalized = platform.trim().lowercase(Locale.ROOT)
        if (normalized in knownSources) return normalized
        val label = sourceLabel.trim()
        if (label.isNotEmpty()) {
            when {
                label.startsWith("迅雷") -> return "xunlei"
                label.startsWith("百度") -> return "baidu"
                label.startsWith("夸克") -> return "quark"
                label.startsWith("UC", ignoreCase = true) -> return "uc"
                label.startsWith("115") -> return "pan115"
                label.startsWith("123") -> return "pan123"
                label.startsWith("139") || label.startsWith("移动") -> return "c139"
                label.startsWith("光鸭") -> return "guangya"
                label.startsWith("蓝奏优享") -> return "ilanzou"
                label.startsWith("蓝奏") -> return "lanzou"
                label.startsWith("小飞机") -> return "feijipan"
                label.startsWith("城通") -> return "ctfile"
            }
        }
        val host = runCatching {
            URI(url).host?.lowercase(Locale.ROOT)
        }.getOrNull() ?: return null
        fun under(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            under("quark.cn") -> "quark"
            under("uc.cn") || under("ucweb.com") -> "uc"
            under("xunlei.com") || under("sandai.net") -> "xunlei"
            under("baidu.com") || under("baidupcs.com") -> "baidu"
            under("115.com") || under("115cdn.com") -> "pan115"
            under("123pan.com") || under("123pan.cn") || under("123684.com") || under("123865.com") -> "pan123"
            under("139.com") || under("mcloud.139.com") || under("yun.139.com") -> "c139"
            else -> null
        }
    }
}
