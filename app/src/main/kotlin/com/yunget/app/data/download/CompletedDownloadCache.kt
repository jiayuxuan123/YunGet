package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import java.io.File

/** Validates a cache file that can be reused instead of downloading it again. */
internal object CompletedDownloadCache {
    private val reusableStages = setOf("PROCESSING", "SAVING", "SAVE_FAILED")

    fun resolve(task: DownloadTaskEntity, cacheRoot: File): File? {
        return try {
            if (task.pipelineStage !in reusableStages ||
                task.completedCachePath.isBlank() ||
                task.completedCacheSize <= 0L
            ) {
                return null
            }

            val cachedFile = File(task.completedCachePath).canonicalFile
            val rootPath = cacheRoot.canonicalPath
            val rootPrefix = if (rootPath.endsWith(File.separator)) {
                rootPath
            } else {
                rootPath + File.separator
            }

            cachedFile.takeIf {
                it.path.startsWith(rootPrefix) &&
                    it.isFile &&
                    it.length() == task.completedCacheSize
            }
        } catch (_: Exception) {
            null
        }
    }
}
