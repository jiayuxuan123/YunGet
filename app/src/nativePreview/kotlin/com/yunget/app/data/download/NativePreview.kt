package com.yunget.app.data.download

import android.content.Context
import android.util.Base64
import com.yunget.app.data.prefs.SettingsRepository
import dev.turbodl.core.BackendContext
import dev.turbodl.core.BackendResult
import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.DnsMode
import dev.turbodl.nativebridge.NativeBridge
import dev.turbodl.nativebridge.NativeTransferClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class NativePreview(
    private val context: Context,
    private val speedLimit: () -> Long,
    private val httpVersionPreference: () -> Int,
    private val sftpConfig: () -> NativeSftpConfig = { NativeSftpConfig() },
) : NativePreviewRouting {
    private val closed = AtomicBoolean(false)
    private val engineRef = AtomicReference<NativeTransferClient?>(null)
    private val transferDetails = ConcurrentHashMap<Long, DownloadDiagnostics>()
    private val recordings = ConcurrentHashMap<Long, Long>()

    override val available: Boolean get() = loaded

    override fun supportsMedia(kind: String): Boolean = when (kind) {
        NativePreviewRouting.MEDIA_HLS -> capabilities.optBoolean("hls")
        NativePreviewRouting.MEDIA_DASH -> capabilities.optBoolean("dash")
        else -> false
    }

    override fun supportsTorrent(): Boolean = capabilities.optBoolean("bittorrent")
    override fun supportsMetalink(): Boolean = capabilities.optBoolean("metalink")
    override fun supportsSftp(): Boolean = capabilities.optBoolean("sftp")

    override fun isRecording(taskId: Long): Boolean = recordings.containsKey(taskId)

    override suspend fun finishRecording(taskId: Long): Boolean = withContext(Dispatchers.IO) {
        val id = recordings[taskId] ?: return@withContext false
        val native = engine()
        if (!native.mediaDetails(id).live) return@withContext false
        native.finishRecording(id)
        recordings.remove(taskId, id)
        true
    }

    private val caBundle: File by lazy {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val roots = store.aliases().toList().filter { it.startsWith("system:") }
        check(roots.isNotEmpty()) { "系统根证书不可用" }
        val bundle = File(context.filesDir, "native-system-ca.pem")
        bundle.parentFile?.mkdirs()
        bundle.bufferedWriter().use { writer ->
            for (alias in roots.sorted()) {
                val certificate = store.getCertificate(alias) ?: continue
                writer.appendLine("-----BEGIN CERTIFICATE-----")
                val pem = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
                for (line in pem.chunked(64)) writer.appendLine(line)
                writer.appendLine("-----END CERTIFICATE-----")
            }
        }
        bundle
    }

    private fun engine(): NativeTransferClient {
        return synchronized(this) {
            check(!closed.get()) { "原生下载引擎已关闭" }
            engineRef.get() ?: NativeTransferClient(
                checkpointDirectory = File(context.filesDir, "native-checkpoints"),
                maxTasks = 16,
                maxConnections = SettingsRepository.MAX_DOWNLOAD_THREADS,
                // 原生核心每个活动请求至少占 80 KiB，512 个请求需要 40 MiB。
                bufferBudgetBytes = 40L * 1024 * 1024,
            ).also {
                it.setSpeedLimit(speedLimit())
                engineRef.set(it)
            }
        }
    }

    override fun close() {
        val native = synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
            engineRef.getAndSet(null)
        }
        native?.close()
    }

    override fun diagnostics(taskId: Long): DownloadDiagnostics? = transferDetails[taskId]
    override fun takeDiagnostics(taskId: Long): DownloadDiagnostics? = transferDetails.remove(taskId)

    override val backend = object : DownloadBackend {
        override val name = NativePreviewRouting.NATIVE

        override fun supports(request: DownloadRequest): Boolean =
            request.tags[NativePreviewRouting.TAG] == NativePreviewRouting.NATIVE &&
                (
                    request.url.startsWith("http://", true) ||
                        request.url.startsWith("https://", true) ||
                        (NativePreviewRouting.isFtp(request.url) && capabilities.optBoolean("ftp")) ||
                        (NativePreviewRouting.isSftp(request.url) && capabilities.optBoolean("sftp")) ||
                        (request.tags[NativePreviewRouting.MEDIA_TAG] == NativePreviewRouting.METALINK_FILE &&
                            capabilities.optBoolean("metalink")) ||
                        (NativePreviewRouting.isMagnet(request.url) &&
                            capabilities.optBoolean("bittorrent"))
                    )

        override suspend fun download(context: BackendContext): BackendResult =
            withContext(Dispatchers.IO) {
                check(available) { "原生测试内核无法加载，断点已保留" }
                val request = context.request
                val config = context.config
                val headers = request.headers.toMutableMap()
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    headers["User-Agent"] = config.userAgent
                }
                val native = engine()
                val kind = request.tags[NativePreviewRouting.MEDIA_TAG]
                val nativeHttp = kind == null &&
                    NativePreviewRouting.supportsDirectLink(request.url, request.destination.name)
                val connectionLimit = if (nativeHttp) SettingsRepository.MAX_DOWNLOAD_THREADS
                    else SettingsRepository.COMPAT_DOWNLOAD_THREADS
                val connections = (request.connectionsOverride ?: config.maxConnectionsPerTask)
                    .coerceIn(1, connectionLimit)
                val torrent = NativePreviewRouting.isMagnet(request.url) || kind == NativePreviewRouting.TORRENT_FILE
                val metalink = kind == NativePreviewRouting.METALINK_FILE ||
                    NativePreviewRouting.isMetalinkFile(request.url, request.destination.name)
                val mediaKind = kind?.takeUnless { torrent || metalink }
                check(mediaKind == null || supportsMedia(mediaKind)) { "当前安装包缺少对应的视频内核，断点已保留" }
                check(!torrent || supportsTorrent()) { "当前安装包缺少 BT 内核，断点已保留" }
                check(!metalink || supportsMetalink()) { "当前安装包缺少 Metalink 内核，断点已保留" }
                check(!NativePreviewRouting.isSftp(request.url) || supportsSftp()) { "当前安装包缺少 SFTP 内核，断点已保留" }
                ensureStorage(request.destination, torrent)
                val started = System.nanoTime()
                var task = 0L
                var terminal = false
                try {
                    val taggedAlt = NativePreviewRouting.decodeAltSources(
                        request.tags[NativePreviewRouting.ALT_SOURCES_TAG]
                    )
                    val fallbackCandidates = if (
                        !torrent && !metalink && mediaKind == null && request.expectedSha256.isNullOrBlank()
                    ) {
                        val rawCandidates = when {
                            taggedAlt.isNotEmpty() -> taggedAlt.map { alt ->
                                val altHeaders = alt.headers.toMutableMap()
                                if (altHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                                    altHeaders["User-Agent"] = config.userAgent
                                }
                                NativeTransferClient.HttpSource(alt.url, altHeaders)
                            }
                            request.mirrors.isNotEmpty() -> request.mirrors.map { mirror ->
                                NativeTransferClient.HttpSource(
                                    mirror,
                                    mirrorHeaders(request.url, mirror, request.headers, config.userAgent),
                                )
                            }
                            else -> emptyList()
                        }
                        val seen = linkedSetOf(request.url)
                        rawCandidates.filter { seen.add(it.url) }
                    } else {
                        emptyList()
                    }
                    task = if (torrent) {
                        val stableKey = requireNotNull(request.stableKey)
                        val source = if (NativePreviewRouting.isMagnet(request.url)) {
                            "magnet:" + request.url.substringAfter(':')
                        } else {
                            downloadTorrentMetadata(native, context, headers, config, stableKey)
                        }
                        native.submitTorrent(
                            torrentFileOrMagnet = source,
                            destinationDirectory = request.destination,
                            stableKey = requireNotNull(request.stableKey),
                            policy = NativeTransferClient.TorrentPolicy(
                                peerLimit = connections.coerceIn(1, 256),
                            ),
                        )
                    } else if (metalink) {
                        val stableKey = requireNotNull(request.stableKey)
                        val (document, baseUrl) = loadMetalinkDocument(native, context, headers, config, stableKey)
                        native.submitMetalink(
                            document = document,
                            baseUrl = baseUrl,
                            destination = request.destination,
                            stableKey = stableKey,
                            policy = policy(config, connections, request.url),
                        )
                    } else if (mediaKind != null) {
                        native.submitMedia(
                            source = NativeTransferClient.HttpSource(request.url, headers),
                            destination = request.destination,
                            stableKey = requireNotNull(request.stableKey),
                            expectedSha256 = request.expectedSha256,
                            media = NativeTransferClient.MediaPolicy(
                                protocol = when (mediaKind) {
                                    NativePreviewRouting.MEDIA_HLS -> NativeTransferClient.MediaProtocol.HLS
                                    else -> NativeTransferClient.MediaProtocol.DASH
                                },
                                parallelSegments = connections.coerceAtMost(16),
                                maximumSegmentBytes = 64L * 1024 * 1024,
                            ),
                            policy = policy(config, connections, request.url),
                            network = network(config, request.url),
                        )
                    } else {
                        val mirrorSources = if (!request.expectedSha256.isNullOrBlank()) {
                            when {
                                taggedAlt.isNotEmpty() -> taggedAlt.map { alt ->
                                    val altHeaders = alt.headers.toMutableMap()
                                    if (altHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                                        altHeaders["User-Agent"] = config.userAgent
                                    }
                                    NativeTransferClient.HttpSource(alt.url, altHeaders)
                                }
                                request.mirrors.isNotEmpty() -> request.mirrors.map { mirror ->
                                    NativeTransferClient.HttpSource(
                                        mirror,
                                        mirrorHeaders(request.url, mirror, request.headers, config.userAgent),
                                    )
                                }
                                else -> emptyList()
                            }
                        } else {
                            emptyList()
                        }
                        native.submitHttp(
                            source = NativeTransferClient.HttpSource(
                                NativePreviewRouting.stripSftpUserInfo(request.url),
                                headers,
                            ),
                            destination = request.destination,
                            stableKey = requireNotNull(request.stableKey),
                            expectedSha256 = request.expectedSha256,
                            knownSize = request.knownSize.takeIf { it > 0 },
                            mirrors = mirrorSources,
                            policy = policy(config, connections, request.url),
                            network = network(config, request.url),
                        )
                    }

                    var total = -1L
                    var cap = speedLimit()
                    native.setSpeedLimit(cap)
                    var snapshot = native.query(task)
                    var nextFallbackIndex = 0
                    while (true) {
                        while (!snapshot.terminal) {
                            if (!context.isActive()) throw CancellationException("Download paused")
                            if (mediaKind != null) {
                                val details = native.mediaDetails(task)
                                if (details.live && details.stage == 2) recordings[context.taskId] = task
                                else recordings.remove(context.taskId)
                            }
                            if (snapshot.totalBytes != total) {
                                total = snapshot.totalBytes
                                context.reportTotalSize(total)
                            }
                            transferDetails[context.taskId] = downloadDetails(native, task, snapshot, started, torrent)
                            if (snapshot.state != NativeTransferClient.QUEUED &&
                                (snapshot.state != NativeTransferClient.PROBING || snapshot.downloadedBytes > 0)
                            ) {
                                context.reportProgress(snapshot.downloadedBytes, snapshot.activeRequests)
                            }
                            val nextCap = speedLimit()
                            if (nextCap != cap) {
                                native.setSpeedLimit(nextCap)
                                cap = nextCap
                            }
                            delay(150)
                            snapshot = native.query(task)
                        }
                        val canFailover = snapshot.state == NativeTransferClient.FAILED &&
                            snapshot.errorCode !in setOf(3, 4) &&
                            context.isActive() &&
                            nextFallbackIndex < fallbackCandidates.size
                        if (!canFailover) break
                        val candidate = fallbackCandidates[nextFallbackIndex++]
                        retireTask(native, task)
                        task = native.submitHttp(
                            source = NativeTransferClient.HttpSource(
                                NativePreviewRouting.stripSftpUserInfo(candidate.url),
                                candidate.headers,
                            ),
                            destination = request.destination,
                            stableKey = requireNotNull(request.stableKey),
                            expectedSha256 = request.expectedSha256,
                            knownSize = request.knownSize.takeIf { it > 0 },
                            policy = policy(config, connections, candidate.url),
                            network = network(config, candidate.url),
                        )
                        native.setSpeedLimit(cap)
                        snapshot = native.query(task)
                    }

                    terminal = true
                    if (snapshot.state != NativeTransferClient.COMPLETED) {
                        recordFailure(context, task, snapshot, started, mediaKind != null, torrent)
                        val prefix = snapshot.httpStatus.takeIf { it >= 400 }?.let { "HTTP $it · " }.orEmpty()
                        error(prefix + when (snapshot.errorCode) {
                            5, 6 -> "无法校验原生断点与下载来源，已保留已下载数据"
                            9 -> "服务器限流，请稍后重试"
                            else -> snapshot.message.ifBlank { "原生下载失败" }
                        })
                    }
                    transferDetails[context.taskId] = downloadDetails(native, task, snapshot, started, torrent)
                    context.reportTotalSize(snapshot.totalBytes)
                    context.reportProgress(snapshot.downloadedBytes, 0)
                    if (torrent) {
                        NativePreviewRouting.torrentMetadataFile(request.destination).delete()
                        BackendResult.directory(request.destination, snapshot.totalBytes)
                    } else if (metalink) {
                        NativePreviewRouting.metalinkMetadataFile(request.destination).delete()
                        BackendResult(listOf(request.destination), snapshot.totalBytes)
                    } else {
                        BackendResult(listOf(request.destination), snapshot.totalBytes)
                    }
                } finally {
                    recordings.remove(context.taskId)
                    if (!terminal && task != 0L) transferDetails.remove(context.taskId)
                    if (task != 0L) retireTask(native, task)
                }
            }
    }

    private suspend fun downloadTorrentMetadata(
        native: NativeTransferClient,
        transfer: BackendContext,
        headers: Map<String, String>,
        config: dev.turbodl.core.TurboConfig,
        stableKey: String,
    ): String {
        val request = transfer.request
        val metadata = NativePreviewRouting.torrentMetadataFile(request.destination)
        if (metadata.isFile) return metadata.absolutePath
        val partial = NativePreviewRouting.partialTorrentMetadataFile(request.destination)
        val started = System.nanoTime()
        val task = native.submitHttp(
            source = NativeTransferClient.HttpSource(request.url, headers),
            destination = partial,
            stableKey = "$stableKey-torrent-metadata",
            maximumBytes = 8L * 1024 * 1024,
            policy = policy(config, 1, request.url),
            network = network(config, request.url),
        )
        try {
            var snapshot = native.query(task)
            while (!snapshot.terminal) {
                if (!transfer.isActive()) throw CancellationException("Download paused")
                delay(150)
                snapshot = native.query(task)
            }
            if (snapshot.state != NativeTransferClient.COMPLETED) {
                transferDetails[transfer.taskId] = downloadDetails(native, task, snapshot, started, false)
                    .copy(failureStage = "metadata")
                val http = snapshot.httpStatus.takeIf { it >= 400 }?.let { "HTTP $it · " }.orEmpty()
                error(http + "种子文件下载失败：${snapshot.message}")
            }
            check(partial.renameTo(metadata)) { "无法保存已下载的种子文件" }
            return metadata.absolutePath
        } finally {
            retireTask(native, task)
        }
    }

    private suspend fun loadMetalinkDocument(
        native: NativeTransferClient,
        transfer: BackendContext,
        headers: Map<String, String>,
        config: dev.turbodl.core.TurboConfig,
        stableKey: String,
    ): Pair<String, String> {
        val request = transfer.request
        val localFile = when {
            request.url.startsWith("file://", ignoreCase = true) -> runCatching { File(URI(request.url)) }.getOrNull()
            !request.url.contains("://") -> File(request.url)
            else -> null
        }
        if (localFile != null && localFile.isFile) {
            require(localFile.length() in 1..1_048_576) { "Metalink 文件须小于 1 MiB" }
            return localFile.readText() to ""
        }
        val metadata = NativePreviewRouting.metalinkMetadataFile(request.destination)
        if (metadata.isFile && metadata.length() in 1..1_048_576) {
            return metadata.readText() to request.url
        }
        val partial = NativePreviewRouting.partialMetalinkMetadataFile(request.destination)
        val started = System.nanoTime()
        val task = native.submitHttp(
            source = NativeTransferClient.HttpSource(request.url, headers),
            destination = partial,
            stableKey = "$stableKey-metalink-metadata",
            maximumBytes = 1_048_576L,
            policy = policy(config, 1, request.url),
            network = network(config, request.url),
        )
        try {
            var snapshot = native.query(task)
            while (!snapshot.terminal) {
                if (!transfer.isActive()) throw CancellationException("Download paused")
                delay(150)
                snapshot = native.query(task)
            }
            if (snapshot.state != NativeTransferClient.COMPLETED) {
                transferDetails[transfer.taskId] = downloadDetails(native, task, snapshot, started, false)
                    .copy(failureStage = "metadata")
                val http = snapshot.httpStatus.takeIf { it >= 400 }?.let { "HTTP $it · " }.orEmpty()
                error(http + "Metalink 文件下载失败：${snapshot.message}")
            }
            check(partial.renameTo(metadata)) { "无法保存已下载的 Metalink 文件" }
            return metadata.readText() to request.url
        } finally {
            retireTask(native, task)
        }
    }

    private suspend fun retireTask(native: NativeTransferClient, task: Long) = withContext(NonCancellable) {
        if (!closed.get()) {
            var current = native.query(task)
            if (!current.terminal && current.state != NativeTransferClient.PAUSED) {
                if (current.state != NativeTransferClient.PAUSING) {
                    try {
                        native.pause(task)
                    } catch (error: IllegalStateException) {
                        if (!native.query(task).terminal) throw error
                    }
                }
                do {
                    delay(20)
                    current = native.query(task)
                } while (!current.terminal && current.state != NativeTransferClient.PAUSED)
            }
            native.forget(task)
        }
    }

    private fun policy(
        config: dev.turbodl.core.TurboConfig,
        connections: Int,
        url: String,
    ): NativeTransferClient.Policy = NativeTransferClient.Policy(
        connections = connections,
        requestWindowBytes = config.minSegmentSize.coerceIn(8192, 64L * 1024 * 1024),
        retries = config.maxRetries,
        maxConnectionsPerHost = config.maxConnectionsPerHost.takeIf { it > 0 }
            ?: maxOf(SettingsRepository.COMPAT_DOWNLOAD_THREADS, connections),
        cooldownGroup = runCatching { URI(url).host.orEmpty() }.getOrDefault(""),
        connectTimeoutMs = config.connectTimeoutMs.toInt(),
        readTimeoutMs = config.readTimeoutMs.toInt(),
    )

    private fun ensureStorage(destination: File, directoryTarget: Boolean) {
        val root = if (directoryTarget) destination else destination.parentFile
        val directory = requireNotNull(root) { "下载目标目录不可用" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("原生下载无法创建目标目录：${directory.absolutePath}")
        }
        val probe = File(directory, ".yunget-native-write-${System.nanoTime()}")
        try {
            if (!probe.createNewFile()) throw IllegalStateException("原生下载目标目录不可写")
        } finally {
            probe.delete()
        }
    }

    private fun network(
        config: dev.turbodl.core.TurboConfig,
        url: String,
    ): NativeTransferClient.Network {
        val httpVersion = when {
            httpVersionPreference() == 3 && hasHttp3() && url.startsWith("https://", true) -> 3
            config.effectiveHttpVersionPolicy == dev.turbodl.core.HttpVersionPolicy.FORCE_HTTP1 -> 1
            config.effectiveHttpVersionPolicy == dev.turbodl.core.HttpVersionPolicy.FORCE_HTTP2 &&
                capabilities.optBoolean("http2_compiled") -> 2
            else -> 0
        }
        val isSftp = NativePreviewRouting.isSftp(url)
        val sftp = if (isSftp) sftpConfig() else null
        val uriUserInfo = if (isSftp) runCatching { URI(url).userInfo }.getOrNull().orEmpty() else ""
        val urlUser = uriUserInfo.substringBefore(':', "").takeIf { uriUserInfo.isNotEmpty() }.orEmpty()
        val urlPass = if (':' in uriUserInfo) uriUserInfo.substringAfter(':') else ""
        return NativeTransferClient.Network(
            username = urlUser.ifBlank { sftp?.username.orEmpty() },
            password = urlPass.ifBlank { sftp?.password.orEmpty() },
            proxy = systemProxy(url),
            caBundle = caBundle.absolutePath,
            sshKnownHosts = sftp?.knownHosts.orEmpty(),
            sshPublicKey = sftp?.publicKey.orEmpty(),
            sshPrivateKey = sftp?.privateKey.orEmpty(),
            dohUrl = (config.dns as? DnsMode.DoH)?.dohUrl.orEmpty(),
            httpVersion = httpVersion,
        )
    }

    private fun systemProxy(url: String): String {
        val proxy = runCatching {
            ProxySelector.getDefault()?.select(URI(url)).orEmpty()
                .firstOrNull()
        }.getOrNull() ?: return ""
        val address = proxy.address() as? InetSocketAddress ?: return ""
        val host = address.hostString.let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        if (host.isBlank() || address.port <= 0) return ""
        return when (proxy.type()) {
            Proxy.Type.SOCKS -> "socks5h://$host:${address.port}"
            Proxy.Type.HTTP -> "http://$host:${address.port}"
            Proxy.Type.DIRECT -> ""
        }
    }

    private fun mirrorHeaders(
        primary: String,
        mirror: String,
        headers: Map<String, String>,
        userAgent: String,
    ): Map<String, String> {
        val sameOrigin = runCatching {
            val a = URI(primary)
            val b = URI(mirror)
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port
        }.getOrDefault(false)
        if (sameOrigin) return headers
        val safe = headers.filterKeys {
            it.lowercase() !in setOf(
                "authorization", "cookie", "proxy-authorization", "x-api-key",
                "x-auth-token", "x-csrf-token",
            )
        }.toMutableMap()
        if (safe.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            safe["User-Agent"] = userAgent
        }
        return safe
    }

    private fun recordFailure(
        context: BackendContext,
        task: Long,
        snapshot: NativeTransferClient.Snapshot,
        started: Long,
        media: Boolean,
        torrent: Boolean,
    ) {
        val native = engine()
        val mediaStage = if (media) {
            native.mediaDetails(task).failureStage
        } else 0
        transferDetails[context.taskId] = downloadDetails(native, task, snapshot, started, torrent).copy(
            failureStage = when {
                mediaStage == 1 -> "resolving"
                mediaStage == 2 -> "transferring"
                mediaStage == 3 -> "processing"
                mediaStage == 4 -> "saving"
                snapshot.errorCode == 3 -> "storage"
                snapshot.errorCode == 4 -> "integrity"
                torrent && snapshot.downloadedBytes == 0L -> "metadata"
                snapshot.downloadedBytes == 0L -> "probing"
                else -> "transferring"
            },
        )
    }

    private fun downloadDetails(
        native: NativeTransferClient,
        task: Long,
        snapshot: NativeTransferClient.Snapshot,
        started: Long,
        torrent: Boolean,
    ): DownloadDiagnostics {
        val metrics = if (torrent) null else native.httpMetrics(task)
        return DownloadDiagnostics(
            firstByteMillis = metrics?.taskFirstByteMicroseconds?.takeIf { it > 0 }?.div(1_000),
            probeMillis = 0,
            transferMillis = (System.nanoTime() - started) / 1_000_000,
            lastHttpStatus = snapshot.httpStatus.takeIf { it >= 400 },
            negotiatedHttpVersions = metrics?.negotiatedVersionBits ?: 0,
        )
    }

    companion object {
        private val loaded: Boolean by lazy {
            try {
                NativeBridge.loadPackaged()
                true
            } catch (_: LinkageError) {
                false
            }
        }
        private val capabilities: JSONObject by lazy {
            if (loaded) JSONObject(NativeTransferClient.capabilities()) else JSONObject()
        }

        fun hasHttp3(): Boolean = capabilities.optBoolean("http3_compiled")
        fun hasPackagedCore(): Boolean = loaded
        fun compiledProtocols(): List<String> = buildList {
            val protocols = mapOf(
                "http" to "HTTP/1.1", "http2_compiled" to "HTTP/2", "http3_compiled" to "HTTP/3",
                "ftp" to "FTP", "sftp" to "SFTP", "hls" to "HLS", "dash" to "DASH",
                "bittorrent" to "BT", "metalink" to "Metalink",
            )
            for ((key, label) in protocols) if (capabilities.optBoolean(key)) add(label)
        }
    }
}
