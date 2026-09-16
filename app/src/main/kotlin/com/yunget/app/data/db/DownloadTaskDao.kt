package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task ORDER BY createTime DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    /** 一次性取全部任务（诊断等非响应式场景用，避免为拿一次快照而收集 Flow）。 */
    @Query("SELECT * FROM download_task ORDER BY createTime DESC")
    suspend fun getAllOnce(): List<DownloadTaskEntity>

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun get(id: Long): DownloadTaskEntity?

    /**
     * 进度落库。
     *
     * `totalSize` 用 CASE 兜底：进度事件偶尔会带 `totalBytes = 0`（该字段尚未填入），
     * 旧写法会直接把**已经知道的总大小覆盖成 0**，界面进度文案随之退化成空串
     * （`progressText` 里 `totalSize <= 0` 直接 return ""），表现为进度条有、文字没了。
     */
    @Query(
        "UPDATE download_task SET status = :status, downloadedSize = :downloadedSize, " +
            "totalSize = CASE WHEN :totalSize > 0 THEN :totalSize ELSE totalSize END WHERE id = :id"
    )
    suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long)

    @Query("UPDATE download_task SET chunkCount = :chunkCount, plannedTotalSize = :totalSize WHERE id = :id")
    suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long)

    @Query("UPDATE download_task SET status = 2 WHERE status = 1 OR status = 0")
    suspend fun markInterruptedAsPaused()

    @Query("UPDATE download_task SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("UPDATE download_task SET errorMsg = :errorMsg WHERE id = :id")
    suspend fun updateError(id: Long, errorMsg: String)

    /**
     * 标记完成，并**以实际落盘字节数为准**修正进度。
     *
     * 【为什么不能只写 status/savePath】`downloadedSize` 是由进度事件**节流**写入的
     * （`TurboDownloadManager` 里每 800ms 或每增长 1MB 才写一次），
     * 最后一段时间内的增量完全可能没写进库。于是任务明明已经完成，
     * 界面却显示「已完成 · 18.0/18.1 MB · 99%」——**完成状态与百分比自相矛盾**。
     *
     * 完成时刻的唯一真相是**实际文件的字节数**（引擎在完成前已做过长度校验），
     * 因此这里必须把它一并写回；只靠进度事件的累计值永远可能差最后一截。
     */
    @Query(
        "UPDATE download_task SET status = :status, savePath = :savePath, " +
            "downloadedSize = :downloadedSize, totalSize = :totalSize WHERE id = :id"
    )
    suspend fun complete(id: Long, status: Int, savePath: String, downloadedSize: Long, totalSize: Long)

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
