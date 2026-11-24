package com.yunget.app.data.download

import org.json.JSONObject
import java.util.Locale

/**
 * 跨端共享的下载源换链引用（只存定位信息，绝不存 Cookie / Token 等凭证）。
 *
 * 兼容旧版夸克 `QuarkRefreshReference`（`version == 1` 且无 `platform` 字段时默认视作 `quark` 分享/转存）。
 */
data class TaskRefreshReference(
    val platform: String,
    val mode: String, // "drive" | "share"
    val fileId: String,
    val fileName: String = "",
    val expectedSize: Long = 0L,
    val shareId: String = "",
    val password: String = "",
    val parentId: String = "",
    val savedFileId: String = "",
    val fidToken: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    fun encode(): String {
        val obj = JSONObject()
            .put("version", VERSION)
            .put("platform", platform)
            .put("mode", mode)
            .put("fileId", fileId)
            .put("expectedSize", expectedSize)
        if (fileName.isNotEmpty()) obj.put("fileName", fileName)
        if (shareId.isNotEmpty()) obj.put("shareId", shareId)
        if (password.isNotEmpty()) obj.put("password", password)
        if (parentId.isNotEmpty()) obj.put("parentId", parentId)
        if (savedFileId.isNotEmpty()) obj.put("savedFileId", savedFileId)
        if (fidToken.isNotEmpty()) obj.put("fidToken", fidToken)
        if (extra.isNotEmpty()) {
            val extraObj = JSONObject()
            extra.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) extraObj.put(k, v) }
            if (extraObj.length() > 0) obj.put("extra", extraObj)
        }
        return obj.toString()
    }

    companion object {
        const val VERSION = 1
        const val MODE_DRIVE = "drive"
        const val MODE_SHARE = "share"

        private val supportedPlatforms = setOf("quark", "uc", "xunlei", "pan123", "c139")

        fun forDrive(
            platform: String,
            fileId: String,
            fileName: String = "",
            expectedSize: Long = 0L,
            fidToken: String = "",
            extra: Map<String, String> = emptyMap(),
        ): TaskRefreshReference = TaskRefreshReference(
            platform = platform.trim().lowercase(Locale.ROOT),
            mode = MODE_DRIVE,
            fileId = fileId,
            fileName = fileName,
            expectedSize = expectedSize.coerceAtLeast(0L),
            savedFileId = fileId,
            fidToken = fidToken,
            extra = extra,
        )

        fun forShare(
            platform: String,
            shareId: String,
            fileId: String,
            parentId: String = "",
            password: String = "",
            expectedSize: Long = 0L,
            savedFileId: String = "",
            fileName: String = "",
            fidToken: String = "",
        ): TaskRefreshReference = TaskRefreshReference(
            platform = platform.trim().lowercase(Locale.ROOT),
            mode = MODE_SHARE,
            fileId = fileId,
            fileName = fileName,
            expectedSize = expectedSize.coerceAtLeast(0L),
            shareId = shareId,
            password = password,
            parentId = parentId,
            savedFileId = savedFileId,
            fidToken = fidToken,
        )

        fun decode(raw: String): TaskRefreshReference? = runCatching {
            if (raw.isBlank() || raw.length > 8192) return@runCatching null
            val json = JSONObject(raw)
            fun longField(name: String, defaultValue: Long? = null): Long {
                if (!json.has(name)) return defaultValue ?: throw IllegalArgumentException("missing $name")
                return when (val v = json.get(name)) {
                    is Int -> v.toLong()
                    is Long -> v
                    else -> throw IllegalArgumentException("invalid integer $name")
                }
            }
            fun textField(name: String, optional: Boolean = true): String {
                if (!json.has(name)) {
                    if (optional) return ""
                    throw IllegalArgumentException("missing $name")
                }
                return json.get(name) as? String ?: throw IllegalArgumentException("invalid text $name")
            }

            require(longField("version") == VERSION.toLong())
            val platform = textField("platform").trim().lowercase(Locale.ROOT).ifBlank { "quark" }
            require(platform in supportedPlatforms)
            val fileId = textField("fileId", optional = false)
            val shareId = textField("shareId")
            val savedFileId = textField("savedFileId")
            val mode = textField("mode").trim().lowercase(Locale.ROOT).ifBlank {
                if (shareId.isNotBlank()) MODE_SHARE else MODE_DRIVE
            }
            require(mode == MODE_DRIVE || mode == MODE_SHARE)
            require(fileId.isNotBlank() && fileId.length <= 512)
            if (mode == MODE_SHARE) require(shareId.isNotBlank() || savedFileId.isNotBlank())
            val expectedSize = longField("expectedSize", 0L)
            require(expectedSize >= 0L)
            val password = textField("password")
            require(password.length <= 128)
            val extraMap = buildMap<String, String> {
                val extraObj = json.optJSONObject("extra")
                if (extraObj != null) {
                    val keys = extraObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (size >= 16) break
                        val value = extraObj.optString(key)
                        if (key.isNotBlank() && value.isNotBlank() && value.length <= 1024) {
                            put(key, value)
                        }
                    }
                }
            }
            TaskRefreshReference(
                platform = platform,
                mode = mode,
                fileId = fileId,
                fileName = textField("fileName").take(512),
                expectedSize = expectedSize,
                shareId = shareId.take(256),
                password = password,
                parentId = textField("parentId").take(256),
                savedFileId = savedFileId.take(512),
                fidToken = textField("fidToken").take(512),
                extra = extraMap,
            )
        }.getOrNull()
    }
}
