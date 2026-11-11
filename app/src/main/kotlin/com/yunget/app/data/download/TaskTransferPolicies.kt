package com.yunget.app.data.download

import dev.turbodl.core.AdaptiveConcurrencyMode
import dev.turbodl.core.TaskTransferPolicy
import dev.turbodl.core.TurboConfig
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * 任务级传输策略的持久化边界。
 *
 * JSON 只保存引擎参数与不可逆的账号分组标识，不保存 Cookie 或原始账号 ID。
 * 旧任务仍是 `{}`，[decode] 返回 null 后调用方应保持原来的 submit 行为。
 */
internal object TaskTransferPolicies {
    private const val VERSION = 1
    private const val HASH_PREFIX_LENGTH = 16
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val DEFAULT_LABEL = "默认策略"

    private const val FIELD_VERSION = "version"
    private const val FIELD_CONNECTIONS = "connections"
    private const val FIELD_MIN_SEGMENT_SIZE = "minSegmentSize"
    private const val FIELD_BLOCK_SIZE = "blockSize"
    private const val FIELD_SEGMENTS_PER_CONNECTION = "segmentsPerConnection"
    private const val FIELD_SLOW_START = "slowStart"
    private const val FIELD_SLOW_START_INITIAL = "slowStartInitial"
    private const val FIELD_ADAPTIVE_CONCURRENCY_MODE = "adaptiveConcurrencyMode"
    private const val FIELD_MAX_CONNECTIONS_PER_HOST = "maxConnectionsPerHost"
    private const val FIELD_BACKPRESSURE_GROUP = "backpressureGroup"

    /**
     * 把当前有效配置冻结为任务策略。
     *
     * [accountId] 非空时只写入 `platform:sha256(accountId).take(16)`，用于同平台
     * 账号间的背压隔离；哈希前的账号 ID 不会进入 JSON。
     */
    fun encode(config: TurboConfig, platform: String, accountId: String): String {
        val json = JSONObject()
            .put(FIELD_VERSION, VERSION)
            .put(FIELD_CONNECTIONS, config.maxConnectionsPerTask)
            .put(FIELD_MIN_SEGMENT_SIZE, config.minSegmentSize)
            .put(FIELD_BLOCK_SIZE, config.blockSize)
            .put(FIELD_SEGMENTS_PER_CONNECTION, config.segmentsPerConnection)
            .put(FIELD_SLOW_START, config.slowStart)
            .put(FIELD_SLOW_START_INITIAL, config.slowStartInitial)
            .put(FIELD_ADAPTIVE_CONCURRENCY_MODE, config.adaptiveConcurrencyMode.name)
            .put(FIELD_MAX_CONNECTIONS_PER_HOST, config.maxConnectionsPerHost)

        if (accountId.isNotBlank()) {
            json.put(
                FIELD_BACKPRESSURE_GROUP,
                "$platform:${sha256Prefix(accountId)}",
            )
        }
        return json.toString()
    }

    /**
     * 恢复版本 1 的任务策略。
     *
     * 空串、`{}`、未知版本、字段类型错误或 SDK 校验失败都返回 null。这里故意
     * 不记录输入原文，避免损坏的策略 JSON 把敏感任务信息带进日志。
     */
    fun decode(json: String): TaskTransferPolicy? {
        if (json.isBlank()) return null
        val value = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (value.length() == 0) return null

        return try {
            if (value.optionalLong(FIELD_VERSION) != VERSION.toLong()) return null
            value.optionalLong(FIELD_CONNECTIONS)?.let { require(it in 1L..256L) }
            val adaptiveConcurrencyMode = value
                .optionalString(FIELD_ADAPTIVE_CONCURRENCY_MODE)
                ?.let(AdaptiveConcurrencyMode::valueOf)

            TaskTransferPolicy.builder()
                .minSegmentSize(value.optionalLong(FIELD_MIN_SEGMENT_SIZE))
                .blockSize(value.optionalLong(FIELD_BLOCK_SIZE))
                .segmentsPerConnection(value.optionalInt(FIELD_SEGMENTS_PER_CONNECTION))
                .slowStart(value.optionalBoolean(FIELD_SLOW_START))
                .slowStartInitial(value.optionalInt(FIELD_SLOW_START_INITIAL))
                .adaptiveConcurrencyMode(adaptiveConcurrencyMode)
                .maxConnectionsPerHost(value.optionalInt(FIELD_MAX_CONNECTIONS_PER_HOST))
                .backpressureGroup(value.optionalString(FIELD_BACKPRESSURE_GROUP))
                .build()
        } catch (_: Exception) {
            null
        }
    }

    fun connectionLimit(json: String, userLimit: Int): Int {
        val upper = userLimit.coerceIn(1, 256)
        if (decode(json) == null) return upper
        return runCatching {
            val saved = JSONObject(json).optionalLong(FIELD_CONNECTIONS)?.toInt() ?: upper
            minOf(saved, upper)
        }.getOrDefault(upper)
    }

    /** 返回适合任务详情展示的短标签，不暴露持久化 JSON 原文。 */
    fun label(json: String): String {
        val policy = decode(json) ?: return DEFAULT_LABEL
        val parts = buildList {
            policy.minSegmentSize?.let { add(formatBytes(it)) }
            policy.segmentsPerConnection?.let { add("$it 分片/连接") }
        }
        return parts.joinToString(" · ").ifEmpty { DEFAULT_LABEL }
    }

    private fun sha256Prefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(HASH_PREFIX_LENGTH) {
            repeat(HASH_PREFIX_LENGTH / 2) { index ->
                val byte = digest[index].toInt() and 0xff
                append(HEX_DIGITS[byte ushr 4])
                append(HEX_DIGITS[byte and 0x0f])
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val amount = if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$amount ${units[unit]}"
    }
}

private fun JSONObject.optionalLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return when (val value = get(name)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        is BigInteger -> value.toLongInRangeOrThrow(name)
        is BigDecimal -> value.toLongInRangeOrThrow(name)
        is Float -> if (value.isFinite() && value % 1f == 0f) {
            value.toLong()
        } else {
            throw IllegalArgumentException("invalid integer field: $name")
        }
        is Double -> if (value.isFinite() && value % 1.0 == 0.0) {
            value.toLong()
        } else {
            throw IllegalArgumentException("invalid integer field: $name")
        }
        else -> throw IllegalArgumentException("invalid integer field: $name")
    }
}

private val LONG_MIN_AS_BIG_INTEGER = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_AS_BIG_INTEGER = BigInteger.valueOf(Long.MAX_VALUE)

private fun BigInteger.toLongInRangeOrThrow(name: String): Long {
    if (this < LONG_MIN_AS_BIG_INTEGER || this > LONG_MAX_AS_BIG_INTEGER) {
        throw IllegalArgumentException("integer field out of range: $name")
    }
    return toLong()
}

private fun BigDecimal.toLongInRangeOrThrow(name: String): Long {
    val normalized = stripTrailingZeros()
    if (normalized.scale() > 0) {
        throw IllegalArgumentException("invalid integer field: $name")
    }
    return normalized.toBigInteger().toLongInRangeOrThrow(name)
}

private fun JSONObject.optionalInt(name: String): Int? =
    optionalLong(name)?.let { value ->
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("integer field out of range: $name")
        }
        value.toInt()
    }

private fun JSONObject.optionalBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return get(name) as? Boolean
        ?: throw IllegalArgumentException("invalid boolean field: $name")
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return get(name) as? String
        ?: throw IllegalArgumentException("invalid string field: $name")
}
