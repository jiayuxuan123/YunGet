package com.yunget.app.data.download

import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuarkRefreshReferenceTest {
    @Test
    fun `sharing file reference survives real JSON round trip`() {
        val reference = QuarkRefreshReference.from(
            session = testSession(),
            file = testFile(),
            password = TEST_PASSWORD,
        )

        assertEquals("", reference.savedFileId)
        assertJsonRoundTrip(reference)
    }

    @Test
    fun `saved file reference preserves both file ids and password`() {
        val reference = QuarkRefreshReference.from(
            session = testSession(),
            file = testFile(),
            password = TEST_PASSWORD,
            savedFileId = TEST_SAVED_FILE_ID,
        )

        assertEquals(TEST_FILE_ID, reference.fileId)
        assertEquals(TEST_SAVED_FILE_ID, reference.savedFileId)
        assertEquals(TEST_PASSWORD, reference.password)
        assertJsonRoundTrip(reference)
    }

    @Test
    fun `empty and malformed JSON are rejected`() {
        listOf("", "   ", "not-json", "[]", "{}", """{"version":1""").forEach { raw ->
            assertNull("decode must reject: $raw", QuarkRefreshReference.decode(raw))
        }
    }

    @Test
    fun `unknown versions are rejected`() {
        listOf(0, 2, 99).forEach { version ->
            val raw = JSONObject(validJson()).put("version", version).toString()
            assertNull("version $version must be rejected", QuarkRefreshReference.decode(raw))
        }
    }

    @Test
    fun `negative size and invalid required fields are rejected`() {
        val invalidDocuments = listOf(
            JSONObject(validJson()).put("expectedSize", -1L),
            JSONObject(validJson()).put("version", "not-a-number"),
            JSONObject(validJson()).put("version", "1"),
            JSONObject(validJson()).put("version", 1.5),
            JSONObject(validJson()).put("expectedSize", "4096"),
            JSONObject(validJson()).put("expectedSize", 4096.5),
            JSONObject(validJson()).put("shareId", ""),
            JSONObject(validJson()).put("shareId", "   "),
            JSONObject(validJson()).put("fileId", ""),
            JSONObject(validJson()).put("fileId", "   "),
            JSONObject(validJson()).apply { remove("shareId") },
            JSONObject(validJson()).apply { remove("fileId") },
            JSONObject(validJson()).apply { remove("parentId") },
            JSONObject(validJson()).apply { remove("expectedSize") },
            JSONObject(validJson()).put("shareId", JSONObject()),
            JSONObject(validJson()).put("fileId", 123),
            JSONObject(validJson()).put("password", JSONObject.NULL),
            JSONObject(validJson()).put("expectedSize", JSONObject()),
            JSONObject(validJson()).put("password", "x".repeat(129)),
        )

        invalidDocuments.forEachIndexed { index, document ->
            assertNull(
                "invalid document $index must be rejected: $document",
                QuarkRefreshReference.decode(document.toString()),
            )
        }
    }

    private fun assertJsonRoundTrip(expected: QuarkRefreshReference) {
        val encoded = expected.encode()
        val json = JSONObject(encoded)

        assertEquals(1, json.getInt("version"))
        assertEquals(expected.shareId, json.getString("shareId"))
        assertEquals(expected.password, json.getString("password"))
        assertEquals(expected.fileId, json.getString("fileId"))
        assertEquals(expected.parentId, json.getString("parentId"))
        assertEquals(expected.expectedSize, json.getLong("expectedSize"))
        assertEquals(expected.savedFileId, json.getString("savedFileId"))
        assertEquals(expected, QuarkRefreshReference.decode(encoded))
    }

    private fun testSession() = ShareSession(
        shareId = TEST_SHARE_ID,
        stoken = TEST_STOKEN,
        title = "Synthetic Share",
    )

    private fun testFile() = ShareFile(
        fid = TEST_FILE_ID,
        fname = "synthetic-file.bin",
        fsize = TEST_SIZE,
        isdir = false,
        pdirFid = TEST_PARENT_ID,
        fidToken = TEST_FID_TOKEN,
    )

    private fun validJson() = JSONObject()
        .put("version", 1)
        .put("shareId", TEST_SHARE_ID)
        .put("password", TEST_PASSWORD)
        .put("fileId", TEST_FILE_ID)
        .put("parentId", TEST_PARENT_ID)
        .put("expectedSize", TEST_SIZE)
        .put("savedFileId", TEST_SAVED_FILE_ID)
        .toString()

    private companion object {
        const val TEST_SHARE_ID = "synthetic-share-id"
        const val TEST_PASSWORD = "synthetic-extraction-code"
        const val TEST_STOKEN = "synthetic-stoken"
        const val TEST_FID_TOKEN = "test-fid-token"
        const val TEST_FILE_ID = "synthetic-file-id"
        const val TEST_PARENT_ID = "synthetic-parent-id"
        const val TEST_SAVED_FILE_ID = "synthetic-saved-file-id"
        const val TEST_SIZE = 4_096L
    }
}
