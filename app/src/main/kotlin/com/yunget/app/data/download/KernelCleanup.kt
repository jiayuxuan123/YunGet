package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import java.io.File
import java.io.IOException

internal object KernelCleanup {
    fun blockedByTasks(tasks: List<DownloadTaskEntity>, gopeed: Boolean): Boolean = tasks.any {
        (it.status == DownloadTaskEntity.STATUS_DOWNLOADING || it.status == DownloadTaskEntity.STATUS_PENDING) &&
            (if (gopeed) it.engineTaskId.isNotBlank() else it.engineTaskId.isBlank())
    }

    fun aria2Cache(cacheRoot: File): Long {
        val root = checkedChild(cacheRoot, "kernels")
        return root.listFiles().orEmpty().filter { file ->
            file.name == Aria2KernelDownload.NAME ||
                (file.name.startsWith("aria2-") && file.name.endsWith(".part.apk"))
        }.sumOf { erase(it, root) }
    }

    fun gopeedPackages(privateFilesRoot: File): Long {
        val root = checkedChild(privateFilesRoot, "gopeed/kernel")
        return root.listFiles().orEmpty().sumOf { erase(it, root) }
    }

    private fun checkedChild(base: File, relative: String): File {
        val path = File(base.canonicalFile, relative).absoluteFile
        require(path.canonicalFile == path) { "拒绝清理链接指向的目录" }
        return path
    }

    private fun erase(file: File, root: File): Long {
        require(file.canonicalFile == file.absoluteFile && file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "拒绝清理内核缓存之外的文件"
        }
        val bytes = if (file.isDirectory) file.listFiles().orEmpty().sumOf { erase(it, root) } else file.length()
        if (file.exists() && !file.delete()) throw IOException("部分内核缓存未能删除，请稍后重试")
        return bytes
    }
}
