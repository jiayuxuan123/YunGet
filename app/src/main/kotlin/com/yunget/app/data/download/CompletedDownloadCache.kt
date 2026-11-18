package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import java.io.File

/** Validates a cache file that can be reused instead of downloading it again. */
internal object CompletedDownloadCache {
    private val reusableStages = setOf("PROCESSING", "SAVING", "SAVE_FAILED")

    fun resolve(task: DownloadTaskEntity, vararg cacheRoots: File): File? {
        return try {
            if (task.pipelineStage !in reusableStages ||
                task.completedCachePath.isBlank() ||
                task.completedCacheSize <= 0L
            ) {
                return null
            }

            val cachedFile = File(task.completedCachePath).canonicalFile
            if (cacheRoots.none { DownloadPathPolicy.isContained(it, cachedFile) }) return null

            val actualSize = when {
                cachedFile.isFile -> cachedFile.length()
                cachedFile.isDirectory -> cachedFile.walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
                else -> -1L
            }
            cachedFile.takeIf { actualSize == task.completedCacheSize }
        } catch (_: Exception) {
            null
        }
    }
}
