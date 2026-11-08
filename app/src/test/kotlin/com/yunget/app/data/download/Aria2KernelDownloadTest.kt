package com.yunget.app.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class Aria2KernelDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = ByteArray(4096) { (it * 37).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private val origin = "https://github.com/jiayuxuan123/YunGet/releases/download/v2.7.25/${Aria2KernelDownload.NAME}"
    private val manifest get() = """{"assets":[{"name":"${Aria2KernelDownload.NAME}","browser_download_url":"$origin","size":${bytes.size},"digest":"sha256:$hash"}]}"""

    private fun client(handler: (String) -> Pair<Int, ByteArray>) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val (status, body) = handler(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }).build()

    @Test fun apiUnavailableUsesBundledManifestAndValidatesBeforePublishing() = runBlocking {
        var validated = false
        val calls = mutableListOf<String>()
        val download = Aria2KernelDownload(client { url ->
            calls += url
            if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf() else 200 to bytes
        })
        val file = download.download(temp.root, "", manifest, {}, { _, _ -> }) {
            assertArrayEquals(bytes, it.readBytes())
            assertFalse(java.io.File(temp.root, Aria2KernelDownload.NAME).exists())
            validated = true
        }
        assertTrue(validated)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(4, calls.size)
    }

    @Test fun officialMetadataWorksWithoutGithubApi() = runBlocking {
        val calls = mutableListOf<String>()
        val file = Aria2KernelDownload(client { url ->
            calls += url
            if (url == Aria2KernelDownload.metadataUrls.first())
                200 to """{"latest":$manifest}""".toByteArray() else 200 to bytes
        }).download(temp.root, "", "invalid fallback", {}, { _, _ -> }, {})
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(2, calls.size)
    }

    @Test fun edgeFailureFallsBackToConfiguredMirror() = runBlocking {
        val downloads = mutableListOf<String>()
        val file = Aria2KernelDownload(client { url ->
            if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf() else {
                downloads += url
                if (url.startsWith("https://mirror.example/")) 200 to bytes else 520 to byteArrayOf()
            }
        }).download(temp.root, "https://mirror.example/", manifest, {}, { _, _ -> }, {})
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(2, downloads.size)
        assertEquals("https://mirror.example/$origin", downloads.last())
    }

    @Test fun failedEdgeAndMirrorFallBackToDirectGithub() = runBlocking {
        val calls = mutableListOf<String>()
        val file = Aria2KernelDownload(client { url ->
            calls += url
            if (url == origin) 200 to bytes else 503 to byteArrayOf()
        }).download(temp.root, "https://mirror.example", manifest, {}, { _, _ -> }, {})
        assertEquals(origin, calls.last())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun sameSizeCorruptMirrorIsRejectedAndNextRouteIsUsed() = runBlocking {
        var validations = 0
        val file = Aria2KernelDownload(client { url ->
            if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf()
            else 200 to if (url == origin) bytes else ByteArray(bytes.size)
        }).download(temp.root, "", manifest, {}, { _, _ -> }, { validations++ })
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, validations)
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test fun incompleteDownloadsNeverPublishOrReachSignatureValidation() = runBlocking {
        try {
            Aria2KernelDownload(client { url ->
                if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf()
                else 200 to bytes.copyOf(12)
            }).download(temp.root, "", manifest, {}, { _, _ -> }, { fail("incomplete APK validated") })
            fail("incomplete download accepted")
        } catch (e: IOException) { assertTrue(e.message!!.contains("不完整")) }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun untrustedApkNeverPublishes() = runBlocking {
        try {
            Aria2KernelDownload(client { url ->
                if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf() else 200 to bytes
            }).download(temp.root, "", manifest, {}, { _, _ -> }, { throw SecurityException("wrong signer") })
            fail("untrusted APK published")
        } catch (e: IOException) { assertTrue(e.message!!.contains("wrong signer")) }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDoesNotStartAnotherRoute() = runBlocking {
        var transfers = 0
        try {
            Aria2KernelDownload(client { url ->
                if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf()
                else { transfers++; 200 to bytes }
            }).download(temp.root, "", manifest, {}, { _, _ -> throw CancellationException("cancel") }, {})
            fail("cancel ignored")
        } catch (_: CancellationException) { }
        assertEquals(1, transfers)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun cachedPackageIsRevalidatedAndNotDownloadedAgain() = runBlocking {
        java.io.File(temp.root, Aria2KernelDownload.NAME).writeBytes(bytes)
        var validated = false
        Aria2KernelDownload(client { url ->
            assertTrue(url in Aria2KernelDownload.metadataUrls)
            403 to byteArrayOf()
        }).download(temp.root, "", manifest, {}, { _, _ -> fail("cached APK re-downloaded") }, { validated = true })
        assertTrue(validated)
    }

    @Test fun metadataRejectsWrongRepositorySizeAndMissingHash() {
        val item = JSONObject(manifest).getJSONArray("assets").getJSONObject(0)
        val cases = listOf(
            JSONObject(item.toString()).put("browser_download_url", origin.replace("YunGet/", "other/")),
            JSONObject(item.toString()).put("size", 0),
            JSONObject(item.toString()).put("digest", ""),
        )
        for (bad in cases) {
            try {
                Aria2KernelDownload.parseAsset("""{"assets":[$bad]}""")
                fail("invalid metadata accepted: $bad")
            } catch (_: IOException) { }
        }
    }

    @Test fun oversizedResponseFailsBeforeValidation() = runBlocking {
        try {
            Aria2KernelDownload(client { url ->
                if (url in Aria2KernelDownload.metadataUrls) 403 to byteArrayOf()
                else 200 to bytes.copyOf(bytes.size + 1)
            }).download(temp.root, "", manifest, {}, { _, _ -> }, { fail("oversize validated") })
            fail("oversize accepted")
        } catch (e: IOException) { assertTrue(e.message!!.contains("超过")) }
    }

    @Test fun insecureOrInvalidMirrorCannotReceivePackageRequest() {
        val asset = Aria2KernelDownload.parseAsset(manifest)
        for (mirror in listOf("http://mirror.example/", "https://user:x@mirror.example/", "https://mirror.example/?url=")) {
            assertEquals(2, Aria2KernelDownload.downloadUrls(asset, mirror).size)
        }
    }
}
