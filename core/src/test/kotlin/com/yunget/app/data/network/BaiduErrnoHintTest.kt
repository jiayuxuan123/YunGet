package com.yunget.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduErrnoHintTest {

    @Test
    fun providesClearHintForRiskControlAndAuthErrors() {
        val riskHint = baiduErrnoHint(9013)
        assertNotNull(riskHint)
        assertTrue(riskHint!!.contains("风控"))

        val authHint = baiduErrnoHint(-6)
        assertNotNull(authHint)
        assertTrue(authHint!!.contains("重新登录"))

        assertNotNull(baiduErrnoHint(-12))
        assertNotNull(baiduErrnoHint(31066))
        assertNull(baiduErrnoHint(0))
    }

    @Test
    fun shareCookieReplacesStaleBdclndInsteadOfKeepingOldShare() {
        val stale = "BDUSS=abc; BDCLND=old_share_key; STOKEN=xyz"
        assertEquals(
            "BDUSS=abc; STOKEN=xyz; BDCLND=new_key",
            withBaiduShareCookie(stale, "new_key"),
        )
        assertEquals(
            "BDUSS=abc; STOKEN=xyz",
            withBaiduShareCookie(stale, ""),
        )
        assertEquals(
            "BDUSS=abc; BDCLND=only_new",
            withBaiduShareCookie("BDUSS=abc", "only_new"),
        )
    }
}
