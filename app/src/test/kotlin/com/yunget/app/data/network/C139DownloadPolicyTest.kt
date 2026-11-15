package com.yunget.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class C139DownloadPolicyTest {
    @Test fun `signed download links always carry the official referer`() {
        val headers = C139DownloadPolicy.headers()
        assertEquals(C139Constants.PC_UA, headers["User-Agent"])
        assertEquals("https://yun.139.com/", headers["Referer"])
    }
}
