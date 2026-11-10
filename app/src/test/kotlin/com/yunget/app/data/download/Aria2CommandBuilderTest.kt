package com.yunget.app.data.download

import com.yunget.app.data.prefs.SettingsRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2CommandBuilderTest {
    private fun command(workers: Int, speedLimit: Long = 0): List<String> = Aria2CommandBuilder.build(
        File("aria2c"), File("ca.pem"),
        DownloadEngineRunner.RunSpec(
            taskId = 7, url = "https://example.com/file.bin", total = 1024,
            headers = mapOf("User-Agent" to "test-client", "Referer" to "https://example.com", "Host" to "ignored", "Content-Length" to "123"),
            chunkDir = File("work"), targetWorkers = workers, resumeFrom = 64,
            speedLimitBytesPerSec = speedLimit, onBytes = { _, _ -> }, onWorkers = {}, isActive = { true },
        ),
    )

    @Test fun `selected concurrency reaches the actual command instead of being silently capped at sixteen`() {
        for (requested in listOf(1, 4, 16, 32, 64, 128, 256)) {
            assertTrue("requested=$requested", "--stream-max-connections=$requested" in command(requested))
        }
    }

    @Test fun `default remains conservative and invalid values stay within the supported kernel range`() {
        assertEquals(16, SettingsRepository.DEFAULT_DOWNLOAD_THREADS)
        assertTrue("--stream-max-connections=16" in command(SettingsRepository.DEFAULT_DOWNLOAD_THREADS))
        assertTrue("--stream-max-connections=1" in command(-1))
        assertTrue("--stream-max-connections=256" in command(512))
    }

    @Test fun `parallelism does not discard resume speed limit certificate or source headers`() {
        val args = command(128, 123456)
        assertTrue("-c" in args)
        assertTrue("--max-overall-download-limit=123456" in args)
        assertTrue(args.any { it.startsWith("--ca-certificate=") })
        assertTrue("--check-certificate=true" in args)
        assertTrue("--header=User-Agent: test-client" in args)
        assertTrue("--header=Referer: https://example.com" in args)
        assertFalse(args.any { it.startsWith("--header=Host:") || it.startsWith("--header=Content-Length:") })
        assertEquals("https://example.com/file.bin", args.last())
        assertFalse(args.any { it == "-x" || it == "-s" || it == "-k" })
    }

    @Test fun `restart retains task identity and a task owned recovery database`() {
        val first = command(64)
        val resumed = command(128)
        val gid = first.single { it.startsWith("--gid=") }
        assertTrue(Regex("--gid=[0-9a-f]{16}").matches(gid))
        assertEquals(gid, resumed.single { it.startsWith("--gid=") })
        assertTrue("--state-dir=${File("work/aria2-state").absolutePath}" in first)
    }
}
