package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task ORDER BY createTime DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun get(id: Long): DownloadTaskEntity?

    @Query("UPDATE download_task SET status = :status, downloadedSize = :downloadedSize, totalSize = :totalSize WHERE id = :id")
    suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long)

    @Query("UPDATE download_task SET chunkCount = :chunkCount, plannedTotalSize = :totalSize WHERE id = :id")
    suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long)

    @Query("UPDATE download_task SET status = 2 WHERE status = 1 OR status = 0")
    suspend fun markInterruptedAsPaused()

    @Query("UPDATE download_task SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("UPDATE download_task SET errorMsg = :errorMsg WHERE id = :id")
    suspend fun updateError(id: Long, errorMsg: String)

    @Query("UPDATE download_task SET status = :status, savePath = :savePath WHERE id = :id")
    suspend fun complete(id: Long, status: Int, savePath: String)

    /** 持久化请求头（Cookie/Referer/UA），供进程重启后恢复下载。 */
    @Query("UPDATE download_task SET requestHeadersJson = :headersJson WHERE id = :id")
    suspend fun updateHeaders(id: Long, headersJson: String)

    /** 持久化完成/删除后需清理的云端临时目录 ID（如夸克转存目录）。 */
    @Query("UPDATE download_task SET cleanupId = :cleanupId WHERE id = :id")
    suspend fun updateCleanupId(id: Long, cleanupId: String)

    /** 修正文件名（如探测到服务器 Content-Disposition 建议名，比 URL 末段的 UUID 可读）。 */
    @Query("UPDATE download_task SET fileName = :fileName WHERE id = :id")
    suspend fun updateFileName(id: Long, fileName: String)

    /** 查询处于暂停态的任务（进程重启后据此恢复）。 */
    @Query("SELECT * FROM download_task WHERE status = 2 ORDER BY createTime ASC")
    suspend fun getPausedTasks(): List<DownloadTaskEntity>

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: Long)
}
