package com.yunget.host

import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.LanzouConstants
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.repository.LanzouResolveRepository
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.ProxyMode
import dev.turbodl.core.ProxyType
import dev.turbodl.core.TaskState
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboEvent
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主本体：把「解析」和「下载」两件事翻译成 JSON。
 *
 * 边界很硬：这里不碰 UI，也不猜界面要什么；界面要什么就在协议里加一个方法。
 * 引擎负责分片与续传，宿主只负责三件它不做的事 —— 任务表、任务 id 映射、设置。
 */
class Host(private val scope: CoroutineScope, private val out: Output) {

    /**
     * 一次解析会话：仓库返回的会话（分享地址 + 提取码）+ 已列出的文件。
     *
     * 列表与取链接口都走 [LanzouResolveRepository] —— 它已经处理了"单文件 vs 文件夹"
     * 的分支。宿主自己直接调 Api 的话，单文件分享会被当成文件夹去请求分页参数，
     * 于是报"分页异常"（这个错误真在测试里出现过）。
     */
    private class Session(val share: ShareSession) {
        val files = ConcurrentHashMap<String, ShareFile>()
    }

    private var settings = Settings.load()
    private val store = TaskStore()
    private val lanzouRepo = LanzouResolveRepository(LanzouApi())
    private val client = TurboClient(buildConfig(settings))

    private val sessions = ConcurrentHashMap<String, Session>()
    private val appToTurbo = ConcurrentHashMap<Long, Long>()
    private val turboToApp = ConcurrentHashMap<Long, Long>()
    private val lastPersist = ConcurrentHashMap<Long, Long>()
    private var eventJob: Job? = null

    init {
        store.markInterruptedAsPaused()
        eventJob = scope.launch {
            client.events.collect { event -> runCatching { onEvent(event) } }
        }
    }

    suspend fun handle(method: String, id: String?, params: JSONObject) {
        when (method) {
            "ping" -> out.ok(id, mapOf("host" to "yunget-host", "engine" to ENGINE_VERSION))
            "resolve" -> resolve(id, params)
            "list" -> list(id, params)
            "directLink" -> directLink(id, params)
            "download" -> download(id, params)
            "pause" -> control(id, params) { c, turbo -> c.pause(turbo) }
            "resume" -> resume(id, params)
            "cancel" -> cancel(id, params)
            "tasks" -> out.ok(id, store.all().map { it.toJson() })
            "settings" -> settings(id, params)
            else -> out.error(id, "未知方法：$method")
        }
    }

    // ---------------------------------------------------------------- 解析

    private suspend fun resolve(id: String?, p: JSONObject) {
        val link = p.optString("link").trim()
        if (link.isEmpty()) return out.error(id, "链接不能为空")
        val pwd = p.optString("pwd").takeIf { it.isNotBlank() }
        val parsed = ShareLinkParser.parse(link)
            ?: return out.error(id, "无法识别的分享链接（首版桌面端支持网盘分享链接，直链请用 download）")
        when (parsed.platform) {
            SharePlatform.LANZOU -> {
                // 必须把**原始链接**交给解析：parse 给出的 shareId 只是末段 id，
                // 不能当 URL 用（OkHttp 会直接报 "no scheme found"）。
                val share = lanzouRepo.createSession(link, pwd ?: parsed.pwd, "").getOrThrow()
                val sid = UUID.randomUUID().toString().take(8)
                sessions[sid] = Session(share)
                out.ok(
                    id,
                    mapOf(
                        "session" to sid,
                        "platform" to parsed.platform.name,
                        "title" to share.title,
                    )
                )
            }
            else -> out.error(id, "该平台尚未接入桌面端：${parsed.platform.name}")
        }
    }

    private suspend fun list(id: String?, p: JSONObject) {
        val session = sessions[p.optString("session")] ?: return out.error(id, "会话已失效，请重新解析")
        val dir = p.optString("dir")
        val files = lanzouRepo.listFiles(session.share, dir, "").getOrThrow()
        files.forEach { session.files[it.fid] = it }
        val array = JSONArray()
        files.forEach { f ->
            array.put(
                JSONObject()
                    .put("fid", f.fid)
                    .put("name", f.fname)
                    .put("size", f.fsize)
                    .put("isDir", f.isdir)
                    .put("time", f.modifyTime)
            )
        }
        out.ok(id, array)
    }

    private suspend fun directLink(id: String?, p: JSONObject) {
        val session = sessions[p.optString("session")] ?: return out.error(id, "会话已失效，请重新解析")
        val fid = p.optString("file")
        val file = session.files[fid] ?: return out.error(id, "文件不存在，请刷新列表")
        if (file.isdir) return out.error(id, "这是文件夹，不能直接下载")
        val link = lanzouRepo.getShareDownloadLink(session.share, file, "").getOrThrow()
        // 蓝奏的下载节点要求 Referer 与 down_ip Cookie；这两个头必须由宿主给出，
        // 界面只负责原样传回来（它不该知道协议的细节）。
        val origin = runCatching { URI(link.downloadUrl) }.getOrNull()
            ?.let { "${it.scheme}://${it.host}" }.orEmpty()
        out.ok(
            id,
            mapOf(
                "url" to link.downloadUrl,
                "size" to link.size,
                "fileName" to link.filename.ifBlank { file.fname },
                "headers" to mapOf(
                    "User-Agent" to LanzouConstants.WEB_UA,
                    "Referer" to "$origin/",
                    "Cookie" to "down_ip=1",
                ),
            )
        )
    }

    // ---------------------------------------------------------------- 下载

    private fun download(id: String?, p: JSONObject) {
        val url = p.optString("url")
        if (url.isEmpty()) return out.error(id, "url 不能为空")
        val name = sanitize(p.optString("fileName").ifBlank { fileNameFromUrl(url) })
        val headers = mutableMapOf<String, String>()
        p.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        val size = p.optLong("size", -1L)

        val dir = File(settings.downloadDir).apply { mkdirs() }
        val dest = uniqueFile(dir, name)
        val task = store.add(url, dest.name, dest.absolutePath, headers, size)
        out.ok(id, task.toJson())
        submit(task)
    }

    private fun submit(task: DownloadTask) {
        val dest = File(task.destPath)
        dest.parentFile?.mkdirs()
        val request = DownloadRequest(
            url = task.url,
            destination = dest,
            headers = task.headers,
            knownSize = task.size,
            // stableKey 是引擎跨重启续传的唯一凭据：任务 id 不变，续传就命中同一份分片。
            stableKey = "task-${task.id}",
        )
        val turboId = client.submit(request)
        appToTurbo[task.id] = turboId
        turboToApp[turboId] = task.id
        store.update(task.id) { it.status = "RUNNING"; it.error = null }
        out.event("task") { put("task", store.get(task.id)!!.toJson()) }
    }

    private fun resume(id: String?, p: JSONObject) {
        val taskId = p.optLong("task", -1L)
        val task = store.get(taskId) ?: return out.error(id, "任务不存在")
        if (task.status == "DONE") return out.error(id, "任务已完成")
        val turboId = appToTurbo[taskId]
        // 进程重启过：引擎里没有这个任务，重新提交即可（分片还在，stableKey 未变）。
        if (turboId == null || client.progress.value[turboId] == null) {
            submit(task)
        } else if (!client.resume(turboId)) {
            submit(task)
        }
        out.ok(id, store.get(taskId)?.toJson())
    }

    private fun control(id: String?, p: JSONObject, block: (TurboClient, Long) -> Unit) {
        val taskId = p.optLong("task", -1L)
        val turboId = appToTurbo[taskId] ?: return out.error(id, "任务没有在跑")
        block(client, turboId)
        out.ok(id, store.get(taskId)?.toJson())
    }

    private fun cancel(id: String?, p: JSONObject) {
        val taskId = p.optLong("task", -1L)
        appToTurbo[taskId]?.let { runCatching { client.cancel(it, deleteOutput = true) } }
        appToTurbo.remove(taskId)
        val removed = store.remove(taskId)
        out.ok(id, mapOf("removed" to (removed != null)))
    }

    // ---------------------------------------------------------------- 设置

    private fun settings(id: String?, p: JSONObject) {
        if (p.length() > 0) {
            settings = settings.copy(
                downloadDir = p.optString("downloadDir", settings.downloadDir),
                connections = p.optInt("connections", settings.connections).coerceIn(1, 256),
                concurrentTasks = p.optInt("concurrentTasks", settings.concurrentTasks).coerceIn(1, 64),
                speedLimitBytesPerSec = p.optLong("speedLimitBytesPerSec", settings.speedLimitBytesPerSec),
                proxy = p.optString("proxy", settings.proxy),
                proxyHost = p.optString("proxyHost", settings.proxyHost),
                proxyPort = p.optInt("proxyPort", settings.proxyPort),
                readTimeoutMs = p.optLong("readTimeoutMs", settings.readTimeoutMs),
            )
            settings.save()
            runCatching { client.updateConfig(buildConfig(settings)) }
        }
        out.ok(id, settings.toJson())
    }

    // ---------------------------------------------------------------- 引擎事件

    private fun onEvent(event: TurboEvent) {
        val taskId = turboToApp[event.taskId] ?: return
        when (event) {
            is TurboEvent.Progress -> {
                val pr = event.progress
                store.update(taskId) {
                    it.downloaded = pr.downloadedBytes
                    it.speed = pr.speedBytesPerSec
                    it.connections = pr.activeConnections
                    if (it.size <= 0 && pr.totalBytes > 0) it.size = pr.totalBytes
                    if (pr.state == TaskState.PAUSED) it.status = "PAUSED"
                    else if (pr.state == TaskState.DOWNLOADING || pr.state == TaskState.PROBING) it.status = "RUNNING"
                }
                out.event("task") { put("task", store.get(taskId)!!.toJson()) }
            }
            is TurboEvent.Completed -> {
                store.update(taskId) {
                    it.status = "DONE"
                    it.downloaded = event.totalBytes
                    it.size = event.totalBytes
                    it.speed = 0
                    it.connections = 0
                }
                appToTurbo.remove(taskId)
                out.event("task") { put("task", store.get(taskId)!!.toJson()) }
            }
            is TurboEvent.Failed -> {
                store.update(taskId) {
                    it.status = "FAILED"
                    it.error = event.reason
                    it.speed = 0
                    it.connections = 0
                }
                appToTurbo.remove(taskId)
                out.event("task") { put("task", store.get(taskId)!!.toJson()) }
            }
            is TurboEvent.StateChanged -> {
                if (event.state == TaskState.PAUSED) {
                    store.update(taskId) { it.status = "PAUSED"; it.speed = 0; it.connections = 0 }
                    out.event("task") { put("task", store.get(taskId)!!.toJson()) }
                }
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 收尾

    /** 父进程断开：把在跑的任务标回已暂停（分片保留），再关引擎。 */
    fun close() {
        appToTurbo.keys.toList().forEach { id ->
            val turboId = appToTurbo[id]
            runCatching { if (turboId != null) client.pause(turboId) }
            runCatching { store.update(id) { it.status = "PAUSED"; it.speed = 0; it.connections = 0 } }
        }
        runCatching { eventJob?.cancel() }
        runCatching { client.shutdown() }
    }

    suspend fun awaitIdle() {
        // 目前没有需要等待的队列；留作以后批量任务的收尾口。
    }

    // ---------------------------------------------------------------- 工具

    private fun buildConfig(s: Settings): TurboConfig = TurboConfig(
        maxConnectionsPerTask = s.connections,
        maxConcurrentTasks = s.concurrentTasks,
        globalSpeedLimitBytesPerSec = s.speedLimitBytesPerSec,
        readTimeoutMs = s.readTimeoutMs,
        // 分片目录必须固定：换目录等于丢掉所有续传进度。
        workDir = Paths.chunksDir,
        proxy = when (s.proxy) {
            "direct" -> ProxyMode.Direct
            "manual" -> ProxyMode.Manual(ProxyType.HTTP, s.proxyHost, s.proxyPort)
            else -> ProxyMode.System
        },
    )

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\/:*?"<>|]"""), "_").trim().ifBlank { "download.bin" }

    private fun fileNameFromUrl(url: String): String =
        runCatching { URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() } }
            .getOrNull() ?: "download.bin"

    /** 同名文件已存在时加 ` (n)`，避免覆盖用户已有文件。 */
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) " ($i)" else " ($i).$ext"
            candidate = File(dir, "$base$suffix")
            i++
        }
        return candidate
    }

    private companion object {
        const val ENGINE_VERSION = "0.2.7.0"
    }
}
