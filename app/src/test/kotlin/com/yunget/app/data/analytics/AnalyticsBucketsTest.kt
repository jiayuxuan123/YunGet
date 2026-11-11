package com.yunget.app.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsBucketsTest {
    @Test
    fun sizeBucketsDoNotStoreExactFileSizes() {
        assertEquals("1-10MiB", analyticsSizeBucket(5L * 1024 * 1024))
        assertEquals(">=1GiB", analyticsSizeBucket(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun durationBucketsAvoidPreciseTimingFingerprints() {
        assertEquals("<5s", analyticsDurationBucket(1_200))
        assertEquals("2-10m", analyticsDurationBucket(180_000))
    }

    @Test
    fun errorCategoriesNeverNeedRawMessagesInTheDashboard() {
        assertEquals("auth", analyticsErrorCategory("HTTP 401 unauthorized"))
        assertEquals("rate_limited", analyticsErrorCategory("HTTP 429 too many requests"))
        assertEquals("checksum", analyticsErrorCategory("SHA-256 mismatch"))
        assertEquals("timeout", analyticsErrorCategory("socket timeout"))
        assertEquals("network", analyticsErrorCategory("socket connection reset"))
    }

    @Test
    fun telemetryStaysOffUntilTheDisclosureIsAccepted() {
        assertEquals(false, analyticsConsentAllowsSending(true, 0))
        assertEquals(false, analyticsConsentAllowsSending(false, 1))
        assertEquals(true, analyticsConsentAllowsSending(true, 1))
    }
}
