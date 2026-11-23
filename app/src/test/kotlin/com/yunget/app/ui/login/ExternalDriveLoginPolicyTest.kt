package com.yunget.app.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDriveLoginPolicyTest {

    @Test
    fun navigationUpgradesHttpAndBlocksCustomSchemes() {
        assertEquals(
            ExternalDriveNavigationAction.Allow,
            resolveExternalDriveNavigation("https://www.weiyun.com/disk"),
        )
        assertEquals(
            ExternalDriveNavigationAction.RedirectHttps("https://www.weiyun.com/disk/index.html"),
            resolveExternalDriveNavigation("http://www.weiyun.com/disk/index.html"),
        )
        assertEquals(
            ExternalDriveNavigationAction.RedirectHttps("https://www.weiyun.com/disk"),
            resolveExternalDriveNavigation("Http://www.weiyun.com/disk"),
        )
        assertEquals(
            ExternalDriveNavigationAction.Block,
            resolveExternalDriveNavigation("http://"),
        )
        assertEquals(
            ExternalDriveNavigationAction.Block,
            resolveExternalDriveNavigation("wtloginmqq://ptlogin/qlogin"),
        )
        assertEquals(
            ExternalDriveNavigationAction.Block,
            resolveExternalDriveNavigation("weiyun://open"),
        )
    }

    @Test
    fun detectsWeiyunDiskSessionTargetsOnly() {
        assertTrue(isWeiyunSessionTarget("https://www.weiyun.com/disk"))
        assertTrue(isWeiyunSessionTarget("https://www.weiyun.com/disk/all"))
        assertTrue(isWeiyunSessionTarget("https://h5.weiyun.com/disk"))
        assertFalse(isWeiyunSessionTarget("https://www.weiyun.com/"))
        assertFalse(isWeiyunSessionTarget("https://graph.qq.com/connect/?redirect_uri=https://www.weiyun.com/disk"))
        assertFalse(isWeiyunSessionTarget("https://evilweiyun.com/disk"))
    }

    @Test
    fun browseModeUsesCleanTitleAndWeiyunClearsAuthSites() {
        assertEquals("腾讯微云登录", externalDriveScreenTitle("腾讯微云", browseMode = false))
        assertEquals("腾讯微云", externalDriveScreenTitle("腾讯微云", browseMode = true))
        val weiyun = externalDrives["weiyun"]!!
        assertTrue("qq.com" in weiyun.additionalSessionSites)
        assertTrue("open.weixin.qq.com" in weiyun.additionalSessionSites)
    }
}
