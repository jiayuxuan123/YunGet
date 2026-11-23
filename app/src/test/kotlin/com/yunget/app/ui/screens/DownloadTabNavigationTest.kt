package com.yunget.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTabNavigationTest {

    @Test
    fun switchesOnlyWhenNewActiveTabRequestArrives() {
        // 初始无请求不强制切页，保留用户已有页签
        assertFalse(shouldSwitchToActiveDownloadTab(requestId = 0, handledRequestId = 0))
        // 解析/网盘发起新下载，requestId 递增，必须切回正在下载
        assertTrue(shouldSwitchToActiveDownloadTab(requestId = 1, handledRequestId = 0))
        // 已消费后，用户手动切回「已完成」并反复切换主 Tab 时不再被拉回
        assertFalse(shouldSwitchToActiveDownloadTab(requestId = 1, handledRequestId = 1))
        // 下一次发起新下载再次切回正在下载
        assertTrue(shouldSwitchToActiveDownloadTab(requestId = 2, handledRequestId = 1))
    }
}
