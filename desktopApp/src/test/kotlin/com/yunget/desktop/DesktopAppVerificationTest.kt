package com.yunget.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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
