package com.yunget.app.data.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRefreshReferenceTest {
    @Test
    fun decodesLegacyQuarkRefreshReferenceJson() {
        val legacy = JSONObject()
            .put("version", 1)
            .put("shareId", "share-abc")
            .put("password", "1234")
            .put("fileId", "fid-1")
            .put("parentId", "0")
            .put("expectedSize", 1024L)
            .put("savedFileId", "saved-9")
            .toString()
        val decoded = TaskRefreshReference.decode(legacy)
        assertNotNull(decoded)
        assertEquals("quark", decoded!!.platform)
        assertEquals(TaskRefreshReference.MODE_SHARE, decoded.mode)
        assertEquals("share-abc", decoded.shareId)
        assertEquals("saved-9", decoded.savedFileId)
        assertEquals(1024L, decoded.expectedSize)
    }

    @Test
    fun roundTripsPersonalDriveReferencesAcrossProviders() {
        val ref = TaskRefreshReference.forDrive(
            platform = "pan123",
            fileId = "998877",
            fileName = "archive.7z",
            expectedSize = 512_000_000L,
            fidToken = "etag-abc",
            extra = mapOf("s3KeyFlag" to "181-0"),
        )
        val decoded = TaskRefreshReference.decode(ref.encode())
        assertEquals(ref, decoded)
    }

    @Test
    fun rejectsMalformedOrUnsupportedReferences() {
        assertNull(TaskRefreshReference.decode("{}"))
        assertNull(TaskRefreshReference.decode("""{"version":2,"platform":"quark","fileId":"a"}"""))
        assertNull(TaskRefreshReference.decode("""{"version":1,"platform":"unknown","fileId":"a"}"""))
        assertNull(TaskRefreshReference.decode("""{"version":1,"platform":"xunlei","fileId":"","mode":"drive"}"""))
    }

    @Test
    fun exportsCurlAndAria2CommandsWithHeaders() {
        val headers = mapOf(
            "User-Agent" to "Quark/1.0",
            "Referer" to "https://pan.quark.cn/",
            "Cookie" to "k=v",
        )
        val curl = DirectLinkExport.toCurlCommand("https://dl.example.com/a?x=1", "my file.zip", headers)
        assertTrue(curl.startsWith("curl -L -o \"my file.zip\""))
        assertTrue(curl.contains("-H \"Cookie: k=v\""))
        assertTrue(curl.contains("-H \"Referer: https://pan.quark.cn/\""))
        assertTrue(curl.contains("-H \"User-Agent: Quark/1.0\""))

        val aria2 = DirectLinkExport.toAria2Command("https://dl.example.com/a", "test.iso", headers, connections = 16)
        assertTrue(aria2.startsWith("aria2c -x 16 -s 16 -o \"test.iso\""))
        assertTrue(aria2.contains("--header=\"Cookie: k=v\""))
    }
}
