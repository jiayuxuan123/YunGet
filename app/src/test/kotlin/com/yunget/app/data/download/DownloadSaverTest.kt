package com.yunget.app.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadSaverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `save commits after closing the output stream`() {
        val content = "downloaded payload".toByteArray()
        val source = temp.newFile("source.bin").apply { writeBytes(content) }
        val target = temp.newFile("target.bin")
        var streamClosed = false
        var committed = false
        var aborted = false
        val destination = DownloadDestination(
            path = target.absolutePath,
            openStream = { trackingStream(target) { streamClosed = true } },
            onCommit = {
                assertTrue("output stream must be closed before commit", streamClosed)
                committed = true
            },
            onAbort = {
                aborted = true
                target.delete()
            }
        )

        val saved = DownloadSaver.saveToDestination(destination, source) {
            fail("save should not fail: ${it.message}")
        }

        assertEquals(target.absolutePath, saved)
        assertTrue("commit must be called", committed)
        assertFalse("successful save must not abort", aborted)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `commit exception reaches save and aborts the destination`() {
        val source = temp.newFile("source.bin").apply { writeText("partial payload") }
        val target = temp.newFile("target.bin").apply { writeText("half-written") }
        val unrelated = temp.newFile("existing.bin").apply { writeText("keep me") }
        val commitFailure = IOException("commit failed")
        var observedFailure: Exception? = null
        var aborted = false
        val destination = DownloadDestination(
            path = target.absolutePath,
            openStream = { FileOutputStream(target) },
            onCommit = { throw commitFailure },
            onAbort = {
                aborted = true
                target.delete()
            }
        )

        val saved = DownloadSaver.saveToDestination(destination, source) {
            observedFailure = it
        }

        assertNull("failed save must return null", saved)
        assertSame(commitFailure, observedFailure)
        assertTrue("commit failure must abort the target", aborted)
        assertFalse("the current target must be deleted", target.exists())
        assertTrue("other files must not be touched", unrelated.exists())
    }

    @Test
    fun `abort only removes its own destination`() {
        val target = temp.newFile("target.bin").apply { writeText("partial") }
        val unrelated = temp.newFile("existing.bin").apply { writeText("keep me") }
        val destination = DownloadDestination(
            path = target.absolutePath,
            openStream = { error("not used") },
            onCommit = { error("not used") },
            onAbort = { target.delete() }
        )

        destination.abort()

        assertFalse("abort must delete the current target", target.exists())
        assertTrue("abort must not delete other files", unrelated.exists())
    }

    @Test
    fun `open failure aborts and returns null`() {
        val source = temp.newFile("source.bin").apply { writeText("payload") }
        val target = temp.newFile("target.bin").apply { writeText("pending placeholder") }
        var observedFailure: Exception? = null
        var aborted = false
        val destination = DownloadDestination(
            path = target.absolutePath,
            openStream = { throw IOException("open failed") },
            onCommit = { fail("commit must not run when open fails") },
            onAbort = {
                aborted = true
                target.delete()
            }
        )

        val saved = DownloadSaver.saveToDestination(destination, source) {
            observedFailure = it
        }

        assertNull("open failure must return null", saved)
        assertTrue(observedFailure is IllegalStateException)
        assertTrue("open failure must abort the created target", aborted)
        assertFalse(target.exists())
    }

    @Test
    fun `cancellation before commit aborts without committing`() {
        val source = temp.newFile("source.bin").apply { writeText("payload") }
        val target = temp.newFile("target.bin").apply { writeText("partial") }
        var shouldContinue = true
        var committed = false
        var aborted = false
        val destination = DownloadDestination(
            path = target.absolutePath,
            openStream = {
                trackingStream(target) {
                    // Simulate the job becoming inactive after the copy but before commit.
                    shouldContinue = false
                }
            },
            onCommit = { committed = true },
            onAbort = {
                aborted = true
                target.delete()
            }
        )

        assertThrows(CancellationException::class.java) {
            DownloadSaver.saveToDestination(
                destination = destination,
                source = source,
                shouldContinue = { shouldContinue }
            ) {
                fail("cancellation must not be reported as an ordinary save failure")
            }
        }

        assertFalse("cancelled save must not commit", committed)
        assertTrue("cancelled save must abort the target", aborted)
        assertFalse(target.exists())
    }

    @Test
    fun `media store commit rejects zero updated rows`() {
        val failure = assertThrows(IOException::class.java) {
            DownloadSaver.ensureMediaStoreCommit(0)
        }

        assertTrue(failure.message.orEmpty().contains("0"))
        DownloadSaver.ensureMediaStoreCommit(1)
    }

    private fun trackingStream(file: File, onClose: () -> Unit): OutputStream {
        val delegate = FileOutputStream(file)
        return object : OutputStream() {
            override fun write(b: Int) = delegate.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) {
                delegate.write(b, off, len)
            }

            override fun flush() = delegate.flush()

            override fun close() {
                try {
                    delegate.close()
                } finally {
                    onClose()
                }
            }
        }
    }
}
