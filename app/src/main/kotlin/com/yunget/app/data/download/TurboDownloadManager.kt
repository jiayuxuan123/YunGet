package com.yunget.app.data.download

import android.content.Context
import android.util.Log
import com.yunget.app.data.db.DownloadTaskDao
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.gopeed.GopeedEngine
import com.yunget.app.data.prefs.SettingsRepository
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.DnsMode
import dev.turbodl.core.ProxyMode
import dev.turbodl.core.TaskState
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboDiagnostics
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.yunget.app.util.DiagLog

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,        // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1       // 分片（线程）数
)

private const val TAG = "YunGet-DL"

/**
 * 诊断最多探活多少个候选任务。
 *
 * 每次探活只是一次 1 字节 Range 请求（几十毫秒），但任务多时逐个探完没意义 ——
 * 挑大的前几个即可，反正真正测的是"链接可用"这件事。
 */
private const val DIAG_PROBE_MAX_CANDIDATES = 8

/**
 * 「自动 DNS」的哨兵值（与设置页 `AUTO_DOH` 必须一致）。
 *
 * 设置里只存字符串，这里把它翻译成引擎的 `DnsMode.Auto`。
 * 用哨兵而非空串：空串已被「不使用 DoH」占用，两者语义不同。
 */
private const val AUTO_DOH_SENTINEL = "auto://best"

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
) : DownloadManager {
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
    override val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 任务列表（Room Flow 直通）*/
    override val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 存储权限检查（UI 注入）；Android 10+ 或已授权返回 true。 */
    override var storagePermissionProvider: suspend () -> Boolean = { true }

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

    // ---------- 外部下载引擎（Gopeed）路由状态 ----------
    //
    // 为什么要有这一套：设置页的 `downloadEngine` 选了 Gopeed 时，「执行」这一层交给 Gopeed 内核
    // （进程内 gomobile 核心），TurboDL 完全不参与；但任务登记/落库/进度/前台服务/清理回调
    // 与内置路径**完全一致**，所以这里只加路由与同步，不改动既有的 TurboDL 路径。

    /** 下载设置（引擎选择等）；管理器是长生命周期对象，懒读一次即可 */
    private val settings by lazy { SettingsRepository(context) }

    /**
     * roomId → Gopeed 引擎任务 ID 的内存索引。
     *
     * DB 的 `engineTaskId` 才是持久化真源（进程重启后靠它找回任务），这里只给
     * pause/remove 这类**同步**入口做判断，避免在主线程上查库。
     */
    private val taskEngineIds = ConcurrentHashMap<Long, String>()

    /**
     * roomId → 引擎任务的落盘目录（建任务时确定）。
     *
     * 完成时要拼「真实保存路径」，而 [GopeedEngine.resolveDownloadDir] 可能因为用户中途改设置
     * 或权限变化而返回**另一个**目录 —— 引擎产物在哪个目录是建任务那一刻定下的，必须记住。
     */
    private val taskEngineDirs = ConcurrentHashMap<Long, File>()

    /**
     * 已经计入「前台服务保活」的引擎任务。
     *
     * 引擎任务不走 TurboDL 的事件回调路径，保活必须自己配对：**加入时**才拉起前台服务 + WakeLock，
     * **移除时**才允许释放。用集合的 add/remove 返回值去重，保证无论从哪条路径终结
     * （完成 / 失败 / 用户暂停 / 用户删除 / 引擎侧自己变 pause）都只扣一次。
     */
    private val engineKeepAliveIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 引擎任务进度同步协程（同一时刻只跑一个；没有可同步任务时自己退出） */
    private var engineSyncJob: Job? = null

    /**
     * roomId → 引擎侧最近一次被处理的状态（"running"/"pause"/"wait"…）。
     *
     * 只用于「状态没变就不写库」：引擎的 pause/wait 会持续多轮，每轮都写库等于每 700ms
     * 触发一次全表 Flow 重发 → 主线程重组整个下载列表。
     */
    private val engineLastEngineStatus = ConcurrentHashMap<Long, String>()

    /** 引擎任务同步间隔（毫秒）：调用是本进程内分发、不走网络，可以问得勤一点 */
    private val engineSyncIntervalMs = 700L

    /**
     * 引擎「启动 + 建任务」的串行锁。
     *
     * [GopeedEngine.start] 的幂等判断是「先读状态、再启动」，不是原子的：并发进入会重复加载 .so、
     * 重复启动（Go 侧直接报错）。整目录批量入队时多条任务会并发走到这里，必须有这道闸。
     */
    private val engineLock = Mutex()

    /** 前台服务任务计数 */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** 通知节流：按 roomId 分别记录上次通知时间，避免多任务互相干扰 */
    private val notifyThrottleMs = 1000L
    private val lastNotifyTsByTask = ConcurrentHashMap<Long, Long>()
    /** 进度写库节流：按 roomId 记录上次写入时间/字节，避免高频 DB 竞争 */
    private val lastDbWriteTs = ConcurrentHashMap<Long, Long>()
    private val lastDbWriteBytes = ConcurrentHashMap<Long, Long>()

    companion object {
        /**
         * 进度落盘的时间间隔（毫秒）。**这是唯一节流条件**。
         *
         * 每次写库都会触发 `observeAll()` 的 Flow 重发 → 主线程重组整个下载列表。
         * 取 800ms：既让「杀掉进程后重进」看到的进度足够新，又把列表重组频率
         * 压到人眼无感的水平（约 1.25 次/秒，与下载速度无关）。
         */
        private const val DB_WRITE_INTERVAL_MS = 800L
    }

    /** 启动阶段诊断：Metadata(探测完成)时刻，用于在首个 Progress 时算出「首字节耗时」。任务结束即清。 */
    private val metadataAtMs = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var lastConfigSignature: String = ""

    init {
        // 监听 TurboDL 事件流：桥接进度到 UI stats、驱动持久化与完成保存。
        scope.launch {
            client.events.collect { ev -> onTurboEvent(ev) }
        }
        // 引擎任务也接上进度同步：应用重启后引擎里未完成的任务（引擎自己持久化在它的 store 里）
        // 必须继续回写本地记录，否则界面上的进度会永远停在重启前那一刻。
        startEngineSync()
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
        // DNS：
        //  - 设置为「自动」哨兵 → DnsMode.Auto（并发探测最快的公共 DoH，失败回退系统 DNS）
        //  - 配了具体 DoH 地址 → DnsMode.DoH（用户显式指定）
        //  - 未配置 → DnsMode.System
        dns = when (val d = dohUrlProvider()?.takeIf { it.isNotBlank() }) {
            null -> DnsMode.System
            AUTO_DOH_SENTINEL -> DnsMode.Auto()
            else -> DnsMode.DoH(d)
        },
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

    /**
     * 【开发诊断】状态与结果都**挂在管理器上**（而不是界面的 remember 里）——
     * 否则用户一切页面/退出设置页，状态就丢了，"跑没跑完"无从判断。
     */
    @Volatile
    override var diagnoseStatus: String? = null
        private set

    @Volatile
    override var diagnoseLastResult: String? = null
        private set

    /** 诊断目标：任务的链接、请求头与已知大小。 */
    private data class DiagTarget(
        val id: Long,
        val fileName: String,
        val url: String,
        val headers: Map<String, String>,
        val knownSize: Long,
    )

    /** 文件太小的话，固定时长窗口内会下完 → 速率不可比（见 [pickDiagTarget] 注释）。 */
    private fun sizeWarning(knownSize: Long): String =
        if (knownSize in 1..(64L * 1024 * 1024)) {
            "⚠ 该文件仅 ${knownSize / 1048576} MB，可能在窗口内下完导致数据不可比；" +
                "建议改用 ≥200MB 的任务复测。\n\n"
        } else ""

    /**
     * 【开发诊断】按"最可能成功"的顺序尝试任务，返回第一个**链接仍然可用**的目标。
     *
     * 【为什么必须探活】任务表存的是**取链时刻的签名直链**：夸克 `__puus` 约 3 小时过期，
     * 且分享转存类任务在下载完成后会**删掉云端临时目录** —— 那条直链永久失效。
     * 实测事故：诊断拿这种死链连跑 4 档 × 15 秒，最后只给出"四档全 0 + 样本不足"，
     * 用户白等 1 分钟还看不出是链接的问题。
     *
     * 【为什么要重新取链】仅探活还不够：用户的旧任务**迟早全部过期**
     * （网盘直链本就是短时的），那时诊断会一律报"没有链接可用的任务"——
     * 表面看就是"功能用不了"。故先按任务里存的 URL 探活，
     * 失败时通过 [freshUrlProvider] 用**当前登录态**重新取一次直链再探。
     *
     * 取到新链后**只用于本次诊断**，不回写任务表：任务的 URL 属于该任务自身的取链时刻，
     * 擅自改写会让"暂停/续传"等操作落到用户没预期的地址上。
     */
    private suspend fun pickAliveDiagTarget(
        freshUrlProvider: suspend (taskId: Long) -> String? = { null },
    ): DiagTarget? {
        val tasks = dao.getAllOnce()
        fun sizeOf(t: DownloadTaskEntity) = taskSizes[t.id] ?: t.totalSize.takeIf { it > 0 } ?: -1L
        // 排序：先按"最新创建"（直链有时效，越新越可能还有效），再按大小降序
        // （太小的文件在窗口内会下完，速率不可比）。
        val pool = tasks.filter { it.status != DownloadTaskEntity.STATUS_COMPLETED }
            .ifEmpty { tasks }
            .sortedWith(compareByDescending<DownloadTaskEntity> { it.createTime }
                .thenByDescending { sizeOf(it) })
        if (pool.isEmpty()) return null

        var lastReason: String? = null
        var refreshTried = 0
        var refreshSucceeded = 0
        for (t in pool.take(DIAG_PROBE_MAX_CANDIDATES)) {
            val headers = taskHeaders[t.id] ?: parseHeadersJson(t.requestHeadersJson)
            val stored = TurboDiagnostics.checkReachableAsync(t.url, headers)
            if (stored == null) {
                return DiagTarget(t.id, t.fileName, t.url, headers, sizeOf(t))
            }
            lastReason = stored
            Log.i(TAG, "诊断候选不可用（存库直链）task=${t.id} ${t.fileName.take(40)} 原因=$stored")

            // 存库直链失效 → 用当前登录态重新取一次（这正是修复"功能用不了"的关键）
            refreshTried++
            val fresh = runCatching { freshUrlProvider(t.id) }.getOrNull()
            if (fresh.isNullOrBlank()) continue
            val freshBad = TurboDiagnostics.checkReachableAsync(fresh, headers)
            if (freshBad == null) {
                refreshSucceeded++
                Log.i(TAG, "诊断候选已重新取链 task=${t.id} ${t.fileName.take(40)}")
                return DiagTarget(t.id, t.fileName, fresh, headers, sizeOf(t))
            }
            lastReason = freshBad
            Log.i(TAG, "诊断候选重新取链后仍不可用 task=${t.id} 原因=$freshBad")
        }

        diagnoseStatus = "❌ 没有链接可用的任务（试了 ${minOf(pool.size, DIAG_PROBE_MAX_CANDIDATES)} 个" +
            if (refreshTried > 0) "，其中 $refreshTried 个尝试重新取链、成功 $refreshSucceeded 个" else "" +
            "）：${lastReason ?: "未知原因"}\n。若刚解析过链接仍失败，可能是该网盘登录态已过期 —— " +
            "请到「网盘」页确认登录状态，或换一个较大的分享文件新建下载任务后再诊断。"
        return null
    }

    /**
     * 【开发诊断】并发任务扫描：固定每任务连接数，只改**同时下载的任务数**（1/2/3）。
     *
     * 回答连接数扫描回答不了的问题：这个固定速率是**单文件上限**（多任务可叠加 → 想更快就并行多任务）
     * 还是 **IP/账户总量上限**（多任务无用 → 任何客户端手段都突破不了）。
     */
    override suspend fun diagnoseConcurrentTasks(
        taskCounts: List<Int>,
        connectionsPerTask: Int,
        windowMs: Long,
        freshUrlProvider: suspend (taskId: Long) -> String?,
    ): String {
        val target = pickAliveDiagTarget(freshUrlProvider) ?: run {
            if (diagnoseStatus == null) {
                diagnoseStatus = "❌ 没有可用的任务：先添加一个下载任务，再跑诊断。"
            }
            return diagnoseStatus!!
        }
        diagnoseLastResult = null
        diagnoseStatus = "运行中（并发任务）0/${taskCounts.size}：准备…"
        Log.i(
            TAG,
            "=== 并发任务诊断开始 task=${target.id} url=${target.url.take(100)} " +
                "任务数=$taskCounts 每任务连接=$connectionsPerTask 每档=${windowMs}ms ==="
        )
        var done = 0
        val results = runCatching {
            TurboDiagnostics.sweepConcurrentTasks(
                url = target.url,
                headers = target.headers,
                knownSize = target.knownSize,
                taskCounts = taskCounts,
                connectionsPerTask = connectionsPerTask,
                windowMs = windowMs,
                workDir = chunkWorkDir(),
            ) { r ->
                done += 1
                diagnoseStatus = "运行中（并发任务）$done/${taskCounts.size}：${r.axisLabel} → %.2f MB/s"
                    .format(r.mbPerSec)
                Log.i(TAG, "并发任务诊断: $r")
            }
        }.getOrElse { e ->
            Log.w(TAG, "并发任务诊断失败: ${e.message}")
            diagnoseStatus = "❌ 诊断失败：${e.message}"
            return diagnoseStatus!!
        }
        val verdict = TurboDiagnostics.interpretConcurrent(results)
        Log.i(TAG, "并发任务诊断判读: $verdict")

        val text = buildString {
            appendLine("云取 · 并发任务诊断（每任务 $connectionsPerTask 连接）")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
            appendLine("任务：${target.fileName.ifBlank { target.url.take(60) }}")
            appendLine("文件大小：${if (target.knownSize > 0) "${target.knownSize / 1048576} MB" else "未知"}")
            appendLine("档位：${taskCounts.joinToString(" / ")} 个任务并行（每档 ${windowMs / 1000}s）")
            appendLine()
            append(sizeWarning(target.knownSize))
            results.forEach { appendLine(it.toString()) }
            appendLine()
            append(verdict)
        }
        diagnoseLastResult = text
        val savedPath = runCatching {
            val dir = File(context.getExternalFilesDir(null), "diagnostics").apply { mkdirs() }
            val f = File(dir, "concurrent-sweep-${System.currentTimeMillis()}.txt")
            f.writeText(text)
            f.absolutePath
        }.getOrNull()
        Log.i(TAG, "并发任务诊断结果已写入：${savedPath ?: "(落盘失败)"}")
        diagnoseStatus = "✅ 已完成（并发任务 ${results.size} 档）" +
            if (savedPath != null) " · 结果文件：$savedPath" else " · 结果见日志"
        return text
    }

    /**
     * 【开发诊断】用最近一个任务（优先未完成的）的**真实链接**做连接数扫描，返回可读报告。
     *
     * 为什么必须在手机上跑：夸克等网盘的直链带签名/Cookie，且限速行为与出口 IP 相关 ——
     * 只有在真实设备 + 真实链接上测，曲线才有意义（回环或代理都测不出真实模型）。
     *
     * 只改连接数、其他配置不变，每档跑固定窗口后取消（**不会**下完整个文件）。
     * 曲线形状的判读见 `TurboDiagnostics.interpret`：
     * 线性上升=每连接限速；持平=按 IP 聚合限速；先升后降=对并发有惩罚。
     *
     * 结果会：① 写入应用外部目录的文件（可直接取走）② 写进日志（可随"导出日志"回传）
     * ③ 保留在 [diagnoseLastResult] 供界面随时查看/分享。
     */
    override suspend fun diagnoseConnections(
        // 档位上限压到 64：连接数拉满容易触发网盘风控（用户 2026-09-17 明确要求）。
        // 8/16/32/64 四点已足够看出单调性/平坦/拐点三种形状。
        tiers: List<Int>,
        windowMs: Long,
        urlOverride: String?,
        freshUrlProvider: suspend (taskId: Long) -> String?,
    ): String {
        // 指定了链接：直接探活并使用（用于"任务直链都已过期"时的兜底）
        val overridden = urlOverride?.takeIf { it.isNotBlank() }?.let { url ->
            val bad = TurboDiagnostics.checkReachableAsync(url)
            if (bad == null) {
                DiagTarget(0L, "（指定链接）", url, emptyMap(), -1L)
            } else {
                diagnoseStatus = "❌ 指定的链接不可用：$bad"
                return diagnoseStatus!!
            }
        }
        val target = overridden ?: pickAliveDiagTarget(freshUrlProvider) ?: run {
            if (diagnoseStatus == null) {
                diagnoseStatus = "❌ 没有可用的任务：先添加一个下载任务，再用它的链接做诊断。"
            }
            return diagnoseStatus!!
        }
        val headers = target.headers
        val known = target.knownSize

        diagnoseLastResult = null
        diagnoseStatus = "运行中 0/${tiers.size}：准备…"
        Log.i(
            TAG,
            "=== 连接数诊断开始 task=${target.id} url=${target.url.take(100)} " +
                "knownSize=$known 档位=$tiers 每档=${windowMs}ms ==="
        )
        var done = 0
        val results = runCatching {
            TurboDiagnostics.sweepConnections(
                url = target.url,
                headers = headers,
                knownSize = known,
                tiers = tiers,
                windowMs = windowMs,
                workDir = chunkWorkDir(),
            ) { r ->
                done += 1
                diagnoseStatus = if (r.error != null) {
                    "运行中 $done/${tiers.size}：连接=${r.connections} ❌ ${r.error}"
                } else {
                    "运行中 $done/${tiers.size}：连接=${r.connections} → %.2f MB/s".format(r.mbPerSec)
                }
                Log.i(TAG, "连接数诊断: $r")
            }
        }.getOrElse { e ->
            Log.w(TAG, "连接数诊断失败: ${e.message}")
            diagnoseStatus = "❌ 诊断失败：${e.message}"
            return diagnoseStatus!!
        }
        val verdict = TurboDiagnostics.interpret(results)
        Log.i(TAG, "连接数诊断判读: $verdict")

        val text = buildString {
            appendLine("云取 · 连接数诊断")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
            appendLine("任务：${target.fileName.ifBlank { target.url.take(60) }}")
            appendLine("文件大小：${if (target.knownSize > 0) "${target.knownSize / 1048576} MB" else "未知"}")
            appendLine("档位：${tiers.joinToString(" / ")}（每档 ${windowMs / 1000}s）")
            appendLine()
            append(sizeWarning(target.knownSize))
            results.forEach { appendLine(it.toString()) }
            appendLine()
            append(verdict)
        }
        diagnoseLastResult = text

        // 落盘：结果文件放在应用外部目录，文件管理器可直接取走（通知里会给路径）。
        val savedPath = runCatching {
            val dir = File(context.getExternalFilesDir(null), "diagnostics").apply { mkdirs() }
            val f = File(dir, "conn-sweep-${System.currentTimeMillis()}.txt")
            f.writeText(text)
            f.absolutePath
        }.getOrNull()
        Log.i(TAG, "连接数诊断结果已写入：${savedPath ?: "(落盘失败)"}")

        diagnoseStatus = "✅ 已完成（${results.size} 档）" +
            if (savedPath != null) " · 结果文件：$savedPath" else " · 结果见日志"
        return text
    }

    /** 每次入队/开始前按当前设置热更新引擎配置（限速/并发/线程/忽略SSL 即时生效）。 */
    private fun refreshConfigIfChanged() {        val cfg = buildConfig()        // 签名覆盖所有会影响下载行为的字段（原先只有 5 个，forceHttp1/segmentsPerConnection 等改了不生效）。
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

    /**
     * 释放本管理器持有的全部资源（引擎、OkHttp 连接池与线程、事件收集协程、WakeLock）。
     *
     * 【为什么必须有】管理器在 `MainScreen` 里用 `remember {}` 创建，而 `remember` 无 key：
     * Activity 每次重建（旋转屏幕、改主题/图标、系统回收重建）都会 new 一个新实例。
     * 旧实例若不被释放，它的 TurboClient（含两个 OkHttpClient 的 Dispatcher 线程池与连接池）
     * 与 `client.events.collect` 协程会**永久残留**——长跑下来就是无界增长。
     *
     * 幂等：重复调用安全（内部各步都做了容错；[TurboBootstrap.shutdown] 自身可重复进入）。
     */
    override fun shutdown() {
        if (!shutdownFlag.compareAndSet(false, true)) return
        runCatching { scope.cancel() }
        runCatching { bootstrap.shutdown() }
        runCatching { releaseWakeLock() }
        // 保活引用要**归还**而不是直接 stopService：引用计数是跨来源共享的
        // （Gopeed 内核包下载 KernelProvisioner 也在用同一条前台服务），直接停会把别人的保活一起关掉。
        // getAndSet(0) 的旧值 > 0 说明本管理器确实持有一份（acquire 只在 0→1 时真正拉起服务）。
        if (activeTaskCount.getAndSet(0) > 0) runCatching { DownloadService.release(context) }
        turboIds.clear()
        turboIdToRoomId.clear()
        taskHeaders.clear()
        taskSizes.clear()
        taskNames.clear()
        taskCallbacks.clear()
        turboOutputs.clear()
        Log.i(TAG, "manager shutdown 完成（引擎与连接池已释放）")
    }

    /** 是否已释放（释放后再调用 start/enqueue 会被忽略，避免用已关闭的 client 提交任务）。 */
    val isShutdown: Boolean get() = shutdownFlag.get()

    private val shutdownFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 入队并立即开始下载。返回 Room 任务 id。 */
    override suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String>,
        size: Long,
        onComplete: suspend () -> Unit,
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
        // 引擎路由：选了 Gopeed 且内核已导入 → 「执行」交给引擎，任务登记/写库/回调与内置路径一致；
        // 否则一行不改地走原来的 TurboDL 路径。
        if (shouldUseEngine()) {
            startViaEngine(id, url, safeName, headers)
        } else {
            start(id, headers)
        }
        return id
    }

    /** 开始/恢复下载（断点续传）。 */
    override fun start(id: Long, headers: Map<String, String>) {
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
        // 已在运行：忽略重复启动
        if (turboIds.containsKey(id)) return
        refreshConfigIfChanged()
        scope.launch {
            val task = dao.get(id) ?: return@launch
            // 引擎任务分流：DB 里的 engineTaskId 非空说明这条任务由 Gopeed 引擎执行。
            // ★ 绝不能落到下面的 TurboDL 路径 —— 同一个 URL 会被两套引擎重复下载。
            // 走 DB 而不是内存索引：进程重启后索引是空的，而库里的记录还在。
            if (task.engineTaskId.isNotBlank()) {
                taskNames[id] = task.fileName
                resumeEngineTask(id, task.engineTaskId)
                return@launch
            }
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
    override fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        // 引擎任务分流：内存索引命中说明该任务由 Gopeed 引擎执行 —— 转发引擎暂停后直接返回。
        // 引擎任务没有分片/临时文件，也不在 turboIds 里，下面的 TurboDL 清理对它全是无操作，
        // 但会多扣一次保活计数（把还在下载的其它任务的前台服务一起关掉），所以必须分流。
        taskEngineIds[id]?.let { engineId ->
            pauseEngineTask(id, engineId)
            return
        }
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
    override fun remove(id: Long, deleteLocal: Boolean) {
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        // 引擎任务分流（只做「额外」的事）：通知引擎把任务删掉；本地清理（DB 记录、已下载文件、
        // 清理回调）继续走下面的原逻辑 —— 语义与内置路径完全一致。
        val engineId = taskEngineIds.remove(id)
        if (engineId != null) {
            releaseEngineTaskMemory(id)
            // 保活按集合精确配对：可能从未计入（进程重启后才发现的历史任务），那时不扣。
            if (engineKeepAliveIds.remove(id)) onTaskFinished()
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { GopeedEngine.deleteTask(engineId) } }
                    .onFailure { Log.e(TAG, "引擎任务删除失败：id=$id ${it.message}", it) }
            }
        }
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
            // 引擎任务的保活已在上面的分流里按集合精确配对过，这里不能再扣（否则会多扣一次，
            // 把其它仍在下载的任务的前台服务一起关掉）
            if (engineId == null) onTaskFinished()
        }
    }

    /**
     * 进程启动时调用：把上次被杀遗留的「下载中/等待中」任务标为已暂停。
     *
     * 不调用的话，进程被杀后任务状态永远停在“下载中”，UI 上既不跑也无法恢复。
     * 标为暂停后用户可手动点击恢复（因分片目录用 stableKey 保留，恢复从断点继续）。
     */
    override fun recoverInterruptedTasks() {
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

    // ---------- 外部下载引擎（Gopeed）路由 ----------

    /**
     * 是否把任务交给 Gopeed 引擎：设置里选了引擎 + 内核已导入。
     *
     * 内核不存在时这里**不静默改设置**（自愈在应用启动时做一次，见 `YunGetApp.autoStartGopeedIfSelected`），
     * 只让这条任务按内置路径走 —— 「内核被删了」不该让下载功能整个失效。
     */
    private fun shouldUseEngine(): Boolean =
        settings.downloadEngine == SettingsRepository.ENGINE_GOPEED && GopeedEngine.isInstalled(context)

    /**
     * 用 Gopeed 引擎开始下载：确保引擎在跑 → 建任务 → 引擎任务 ID 写回本地记录 → 拉起进度同步。
     *
     * 建任务失败按普通失败任务落库（errorMsg 写引擎原文），**不做静默回退到内置下载器** ——
     * 用户明确选了引擎，悄悄换下载器比报错更难排查。
     */
    private suspend fun startViaEngine(
        id: Long,
        url: String,
        fileName: String,
        headers: Map<String, String>,
    ) {
        val created = try {
            engineLock.withLock {
                withContext(Dispatchers.IO) {
                    val dir = GopeedEngine.resolveDownloadDir(context)
                    if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                        GopeedEngine.start(context, dir)
                    }
                    val engineTaskId = GopeedEngine.createTask(
                        url = url,
                        saveDir = dir,
                        headers = headers,
                        // 连接数用与内置路径同一个设置值（同一个「下载线程数」口径，避免两套设置各说各话）
                        connections = threadProvider().coerceIn(1, 128),
                        name = fileName,
                        // 任务侧 id 塞进标签：出了问题时能在引擎侧直接反查是本地哪条任务
                        labels = mapOf("yungetTaskId" to id.toString()),
                    )
                    dir to engineTaskId
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "引擎建任务失败：id=$id ${e.message}", e)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, e.message ?: e.toString())
            return
        }
        val dir = created.first
        val engineTaskId = created.second
        Log.d(TAG, "引擎任务已创建：roomId=$id engineId=$engineTaskId dir=${dir.absolutePath}")
        taskEngineIds[id] = engineTaskId
        taskEngineDirs[id] = dir
        // 保活：第一个引擎任务拉起前台服务 + WakeLock，锁屏/退后台引擎才不会被系统收掉
        if (engineKeepAliveIds.add(id)) onTaskStarted(id)
        dao.updateEngineTaskId(id, engineTaskId)
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        startEngineSync()
    }

    /**
     * 引擎任务进度同步：把引擎侧状态回写到本地记录（下载页读的是 Room，所以必须回写），
     * 完成时触发 onComplete 清理回调。没有可同步任务就自动退出，下次建引擎任务时再拉起。
     *
     * 每轮先确保引擎在运行：进程重启后引擎里可能还有未完成任务（引擎自己持久化在它的 store 里），
     * 引擎重启后这些任务一般是 pause，会被回写成本地「已暂停」，由用户决定是否继续。
     */
    private fun startEngineSync() {
        if (engineSyncJob?.isActive == true) return
        engineSyncJob = scope.launch {
            while (isActive) {
                val pending = runCatching { dao.listSyncableEngineTasks() }.getOrDefault(emptyList())
                if (pending.isEmpty()) return@launch
                if (GopeedEngine.state.value != GopeedEngine.State.RUNNING && GopeedEngine.isInstalled(context)) {
                    runCatching {
                        engineLock.withLock {
                            withContext(Dispatchers.IO) {
                                GopeedEngine.start(context, GopeedEngine.resolveDownloadDir(context))
                            }
                        }
                    }.onFailure { Log.e(TAG, "引擎同步时启动引擎失败：${it.message}", it) }
                }
                for (task in pending) {
                    // 索引每轮补一次：进程重启后的历史引擎任务靠它进入 pause/remove 的分流判据
                    taskEngineIds[task.id] = task.engineTaskId
                    taskNames[task.id] = task.fileName
                    val status = runCatching {
                        withContext(Dispatchers.IO) { GopeedEngine.taskStatus(task.engineTaskId) }
                    }.getOrNull() ?: continue
                    when (status.status) {
                        "done" -> completeEngineTask(task, status.total)
                        "error" -> {
                            Log.e(TAG, "引擎任务失败：roomId=${task.id} engineId=${task.engineTaskId}")
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED)
                            dao.updateError(task.id, "Gopeed 引擎下载失败")
                            _stats.update { it - task.id }
                            taskEngineIds.remove(task.id)
                            releaseEngineTaskMemory(task.id)
                            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
                        }
                        "pause" -> {
                            // 引擎侧自己变暂停（进程重启后未续传等）：保活到此为止，用户点继续时会重新拉起。
                            // ★ 状态没变就不写库：暂停中的任务每轮都会读回 "pause"，不加这道闸就是
                            //   每 700ms 一次全表 Flow 重发 → 下载列表整表重组。
                            if (engineStatusChanged(task.id, "pause")) {
                                dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                            }
                            _stats.update { it - task.id }
                            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
                        }
                        "wait" -> {
                            // 超出「最大同时下载任务数」，被引擎排进 waitTasks 等空位
                            // （上限见 GopeedEngine.applyRuntimeConfig）→ 本地记成「等待中」。
                            // ★ 不能落到下面的 else：那会显示成 0% 的「下载中」，看着像卡死；
                            //   进度一律不动，任务重新上车时库里的进度还是上次那份。
                            if (engineStatusChanged(task.id, "wait")) {
                                dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PENDING)
                            }
                            _stats.update { it - task.id }
                        }
                        else -> {
                            // 其余（running/ready…）按「正在下载」处理；记一次基准供上面的状态比较使用
                            engineStatusChanged(task.id, "running")
                            val total = if (status.total > 0L) status.total else task.totalSize
                            // 复用 TurboDL 那条写库节流（纯时间节流）：引擎每 700ms 报一次，
                            // 不节流的话每次写库都会让 observeAll() 重发整表、主线程重组下载列表
                            if (shouldWriteDb(task.id, status.downloaded)) {
                                dao.updateProgress(
                                    task.id,
                                    DownloadTaskEntity.STATUS_DOWNLOADING,
                                    status.downloaded,
                                    total,
                                )
                            }
                            // 实时速度直接取引擎给的，剩余时间自己算
                            _stats.update {
                                it + (task.id to DownloadStats(
                                    speed = status.speed,
                                    remainMillis = if (status.speed > 0L && total > status.downloaded) {
                                        (total - status.downloaded) * 1000L / status.speed
                                    } else {
                                        -1L
                                    },
                                    chunkCount = threadProvider().coerceAtLeast(1),
                                ))
                            }
                            // 前台通知：与内置路径共用同一条节流与速度来源（_stats），故写在 _stats 之后
                            notifyProgress(task.id, task.fileName, status.downloaded, total)
                        }
                    }
                }
                delay(engineSyncIntervalMs)
            }
        }
    }

    /**
     * 引擎状态变化判定（原子）。引擎任务的「暂停/排队」会持续多轮，状态没变就不该写库。
     *
     * 返回值被忽略的调用点（"running" 分支）只是把基准刷新成当下状态，
     * 这样「运行 → 暂停」在下一次真实变化时仍能触发写入。
     */
    private fun engineStatusChanged(id: Long, status: String): Boolean =
        engineLastEngineStatus.put(id, status) != status

    /**
     * 引擎任务完成：读一次任务详情拿**真实文件名**（BT/磁力的名字要等引擎解析完元数据才有），
     * 把保存路径与真实落盘大小落库，再走与内置路径一致的收尾（清理回调 / 保活 / 统计）。
     *
     * 与内置路径的区别：引擎**直接写进最终目录**（不经过应用缓存再交 DownloadSaver），
     * 所以这里没有「保存到 MediaStore」这一步，savePath 就是引擎产物路径。
     */
    private suspend fun completeEngineTask(task: DownloadTaskEntity, size: Long) {
        // 建任务时记下的目录优先（引擎产物就在那儿）；索引没有（进程重启后）才重新解析
        val dir = taskEngineDirs.remove(task.id)
            ?: runCatching { GopeedEngine.resolveDownloadDir(context) }
                .getOrElse { File(context.getExternalFilesDir(null) ?: context.filesDir, "gopeed") }
        val detail = runCatching { withContext(Dispatchers.IO) { GopeedEngine.taskDetail(task.engineTaskId) } }
            .onFailure { Log.w(TAG, "读取引擎任务详情失败，用本地文件名兜底：id=${task.id} ${it.message}") }
            .getOrNull()
        val realName = detail?.name?.takeIf { it.isNotBlank() } ?: task.fileName
        val savedPath = File(dir, realName).absolutePath
        // 完成时用**实际落盘大小**修正进度（与内置路径同一口径）：进度是节流写库的，
        // 只写 status 会让界面停在「已完成 · 99%」。多文件种子（folder=true）落盘是目录，
        // length() 没有意义，此时退回引擎给出的总大小。
        val landed = File(savedPath)
        val actualSize = if (landed.isFile) landed.length() else size
        val finalTotal = maxOf(if (size > 0) size else 0L, actualSize)
        dao.complete(task.id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, actualSize, finalTotal)
        Log.d(
            TAG,
            "引擎任务完成：roomId=${task.id} engineId=${task.engineTaskId} path=$savedPath " +
                "size=$actualSize total=$finalTotal files=${detail?.fileCount ?: -1} folder=${detail?.folder ?: false}"
        )
        _stats.update { it - task.id }
        if (realName != task.fileName) {
            // 引擎命名与本地占位名不同（BT 种子名 / 服务器建议名）：回写，失败只记日志、不影响已完成状态
            runCatching { dao.updateFileName(task.id, realName) }
                .onFailure { Log.w(TAG, "回写引擎文件名失败：id=${task.id} ${it.message}") }
        }
        taskEngineIds.remove(task.id)
        releaseEngineTaskMemory(task.id)
        // 保活收尾（可能从未计入，见 engineKeepAliveIds 注释）
        if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
        // 保存成功才触发清理回调（如删除网盘临时转存文件），语义与内置路径一致
        taskCallbacks.remove(task.id)?.let { cb -> runCatching { cb() } }
    }

    /** 清掉只服务于某个引擎任务的内存数据（请求头/大小来自入队，引擎侧不用它们） */
    private fun releaseEngineTaskMemory(id: Long) {
        taskHeaders.remove(id)
        taskSizes.remove(id)
        taskEngineDirs.remove(id)
        engineLastEngineStatus.remove(id)
    }

    /**
     * 引擎任务暂停（用户点击）：转发给引擎，并把本地状态写成「已暂停」。
     *
     * 保活在**这里**配对收尾，且下面的 TurboDL 收尾不会执行（见 [pause] 的分流），
     * 因此每个引擎任务的 保活 +1 恰好对应一次 -1。
     */
    private fun pauseEngineTask(id: Long, engineId: String) {
        Log.d(TAG, "引擎任务暂停：id=$id engineId=$engineId")
        _stats.update { it - id }
        if (engineKeepAliveIds.remove(id)) onTaskFinished()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GopeedEngine.pauseTask(engineId) } }
                .onFailure { Log.e(TAG, "引擎暂停失败：id=$id ${it.message}", it) }
            dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
        }
    }

    /** 引擎任务继续：转发 continue 并重新拉起同步循环 */
    private fun resumeEngineTask(id: Long, engineId: String) {
        Log.d(TAG, "引擎任务继续：id=$id engineId=$engineId")
        taskEngineIds[id] = engineId
        // 继续下载：重新拉起前台服务保活（暂停/完成时刚收尾过）
        if (engineKeepAliveIds.add(id)) onTaskStarted(id)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GopeedEngine.continueTask(engineId) } }
                .onFailure { Log.e(TAG, "引擎继续失败：id=$id ${it.message}", it) }
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            startEngineSync()
        }
    }

    // ---------- TurboDL 事件桥接 ----------

    private fun onTurboEvent(ev: TurboEvent) {
        val roomId = turboIdToRoomId[ev.taskId] ?: return
        when (ev) {
            is TurboEvent.Progress -> {
                val p = ev.progress
                // 【诊断】首字节耗时 = 从探测完成到第一批字节。若它很大而探测(见 Metadata 日志)很小，
                // 说明慢在**首连接**（DNS / IPv6 路由 / TLS），而不是解析。
                val metaAt = if (p.downloadedBytes > 0) metadataAtMs.remove(roomId) else null
                metaAt?.let { t0 ->
                    Log.i(TAG, "首字节: id=$roomId 距探测完成 ${System.currentTimeMillis() - t0}ms downloaded=${p.downloadedBytes}")
                }
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
                metadataAtMs.remove(roomId)
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
                // 【诊断】解析(探测)耗时 + 续传判定：
                //  - probe 大 → 探测/服务器慢；probe≈0 而首字节慢 → 慢在首连接。
                //  - resume=… → 定位"断点续传为什么不生效"（没找到旧分片 / 校验器变了 / 指纹不通过）。
                metadataAtMs[roomId] = System.currentTimeMillis()
                // 【必须落盘，不能只打 logcat】实测（OnePlus/ColorOS/Android 16）系统日志缓冲
                // 只保留几秒，用户"点下载→导出日志"时这行早被冲掉，导出文件里一行本应用日志都没有。
                // 走 DiagLog：同时进 logcat 与 filesDir/diag/diag.log，导出时整份附加。
                DiagLog.i(
                    context,
                    "启动阶段",
                    "id=$roomId probe=${ev.probeMs}ms total=${ev.totalBytes} " +
                        "range=${ev.supportsRange} resume=[${ev.resumeNote}]"
                )
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

    /**
     * 进度落盘节流判定（原子）。**纯时间节流**；完成/暂停由各自分支强制写。
     *
     * 【为什么必须原子】多个 worker 的进度回调可能**并发**进入这里。
     * 原先的「读 map → 判断 → 写 map」不是原子操作：两个线程可以同时读到过期的
     * lastTs、同时通过判断、同时写库（重复 UPDATE + 两次全表 Flow 重发）。
     *
     * 【为什么首次要用 putIfAbsent】`ConcurrentHashMap.replace(k, old, new)` 在**键不存在**时
     * 返回 false。若首次调用也走 replace 分支（以 `?: 0L` 作 old），CAS 必然失败 →
     * 该任务**永远不写库**，进度永不落盘。（由 `ProgressPersistThrottleTest` 抓出。）
     *
     * 【为什么不能加"字节阈值"】曾用「800ms 或增长 1MB」双条件。但高速下载（50MB/s）下
     * 1MB 阈值先于时间触发 → 每秒写库约 50 次 → 每次写库都让 `observeAll()` 重发整表 →
     * 主线程反复重组下载列表 → **ANR**。这正是上游 #64 报告的问题。
     * 故改为纯时间节流：无论多快，写库频率恒定为每秒约 1.25 次。
     * 进度条由内存 `_stats` 高频刷新，数据库只是持久化（重启最多回退几百毫秒进度）。
     */
    private fun shouldWriteDb(roomId: Long, downloaded: Long): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastDbWriteTs[roomId]
        // 首次：原子占位，成功者负责写库
        if (prev == null) {
            if (lastDbWriteTs.putIfAbsent(roomId, now) != null) return false
            lastDbWriteBytes[roomId] = downloaded
            return true
        }
        if (now - prev < DB_WRITE_INTERVAL_MS) return false
        // CAS 占位：并发下只有一个线程能把 prev 换成 now
        if (!lastDbWriteTs.replace(roomId, prev, now)) return false
        lastDbWriteBytes[roomId] = downloaded
        return true
    }

    // ---------- 前台服务 / 通知 / WakeLock ----------

    private fun onTaskStarted(id: Long) {
        if (activeTaskCount.getAndIncrement() == 0) {
            val name = taskNames[id] ?: "下载任务"
            // 走引用计数版：Gopeed 内核包下载（KernelProvisioner）也在用同一条前台服务，
            // 谁都不能直接把服务停掉（详见 DownloadService.acquire/release）
            DownloadService.acquire(context, name)
        }
        acquireWakeLockIfNeeded()
    }

    private fun onTaskFinished() {
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            DownloadService.release(context)
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
            metadataAtMs.remove(roomId)
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
