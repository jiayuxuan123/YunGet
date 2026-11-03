package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatsTest {
    @Test
    fun `connection summary does not invent an active connection`() {
        assertEquals("连接 --/16", DownloadStats(connectionLimit = 16).connectionSummary())
        assertEquals("连接 --", DownloadStats().connectionSummary())
    }

    @Test
    fun `connection summary exposes active and configured values`() {
        assertEquals(
            "12/16 连接",
            DownloadStats(chunkCount = 12, connectionLimit = 16).connectionSummary(),
        )
        assertEquals("12 连接", DownloadStats(chunkCount = 12).connectionSummary())
    }
}
