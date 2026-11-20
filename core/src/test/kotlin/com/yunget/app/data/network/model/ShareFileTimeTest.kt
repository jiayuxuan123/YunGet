package com.yunget.app.data.network.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ShareFileTimeTest {
    @Test
    fun displaysProviderTimesWithoutInventingMissingDates() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        assertEquals("2026-01-01 08:00", formatShareFileTime("1767225600", zone))
        assertEquals("2026-01-01 08:00", formatShareFileTime("1767225600000", zone))
        assertEquals("2026-01-01 16:13:24", formatShareFileTime("2026-01-01 16:13:24", zone))
        for (value in listOf("", "null", "0")) {
            assertEquals("", formatShareFileTime(value, zone))
        }
    }
}
