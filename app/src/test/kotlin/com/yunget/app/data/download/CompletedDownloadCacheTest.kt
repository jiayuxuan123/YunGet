package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompletedDownloadCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `accepts complete files in reusable pipeline stages`() {
        listOf("PROCESSING", "SAVING", "SAVE_FAILED").forEachIndexed { index, stage ->
            val cacheRoot = temp.newFolder("cache-$index")
            val cacheFile = File(cacheRoot, "payload.bin").apply {
                writeBytes("complete payload".toByteArray())
            }

            val resolved = CompletedDownloadCache.resolve(
                task(stage, cacheFile, cacheFile.length()),
                cacheRoot,
            )

            assertNotNull("$stage should accept its completed cache", resolved)
            assertEquals(cacheFile.canonicalPath, resolved!!.canonicalPath)
        }
    }

    @Test
    fun `rejects a full length cache while still in network stage`() {
        val cacheRoot = temp.newFolder("cache")
        val cacheFile = File(cacheRoot, "payload.bin").apply {
            writeBytes("network payload".toByteArray())
        }

        assertNull(
            CompletedDownloadCache.resolve(
                task("NETWORK", cacheFile, cacheFile.length()),
                cacheRoot,
            ),
        )
    }

    @Test
    fun `rejects missing and shortened cache files`() {
        val cacheRoot = temp.newFolder("cache")
        val missing = File(cacheRoot, "missing.bin")
        val shortened = File(cacheRoot, "shortened.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertNull(CompletedDownloadCache.resolve(task("SAVE_FAILED", missing, 8L), cacheRoot))
        assertNull(
            CompletedDownloadCache.resolve(
                task("SAVING", shortened, shortened.length() + 1L),
                cacheRoot,
            ),
        )
    }

    @Test
    fun `rejects direct outside paths traversal and prefix siblings`() {
        val cacheRoot = temp.newFolder("cache")
        val outside = temp.newFile("outside.bin").apply {
            writeBytes("outside payload".toByteArray())
        }
        val siblingRoot = temp.newFolder("cache-other")
        val sibling = File(siblingRoot, "payload.bin").apply {
            writeBytes("sibling payload".toByteArray())
        }

        assertNull(
            CompletedDownloadCache.resolve(
                task("PROCESSING", outside, outside.length()),
                cacheRoot,
            ),
        )
        assertNull(
            CompletedDownloadCache.resolve(
                task("PROCESSING", File(cacheRoot, "../outside.bin"), outside.length()),
                cacheRoot,
            ),
        )
        assertNull(
            CompletedDownloadCache.resolve(
                task("PROCESSING", sibling, sibling.length()),
                cacheRoot,
            ),
        )
    }

    @Test
    fun `rejects directories zero sized blank and invalid paths`() {
        val cacheRoot = temp.newFolder("cache")
        val directory = File(cacheRoot, "directory").apply { mkdirs() }
        val zeroSized = File(cacheRoot, "zero.bin").apply { createNewFile() }

        assertNull(
            CompletedDownloadCache.resolve(
                task("SAVE_FAILED", directory, maxOf(1L, directory.length())),
                cacheRoot,
            ),
        )
        assertNull(
            CompletedDownloadCache.resolve(
                task("SAVING", zeroSized, zeroSized.length()),
                cacheRoot,
            ),
        )
        assertNull(CompletedDownloadCache.resolve(task("SAVING", "", 1L), cacheRoot))
        assertNull(CompletedDownloadCache.resolve(task("SAVING", "\u0000", 1L), cacheRoot))
    }

    private fun task(
        stage: String,
        file: File,
        completedSize: Long,
    ): DownloadTaskEntity = task(stage, file.path, completedSize)

    private fun task(
        stage: String,
        path: String,
        completedSize: Long,
    ): DownloadTaskEntity = DownloadTaskEntity(
        url = "https://example.invalid/payload.bin",
        fileName = "payload.bin",
        pipelineStage = stage,
        completedCachePath = path,
        completedCacheSize = completedSize,
    )
}
