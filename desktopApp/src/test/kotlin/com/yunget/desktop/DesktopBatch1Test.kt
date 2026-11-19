package com.yunget.desktop

import com.sun.net.httpserver.HttpServer
import com.yunget.app.data.network.video.BrowserPageExtractorRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory

class DesktopBatch1Test {

    @Test
    fun dpapiEncryptsDecryptsAndMigratesPlaintextAccounts() {
        assumeTrue("Windows-only DPAPI check", DesktopCredentialCrypto.isWindows)
        val secret = "BDUSS=secret_token_12345; STOKEN=abcd_67890"
        val protectedValue = DesktopCredentialCrypto.protect(secret)
        assertTrue("Expected dpapi:v1: prefix", DesktopCredentialCrypto.isEncrypted(protectedValue))
        assertFalse("Protected payload must not contain plaintext", protectedValue.contains("secret_token_12345"))
        assertEquals(secret, DesktopCredentialCrypto.unprotect(protectedValue))

        // Legacy plaintext accounts.json should load cleanly and auto-migrate to DPAPI on disk.
        val dir = createTempDirectory("yunget-dpapi-test-").toFile()
        try {
            val accountsFile = File(dir, "accounts.json")
            val legacy = JSONArray().put(
                JSONObject()
                    .put("kind", DriveAccountKind.GUANGYA.name)
                    .put("credential", "legacy-access-token-xyz")
                    .put("nickname", "tester")
                    .put("usedBytes", 100L)
                    .put("totalBytes", 1000L)
                    .put("extra", JSONObject().put("refreshToken", "legacy-refresh-secret-999").put("deviceId", "dev123"))
                    .put("updatedAt", 123456L)
            )
            accountsFile.writeText(legacy.toString(2))

            val store = DesktopAccountStore(dir)
            val loaded = store.get(DriveAccountKind.GUANGYA)
            assertNotNull(loaded)
            assertEquals("legacy-access-token-xyz", loaded!!.credential)
            assertEquals("legacy-refresh-secret-999", loaded.extra["refreshToken"])
            assertEquals("dev123", loaded.extra["deviceId"])

            // Disk file should now be encrypted and no longer expose plaintext tokens.
            val migratedText = accountsFile.readText()
            assertTrue("Expected migrated file to use DPAPI", migratedText.contains("dpapi:v1:"))
            assertFalse("Plaintext credential should be scrubbed", migratedText.contains("legacy-access-token-xyz"))
            assertFalse("Plaintext refreshToken should be scrubbed", migratedText.contains("legacy-refresh-secret-999"))

            // Re-loading from encrypted file must recover exact values.
            val reloaded = DesktopAccountStore(dir).get(DriveAccountKind.GUANGYA)
            assertNotNull(reloaded)
            assertEquals("legacy-access-token-xyz", reloaded!!.credential)
            assertEquals("legacy-refresh-secret-999", reloaded.extra["refreshToken"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun webLoginSpecsAndDirectLoginFlagsCoverExpectedPlatforms() {
        val expectedWeb = setOf(
            DriveAccountKind.QUARK,
            DriveAccountKind.BAIDU,
            DriveAccountKind.UC,
            DriveAccountKind.XUNLEI,
            DriveAccountKind.C139,
            DriveAccountKind.PAN123,
            DriveAccountKind.PAN115,
            DriveAccountKind.LANZOU,
            DriveAccountKind.BILIBILI,
            DriveAccountKind.XHS,
            DriveAccountKind.PIXIV,
        )
        assertEquals(expectedWeb, DesktopWebLoginRegistry.allSupportedKinds())

        assertTrue(DriveAccountKind.GUANGYA.supportsPasswordLogin)
        assertTrue(DriveAccountKind.GUANGYA.supportsSmsLogin)
        assertTrue(DriveAccountKind.LANZOU.supportsPasswordLogin)
        assertTrue(DriveAccountKind.LANZOU.supportsWebLogin)
        assertTrue(DriveAccountKind.FEIJIPAN.supportsPasswordLogin)
        assertTrue(DriveAccountKind.XUNLEI.supportsWebLogin)
        assertTrue(DriveAccountKind.XUNLEI.supportsPasswordLogin)
        assertTrue(DriveAccountKind.XUNLEI.supportsSmsLogin)
        assertFalse(DriveAccountKind.GITHUB.supportsWebLogin)

        val sampleCookies = listOf(
            CdpCookie("SESSDATA", "abc", ".bilibili.com", "/"),
            CdpCookie("bili_jct", "def", ".bilibili.com", "/"),
            CdpCookie("unrelated", "zzz", ".example.com", "/"),
        )
        val filtered = filterCookiesForDomains(sampleCookies, listOf("bilibili.com"))
        val header = formatCookieHeader(filtered)
        assertEquals("SESSDATA=abc; bili_jct=def", header)
        assertTrue(DesktopWebLoginRegistry.specFor(DriveAccountKind.BILIBILI)!!.isPlausible(header))
    }

    @Test
    fun headlessCdpExtractorRendersLocalPageWithDocumentStartHook() = runBlocking {
        val browser = DesktopBrowserLocator.findBrowser()
        assumeTrue("Edge/Chrome required for headless CDP integration test", browser != null)

        val dir = createTempDirectory("yunget-cdp-test-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/test") { exchange ->
            val html = """
                <!doctype html>
                <html><body>
                  <div id="status">loading</div>
                  <script>
                    setTimeout(function() {
                      document.getElementById('status').textContent = 'ready:' + (window.__hookSeed || 'none');
                      window.__pageDone = true;
                    }, 150);
                  </script>
                </body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        try {
            val extractor = DesktopCdpBrowserPageExtractor(dir)
            BrowserPageExtractorRegistry.install(extractor)
            assertTrue(BrowserPageExtractorRegistry.current.isAvailable)

            val port = server.address.port
            val payload = BrowserPageExtractorRegistry.current.loadAndExtract(
                url = "http://127.0.0.1:$port/test",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
                readyCheckJs = "Boolean(window.__pageDone === true)",
                extractJs = "({ status: document.getElementById('status').textContent, webdriver: navigator.webdriver === true })",
                timeoutMs = 20_000L,
                pollIntervalMs = 150L,
                extraDocumentStartJs = listOf("window.__hookSeed = 'injected-ok';"),
            )
            assertNotNull("Headless CDP extractor should return JSON payload", payload)
            val json = JSONObject(payload!!)
            assertEquals("ready:injected-ok", json.optString("status"))
            assertFalse("navigator.webdriver should be hidden", json.optBoolean("webdriver", true))

            val tempRoot = File(dir, "browser-temp")
            val leftovers = tempRoot.listFiles().orEmpty()
            assertTrue("Temp browser profile should be cleaned up", leftovers.isEmpty())
        } finally {
            BrowserPageExtractorRegistry.reset()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
