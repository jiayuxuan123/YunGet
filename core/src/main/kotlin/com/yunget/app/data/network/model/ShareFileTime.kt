package com.yunget.app.data.network.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 网盘可能返回 Unix 秒、毫秒或已格式化的时间。缺少时间的条目不显示日期。 */
fun formatShareFileTime(value: String, timeZone: TimeZone = TimeZone.getDefault()): String {
    val text = value.trim()
    if (text.isEmpty() || text.equals("null", ignoreCase = true)) return ""
    val timestamp = text.toLongOrNull() ?: return text
    if (timestamp <= 0) return ""
    val millis = if (timestamp < 100_000_000_000L) timestamp * 1000 else timestamp
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
        this.timeZone = timeZone
    }.format(Date(millis))
}
