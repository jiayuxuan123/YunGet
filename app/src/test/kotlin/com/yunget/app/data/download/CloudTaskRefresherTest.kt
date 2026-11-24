package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTaskRefresherTest {
    private class FakeDriveAccess(
        private val quarkLink: Pair<DownloadLink, String>? = null,
        private val ucLink: Pair<DownloadLink, String>? = null,
        private val xunleiLink: DownloadLink? = null,
        private val pan123Link: DownloadLink? = null,
        private val c139Link: DownloadLink? = null,
    ) : CloudDriveRefreshAccess {
        var lastPan123File: ShareFile? = null
        override suspend fun refreshQuarkDrive(accountId: String, fileId: String) = quarkLink
        override suspend fun refreshUcDrive(accountId: String, fileId: String) = ucLink
        override suspend fun refreshXunleiDrive(accountId: String, fileId: String) = xunleiLink
        override suspend fun refreshPan123Drive(accountId: String, file: ShareFile): DownloadLink? {
            lastPan123File = file
            return pan123Link
        }
        override suspend fun refreshC139Drive(accountId: String, fileId: String) = c139Link
    }

    private val unusedQuarkShare = QuarkTaskRefresher(object : QuarkRefreshAccess {
        override suspend fun freshCookie(accountId: String): String? = null
        override suspend fun currentCookie(accountId: String): String? = null
        override suspend fun savedLink(fileId: String, cookie: String): DownloadLink? = null
        override suspend fun shareToken(reference: QuarkRefreshReference, cookie: String): String? = null
        override suspend fun shareFiles(reference: QuarkRefreshReference, token: String, cookie: String, page: Int): List<ShareFile>? = null
        override suspend fun sharedLink(file: ShareFile, reference: QuarkRefreshReference, token: String, cookie: String): DownloadLink? = null
    })

    @Test
    fun refreshesUcXunleiPan123AndC139PersonalDriveTasks() = runBlocking {
        val access = FakeDriveAccess(
            ucLink = DownloadLink("u1", "movie.mp4", "https://uc.example/new", 1024L) to "__puus=fresh",
            xunleiLink = DownloadLink("x1", "game.iso", "https://xl.example/new", 2048L),
            pan123Link = DownloadLink("p1", "pack.zip", "https://123.example/new", 4096L),
            c139Link = DownloadLink("c1", "doc.pdf", "https://139.example/new", 512L),
        )
        val refresher = CloudTaskRefresher(unusedQuarkShare, access)

        val ucTask = task("uc", TaskRefreshReference.forDrive("uc", "u1", "movie.mp4", 1024L).encode())
        val ucRes = refresher.refresh(ucTask, mapOf("Referer" to "https://drive.uc.cn/", "Cookie" to "old"))
        assertNotNull(ucRes)
        assertEquals("https://uc.example/new", ucRes!!.link.downloadUrl)
        assertEquals("__puus=fresh", ucRes.headers["Cookie"])
        assertEquals("https://drive.uc.cn/", ucRes.headers["Referer"])

        val xlTask = task("xunlei", TaskRefreshReference.forDrive("xunlei", "x1", "game.iso", 2048L).encode())
        val xlRes = refresher.refresh(xlTask, mapOf("User-Agent" to "XL"))
        assertEquals("https://xl.example/new", xlRes?.link?.downloadUrl)

        val p123Task = task(
            "pan123",
            TaskRefreshReference.forDrive("pan123", "p1", "pack.zip", 4096L, fidToken = "s3|etag|0").encode(),
        )
        val p123Res = refresher.refresh(p123Task, emptyMap())
        assertEquals("https://123.example/new", p123Res?.link?.downloadUrl)
        assertEquals("s3|etag|0", access.lastPan123File?.fidToken)

        val c139Task = task("c139", TaskRefreshReference.forDrive("c139", "c1", "doc.pdf", 512L).encode())
        val c139Res = refresher.refresh(c139Task, emptyMap())
        assertEquals("https://139.example/new", c139Res?.link?.downloadUrl)
    }

    @Test
    fun rejectsMismatchedFileSizeOnRefresh() = runBlocking {
        val access = FakeDriveAccess(
            xunleiLink = DownloadLink("x1", "game.iso", "https://xl.example/wrong", 9999L),
        )
        val refresher = CloudTaskRefresher(unusedQuarkShare, access)
        val xlTask = task("xunlei", TaskRefreshReference.forDrive("xunlei", "x1", "game.iso", 2048L).encode())
        assertNull(refresher.refresh(xlTask, emptyMap()))
    }

    private fun task(platform: String, refJson: String) = DownloadTaskEntity(
        id = 1L,
        url = "https://old.example/file",
        fileName = "file.bin",
        platform = platform,
        refreshReferenceJson = refJson,
    )
}
