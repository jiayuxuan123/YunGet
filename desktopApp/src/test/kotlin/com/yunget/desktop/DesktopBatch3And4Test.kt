package com.yunget.desktop

import com.yunget.app.data.network.CoreLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DesktopBatch3And4Test {

    @Test
    fun resolveHistoryAndFavoritesPersistAndDeduplicate() {
        val dir = createTempDirectory("yunget-history-test-").toFile()
        try {
            val store = DesktopResolveHistoryStore(dir)
            store.recordHistory("https://pan.quark.cn/s/abc123", "1234", "项目资料", "夸克网盘")
            store.recordHistory("https://github.com/jiayuxuan123/YunGet", "", "jiayuxuan123/YunGet", "GitHub")
            // Recording an existing link moves it to the top instead of duplicating.
            store.recordHistory("https://pan.quark.cn/s/abc123", "5678", "项目资料-更新", "夸克网盘")

            assertEquals(2, store.history.value.size)
            assertEquals("项目资料-更新", store.history.value.first().title)
            assertEquals("5678", store.history.value.first().passcode)

            assertTrue(store.toggleFavorite("https://pan.quark.cn/s/abc123", "5678", "项目资料-更新", "夸克网盘"))
            assertTrue(store.isFavorited("https://pan.quark.cn/s/abc123"))

            val reloaded = DesktopResolveHistoryStore(dir)
            assertEquals(2, reloaded.history.value.size)
            assertEquals(1, reloaded.favorites.value.size)
            assertTrue(reloaded.isFavorited("https://pan.quark.cn/s/abc123"))

            assertFalse(reloaded.toggleFavorite("https://pan.quark.cn/s/abc123", "5678", "项目资料-更新", "夸克网盘"))
            assertTrue(reloaded.favorites.value.isEmpty())
            reloaded.clearHistory()
            assertTrue(reloaded.history.value.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun settingsExportAndImportRoundTripAndProtectSecrets() {
        val dirA = createTempDirectory("yunget-cfg-a-").toFile()
        val dirB = createTempDirectory("yunget-cfg-b-").toFile()
        try {
            val storeA = DesktopSettingsStore(dirA)
            storeA.update {
                it.copy(
                    themeMode = ThemeMode.DARK,
                    maxConcurrentTasks = 6,
                    connectionsPerTask = 24,
                    speedLimitMbPerSec = 15.0,
                    minimizeToTray = false,
                    systemNotifications = false,
                    clipboardMonitor = true,
                    sftpUsername = "alice",
                    sftpPassword = "super-secret-password",
                    sftpPrivateKey = "C:\\keys\\id_ed25519",
                )
            }

            val exported = storeA.exportConfigJson(includeSftpSecrets = false)
            val exportedJson = JSONObject(exported)
            assertEquals("yunget-desktop-config-v1", exportedJson.optString("schema"))
            assertFalse("Default export must omit sftpPassword", exportedJson.has("sftpPassword"))
            assertFalse("Default export must omit sftpPrivateKey", exportedJson.has("sftpPrivateKey"))

            val storeB = DesktopSettingsStore(dirB)
            val imported = storeB.importConfigJson(exported)
            assertEquals(ThemeMode.DARK, imported.themeMode)
            assertEquals(6, imported.maxConcurrentTasks)
            assertEquals(24, imported.connectionsPerTask)
            assertEquals(15.0, imported.speedLimitMbPerSec, 0.01)
            assertFalse(imported.minimizeToTray)
            assertFalse(imported.systemNotifications)
            assertTrue(imported.clipboardMonitor)
            assertEquals("alice", imported.sftpUsername)
            assertEquals("", imported.sftpPassword)
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    @Test
    fun diagnosticLogStoreHooksCoreLogAndPersistsToFile() {
        val dir = createTempDirectory("yunget-log-test-").toFile()
        try {
            DesktopLogStore.clear()
            DesktopLogStore.install(dir)
            CoreLog.event(CoreLog.NETWORK, "test_evt", "hello-core-log")
            CoreLog.warn(CoreLog.NETWORK, "test_warn", "warn-detail")
            CoreLog.error(CoreLog.NETWORK, "test_err", "boom", IllegalStateException("bad-state"))

            val text = DesktopLogStore.exportText()
            assertTrue(text.contains("hello-core-log"))
            assertTrue(text.contains("warn-detail"))
            assertTrue(text.contains("bad-state"))

            val logFile = File(dir, "yunget-desktop.log")
            assertTrue(logFile.isFile)
            val diskText = logFile.readText()
            assertTrue(diskText.contains("hello-core-log"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateCheckerComparesSemanticVersionsCorrectly() {
        assertTrue(DesktopUpdateChecker.compareVersions("2.7.44", "2.7.43") > 0)
        assertTrue(DesktopUpdateChecker.compareVersions("v2.8.0", "2.7.99") > 0)
        assertEquals(0, DesktopUpdateChecker.compareVersions("v2.7.43", "2.7.43"))
        assertEquals(0, DesktopUpdateChecker.compareVersions("2.7.43-beta.1", "2.7.43"))
        assertTrue(DesktopUpdateChecker.compareVersions("2.7.42", "2.7.43") < 0)
    }
}
