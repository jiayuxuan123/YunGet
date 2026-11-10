package com.yunget.app.data.download

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2ReadoutTest {
    @Test fun `reads actual bytes and connections from both supported console formats`() {
        assertEquals(Aria2Readout(65536, 67108864, 16), Aria2Readout.parse("[#940138 65536B/67108864B(0%) CN:16 DL:95819B ETA:11m39s]"))
        assertEquals(Aria2Readout(17825792, 67108864, 8), Aria2Readout.parse("[#027130 [=====>    ]  26% 17825792B/67108864B CN:8 DL:17807984B]"))
        assertEquals(0, Aria2Readout.parse("[#027130 20B/100B CN:0 DL:0B]")?.connections)
    }

    @Test fun `rejects log fragments malformed counters and overflow`() {
        for (line in listOf("retry 1/3 CN:2", "[#abc 101B/100B CN:1]", "[#abc 999999999999999999999B/100B CN:2]", "[#abc 1B/100B CN:999999999999]")) {
            assertNull(line, Aria2Readout.parse(line))
        }
    }

    @Test fun `a logical file length cannot masquerade as transferred bytes`() {
        val file = Files.createTempFile("aria2-readout-", ".bin").toFile()
        try {
            RandomAccessFile(file, "rw").use { it.seek(1024L * 1024 - 1); it.write(1) }
            assertEquals(1024L * 1024, file.length())
            val monitor = Aria2ProgressMonitor()
            assertNull(monitor.poll())
            monitor.accept("[#abc 4096B/1048576B CN:8]")
            assertEquals(4096L, monitor.poll()?.completed)
            monitor.accept("[#abc 8192B/1048576B CN:3]")
            val next = monitor.poll()!!
            assertEquals(8192L, next.completed)
            assertEquals(4096L, next.delta)
            assertEquals(3, next.connections)
        } finally { file.delete() }
    }

    @Test fun `resumed bytes establish a baseline and idle connection changes still reach the UI`() {
        val monitor = Aria2ProgressMonitor()
        monitor.accept("[#abc 524288B/1048576B CN:4]")
        val first = monitor.poll()!!
        assertTrue(first.initial)
        assertEquals(0L, first.delta)
        monitor.accept("[#abc 532480B/1048576B CN:4]")
        assertEquals(8192L, monitor.poll()?.delta)
        monitor.accept("[#abc 532480B/1048576B CN:0]")
        val idle = monitor.poll()!!
        assertFalse(idle.initial)
        assertEquals(0L, idle.delta)
        assertEquals(0, idle.connections)
    }
}
