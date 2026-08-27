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
import java.util.concurrent.atomic.AtomicLong

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** TurboDL 引导：装配 PluginHost + TurboClient + 基础插件；额外装 HLS 插件。 */
    private val bootstrap: TurboBootstrap = TurboBootstrap.create(
        config = buildConfig(),
        extraPlugins = listOf(HlsPlugin()),
    )
    private val client get() = bootstrap.client

    /** 实时统计（UI）*/
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 任务列表（Room Flow 直通）*/
    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 存储权限检查（UI 注入）；Android 10+ 或已授权返回 true。 */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /** roomId → TurboDL taskId */
    private val turboIds = ConcurrentHashMap<Long, Long>()
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
    /** 通知节流 */
    private val notifyThrottleMs = 2000L
    private val lastNotifyTs = AtomicLong(0)

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
        maxConnectionsPerTask = threadProvider().coerceIn(1, 256),
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
        proxy = ProxyMode.System,
        dns = DnsMode.System,
        trustAllCerts = ignoreSslProvider(),
    )

    /** 每次入队/开始前按当前设置热更新引擎配置（限速/并发/线程/忽略SSL 即时生效）。 */
    private fun refreshConfigIfChanged() {
        val cfg = buildConfig()
        val sig = "${cfg.maxConnectionsPerTask}|${cfg.maxConcurrentTasks}|" +
            "${cfg.globalSpeedLimitBytesPerSec}|${cfg.maxRetries}|${cfg.trustAllCerts}"
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
        val id = dao.insert(DownloadTaskEntity(url = url, fileName = safeName))
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
            if (effectiveHeaders.isNotEmpty()) taskHeaders[id] = effectiveHeaders
            onTaskStarted(id)
            // TurboDL 下到应用缓存，完成后再交 DownloadSaver 保存到公共目录。
            val out = File(turboCacheDir().apply { mkdirs() }, "task_${id}.part")
            turboOutputs[id] = out
            val request = DownloadRequest(
                url = task.url,
                destination = out,
                headers = effectiveHeaders,
                knownSize = taskSizes[id] ?: -1L,
                connectionsOverride = threadProvider().coerceIn(1, 256),
            )
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            val turboId = client.submit(request)
            turboIds[id] = turboId
        }
    }

    /** 暂停下载（保留断点）。 */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        val turboId = turboIds.remove(id)
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

    // ---------- TurboDL 事件桥接 ----------

    private fun onTurboEvent(ev: TurboEvent) {
        val roomId = turboIds.entries.firstOrNull { it.value == ev.taskId }?.key ?: return
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
                scope.launch {
                    dao.updateProgress(
                        roomId,
                        DownloadTaskEntity.STATUS_DOWNLOADING,
                        p.downloadedBytes,
                        if (p.totalBytes > 0) p.totalBytes else 0L,
                    )
                }
                taskNames[roomId]?.let { name ->
                    notifyProgress(roomId, name, p.downloadedBytes, p.totalBytes)
                }
            }
            is TurboEvent.Completed -> scope.launch { onTurboCompleted(roomId, ev.file, ev.totalBytes) }
            is TurboEvent.Failed -> scope.launch {
                Log.e(TAG, "task $roomId failed: ${ev.reason}")
                turboIds.remove(roomId)
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
            else -> {}
        }
    }

    /** TurboDL 下载完成：保存到公共目录（MediaStore/SAF）→ 更新状态 → 完成回调 → 清理。 */
    private suspend fun onTurboCompleted(roomId: Long, file: File, total: Long) {
        val task = dao.get(roomId) ?: return
        try {
            if (!storagePermissionProvider()) {
                file.delete()
                turboIds.remove(roomId)
                dao.updateStatus(roomId, DownloadTaskEntity.STATUS_FAILED)
                dao.updateError(roomId, "未授予存储权限，无法保存到下载目录")
                onTaskFinished()
                return
            }
            val savedPath = withContext(Dispatchers.IO) {
                DownloadSaver.save(context, task.fileName, file, saveDirProvider())
            } ?: throw IllegalStateException("保存到下载目录失败")
            dao.complete(roomId, DownloadTaskEntity.STATUS_COMPLETED, savedPath)
            Log.d(TAG, "onTurboCompleted: id=$roomId saved=$savedPath size=${file.length()}")
            taskCallbacks.remove(roomId)?.let { cb -> runCatching { cb() } }
        } catch (e: Exception) {
            Log.e(TAG, "onTurboCompleted save failed id=$roomId: ${e.message}", e)
            dao.updateStatus(roomId, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(roomId, e.message ?: "保存失败")
        } finally {
            turboIds.remove(roomId)
            _stats.update { it - roomId }
            turboOutputs.remove(roomId)?.delete()
            file.delete()
            onTaskFinished()
        }
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
        if (now - lastNotifyTs.get() >= notifyThrottleMs) {
            lastNotifyTs.set(now)
            val percent = if (total > 0) ((new * 100 / total).toInt().coerceIn(0, 100)) else -1
            val speed = _stats.value[id]?.speed ?: 0L
            val speedText = if (speed > 0) formatSpeed(speed) else ""
            DownloadService.update(context, fileName, percent, speedText, showSpeedProvider())
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
