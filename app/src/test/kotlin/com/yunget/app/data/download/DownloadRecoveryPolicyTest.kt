package com.yunget.app.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals

class DownloadRecoveryPolicyTest {
    @Test
    fun `unverifiable resume is explained while preserving partial bytes`() {
        val reason = "Cannot verify existing download progress; retained for a later retry"
        assertFalse(DownloadRecoveryPolicy.shouldDiscardPartial(reason))
        assertEquals("无法验证已保存的下载进度，原数据已保留。请重新解析链接后重试。", DownloadRecoveryPolicy.displayFailure(reason))
        assertEquals("HTTP 403", DownloadRecoveryPolicy.displayFailure("HTTP 403"))
    }
    @Test
    fun `network and authorization failures retain resumable bytes`() {
        assertFalse(DownloadRecoveryPolicy.shouldDiscardPartial("HTTP 403"))
        assertFalse(DownloadRecoveryPolicy.shouldDiscardPartial("连接被重置"))
        assertFalse(DownloadRecoveryPolicy.shouldDiscardPartial("HTTP 429：持续被限流"))
        assertFalse(DownloadRecoveryPolicy.shouldDiscardPartial("链接已过期，请重新解析"))
    }

    @Test
    fun `proven assembly corruption discards invalid bytes`() {
        assertTrue(DownloadRecoveryPolicy.shouldDiscardPartial("SHA-256 校验失败：期望 a，实际 b"))
        assertTrue(DownloadRecoveryPolicy.shouldDiscardPartial("大小校验失败：期望 10，实际 9"))
        assertTrue(DownloadRecoveryPolicy.shouldDiscardPartial("合并分片失败"))
    }
}
