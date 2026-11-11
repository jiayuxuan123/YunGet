package com.yunget.app.ui.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCompletionHintTest {
    @Test
    fun apkCompletionSaysItCanBeInstalled() {
        val hint = downloadCompletionHint("更新.apk", hasSavedFile = true)
        assertEquals("下载完成，可以安装", hint.title)
        assertTrue(hint.available)
    }

    @Test
    fun videoCompletionSaysItCanBePreviewed() {
        val hint = downloadCompletionHint("旅行.mp4", hasSavedFile = true)
        assertEquals("下载完成，可以预览", hint.title)
        assertTrue(hint.available)
    }

    @Test
    fun genericCompletionSaysItCanBeOpened() {
        val hint = downloadCompletionHint("资料.zip", hasSavedFile = true)
        assertEquals("下载完成，可以打开", hint.title)
        assertTrue(hint.available)
    }

    @Test
    fun missingLocalFileDoesNotClaimTheFileCanBeOpened() {
        val hint = downloadCompletionHint("更新.apk", hasSavedFile = false)
        assertFalse(hint.available)
        assertTrue(hint.title.contains("本地文件已不存在"))
    }
}
