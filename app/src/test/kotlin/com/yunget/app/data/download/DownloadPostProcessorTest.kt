package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPostProcessorTest {
    private fun task(sources: List<DownloadSource>, fallback: String = "") = DownloadTaskEntity(
        url = "https://source.example/file", fileName = "file.bin",
        altSourcesJson = DownloadPostProcessor.encodeSources(sources), fallbackUrl = fallback,
    )

    @Test
    fun emptySourcesRemainSingleSource() {
        assertEquals(emptyList<String>(), DownloadPostProcessor.mirrors(task(emptyList()), emptyMap()))
    }

    @Test
    fun matchingHeadersPreserveMirrorAndFallbackOrder() {
        val headers = mapOf("Referer" to "https://source.example/")
        val task = task(listOf(DownloadSource("https://mirror.example/file", headers)), "https://fallback.example/file")
        assertEquals(listOf("https://fallback.example/file", "https://mirror.example/file"),
            DownloadPostProcessor.mirrors(task, headers))
    }

    @Test(expected = IllegalArgumentException::class)
    fun credentialsFromDifferentAccountsAreNeverMixed() {
        val task = task(listOf(DownloadSource("https://mirror.example/file", mapOf("Cookie" to "account=B"))))
        DownloadPostProcessor.mirrors(task, mapOf("Cookie" to "account=A"))
    }
}
