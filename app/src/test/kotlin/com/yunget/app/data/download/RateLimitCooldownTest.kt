package com.yunget.app.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitCooldownTest {
    @Test fun recognizesOnlyCapacityOrThrottleFailures() {
        assertTrue(RateLimitCooldown.applies("HTTP 503", 503))
        assertTrue(RateLimitCooldown.applies("来源要求较长时间限流（HTTP 429）", 429))
        assertFalse(RateLimitCooldown.applies("HTTP 404", 404))
        assertFalse(RateLimitCooldown.applies("连接被重置", null))
    }

    @Test fun sourceCooldownUsesKnownPlatformOrHostButNeverAllDirectTraffic() {
        assertEquals("xunlei", RateLimitCooldown.sourceKey("xunlei", "https://example.invalid/file"))
        assertEquals("quark", RateLimitCooldown.sourceKey("", "https://drive-pc.quark.cn/file"))
        assertEquals(null, RateLimitCooldown.sourceKey("direct", "https://example.invalid/file"))
    }

    @Test fun xunleiRecoveryStepsDownAndWaitsLongerAfterRepeatedFailures() {
        val url = "https://example.invalid/file"
        assertEquals(listOf(8, 4, 2, 1, 1), (0..4).map { RateLimitCooldown.connectionLimit("xunlei", url, 32, it) })
        assertEquals(listOf(30_000L, 120_000L, 300_000L, 600_000L, 600_000L), (1..5).map(RateLimitCooldown::delayMs))
        assertEquals(32, RateLimitCooldown.connectionLimit("quark", url, 32, failures = 3))
    }
}
