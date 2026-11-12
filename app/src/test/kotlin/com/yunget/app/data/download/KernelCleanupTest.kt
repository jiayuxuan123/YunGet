package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity as Task
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class KernelCleanupTest {
    @Test fun clearsOnlyAria2InstallersAndKeepsOtherFiles() {
        val root = Files.createTempDirectory("kernel-clean-").toFile().canonicalFile
        try {
            val kernels = File(root, "kernels").apply { mkdirs() }
            File(kernels, Aria2KernelDownload.NAME).writeBytes(ByteArray(64))
            File(kernels, "aria2-synthetic.part.apk").writeBytes(ByteArray(12))
            val unrelated = File(kernels, "personal.zip").apply { writeText("keep") }
            val downloads = File(root, "Downloads").apply { mkdirs() }
            File(downloads, "task.part").writeText("resume")
            assertEquals(76L, KernelCleanup.aria2Cache(root))
            assertTrue(unrelated.isFile)
            assertTrue(File(downloads, "task.part").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun gopeedCleanupKeepsEngineDatabaseLibraryAndDownloads() {
        val root = Files.createTempDirectory("gopeed-clean-").toFile().canonicalFile
        try {
            val kernel = File(root, "gopeed/kernel/chunks").apply { mkdirs() }
            File(kernel, "part0").writeBytes(ByteArray(20))
            val db = File(root, "gopeed/data/state.db").apply { parentFile.mkdirs(); writeText("keep") }
            val library = File(root, "gopeed/lib/libgojni.so").apply { parentFile.mkdirs(); writeText("keep") }
            assertEquals(20L, KernelCleanup.gopeedPackages(root))
            assertTrue(db.isFile)
            assertTrue(library.isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun activeTasksBlockOnlyTheirKernelFamily() {
        val http = Task(url="https://example.invalid/file", fileName="file", status=Task.STATUS_DOWNLOADING)
        val bt = http.copy(engineTaskId="synthetic-gopeed-id")
        assertTrue(KernelCleanup.blockedByTasks(listOf(http), false))
        assertFalse(KernelCleanup.blockedByTasks(listOf(http), true))
        assertTrue(KernelCleanup.blockedByTasks(listOf(bt), true))
        assertFalse(KernelCleanup.blockedByTasks(listOf(bt.copy(status=Task.STATUS_PAUSED)), true))
    }
}
