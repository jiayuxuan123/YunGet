package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDownloadPolicyTest {
    @Test
    fun capsRiskyProvidersWhileKeepingDirectUrlsUncapped() {
        val directUrl = "https://releases.example.com/archive.bin"
        assertEquals(512, ProviderDownloadPolicy.effectiveConnections("direct", directUrl, 512))
        assertEquals(8, ProviderDownloadPolicy.effectiveConnections("xunlei", directUrl, 512))
        assertEquals(16, ProviderDownloadPolicy.effectiveConnections("baidu", directUrl, 512))
        assertEquals(16, ProviderDownloadPolicy.effectiveConnections("uc", directUrl, 512))
        assertEquals(16, ProviderDownloadPolicy.effectiveConnections("pan115", directUrl, 512))
        assertEquals(32, ProviderDownloadPolicy.effectiveConnections("pan123", directUrl, 512))
        assertEquals(32, ProviderDownloadPolicy.effectiveConnections("c139", directUrl, 512))
        assertEquals(64, ProviderDownloadPolicy.effectiveConnections("quark", directUrl, 512))
        assertEquals(32, ProviderDownloadPolicy.effectiveConnections("quark", directUrl, 32, failures = 3))
    }

    @Test
    fun allowsUserOverridesAndDisablingSafeCap() {
        val url = "https://cdn.sandai.net/file.bin"
        // 用户手动调高迅雷到 64：立即尊重覆盖值
        assertEquals(
            64,
            ProviderDownloadPolicy.effectiveConnections(
                platform = "xunlei",
                url = url,
                configured = 16,
                providerOverrides = mapOf("xunlei" to 64),
            ),
        )
        // 用户设为 FOLLOW_GLOBAL(0)：直接跟随全局 512
        assertEquals(
            512,
            ProviderDownloadPolicy.effectiveConnections(
                platform = "xunlei",
                url = url,
                configured = 512,
                providerOverrides = mapOf("xunlei" to ProviderDownloadPolicy.FOLLOW_GLOBAL),
            ),
        )
        // 用户关闭安全兜底总开关：全部网盘直接用全局配置
        assertEquals(
            256,
            ProviderDownloadPolicy.effectiveConnections(
                platform = "baidu",
                url = "https://d.baidupcs.com/f",
                configured = 256,
                enforceSafeCap = false,
            ),
        )
        // 编解码往返一致且过滤非法值
        val encoded = ProviderDownloadPolicy.encodeOverrides(
            mapOf("xunlei" to 32, "quark" to 0, "invalid" to 64, "baidu" to 9999)
        )
        assertEquals(mapOf("xunlei" to 32, "quark" to 0), ProviderDownloadPolicy.decodeOverrides(encoded))
    }

    @Test
    fun decaysConnectionsOnConsecutiveRateLimitFailuresForStrictProviders() {
        val url = "https://cdn.sandai.net/file.bin"
        assertEquals(listOf(8, 4, 2, 1, 1), (0..4).map {
            ProviderDownloadPolicy.effectiveConnections("xunlei", url, 64, failures = it)
        })
        assertEquals(listOf(16, 8, 4, 2, 2), (0..4).map {
            ProviderDownloadPolicy.effectiveConnections("baidu", "https://d.baidupcs.com/f", 64, failures = it)
        })
    }

    @Test
    fun recognizesSourceFromLabelAndHost() {
        assertEquals("xunlei", ProviderDownloadPolicy.sourceKey("", "https://example.invalid/a", "迅雷云盘·个人盘"))
        assertEquals("pan123", ProviderDownloadPolicy.sourceKey("", "https://dl.123pan.cn/file"))
        assertEquals("c139", ProviderDownloadPolicy.sourceKey("", "https://mcloud.139.com/dl"))
        assertNull(ProviderDownloadPolicy.sourceKey("generic", "https://github.com/org/repo/releases/download/v1/a.zip"))
    }

    @Test
    fun detectsExpiredSourceStatus() {
        assertTrue(ProviderDownloadPolicy.isExpiredSourceFailure(403, ""))
        assertTrue(ProviderDownloadPolicy.isExpiredSourceFailure(410, ""))
        assertTrue(ProviderDownloadPolicy.isExpiredSourceFailure(null, "HTTP 403 Forbidden"))
        assertFalse(ProviderDownloadPolicy.isExpiredSourceFailure(503, "HTTP 503"))
        assertFalse(ProviderDownloadPolicy.isExpiredSourceFailure(404, "Not Found"))
    }
}
