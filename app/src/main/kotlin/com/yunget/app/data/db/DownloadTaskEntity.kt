package com.yunget.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 下载任务（Room 持久化，断点续传依赖 part 文件 + 已下载大小）。
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val fileName: String,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val status: Int = STATUS_PENDING,
    /** 失败原因（服务端/网络/分片等具体错误信息），成功或进行中为空 */
    val errorMsg: String = "",
    /** 完成后的保存位置：MediaStore uri 或文件绝对路径 */
    val savePath: String = "",
    /** 恢复任务所需的请求头 JSON（Cookie/Referer/UA 等） */
    @ColumnInfo(defaultValue = "'{}'")
    val requestHeadersJson: String = "{}",
    /** 首次探测大小后固定的分片数，恢复时不随设置变化 */
    @ColumnInfo(defaultValue = "0")
    val chunkCount: Int = 0,
    /** 与 chunkCount 对应的服务器总大小 */
    @ColumnInfo(defaultValue = "0")
    val plannedTotalSize: Long = 0L,
    /** 下载完成/删除任务后应清理的云端临时目录 ID（当前为夸克） */
    @ColumnInfo(defaultValue = "''")
    val cleanupId: String = "",
    /**
     * 外部下载引擎（Gopeed）的任务 ID；空串 = 由内置分片下载器执行。
     *
     * 为什么要落库：引擎是独立进程内组件，应用重启后要能按这个 ID 找回任务、
     * 继续同步进度（否则重启后引擎里还在跑的任务在界面上就"消失"了）。
     */
    @ColumnInfo(defaultValue = "''")
    val engineTaskId: String = "",
    /**
     * 平均速度（字节/秒），任务结束后用于列表展示。
     *
     * 【必须与迁移 `MIGRATION_11_12` 保持一致】该迁移会给老库加这一列，
     * 若实体里没有对应字段，Room 打开库时校验表结构**多出一列**就会直接抛
     * `Migration didn't properly handle`，**应用启动即崩**（2.6.17 的真实事故）。
     * 加列时"实体 + 迁移 + 版本号"三者必须同时改，缺一不可 ——
     * `DatabaseMigrationContractTest` 会拦住这类疏漏。
     */
    @ColumnInfo(defaultValue = "0")
    val avgSpeed: Long = 0,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_FAILED = 4

        fun statusText(status: Int): String = when (status) {
            STATUS_PENDING -> "等待中"
            STATUS_DOWNLOADING -> "下载中"
            STATUS_PAUSED -> "已暂停"
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> "失败"
            else -> "未知"
        }
    }
}
