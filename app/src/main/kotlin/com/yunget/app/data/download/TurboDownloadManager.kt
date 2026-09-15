package com.yunget.app.data.download

import android.content.Context
import android.util.Log
import com.yunget.app.data.db.DownloadTaskDao
import com.yunget.app.data.db.DownloadTaskEntity
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.DnsMode
import dev.turbodl.core.ProxyMode
import dev.turbodl.core.TaskState
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,        // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1       // 分片（线程）数
)

private const val TAG = "YunGet-DL"

/**
 * 兼容别名：全 App（ViewModel / UI）以 `DownloadManager` 类型引用下载管理器。
 * 现指向 TurboDL 内核版实现；旧实现保留为 [LegacyDownloadManager]（暂不接线）。
 */
typealias DownloadManager = TurboDownloadManager

/**
 * 下载任务管理器（TurboDL 内核版）。
 *
 * 对 UI / ViewModel 暴露与旧 [LegacyDownloadManager] 完全一致的公开 API
 * （enqueue / start / pause / remove / stats / tasks / storagePermissionProvider），
 * 但把「协议层多线程下载」整体委托给 TurboDL 引擎（[TurboBootstrap] + [dev.turbodl.core.TurboClient]）。
 *
 * YunGet 侧仍自持：
 *  - Room 任务持久化与断点续传状态；
 *  - [DownloadService] 前台服务 + 通知节流 + 锁屏 WakeLock；
 *  - [DownloadSaver] 保存到 MediaStore / SAF（含存储权限动态申请）；
 *  - 网盘临时转存清理回调。
 *
 * TurboDL 侧负责：Range 分片并发 / 动态分段 / 分片级重试 / 全局限速 / 断点续传 /
 * HLS（经 turbo-plugin-hls 插件路由）/ 合并与字节级完整性校验。
 *
 * 任务 id 以 Room 自增 id 为准（UI 唯一标识）；内部维护 roomId → TurboDL taskId 映射。
 */
class TurboDownloadManager(
    private val context: Context,
    private val dao: DownloadTaskDao,
    /** 下载线程数提供者（设置页动态生效），默认 16 */
    private val threadProvider: () -> Int = { 16 },
    /** 自定义下载保存目录提供者（SAF tree Uri，可空）；null 时保存到系统默认 Download */
    private val saveDirProvider: () -> String? = { null },
    /** 最大同时下载任务数提供者（默认 3） */
    private val concurrencyProvider: () -> Int = { 3 },
    /** 全局下载速度限制提供者（字节/秒；0 = 不限速） */
    private val speedLimitProvider: () -> Long = { 0L },
    /** 下载失败后自动重试次数提供者（默认 3，上限 50） */
    private val retryCountProvider: () -> Int = { 3 },
    /** 锁屏后保持下载开关 */
    private val keepWhenLockedProvider: () -> Boolean = { true },
    /** 通知栏显示下载速度开关 */
    private val showSpeedProvider: () -> Boolean = { true },
    /** 忽略 TLS 证书校验（抓包调试；隐藏菜单）*/
    private val ignoreSslProvider: () -> Boolean = { false },
    /** 自定义 DoH 服务器 URL 提供者（空/null = 系统 DNS） */
    private val dohUrlProvider: () -> String? = { null },
    /** 连接预热开关提供者（默认开） */
    private val warmUpProvider: () -> Boolean = { true },
    /** 慢启动开关提供者（默认开） */
    private val slowStartProvider: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** TurboDL 引导：装配 PluginHost + TurboClient + 基础插件；额外装 HLS 插件。 */
    private val bootstrap: TurboBootstrap = TurboBootstrap.create(
        config = buildConfig(),
        extraPlugins = listOf(HlsPlugin()),
    )
    private val client get() = bootstrap.client

    /** 分片临时目录根（应用专属缓存，避免被系统 tmpdir 清理）。 */
    private fun chunkWorkDir(): File =
        File(context.externalCacheDir ?: context.cacheDir, "turbodl_chunks")

    /** 实时统计（UI）*/
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 任务列表（Room Flow 直通）*/
    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 存储权限检查（UI 注入）；Android 10+ 或已授权返回 true。 */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /** roomId → TurboDL taskId */
    private val turboIds = ConcurrentHashMap<Long, Long>()
    /** TurboDL taskId → roomId（反向映射，避免每个进度回调 O(n) 遍历）*/
    private val turboIdToRoomId = ConcurrentHashMap<Long, Long>()
    /** roomId → 请求头（暂停/恢复复用）*/
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()
    /** roomId → 已知大小 */
    private val taskSizes = ConcurrentHashMap<Long, Long>()
    /** roomId → 文件名缓存（事件回调/通知里避免再走 suspend DAO）*/
    private val taskNames = ConcurrentHashMap<Long, String>()
    /** roomId → 完成回调（成功/删除时清理网盘临时转存）*/
    private val taskCallbacks = ConcurrentHashMap<Long, suspend () -> Unit>()
    /** roomId → TurboDL 下载到的临时文件 */
    private val turboOutputs = ConcurrentHashMap<Long, File>()

    /** 前台服务任务计数 */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** 通知节流：按 roomId 分别记录上次通知时间，避免多任务互相干扰 */
    private val notifyThrottleMs = 1000L
    private val lastNotifyTsByTask = ConcurrentHashMap<Long, Long>()
    /** 进度写库节流：按 roomId 记录上次写入时间/字节，避免高频 DB 竞争 */
    private val lastDbWriteTs = ConcurrentHashMap<Long, Long>()
    private val lastDbWriteBytes = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var lastConfigSignature: String = ""

    init {
        // 监听 TurboDL 事件流：桥接进度到 UI stats、驱动持久化与完成保存。
        scope.launch {
            client.events.collect { ev -> onTurboEvent(ev) }
        }
    }

    // ---------- 配置映射 ----------

    private fun buildConfig(): TurboConfig = TurboConfig(
        maxConnectionsPerTask = threadProvider().coerceIn(1, 128),
        maxConcurrentTasks = concurrencyProvider().coerceIn(1, 64),
        globalSpeedLimitBytesPerSec = speedLimitProvider().coerceAtLeast(0L),
        maxRetries = retryCountProvider().coerceIn(0, 50),
        dynamicSegmentation = true,
        // 每连接 4 块：保证设定的线程数全部有活干（块数 = 线程数×4），
        // 且多余块供快连接工作窃取，消除慢连接长尾。
        segmentsPerConnection = 4,
        // 强制 HTTP/1.1：HTTP/2 会把所有分片多路复用到单条 TCP 连接，
        // 共享单个拥塞窗口 → 开几十线程也只有单连接速度（GitHub / 多数 CDN 均启用 h2）。
        forceHttp1 = true,
        // 关闭背压降并发：网盘 CDN 频繁 502/503，开启后线程只降难升，
        // 是“下到后面速度暴跌”的主因；改为仅靠分片重试处理暂时错误，不动并发。
        backpressureConsecutiveFailures = 0,
        // 不设 per-host 上限：单个下载的所有分片都是同一 host，若在此设小值（如 16）
        // 会把每个下载直接限死到该值（表现为“设 64 只跑 16、速度暴跌”）。
        // 迅雷等个别 CDN 的降级问题应由调用方按具体 host 单独处理，不在此全局限。
        maxConnectionsPerHost = 0,
        // 分片临时目录放应用专属缓存，避免系统 tmpdir 被清理导致断点丢失。
        workDir = chunkWorkDir(),
        proxy = ProxyMode.System,
        // DNS：配了 DoH 就用 DoH（可绕过本地 DNS 污染/加速解析），否则系统 DNS。
        dns = dohUrlProvider()?.let { DnsMode.DoH(it) } ?: DnsMode.System,
        warmUpConnections = warmUpProvider(),
        slowStart = slowStartProvider(),
        trustAllCerts = ignoreSslProvider(),
        // 弱校验器续传策略（TurboDL 0.2.0-rc12 新增，默认 false）。
        // 服务器只回 Content-Length、无 ETag/Last-Modified 时，续传令牌退化为 len=N。
        // 此时「服务器换了同样大小的新文件」检测不到 → 旧分片被复用 → 合并出静默损坏的文件，
        // 而最终长度校验恰好通过。false = 丢弃旧分片重下（正确性优先）。
        //
        // 代价：弱校验器来源的下载在**被中断后恢复**时会重下（分片目录已在完成后清理，
        // 因此正常完成的任务不受影响）。若你的来源普遍不提供 ETag/Last-Modified
        // 且更在意省流量，可显式改为 true 并自行承担损坏风险。
        trustWeakValidator = false,
    )

    /** 每次入队/开始前按当前设置热更新引擎配置（限速/并发/线程/忽略SSL 即时生效）。 */
    private fun refreshConfigIfChanged() {
        val cfg = buildConfig()
        // 签名覆盖所有会影响下载行为的字段（原先只有 5 个，forceHttp1/segmentsPerConnection 等改了不生效）。
        val sig = listOf(
            cfg.maxConnectionsPerTask, cfg.maxConcurrentTasks, cfg.globalSpeedLimitBytesPerSec,
            cfg.maxRetries, cfg.trustAllCerts, cfg.forceHttp1, cfg.segmentsPerConnection,
            cfg.dynamicSegmentation, cfg.backpressureConsecutiveFailures, cfg.maxConnectionsPerHost,
            (cfg.dns as? DnsMode.DoH)?.dohUrl ?: "system",
            cfg.warmUpConnections, cfg.slowStart,
        ).joinToString("|")
        if (sig != lastConfigSignature) {
            lastConfigSignature = sig
            client.updateConfig(cfg)
        }
    }

    // ---------- 公开 API（与 LegacyDownloadManager 一致）----------

    /** 入队并立即开始下载。返回 Room 任务 id。 */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        size: Long = -1L,
        onComplete: suspend () -> Unit = {}
    ): Long {
        val safeName = fileName.ifBlank {
            url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "download_${System.currentTimeMillis()}" }
        }
        Log.d(TAG, "enqueue: url=$url fileName=$safeName headers=${headers.keys} size=$size")
        // 请求头持久化到 DB：进程重启后恢复下载仍能带上 Cookie/Referer，否则必定 403。
        val headersJson = if (headers.isNotEmpty()) JSONObject(headers as Map<*, *>).toString() else "{}"
        val id = dao.insert(
            DownloadTaskEntity(
                url = url,
                fileName = safeName,
                requestHeadersJson = headersJson,
                totalSize = if (size > 0) size else 0L,
            )
        )
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        if (size > 0) taskSizes[id] = size
        taskNames[id] = safeName
        taskCallbacks[id] = onComplete
        start(id, headers)
        return id
    }

    /** 开始/恢复下载（断点续传）。 */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
        // 已在运行：忽略重复启动
        if (turboIds.containsKey(id)) return
        refreshConfigIfChanged()
        scope.launch {
            val task = dao.get(id) ?: return@launch
            taskNames[id] = task.fileName
            // 请求头：优先用传入的，其次内存缓存，最后从 DB 恢复（进程重启后）。
            val restoredHeaders = effectiveHeaders.ifEmpty {
                taskHeaders[id] ?: parseHeadersJson(task.requestHeadersJson)
            }
            if (restoredHeaders.isNotEmpty()) taskHeaders[id] = restoredHeaders
            // 已知大小：优先内存缓存，其次 DB（避免重启后重复 probe）。
            val knownSize = taskSizes[id] ?: task.totalSize.takeIf { it > 0 } ?: -1L
            onTaskStarted(id)
            // TurboDL 下到应用缓存，完成后再交 DownloadSaver 保存到公共目录。
            val out = File(turboCacheDir().apply { mkdirs() }, "task_${id}.part")
            turboOutputs[id] = out
            val request = DownloadRequest(
                url = task.url,
                destination = out,
                headers = restoredHeaders,
                knownSize = knownSize,
                connectionsOverride = threadProvider().coerceIn(1, 128),
                // 稳定键 = Room 任务 id：使同一任务多次 submit 复用同一分片目录，
                // 真正实现断点续传（暂停恢复 / 进程重启都从断点继续，而非从头下）。
                stableKey = "room-$id",
            )
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            val turboId = client.submit(request)
            turboIds[id] = turboId
            turboIdToRoomId[turboId] = id
        }
    }

    /** 暂停下载（保留断点）。 */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        val turboId = turboIds.remove(id)
        if (turboId != null) turboIdToRoomId.remove(turboId)
        _stats.update { it - id }
        scope.launch {
            if (turboId != null) client.pause(turboId)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            onTaskFinished()
        }
    }

    /** 删除任务（可选删除本地文件），并触发清理回调。 */
    fun remove(id: Long, deleteLocal: Boolean = false) {
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        val turboId = turboIds.remove(id)
        if (turboId != null) turboIdToRoomId.remove(turboId)
        _stats.update { it - id }
        taskHeaders.remove(id)
        taskSizes.remove(id)
        val cleanup = taskCallbacks.remove(id)
        scope.launch {
            if (turboId != null) client.cancel(turboId, deleteOutput = true)
            turboOutputs.remove(id)?.delete()
            if (deleteLocal) {
                dao.get(id)?.savePath?.takeIf { it.isNotBlank() }?.let {
                    val ok = DownloadSaver.delete(context, it)
                    Log.d(TAG, "remove: id=$id 删除本地文件 ${if (ok) "成功" else "失败/未找到"} ($it)")
                }
            }
            dao.delete(id)
            cleanup?.let { runCatching { it() } }
            onTaskFinished()
        }
    }

    /**
     * 进程启动时调用：把上次被杀遗留的「下载中/等待中」任务标为已暂停。
     *
     * 不调用的话，进程被杀后任务状态永远停在“下载中”，UI 上既不跑也无法恢复。
     * 标为暂停后用户可手动点击恢复（因分片目录用 stableKey 保留，恢复从断点继续）。
     */
    fun recoverInterruptedTasks() {
        scope.launch {
            runCatching { dao.markInterruptedAsPaused() }
                .onFailure { Log.e(TAG, "recoverInterruptedTasks failed: ${it.message}") }
        }
    }

    private fun parseHeadersJson(json: String): Map<String, String> =
        runCatching {
            if (json.isBlank() || json == "{}") return emptyMap()
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
        }.getOrDefault(emptyMap())

    // ---------- TurboDL 事件桥接 ----------

    private fun onTurboEvent(ev: TurboEvent) {
        val roomId = turboIdToRoomId[ev.taskId] ?: return
        when (ev) {
            is TurboEvent.Progress -> {
                val p = ev.progress
                _stats.update {
                    it + (roomId to DownloadStats(
                        speed = p.speedBytesPerSec,
                        remainMillis = p.etaMillis,
                        chunkCount = p.activeConnections.coerceAtLeast(1),
                    ))
                }
                // 进度写库节流：每任务至多每 800ms 或每增长 1MB 写一次，避免高频 launch+DB 竞争拖慢吞吐。
                if (shouldWriteDb(roomId, p.downloadedBytes)) {
                    scope.launch {
                        dao.updateProgress(
                            roomId,
                            DownloadTaskEntity.STATUS_DOWNLOADING,
                            p.downloadedBytes,
                            if (p.totalBytes > 0) p.totalBytes else 0L,
                        )
                    }
                }
                taskNames[roomId]?.let { name ->
                    notifyProgress(roomId, name, p.downloadedBytes, p.totalBytes)
                }
            }
            is TurboEvent.Completed -> scope.launch { onTurboCompleted(roomId, ev.file, ev.totalBytes) }
            is TurboEvent.Failed -> scope.launch {
                Log.e(TAG, "task $roomId failed: ${ev.reason}")
                turboIds.remove(roomId)?.let { turboIdToRoomId.remove(it) }
                _stats.update { it - roomId }
                dao.updateStatus(roomId, DownloadTaskEntity.STATUS_FAILED)
                dao.updateError(roomId, ev.reason)
                turboOutputs.remove(roomId)?.delete()
                onTaskFinished()
            }
            is TurboEvent.StateChanged -> {
                if (ev.state == TaskState.PAUSED || ev.state == TaskState.CANCELED) {
                    _stats.update { it - roomId }
                }
            }
            is TurboEvent.Metadata -> {
                // 静默利用探测到的服务器建议文件名：仅当现名看起来是无意义的
                // （UUID / 无扩展名 / download_ 占位）且服务器给了带扩展名的名字时才替换，
                // 避免覆盖网盘解析得到的准确文件名。失败不影响下载。
                val suggested = ev.suggestedFileName?.trim()
                if (!suggested.isNullOrBlank() && suggested.contains('.')) {
                    val cur = taskNames[roomId]
                    val curLooksPoor = cur == null || !cur.contains('.') ||
                        cur.startsWith("download_") ||
                        Regex("^[0-9a-fA-F-]{16,}$").matches(cur.substringBeforeLast('.'))
                    if (curLooksPoor) {
                        taskNames[roomId] = suggested
                        scope.launch { runCatching { dao.updateFileName(roomId, suggested) } }
                        Log.d(TAG, "metadata: id=$roomId 文件名修正 '$cur' -> '$suggested'")
                    }
                }
            }
            else -> {}
        }
    }

    /** 进度写库节流：时间（800ms）或字节（1MB）阈值任一达到即写；完成/暂停由各自分支强制写。 */
    private fun shouldWriteDb(roomId: Long, downloaded: Long): Boolean {
        val now = System.currentTimeMillis()
        val lastTs = lastDbWriteTs[roomId] ?: 0L
        val lastBytes = lastDbWriteBytes[roomId] ?: 0L
        if (now - lastTs >= 800 || downloaded - lastBytes >= 1L * 1024 * 1024) {
            lastDbWriteTs[roomId] = now
            lastDbWriteBytes[roomId] = downloaded
            return true
        }
        return false
    }

    // ---------- 前台服务 / 通知 / WakeLock ----------

    private fun onTaskStarted(id: Long) {
        if (activeTaskCount.getAndIncrement() == 0) {
            val name = taskNames[id] ?: "下载任务"
            DownloadService.start(context, name)
        }
        acquireWakeLockIfNeeded()
    }

    private fun onTaskFinished() {
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            DownloadService.stop(context)
            releaseWakeLock()
        }
    }

    private fun notifyProgress(id: Long, fileName: String, new: Long, total: Long) {
        val now = System.currentTimeMillis()
        val last = lastNotifyTsByTask[id] ?: 0L
        if (now - last >= notifyThrottleMs) {
            lastNotifyTsByTask[id] = now
            val percent = if (total > 0) ((new * 100 / total).toInt().coerceIn(0, 100)) else -1
            val speed = _stats.value[id]?.speed ?: 0L
            val speedText = if (speed > 0) formatSpeed(speed) else ""
            DownloadService.update(context, fileName, percent, speedText, showSpeedProvider())
        }
    }

    /** TurboDL 下载完成：保存到公共目录（MediaStore/SAF）→ 更新状态 → 完成回调 → 清理。 */
    private suspend fun onTurboCompleted(roomId: Long, file: File, total: Long) {
        val task = dao.get(roomId) ?: return
        try {
            if (!storagePermissionProvider()) {
                // 未授权：保留临时文件，标为失败供重试保存（不删）。
                turboIds.remove(roomId)?.let { turboIdToRoomId.remove(it) }
                _stats.update { it - roomId }
                dao.updateStatus(roomId, DownloadTaskEntity.STATUS_FAILED)
                dao.updateError(roomId, "未授予存储权限，无法保存到下载目录（已保留临时文件，可重试）")
                onTaskFinished()
                return
            }
            val savedPath = withContext(Dispatchers.IO) {
                DownloadSaver.save(context, task.fileName, file, saveDirProvider())
            } ?: throw IllegalStateException("保存到下载目录失败")
            // 完成时用**实际落盘大小**修正进度记录。
            // 进度是节流写库的（每 800ms / 每 1MB），最后一段增量可能压根没写进去，
            // 只写 status 的话界面就会显示「已完成 · 18.0/18.1 MB · 99%」——
            // 状态说完成、百分比说没完成，自相矛盾。以实际文件为准，
            // 并让 totalSize 不小于它，保证完成后恒为 100%。
            val actualSize = file.length()
            val finalTotal = maxOf(if (total > 0) total else 0L, actualSize)
            dao.complete(roomId, DownloadTaskEntity.STATUS_COMPLETED, savedPath, actualSize, finalTotal)
            Log.d(TAG, "onTurboCompleted: id=$roomId saved=$savedPath size=$actualSize total=$finalTotal")
            // 保存成功才清理：临时文件 + 网盘临时转存回调。
            turboIds.remove(roomId)?.let { turboIdToRoomId.remove(it) }
            _stats.update { it - roomId }
            turboOutputs.remove(roomId)?.delete()
            file.delete()
            taskCallbacks.remove(roomId)?.let { cb -> runCatching { cb() } }
            onTaskFinished()
        } catch (e: Exception) {
            Log.e(TAG, "onTurboCompleted save failed id=$roomId: ${e.message}", e)
            // 保存失败：保留临时文件与回调，标为失败供用户重试保存（不删文件、不清理回调）。
            turboIds.remove(roomId)?.let { turboIdToRoomId.remove(it) }
            _stats.update { it - roomId }
            dao.updateStatus(roomId, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(roomId, (e.message ?: "保存失败") + "（已保留临时文件，可重试）")
            onTaskFinished()
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    @Volatile
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLockIfNeeded() {
        if (!keepWhenLockedProvider()) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "yunget:download"
            ).apply { setReferenceCounted(false) }
        }
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun turboCacheDir(): File =
        File(context.externalCacheDir ?: context.cacheDir, "turbodl_tmp")
}
