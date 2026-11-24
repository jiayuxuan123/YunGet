package com.yunget.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class DesktopPracticalFeaturesTest {
    @Test
    fun relayServerAcceptsLoopbackEnqueueAndValidatesPayload() {
        val received = CopyOnWriteArrayList<DesktopRelayRequest>()
        DesktopRelayServer(
            port = 0,
            allowLan = false,
            token = "secret-token-1234",
            onRequest = { received += it },
        ).use { server ->
            server.start()
            val base = "http://127.0.0.1:${server.boundPort}"

            val ping = (URI("$base/ping").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
            }
            assertEquals(200, ping.responseCode)
            val pingJson = JSONObject(ping.inputStream.bufferedReader().readText())
            assertTrue(pingJson.getBoolean("ok"))

            val post = (URI("$base/enqueue").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use {
                    it.write(
                        JSONObject()
                            .put("url", "https://pan.quark.cn/s/abcdef123456")
                            .put("passcode", "9988")
                            .put("mode", "resolve")
                            .put("source", "extension")
                            .toString()
                            .toByteArray()
                    )
                }
            }
            assertEquals(200, post.responseCode)
            assertEquals(1, received.size)
            assertEquals("https://pan.quark.cn/s/abcdef123456", received[0].url)
            assertEquals("9988", received[0].passcode)

            val bad = (URI("$base/enqueue").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use {
                    it.write("""{"url":"file:///etc/passwd"}""".toByteArray())
                }
            }
            assertEquals(400, bad.responseCode)
        }
    }

    @Test
    fun webDavSnapshotRoundTripsMultiAccountsBookmarksAndSettings() = runBlocking {
        val srcDir = Files.createTempDirectory("yg-dav-src").toFile()
        val dstDir = Files.createTempDirectory("yg-dav-dst").toFile()
        try {
            val srcAccounts = DesktopAccountStore(srcDir)
            srcAccounts.saveAccount(
                DesktopAccount(kind = DriveAccountKind.QUARK, credential = "cookie-primary", nickname = "主号"),
                addAsNew = false,
            )
            srcAccounts.saveAccount(
                DesktopAccount(kind = DriveAccountKind.QUARK, credential = "cookie-secondary", nickname = "小号", remark = "备用"),
                addAsNew = true,
                makePrimary = false,
            )
            val srcHistory = DesktopResolveHistoryStore(srcDir)
            srcHistory.toggleFavorite("https://pan.quark.cn/s/abc123", "4321", "资料合集", "夸克网盘")
            val srcCfg = DesktopConfig(
                connectionsPerTask = 32,
                maxConcurrentTasks = 4,
                speedLimitMbPerSec = 10.0,
                httpPolicy = 2,
            )

            val snapshot = exportDesktopWebDavSnapshot(srcAccounts, srcCfg, srcHistory)

            val dstAccounts = DesktopAccountStore(dstDir)
            val dstSettings = DesktopSettingsStore(dstDir)
            val dstHistory = DesktopResolveHistoryStore(dstDir)

            val restoredCount = restoreDesktopWebDavSnapshot(snapshot, dstAccounts, dstSettings, dstHistory)
            assertEquals(2, restoredCount)
            assertEquals("cookie-primary", dstAccounts.get(DriveAccountKind.QUARK)?.credential)
            assertEquals(2, dstAccounts.accountsOf(DriveAccountKind.QUARK).size)
            assertFalse(dstAccounts.accountsOf(DriveAccountKind.QUARK).first { it.credential == "cookie-secondary" }.isPrimary)

            assertEquals(32, dstSettings.config.value.connectionsPerTask)
            assertEquals(4, dstSettings.config.value.maxConcurrentTasks)
            assertEquals(10.0, dstSettings.config.value.speedLimitMbPerSec, 0.01)
            assertEquals(2, dstSettings.config.value.httpPolicy)

            assertEquals(1, dstHistory.favorites.value.size)
            assertEquals("https://pan.quark.cn/s/abc123", dstHistory.favorites.value.first().input)
            assertEquals("4321", dstHistory.favorites.value.first().passcode)
        } finally {
            srcDir.deleteRecursively()
            dstDir.deleteRecursively()
        }
    }

    @Test
    fun settingsStorePersistsPowerAndRelayOptions() {
        val dir = Files.createTempDirectory("yg-settings-practical").toFile()
        try {
            val store = DesktopSettingsStore(dir)
            store.update {
                it.copy(
                    preventSleepWhileDownloading = false,
                    postDownloadAction = PostDownloadAction.OPEN_FOLDER,
                    relayEnabled = true,
                    relayPort = 18888,
                    relayAllowLan = true,
                    relayToken = "tok12345678",
                    webdavUrl = "https://dav.example.com/yunget.bin",
                    webdavUser = "alice",
                    providerSafeCapEnabled = false,
                    providerThreadOverrides = mapOf("xunlei" to 32, "quark" to 0),
                    providerConcurrencyNoticeAckVersion = 1,
                )
            }
            val reloaded = DesktopSettingsStore(dir).config.value
            assertFalse(reloaded.preventSleepWhileDownloading)
            assertEquals(PostDownloadAction.OPEN_FOLDER, reloaded.postDownloadAction)
            assertEquals(18888, reloaded.relayPort)
            assertTrue(reloaded.relayAllowLan)
            assertEquals("tok12345678", reloaded.relayToken)
            assertEquals("https://dav.example.com/yunget.bin", reloaded.webdavUrl)
            assertEquals("alice", reloaded.webdavUser)
            assertFalse(reloaded.providerSafeCapEnabled)
            assertEquals(mapOf("xunlei" to 32, "quark" to 0), reloaded.providerThreadOverrides)
            assertEquals(1, reloaded.providerConcurrencyNoticeAckVersion)
        } finally {
            dir.deleteRecursively()
        }
    }
}
