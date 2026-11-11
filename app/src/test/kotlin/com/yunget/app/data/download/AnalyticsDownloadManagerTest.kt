package com.yunget.app.data.download

import com.yunget.app.data.analytics.analyticsErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsDownloadManagerTest {
    @Test
    fun commonDownloadFailuresAreReducedToStableCategories() {
        assertEquals("auth", analyticsErrorCategory("HTTP 403 forbidden"))
        assertEquals("rate_limited", analyticsErrorCategory("HTTP 503 retry later"))
        assertEquals("storage", analyticsErrorCategory("No space left on device"))
    }
}
