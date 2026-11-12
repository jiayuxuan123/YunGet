package com.yunget.app.data.download

import com.yunget.app.data.analytics.analyticsErrorCategory
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SaveFailureReasonTest {
    @Test fun permissionAndDiskFullOfferDifferentRecoveryWithoutLeakingPaths() {
        val denied = DownloadRecoveryPolicy.processingFailure(SecurityException("/private/synthetic"), true)
        assertTrue(denied.contains("重新选择保存目录"))
        assertFalse(denied.contains("synthetic"))
        assertEquals("permission", analyticsErrorCategory(denied))
        val full = DownloadRecoveryPolicy.processingFailure(IOException("ENOSPC: No space left on device"), true)
        assertTrue(full.contains("释放空间"))
        assertEquals("storage", analyticsErrorCategory(full))
    }

    @Test fun processingFailureKeepsOriginalDataAndDoesNotClaimSaveFailed() {
        val message = DownloadRecoveryPolicy.processingFailure(IOException("synthetic codec error"), false)
        assertTrue(message.contains("重试处理"))
        assertFalse(message.contains("保存失败"))
    }
}
