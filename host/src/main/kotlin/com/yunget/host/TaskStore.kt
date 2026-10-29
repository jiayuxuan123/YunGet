package com.yunget.host

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一条下载任务。字段与 Android 端的下载任务对齐，换端不需要重新理解。 */
data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val destPath: String,
    val headers: Map<String, String>,
    var size: Long,
    var downloaded: Long = 0L,
    /** RUNNING / PAUSED / DONE / FAILED / CANCELED */
    var status: String = "PAUSED",
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var speed: Long = 0L,
    var connections: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("fileName", fileName)
        .put("destPath", destPath)
        .put("headers", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
        .put("size", size)
        .put("downloaded", downloaded)
        .put("status", status)
        .put("error", error ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("speed", speed)
        .put("connections", connections)

    companion object {
        fun fromJson(o: JSONObject): DownloadTask {
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { h ->
                h.keys().forEach { k -> headers[k] = h.optString(k) }
            }
            return DownloadTask(
                id = o.getLong("id"),
                url = o.optString("url"),
                fileName = o.optString("fileName"),
                destPath = o.optString("destPath"),
                headers = headers,
                size = o.optLong("size", -1L),
                downloaded = o.optLong("downloaded", 0L),
                status = o.optString("status", "PAUSED"),
                error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                speed = o.optLong("speed", 0L),
                connections = o.optInt("connections", 0),
            )
        }
    }
}

/**
 * 任务表：内存里按 id 索引，每次变更落盘。
 *
 * 引擎自己不保存任务（只保存分片进度），所以任务表必须由宿主维护：
 * 重启后靠它把"下载中"标回"已暂停"，并把相同的 stableKey 再交给引擎续传。
 */
class TaskStore {
    private val tasks = LinkedHashMap<Long, DownloadTask>()
    private var nextId = 1L

    init {
        load()
    }

    @Synchronized
    fun all(): List<DownloadTask> = tasks.values.toList()

    @Synchronized
    fun get(id: Long): DownloadTask? = tasks[id]

    @Synchronized
    fun add(url: String, fileName: String, destPath: String, headers: Map<String, String>, size: Long): DownloadTask {
        val task = DownloadTask(id = nextId++, url = url, fileName = fileName, destPath = destPath, headers = headers, size = size)
        tasks[task.id] = task
        save()
        return task
    }

    @Synchronized
    fun update(id: Long, block: (DownloadTask) -> Unit): DownloadTask? {
        val task = tasks[id] ?: return null
        block(task)
        save()
        return task
    }

    @Synchronized
    fun remove(id: Long): DownloadTask? {
        val removed = tasks.remove(id)
        save()
        return removed
    }

    /** 启动时把上次没跑完的任务标成已暂停（引擎侧的进度还在）。 */
    @Synchronized
    fun markInterruptedAsPaused() {
        var changed = false
        tasks.values.forEach { t ->
            if (t.status == "RUNNING") {
                t.status = "PAUSED"
                changed = true
            }
        }
        if (changed) save()
    }

    private fun load() {
        val file = Paths.tasksFile
        if (!file.isFile) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until array.length()) {
                val task = DownloadTask.fromJson(array.getJSONObject(i))
                tasks[task.id] = task
                if (task.id >= nextId) nextId = task.id + 1
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        tasks.values.forEach { array.put(it.toJson()) }
        AtomicJson.write(Paths.tasksFile, JSONObject().put("tasks", array))
    }
}
