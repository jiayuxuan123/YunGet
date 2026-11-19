package com.yunget.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.yunget.app.data.network.C139Constants
import com.yunget.app.data.network.C139DeviceInfo
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.XunleiConstants
import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.video.VideoMedia
import com.yunget.app.data.network.video.VideoMediaKind
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.data.network.video.VideoPlatform
import com.yunget.app.ui.navigation.MainTab
import com.yunget.app.ui.theme.buildAppTypography
import com.yunget.app.ui.theme.darkScheme
import com.yunget.app.ui.theme.lightScheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Proxy
import kotlin.io.path.createTempDirectory

class DesktopAppVerificationTest {

    @Test
    fun settingsStorePersistsClampsAndReloads() {
        val root = createTempDirectory("yunget-desktop-settings-").toFile()
        try {
            val store = DesktopSettingsStore(root)
            store.update {
                it.copy(
                    themeMode = ThemeMode.DARK,
                    httpPolicy = 99,
                    maxConcurrentTasks = 99,
                    connectionsPerTask = 0,
                    speedLimitMbPerSec = 12.5,
                    proxyUrl = "  socks5://127.0.0.1:7890  ",
                    dohUrl = " https://dns.alidns.com/dns-query ",
                    conflictPolicy = ConflictPolicy.KEEP_EXISTING,
                    githubMirrorPrefix = " https://ghfast.top/ ",
                    windowWidthDp = 400,
                    windowHeightDp = 900,
                )
            }
            val reloaded = DesktopSettingsStore(root).config.value
            assertEquals(ThemeMode.DARK, reloaded.themeMode)
            assertEquals(3, reloaded.httpPolicy)
            assertEquals(8, reloaded.maxConcurrentTasks)
            assertEquals(1, reloaded.connectionsPerTask)
            assertEquals(12.5, reloaded.speedLimitMbPerSec, 0.001)
            assertEquals("socks5://127.0.0.1:7890", reloaded.proxyUrl)
            assertEquals("https://dns.alidns.com/dns-query", reloaded.dohUrl)
            assertEquals(ConflictPolicy.KEEP_EXISTING, reloaded.conflictPolicy)
            assertEquals("https://ghfast.top/", reloaded.githubMirrorPrefix)
            assertEquals(720, reloaded.windowWidthDp)
            assertEquals(900, reloaded.windowHeightDp)
            assertEquals(store.config.value.c139DeviceId, reloaded.c139DeviceId)
            assertEquals(reloaded.c139DeviceId, C139DeviceInfo.current().deviceId)
            store.update {
                it.copy(
                    sftpUsername = "alice",
                    sftpKnownHosts = "C:\\ssh\\known_hosts",
                )
            }
            val reloadedSftp = DesktopSettingsStore(root).config.value
            assertEquals("alice", reloadedSftp.sftpUsername)
            assertEquals("C:\\ssh\\known_hosts", reloadedSftp.sftpKnownHosts)
            assertEquals(
                "sftp://mirror.example.org:2222/pub/a%20b.iso",
                stripDesktopSftpUserInfo("sftp://alice:demo@mirror.example.org:2222/pub/a%20b.iso"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun xunleiFingerprintStoreRoundTripsIdentity() {
        val root = createTempDirectory("yunget-desktop-xl-fp-").toFile()
        try {
            val store = DesktopXunleiDeviceFingerprintStore(root)
            assertNull(store.load())
            val identity = XunleiDeviceFingerprint.generate()
            store.save(identity)
            assertEquals(identity, store.load())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun desktopLinkDetectorHandlesXunleiC139KoulingAndVideoLinks() {
        val xl = detectDesktopLinkTarget("https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?pwd=nw45")
        assertTrue(xl is DesktopLinkTarget.CloudShare && xl.parsed.platform == SharePlatform.XUNLEI)

        val c139 = detectDesktopLinkTarget("https://yun.139.com/shareweb/#/w/i/0A1B2C3D4E5F")
        assertTrue(c139 is DesktopLinkTarget.CloudShare && c139.parsed.platform == SharePlatform.C139)

        val c139Kouling = detectDesktopLinkTarget("分享文件 /*Abc_123:/ 提取码：a1B2")
        assertTrue(c139Kouling is DesktopLinkTarget.Kouling)
        assertEquals(SharePlatform.C139, (c139Kouling as DesktopLinkTarget.Kouling).platform)
        assertEquals("a1B2", c139Kouling.extractedPwd)

        val xlKouling = detectDesktopLinkTarget("「张三丰资源」")
        assertTrue(xlKouling is DesktopLinkTarget.Kouling)
        assertEquals(SharePlatform.XUNLEI, (xlKouling as DesktopLinkTarget.Kouling).platform)

        val videoSamples = mapOf(
            "https://www.bilibili.com/video/BV1xx411c7mD" to VideoPlatform.BILIBILI,
            "https://v.douyin.com/iRNBho6/" to VideoPlatform.DOUYIN,
            "https://www.xiaohongshu.com/explore/65a1b2c3d4e5f60718293a4b" to VideoPlatform.XHS,
            "https://v.kuaishou.com/abc123" to VideoPlatform.KUAISHOU,
            "https://weibo.com/1234567890/N1a2B3c4D" to VideoPlatform.WEIBO,
            "https://www.pixiv.net/artworks/123456789" to VideoPlatform.PIXIV,
            "https://www.acfun.cn/v/ac12345678" to VideoPlatform.ACFUN,
        )
        videoSamples.forEach { (url, expectedPlatform) ->
            val detected = detectDesktopLinkTarget(url)
            assertTrue("Expected Video target for $url, got $detected", detected is DesktopLinkTarget.Video)
            assertEquals(expectedPlatform, (detected as DesktopLinkTarget.Video).detected.platform)
        }
    }

    @Test
    fun platformHeadersDriveRootsAndVideoDownloadsMatchCorePolicies() {
        val dummyLink = DownloadLink("f1", "demo.mp4", "https://cdn.example.com/demo.mp4", 1024L)
        val xlHeaders = buildPlatformDownloadHeaders(SharePlatform.XUNLEI, "tok", dummyLink)
        assertEquals(XunleiConstants.APP_UA, xlHeaders["User-Agent"])

        val c139Headers = buildPlatformDownloadHeaders(SharePlatform.C139, "cookie", dummyLink)
        assertEquals(C139Constants.PC_UA, c139Headers["User-Agent"])
        assertEquals(C139Constants.DOWNLOAD_REFERER, c139Headers["Referer"])

        val root = createTempDirectory("yunget-desktop-svc-").toFile()
        try {
            val accounts = DesktopAccountStore(root)
            val drive = DesktopDriveService(accounts)
            assertEquals("", drive.rootDirId(DriveAccountKind.XUNLEI))
            assertEquals("/", drive.rootDirId(DriveAccountKind.C139))

            val resolve = DesktopResolveService(accounts)
            val hlsResult = VideoParseResult(
                platform = VideoPlatform.ACFUN,
                title = "演示视频",
                medias = listOf(
                    VideoMedia(
                        url = "https://cdn.acfun.cn/live/index.m3u8",
                        kind = VideoMediaKind.VIDEO,
                        fileName = "clip.m3u8",
                        isHls = true,
                        headers = mapOf("Referer" to "https://www.acfun.cn/"),
                    )
                ),
            )
            val hlsDownload = resolve.buildVideoMediaDownload(hlsResult, hlsResult.medias.first(), 0)
            assertEquals(TransferKind.HLS, hlsDownload.kind)
            assertEquals("clip.mp4", hlsDownload.fileName)
            assertEquals("https://www.acfun.cn/", hlsDownload.headers["Referer"])

            val dashResult = VideoParseResult(
                platform = VideoPlatform.BILIBILI,
                title = "高清测试",
                medias = listOf(
                    VideoMedia(
                        url = "https://cdn.bilivideo.com/video.m4s",
                        kind = VideoMediaKind.VIDEO,
                        fileName = "hd_demo.mp4",
                        headers = mapOf("Referer" to "https://www.bilibili.com/"),
                        audioUrl = "https://cdn.bilivideo.com/audio.m4s",
                        audioHeaders = mapOf("Referer" to "https://www.bilibili.com/", "Origin" to "https://www.bilibili.com"),
                    )
                ),
            )
            val audio = resolve.buildVideoAudioDownload(dashResult, dashResult.medias.first(), 0)
            assertNotNull(audio)
            assertEquals("hd_demo.m4a", audio!!.fileName)
            assertEquals("https://www.bilibili.com", audio.headers["Origin"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun proxyParserHandlesHttpSocksAndBareHost() {
        assertNull(parseJavaProxy(""))
        val http = parseJavaProxy("127.0.0.1:7890")
        assertNotNull(http)
        assertEquals(Proxy.Type.HTTP, http!!.type())
        val socks = parseJavaProxy("socks5://127.0.0.1:10808")
        assertNotNull(socks)
        assertEquals(Proxy.Type.SOCKS, socks!!.type())
    }

    @Test
    fun transferKindInferenceRecognizesAllSupportedProtocols() {
        assertEquals(TransferKind.HTTP, inferTransferKind("https://example.com/archive.zip"))
        assertEquals(TransferKind.HLS, inferTransferKind("https://cdn.example.com/live/index.m3u8?token=abc"))
        assertEquals(TransferKind.DASH, inferTransferKind("https://cdn.example.com/stream/manifest.mpd"))
        assertEquals(TransferKind.TORRENT, inferTransferKind("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertEquals(TransferKind.TORRENT, inferTransferKind("C:\\Downloads\\ubuntu.torrent"))
        assertEquals(TransferKind.METALINK, inferTransferKind("C:\\Downloads\\distro.meta4"))
    }

    @Test
    fun desktopDownloadsIgnoresLegacyObjectTasksJson() {
        assumeTrue(System.getProperty("yunget.native.bin") != null)
        loadDesktopNative()
        val root = createTempDirectory("yunget-desktop-legacy-tasks-").toFile()
        try {
            File(root, "tasks.json").writeText("""{"tasks":[{"id":1,"status":"DONE"}]}""")
            DesktopDownloads(root, allowLoopbackFixtures = true).use { downloads ->
                assertEquals(0, downloads.tasks.value.size)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun allDesktopTabsRenderCleanlyOffscreen() {
        assumeTrue(System.getProperty("yunget.native.bin") != null)
        loadDesktopNative()
        val root = createTempDirectory("yunget-desktop-ui-").toFile()
        val previewDir = File("build/ui-preview").apply { mkdirs() }
        try {
            val settingsStore = DesktopSettingsStore(root)
            val accountStore = DesktopAccountStore(root)
            val resolveService = DesktopResolveService(accountStore)
            val driveService = DesktopDriveService(accountStore)
            DesktopDownloads(root, allowLoopbackFixtures = true).use { downloads ->
                val config = settingsStore.config.value
                for (tab in MainTab.entries) {
                    for (dark in listOf(false, true)) {
                        val scene = ImageComposeScene(
                            width = 1180,
                            height = 760,
                            density = Density(1f),
                        ) {
                            MaterialTheme(
                                colorScheme = if (dark) darkScheme else lightScheme,
                                typography = buildAppTypography(),
                            ) {
                                YunGetDesktopShell(
                                    dataDirectory = root,
                                    currentTab = tab,
                                    onTabSelected = {},
                                    config = config,
                                    settingsStore = settingsStore,
                                    accountStore = accountStore,
                                    resolveService = resolveService,
                                    driveService = driveService,
                                    downloads = downloads,
                                    activeDownloadCount = 0,
                                    totalSpeedBytesPerSec = 0L,
                                    showNewDownloadDialog = false,
                                    onShowNewDownloadDialogChange = {},
                                )
                            }
                        }
                        try {
                            val image = scene.render()
                            val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                            assertNotNull("Failed to encode PNG for ${tab.name}", bytes)
                            assertTrue("Rendered PNG unexpectedly small for ${tab.name}", bytes!!.size > 5_000)
                            if (!dark || tab == MainTab.Resolve) {
                                val suffix = if (dark) "dark" else "light"
                                File(previewDir, "${tab.name.lowercase()}_$suffix.png").writeBytes(bytes)
                            }
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
