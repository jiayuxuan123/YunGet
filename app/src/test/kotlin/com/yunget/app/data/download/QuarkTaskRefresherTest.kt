package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuarkTaskRefresherTest {
    @Test
    fun `uses original task owner for both account checks`() = runBlocking {
        val access = FakeAccess().apply {
            freshCookieResult = FRESH_COOKIE
            currentCookieResult = CURRENT_COOKIE
            savedLinkResult = downloadLink()
        }

        val result = QuarkTaskRefresher(access).refresh(task(), emptyMap())

        assertNotNull(result)
        assertEquals(listOf(SOURCE_OWNER_ID), access.freshCookieAccounts)
        assertEquals(listOf(SOURCE_OWNER_ID), access.currentCookieAccounts)
        assertFalse("different-main-account" in access.freshCookieAccounts)
        assertFalse("different-main-account" in access.currentCookieAccounts)
    }

    @Test
    fun `replaces cookie case insensitively and preserves UA and Referer`() = runBlocking {
        val access = FakeAccess().apply {
            freshCookieResult = FRESH_COOKIE
            currentCookieResult = CURRENT_COOKIE
            savedLinkResult = downloadLink()
        }
        val headers = mapOf(
            "cookie" to STALE_COOKIE,
            "User-Agent" to TEST_USER_AGENT,
            "Referer" to TEST_REFERER,
            "X-Test" to "preserved",
        )

        val result = QuarkTaskRefresher(access).refresh(task(), headers)

        assertNotNull(result)
        val refreshedHeaders = result!!.headers
        assertEquals(CURRENT_COOKIE, refreshedHeaders["Cookie"])
        assertFalse(refreshedHeaders.containsKey("cookie"))
        assertEquals(TEST_USER_AGENT, refreshedHeaders["User-Agent"])
        assertEquals(TEST_REFERER, refreshedHeaders["Referer"])
        assertEquals("preserved", refreshedHeaders["X-Test"])
        assertEquals(1, refreshedHeaders.keys.count { it.equals("Cookie", ignoreCase = true) })
    }

    @Test
    fun `rejects share file size mismatch without requesting a download link`() = runBlocking {
        val access = FakeAccess().apply {
            shareFilesHandler = { listOf(shareFile(size = TEST_SIZE + 1)) }
            sharedLinkResult = downloadLink()
        }

        val result = QuarkTaskRefresher(access).refresh(
            task(refreshReferenceJson = reference()),
            emptyMap(),
        )

        assertNull(result)
        assertTrue(access.sharedLinkCalls.isEmpty())
    }

    @Test
    fun `rejects a download link whose size differs`() = runBlocking {
        val access = FakeAccess().apply {
            shareFilesHandler = { listOf(shareFile()) }
            sharedLinkResult = downloadLink(size = TEST_SIZE + 1)
        }

        val result = QuarkTaskRefresher(access).refresh(
            task(refreshReferenceJson = reference()),
            emptyMap(),
        )

        assertNull(result)
        assertEquals(1, access.sharedLinkCalls.size)
    }

    @Test
    fun `rejects old credentials when the original account logs out`() = runBlocking {
        val access = FakeAccess().apply {
            freshCookieResult = STALE_COOKIE
            currentCookieResult = null
            savedLinkResult = downloadLink()
        }

        val result = QuarkTaskRefresher(access).refresh(task(), emptyMap())

        assertNull(result)
        assertEquals(listOf(SOURCE_OWNER_ID), access.currentCookieAccounts)
        assertEquals(STALE_COOKIE, access.savedLinkCalls.single().cookie)
    }

    @Test
    fun `saved file branch skips share token and listing`() = runBlocking {
        val access = FakeAccess().apply {
            savedLinkResult = downloadLink()
        }

        val result = QuarkTaskRefresher(access).refresh(task(), emptyMap())

        assertNotNull(result)
        assertEquals(TEST_SAVED_FILE_ID, access.savedLinkCalls.single().fileId)
        assertTrue(access.shareTokenCalls.isEmpty())
        assertTrue(access.shareFilesCalls.isEmpty())
        assertTrue(access.sharedLinkCalls.isEmpty())
    }

    @Test
    fun `rejects tasks without a source or owner`() = runBlocking {
        val noSource = FakeAccess()
        assertNull(
            QuarkTaskRefresher(noSource).refresh(
                task(refreshReferenceJson = "{}"),
                emptyMap(),
            ),
        )
        assertEquals(0, noSource.totalCallCount)

        val noOwner = FakeAccess()
        assertNull(
            QuarkTaskRefresher(noOwner).refresh(
                task(sourceAccountId = ""),
                emptyMap(),
            ),
        )
        assertEquals(0, noOwner.totalCallCount)
    }

    @Test
    fun `bounds share listing pagination to twenty pages`() = runBlocking {
        val access = FakeAccess().apply {
            shareTokenResult = SHARE_TOKEN
            shareFilesHandler = { page ->
                List(100) { index ->
                    shareFile(fid = "page-$page-file-$index")
                }
            }
        }

        val result = QuarkTaskRefresher(access).refresh(
            task(refreshReferenceJson = reference()),
            emptyMap(),
        )

        assertNull(result)
        assertEquals((1..20).toList(), access.shareFilesCalls.map { it.page })
        assertEquals(1, access.shareTokenCalls.size)
        assertTrue(access.sharedLinkCalls.isEmpty())
    }

    @Test
    fun `CancellationException propagates unchanged`() = runBlocking {
        val access = FakeAccess().apply {
            freshCookieFailure = CancellationException("test cancellation")
        }

        try {
            QuarkTaskRefresher(access).refresh(task(), emptyMap())
            fail("cancellation must not be converted to a refresh failure")
        } catch (expected: CancellationException) {
            assertEquals("test cancellation", expected.message)
        }
    }

    private fun task(
        sourceAccountId: String = SOURCE_OWNER_ID,
        refreshReferenceJson: String = savedReference(),
    ) = DownloadTaskEntity(
        url = "https://download.example.invalid/task",
        fileName = "synthetic.bin",
    ).copy(
        sourceAccountId = sourceAccountId,
        refreshReferenceJson = refreshReferenceJson,
    )

    private fun savedReference(expectedSize: Long = TEST_SIZE) =
        reference(savedFileId = TEST_SAVED_FILE_ID, expectedSize = expectedSize)

    private fun reference(
        savedFileId: String = "",
        expectedSize: Long = TEST_SIZE,
    ) = QuarkRefreshReference(
        shareId = TEST_SHARE_ID,
        password = TEST_PASSWORD,
        fileId = TEST_FILE_ID,
        parentId = TEST_PARENT_ID,
        expectedSize = expectedSize,
        savedFileId = savedFileId,
    ).encode()

    private fun shareFile(
        fid: String = TEST_FILE_ID,
        size: Long = TEST_SIZE,
    ) = ShareFile(
        fid = fid,
        fname = "synthetic.bin",
        fsize = size,
        isdir = false,
        pdirFid = TEST_PARENT_ID,
        fidToken = TEST_FILE_TOKEN,
    )

    private fun downloadLink(size: Long = TEST_SIZE) = DownloadLink(
        fid = TEST_FILE_ID,
        filename = "synthetic.bin",
        downloadUrl = "https://cdn.example.invalid/synthetic",
        size = size,
    )

    private class FakeAccess : QuarkRefreshAccess {
        var freshCookieResult: String? = "test-fresh-cookie"
        var currentCookieResult: String? = "test-current-cookie"
        var freshCookieFailure: Throwable? = null
        var shareTokenResult: String? = "test-share-token"
        var shareFilesHandler: (Int) -> List<ShareFile>? = { emptyList() }
        var savedLinkResult: DownloadLink? = null
        var sharedLinkResult: DownloadLink? = null

        val freshCookieAccounts = mutableListOf<String>()
        val currentCookieAccounts = mutableListOf<String>()
        val savedLinkCalls = mutableListOf<SavedLinkCall>()
        val shareTokenCalls = mutableListOf<ShareTokenCall>()
        val shareFilesCalls = mutableListOf<ShareFilesCall>()
        val sharedLinkCalls = mutableListOf<SharedLinkCall>()

        val totalCallCount: Int
            get() = freshCookieAccounts.size +
                currentCookieAccounts.size +
                savedLinkCalls.size +
                shareTokenCalls.size +
                shareFilesCalls.size +
                sharedLinkCalls.size

        override suspend fun freshCookie(accountId: String): String? {
            freshCookieAccounts += accountId
            freshCookieFailure?.let { throw it }
            return freshCookieResult
        }

        override suspend fun currentCookie(accountId: String): String? {
            currentCookieAccounts += accountId
            return currentCookieResult
        }

        override suspend fun savedLink(fileId: String, cookie: String): DownloadLink? {
            savedLinkCalls += SavedLinkCall(fileId, cookie)
            return savedLinkResult
        }

        override suspend fun shareToken(
            reference: QuarkRefreshReference,
            cookie: String,
        ): String? {
            shareTokenCalls += ShareTokenCall(reference, cookie)
            return shareTokenResult
        }

        override suspend fun shareFiles(
            reference: QuarkRefreshReference,
            token: String,
            cookie: String,
            page: Int,
        ): List<ShareFile>? {
            shareFilesCalls += ShareFilesCall(reference, token, cookie, page)
            return shareFilesHandler(page)
        }

        override suspend fun sharedLink(
            file: ShareFile,
            reference: QuarkRefreshReference,
            token: String,
            cookie: String,
        ): DownloadLink? {
            sharedLinkCalls += SharedLinkCall(file, reference, token, cookie)
            return sharedLinkResult
        }
    }

    private data class SavedLinkCall(val fileId: String, val cookie: String)

    private data class ShareTokenCall(
        val reference: QuarkRefreshReference,
        val cookie: String,
    )

    private data class ShareFilesCall(
        val reference: QuarkRefreshReference,
        val token: String,
        val cookie: String,
        val page: Int,
    )

    private data class SharedLinkCall(
        val file: ShareFile,
        val reference: QuarkRefreshReference,
        val token: String,
        val cookie: String,
    )

    private companion object {
        const val SOURCE_OWNER_ID = "synthetic-source-owner"
        const val TEST_SHARE_ID = "synthetic-share-id"
        const val TEST_PASSWORD = "test-extraction-code"
        const val TEST_FILE_ID = "synthetic-file-id"
        const val TEST_PARENT_ID = "synthetic-parent-id"
        const val TEST_SAVED_FILE_ID = "synthetic-saved-file-id"
        const val TEST_FILE_TOKEN = "test-file-token"
        const val TEST_SIZE = 4_096L
        const val FRESH_COOKIE = "test-fresh-cookie"
        const val CURRENT_COOKIE = "test-current-cookie"
        const val STALE_COOKIE = "test-stale-cookie"
        const val SHARE_TOKEN = "test-share-token"
        const val TEST_USER_AGENT = "SyntheticAgent/1.0"
        const val TEST_REFERER = "https://referer.example.invalid/share"
    }
}
