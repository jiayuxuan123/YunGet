package com.yunget.desktop

import com.yunget.app.data.network.OfficialWebDrive
import com.yunget.app.data.network.SharePlatform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import kotlin.io.path.createTempDirectory

class DesktopWebDriveTest {
    @Test
    fun officialShareLinksUseWebDriveAndExistingCtfileSharesKeepNativeResolution() {
        val urls = mapOf(
            OfficialWebDrive.ALIYUN to "https://www.aliyundrive.com/s/example",
            OfficialWebDrive.TIANYI to "https://cloud.189.cn/t/example",
            OfficialWebDrive.WEIYUN to "https://share.weiyun.com/example",
            OfficialWebDrive.WOPAN to "https://pan.wo.cn/share/example",
            OfficialWebDrive.PIKPAK to "https://mypikpak.com/s/example",
        )
        for ((drive, url) in urls) {
            assertEquals(DesktopLinkTarget.WebDrive(drive, URI(url)), detectDesktopLinkTarget("分享 $url。 提取码 1234"))
        }
        val ctfile = detectDesktopLinkTarget("https://url.ctfile.com/f/example-1") as DesktopLinkTarget.CloudShare
        assertEquals(SharePlatform.CTFILE, ctfile.parsed.platform)
        assertFalse(OfficialWebDrive.ALIYUN.accepts(URI("https://alipan.com.invalid/s/123")))
        assertFalse(OfficialWebDrive.ALIYUN.accepts(URI("https://name@alipan.com/s/123")))
    }

    @Test
    fun clearingOneProfilePreservesOtherProvidersAndKeepsTheProfileOnReopen() = runBlocking {
        val root = createTempDirectory("yunget-web-drive-test-").toFile()
        try {
            val profiles = root.resolve("web-drive-profiles")
            val first = profiles.resolve("aliyun/Default/test-state")
            val second = profiles.resolve("tianyi/Default/test-state")
            first.parentFile.mkdirs()
            second.parentFile.mkdirs()
            first.writeText("aliyun fixture")
            second.writeText("tianyi fixture")
            val reopened = DesktopWebDriveSessions(root)
            assertEquals(setOf(OfficialWebDrive.ALIYUN, OfficialWebDrive.TIANYI), reopened.savedProfiles.value)

            reopened.clear(OfficialWebDrive.ALIYUN)
            assertFalse(profiles.resolve("aliyun").exists())
            assertTrue(second.isFile)
            assertEquals(setOf(OfficialWebDrive.TIANYI), DesktopWebDriveSessions(root).savedProfiles.value)
            reopened.clear(OfficialWebDrive.ALIYUN)
        } finally {
            root.deleteRecursively()
        }
    }
}
