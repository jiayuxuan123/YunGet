package com.yunget.desktop

import dev.turbodl.nativebridge.NativeBridge
import dev.turbodl.nativebridge.NativeTransferClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.max

internal enum class TransferKind { HTTP, HLS, DASH, TORRENT, METALINK }

internal data class DesktopTask(
    val id: String,
    val source: String,
    val output: File,
    val kind: TransferKind,
    val status: String,
    val downloaded: Long = 0,
    val total: Long = -1,
    val message: String = "",
    val httpVersions: Int = 0,
    val expectedHash: String = "",
    val headers: Map<String, String> = emptyMap(),
    val sourceLabel: String = "",
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long = -1,
    val activeConnections: Int = 0,
    val peakConnections: Int = 0,
    val firstByteMicros: Long = 0,
    val isLive: Boolean = false,
    val recordedMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
) {
    val partial: File get() = output.resolveSibling(".yunget-$id.part")
    val active: Boolean get() = status == "RUNNING" || status == "PAUSING"
    val queuedOrActive: Boolean get() = active || status == "QUEUED"
}

internal class DesktopDownloads(
    dataDirectory: File,
    allowLoopbackFixtures: Boolean = false,
) : Closeable {
    private data class SpeedSample(val bytes: Long, val timeMs: Long, val emaBytesPerSec: Double)

    private val storage = File(dataDirectory, "tasks.json")
    private val checkpointsDir = File(dataDirectory, "checkpoints")
    private val engine = NativeTransferClient(
        checkpointsDir,
        maxTasks = 8,
        maxConnections = MAX_DESKTOP_HTTP_CONNECTIONS,
        // 原生引擎的每个活动 Range 请求至少需要 80 KiB。
        bufferBudgetBytes = MAX_DESKTOP_HTTP_CONNECTIONS * 80L * 1024,
        allowLoopbackFixtures = allowLoopbackFixtures,
    )
    private val lock = Mutex()
    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.IO)
    private val running = mutableMapOf<String, Long>()
    private val speedSamples = mutableMapOf<String, SpeedSample>()
    private val speedHistoryFlow = MutableStateFlow<Map<String, List<Long>>>(emptyMap())
    private val cleanupHooks = mutableMapOf<String, suspend () -> Unit>()
    private val current = MutableStateFlow(readTasks())
    val tasks = current.asStateFlow()
    val speedHistory = speedHistoryFlow.asStateFlow()
    val capabilities = JSONObject(NativeTransferClient.capabilities())

    @Volatile
    private var config: DesktopConfig = DesktopConfig()

    init {
        scope.launch {
            while (isActive) {
                lock.withLock { refresh() }
                delay(200)
            }
        }
    }

    suspend fun applyConfig(next: DesktopConfig) = lock.withLock {
        config = next
        runCatching { engine.setSpeedLimit(next.speedLimitBytesPerSec) }
        pumpQueueLocked(next.httpPolicy)
    }

    suspend fun submit(
        source: String,
        directory: File,
        fileName: String,
        kind: TransferKind,
        httpVersion: Int,
        sha256: String? = null,
        headers: Map<String, String> = emptyMap(),
        autoRename: Boolean = false,
        sourceLabel: String = "",
        knownSize: Long = -1L,
        cleanupHook: (suspend () -> Unit)? = null,
    ): String = lock.withLock {
        val name = fileName.trim()
        require(name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it.isISOControl() || it in "\\/:*?\"<>|" }) { "文件名不可用" }
        require(sha256 == null || sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 格式不正确" }
        Files.createDirectories(directory.toPath())
        val output = if (autoRename) {
            resolveUniqueOutput(directory, name)
        } else {
            val candidate = File(directory, name).absoluteFile
            require(!candidate.exists() && current.value.none { it.output == candidate }) { "目标文件名已被使用" }
            candidate
        }
        val row = DesktopTask(
            id = UUID.randomUUID().toString(),
            source = source,
            output = output,
            kind = kind,
            status = "QUEUED",
            total = knownSize,
            expectedHash = sha256.orEmpty(),
            headers = headers,
            sourceLabel = sourceLabel.ifBlank { defaultSourceLabel(kind) },
            createdAt = System.currentTimeMillis(),
        )
        if (cleanupHook != null) cleanupHooks[row.id] = cleanupHook
        current.value = listOf(row) + current.value
        persist()
        if (running.size < config.maxConcurrentTasks) {
            start(row, httpVersion)
        }
        row.id
    }

    suspend fun pause(id: String) = lock.withLock {
        val row = current.value.firstOrNull { it.id == id } ?: return@withLock
        if (row.status == "QUEUED") {
            update(id) { it.copy(status = "PAUSED", speedBytesPerSec = 0, etaSeconds = -1) }
            persist()
            return@withLock
        }
        val task = running[id] ?: return@withLock
        val snapshot = engine.query(task)
        if (!snapshot.terminal && snapshot.state != NativeTransferClient.PAUSING) {
            engine.pause(task)
            update(id) { it.copy(status = "PAUSING") }
        }
    }

    suspend fun pauseAll() = lock.withLock {
        for (row in current.value) {
            if (row.status == "QUEUED") {
                update(row.id) { it.copy(status = "PAUSED", speedBytesPerSec = 0, etaSeconds = -1) }
            } else {
                val task = running[row.id] ?: continue
                val snapshot = engine.query(task)
                if (!snapshot.terminal && snapshot.state != NativeTransferClient.PAUSING) {
                    engine.pause(task)
                    update(row.id) { it.copy(status = "PAUSING") }
                }
            }
        }
        persist()
    }

    suspend fun resume(id: String, httpVersion: Int) = lock.withLock {
        val row = current.value.firstOrNull { it.id == id } ?: return@withLock
        if (id in running || row.status == "DONE") return@withLock
        if (row.status == "READY_TO_SAVE") {
            finishSaving(row, allowAutoRename = false)
        } else if (running.size < config.maxConcurrentTasks) {
            start(row, httpVersion)
        } else {
            update(id) { it.copy(status = "QUEUED", message = "") }
            persist()
        }
    }

    suspend fun resumeAll(httpVersion: Int) = lock.withLock {
        for (row in current.value) {
            if (row.status == "PAUSED" || row.status == "FAILED") {
                update(row.id) { it.copy(status = "QUEUED", message = "") }
            }
        }
        persist()
        pumpQueueLocked(httpVersion)
    }

    suspend fun saveWithAutoRename(id: String) = lock.withLock {
        val row = current.value.firstOrNull { it.id == id } ?: return@withLock
        if (row.status != "READY_TO_SAVE") return@withLock
        finishSaving(row, allowAutoRename = true)
    }

    suspend fun deleteTask(id: String, deleteLocalFile: Boolean = false) = lock.withLock {
        val row = current.value.firstOrNull { it.id == id } ?: return@withLock
        val nativeId = running[id]
        if (nativeId != null) {
            runCatching { engine.cancel(nativeId) }
            retire(id, nativeId)
        }
        cleanupHooks.remove(id)
        speedSamples.remove(id)
        speedHistoryFlow.value = speedHistoryFlow.value - id
        runCatching { if (row.partial.exists()) row.partial.delete() }
        if (deleteLocalFile && row.status == "DONE") {
            runCatching { if (row.output.exists()) row.output.delete() }
        }
        current.value = current.value.filterNot { it.id == id }
        persist()
        pumpQueueLocked(config.httpPolicy)
    }

    suspend fun clearCompleted() = lock.withLock {
        current.value = current.value.filterNot { it.status == "DONE" }
        persist()
    }

    suspend fun clearFailed() = lock.withLock {
        val toDelete = current.value.filter { it.status == "FAILED" }
        for (row in toDelete) {
            cleanupHooks.remove(row.id)
            speedSamples.remove(row.id)
            speedHistoryFlow.value = speedHistoryFlow.value - row.id
            runCatching { if (row.partial.exists()) row.partial.delete() }
        }
        current.value = current.value.filterNot { it.status == "FAILED" }
        persist()
    }

    suspend fun finishRecording(id: String) = lock.withLock {
        val task = running[id] ?: return@withLock
        if (engine.mediaDetails(task).live) engine.finishRecording(task)
    }

    private fun pumpQueueLocked(httpVersion: Int) {
        while (running.size < config.maxConcurrentTasks) {
            val next = current.value.lastOrNull { it.status == "QUEUED" } ?: break
            start(next, httpVersion)
        }
    }

    private fun start(row: DesktopTask, httpVersion: Int) {
        val cfg = config
        val isSftp = row.source.startsWith("sftp://", ignoreCase = true)
        val uriUserInfo = if (isSftp) runCatching { URI(row.source).userInfo }.getOrNull().orEmpty() else ""
        val urlUser = uriUserInfo.substringBefore(':', "").takeIf { uriUserInfo.isNotEmpty() }.orEmpty()
        val urlPass = if (':' in uriUserInfo) uriUserInfo.substringAfter(':') else ""
        val sanitizedSource = if (isSftp) stripDesktopSftpUserInfo(row.source) else row.source
        val network = NativeTransferClient.Network(
            username = if (isSftp) urlUser.ifBlank { cfg.sftpUsername } else "",
            password = if (isSftp) urlPass.ifBlank { cfg.sftpPassword } else "",
            proxy = cfg.proxyUrl,
            sshKnownHosts = if (isSftp) cfg.sftpKnownHosts else "",
            sshPublicKey = if (isSftp) cfg.sftpPublicKey else "",
            sshPrivateKey = if (isSftp) cfg.sftpPrivateKey else "",
            dohUrl = cfg.dohUrl,
            httpVersion = httpVersion,
        )
        val isHttp = row.kind == TransferKind.HTTP &&
            (sanitizedSource.startsWith("https://", true) || sanitizedSource.startsWith("http://", true))
        val host = if (isHttp) runCatching { URI(sanitizedSource).host.orEmpty().lowercase() }.getOrDefault("") else ""
        val xunlei = isHttp && (row.sourceLabel.startsWith("迅雷") ||
            host == "xunlei.com" || host.endsWith(".xunlei.com") || host == "sandai.net" || host.endsWith(".sandai.net"))
        val maxConnections = when {
            xunlei -> 8
            isHttp -> MAX_DESKTOP_HTTP_CONNECTIONS
            else -> 64
        }
        val connections = cfg.connectionsPerTask.coerceIn(1, maxConnections)
        val policy = NativeTransferClient.Policy(
            connections = connections,
            // 短探测分片避免在高并发档位预占一大片可切分空间。
            requestWindowBytes = if (connections > 256) 64 * 1024L else 1024 * 1024L,
            maxConnectionsPerHost = connections,
        )
        val id = try {
            when (row.kind) {
                TransferKind.HTTP -> engine.submitHttp(
                    source = NativeTransferClient.HttpSource(sanitizedSource, row.headers),
                    destination = row.partial,
                    stableKey = row.id,
                    expectedSha256 = row.expectedHash.takeIf(String::isNotEmpty),
                    knownSize = row.total.takeIf { it > 0L },
                    policy = policy,
                    network = network,
                )
                TransferKind.HLS, TransferKind.DASH -> engine.submitMedia(
                    source = NativeTransferClient.HttpSource(row.source, row.headers),
                    destination = row.partial,
                    stableKey = row.id,
                    media = NativeTransferClient.MediaPolicy(
                        protocol = if (row.kind == TransferKind.HLS)
                            NativeTransferClient.MediaProtocol.HLS else NativeTransferClient.MediaProtocol.DASH,
                        parallelSegments = cfg.connectionsPerTask.coerceIn(1, 16),
                    ),
                    policy = policy,
                    network = network,
                )
                TransferKind.TORRENT -> engine.submitTorrent(
                    torrentFileOrMagnet = row.source,
                    destinationDirectory = row.partial,
                    stableKey = row.id,
                    policy = NativeTransferClient.TorrentPolicy(
                        downloadBytesPerSecond = cfg.speedLimitBytesPerSec,
                    ),
                )
                TransferKind.METALINK -> {
                    val document = File(row.source)
                    require(document.length() in 1..1_048_576) { "Metalink 文件须小于 1 MiB" }
                    engine.submitMetalink(document.readText(), "", row.partial, row.id, policy = policy)
                }
            }
        } catch (error: Exception) {
            update(row.id) { it.copy(status = "FAILED", message = error.message.orEmpty(), speedBytesPerSec = 0, etaSeconds = -1) }
            persist()
            return
        }
        running[row.id] = id
        speedSamples[row.id] = SpeedSample(row.downloaded, System.currentTimeMillis(), 0.0)
        update(row.id) { it.copy(status = "RUNNING", message = "") }
        persist()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        var slotsFreed = false
        for ((id, nativeId) in running.toMap()) {
            val snapshot = engine.query(nativeId)
            val row = current.value.firstOrNull { it.id == id } ?: continue
            val metrics = if (row.kind == TransferKind.TORRENT) null
                else runCatching { engine.httpMetrics(nativeId) }.getOrNull()
            val media = if (row.kind == TransferKind.HLS || row.kind == TransferKind.DASH)
                runCatching { engine.mediaDetails(nativeId) }.getOrNull() else null

            val prev = speedSamples[id]
            val speed = if (prev != null && now > prev.timeMs) {
                val deltaBytes = max(0L, snapshot.downloadedBytes - prev.bytes)
                val instant = deltaBytes * 1000.0 / (now - prev.timeMs)
                val ema = if (prev.emaBytesPerSec <= 0.0) instant else prev.emaBytesPerSec * 0.65 + instant * 0.35
                speedSamples[id] = SpeedSample(snapshot.downloadedBytes, now, ema)
                ema.toLong()
            } else {
                speedSamples[id] = SpeedSample(snapshot.downloadedBytes, now, 0.0)
                0L
            }
            if (snapshot.state != NativeTransferClient.COMPLETED &&
                snapshot.state != NativeTransferClient.PAUSED &&
                snapshot.state != NativeTransferClient.FAILED &&
                snapshot.state != NativeTransferClient.CANCELLED
            ) {
                val curMap = speedHistoryFlow.value
                val prevSeries = curMap[id].orEmpty()
                val nextSeries = (prevSeries + speed).takeLast(40)
                speedHistoryFlow.value = curMap + (id to nextSeries)
            }
            val effectiveTotal = if (snapshot.totalBytes > 0L) snapshot.totalBytes else row.total
            val eta = if (effectiveTotal > 0L && speed > 0L && snapshot.downloadedBytes < effectiveTotal) {
                (effectiveTotal - snapshot.downloadedBytes) / speed
            } else -1L

            val updated = row.copy(
                downloaded = snapshot.downloadedBytes,
                total = effectiveTotal,
                httpVersions = metrics?.negotiatedVersionBits ?: row.httpVersions,
                activeConnections = metrics?.activeConnections ?: 0,
                peakConnections = max(row.peakConnections, metrics?.peakConnections ?: 0),
                firstByteMicros = metrics?.taskFirstByteMicroseconds ?: row.firstByteMicros,
                isLive = media?.live ?: false,
                recordedMs = media?.recordedMilliseconds ?: 0L,
                speedBytesPerSec = speed,
                etaSeconds = eta,
            )
            when (snapshot.state) {
                NativeTransferClient.COMPLETED -> {
                    retire(id, nativeId)
                    slotsFreed = true
                    finishSaving(
                        updated.copy(status = "READY_TO_SAVE", speedBytesPerSec = 0, etaSeconds = -1, activeConnections = 0),
                        allowAutoRename = false,
                    )
                    triggerCleanup(id)
                }
                NativeTransferClient.PAUSED, NativeTransferClient.FAILED, NativeTransferClient.CANCELLED -> {
                    retire(id, nativeId)
                    slotsFreed = true
                    val nextStatus = if (snapshot.state == NativeTransferClient.PAUSED) "PAUSED" else "FAILED"
                    if (nextStatus == "FAILED") {
                        DesktopLogStore.warn("download", "task_failed", "${row.output.name}: ${snapshot.message}")
                    }
                    update(id) { updated.copy(
                        status = nextStatus,
                        message = snapshot.message,
                        speedBytesPerSec = 0,
                        etaSeconds = -1,
                        activeConnections = 0,
                    ) }
                    persist()
                }
                else -> if (updated != row) update(id) { updated }
            }
        }
        if (slotsFreed) pumpQueueLocked(config.httpPolicy)
    }

    private fun triggerCleanup(id: String) {
        val hook = cleanupHooks.remove(id) ?: return
        scope.launch { runCatching { hook() } }
    }

    private fun retire(id: String, nativeId: Long) {
        engine.forget(nativeId)
        running.remove(id)
        speedSamples.remove(id)
    }

    private fun finishSaving(row: DesktopTask, allowAutoRename: Boolean) {
        update(row.id) { row }
        persist()
        val target = if (allowAutoRename && row.output.exists()) {
            resolveUniqueOutput(row.output.parentFile ?: File("."), row.output.name)
        } else {
            row.output
        }
        val finished = try {
            Files.createDirectories(target.parentFile.toPath())
            Files.move(row.partial.toPath(), target.toPath())
            DesktopLogStore.info("download", "task_done", "${target.name} (${formatBytes(row.downloaded)})")
            row.copy(
                output = target,
                status = "DONE",
                message = "",
                speedBytesPerSec = 0,
                etaSeconds = -1,
                completedAt = System.currentTimeMillis(),
            )
        } catch (error: java.io.IOException) {
            row.copy(message = "保存失败，已保留下载缓存。请检查目标位置或是否存在同名文件。")
        }
        update(row.id) { finished }
        persist()
    }

    private fun resolveUniqueOutput(directory: File, fileName: String): File {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val occupied = current.value.mapTo(hashSetOf()) { it.output.absolutePath.lowercase() }
        var candidate = File(directory, fileName).absoluteFile
        var idx = 1
        while (candidate.exists() || candidate.absolutePath.lowercase() in occupied) {
            candidate = File(directory, "$stem ($idx)$ext").absoluteFile
            idx++
        }
        return candidate
    }

    private fun update(id: String, change: (DesktopTask) -> DesktopTask) {
        current.value = current.value.map { if (it.id == id) change(it) else it }
    }

    private fun readTasks(): List<DesktopTask> {
        if (!storage.isFile) return emptyList()
        val text = runCatching { storage.readText().trim() }.getOrNull().orEmpty()
        if (!text.startsWith("[")) return emptyList()
        val rows = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                val headers = row.optJSONObject("headers") ?: JSONObject()
                DesktopTask(
                    id = row.getString("id"),
                    source = row.getString("source"),
                    output = File(row.getString("output")),
                    kind = TransferKind.valueOf(row.getString("kind")),
                    status = row.getString("status").let { if (it in setOf("RUNNING", "PAUSING", "QUEUED")) "PAUSED" else it },
                    downloaded = row.optLong("downloaded"),
                    total = row.optLong("total", -1),
                    message = row.optString("message"),
                    httpVersions = row.optInt("httpVersions"),
                    expectedHash = row.optString("expectedHash"),
                    headers = headers.keys().asSequence().associateWith { key -> headers.optString(key) },
                    sourceLabel = row.optString("sourceLabel", ""),
                    createdAt = row.optLong("createdAt", 0L),
                    completedAt = row.optLong("completedAt", 0L),
                )
            }.getOrNull()
        }
    }

    private fun persist() {
        val rows = JSONArray()
        for (row in current.value) {
            rows.put(JSONObject().put("id", row.id).put("source", row.source)
                .put("output", row.output.absolutePath).put("kind", row.kind.name).put("status", row.status)
                .put("downloaded", row.downloaded).put("total", row.total).put("message", row.message)
                .put("httpVersions", row.httpVersions).put("expectedHash", row.expectedHash)
                .put("headers", JSONObject(row.headers))
                .put("sourceLabel", row.sourceLabel)
                .put("createdAt", row.createdAt)
                .put("completedAt", row.completedAt))
        }
        Files.createDirectories(storage.parentFile.toPath())
        val temporary = storage.resolveSibling("tasks.json.tmp")
        temporary.writeText(rows.toString())
        try {
            Files.move(temporary.toPath(), storage.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), storage.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun close() {
        runBlocking { lifecycle.cancelAndJoin() }
        engine.close()
    }
}

internal fun defaultSourceLabel(kind: TransferKind): String = when (kind) {
    TransferKind.HTTP -> "HTTP 直链"
    TransferKind.HLS -> "HLS 流媒体"
    TransferKind.DASH -> "DASH 流媒体"
    TransferKind.TORRENT -> "BT / 磁力"
    TransferKind.METALINK -> "Metalink"
}

internal fun loadDesktopNative() {
    val directory = File(System.getProperty("yunget.native.bin")
        ?: System.getProperty("compose.application.resources.dir")
        ?: error("请从包含 TurboDL 原生库的云取安装包启动"))
    // Resolve the media DLL chain explicitly for Java's Windows library loader.
    for (component in listOf("avutil", "avcodec", "avformat")) {
        val dll = directory.listFiles { file -> file.name.matches(Regex("$component-[0-9]+\\.dll")) }
            ?.singleOrNull()
        if (dll != null) System.load(dll.absolutePath)
    }
    NativeBridge.load(File(directory, "turbodl_engine.dll"))
}

internal fun inferTransferKind(link: String): TransferKind {
    val trimmed = link.trim()
    if (trimmed.startsWith("magnet:", true)) return TransferKind.TORRENT
    val lower = trimmed.lowercase()
    if (lower.endsWith(".torrent")) return TransferKind.TORRENT
    if (lower.endsWith(".metalink") || lower.endsWith(".meta4")) return TransferKind.METALINK
    val path = runCatching { URI(trimmed).path.orEmpty().lowercase() }.getOrDefault(lower)
    return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> TransferKind.HLS
        path.endsWith(".mpd") -> TransferKind.DASH
        else -> TransferKind.HTTP
    }
}

internal fun stripDesktopSftpUserInfo(url: String): String {
    if (!url.startsWith("sftp://", ignoreCase = true)) return url
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val authStart = schemeEnd + 3
    val authEnd = url.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authStart)
        .let { if (it < 0) url.length else it }
    val atIndex = url.indexOf('@', startIndex = authStart)
    if (atIndex !in authStart until authEnd) return url
    return url.substring(0, authStart) + url.substring(atIndex + 1)
}
