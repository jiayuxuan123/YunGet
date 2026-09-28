package com.yunget.app.data.download

import androidx.compose.runtime.Immutable
import com.yunget.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 下载管理器公共接口 —— 让「下载引擎」成为可替换的实现细节。
 *
 * ## 为什么需要它
 *
 * 原先只有一个 `typealias DownloadManager = TurboDownloadManager`，于是全 App
 * （7 个 ViewModel + MainScreen + SettingsScreen）都**静态绑定到 TurboDL 内核版**：
 * 想换引擎就得改所有调用点，等于「引擎不可替换」。旧实现 [LegacyDownloadManager]
 * 虽然逻辑完整，却因为这条 typealias 而成为死代码。
 *
 * ## 设计原则
 *
 * - **接口只放两边都真正具备的能力**（下载控制、任务列表、统计、存储权限），
 *   这些方法的签名在两个实现里本来就完全一致 —— 抽出来是零成本的；
 * - **引擎专有能力给默认实现**，不强迫 [LegacyDownloadManager] 实现：
 *   - `diagnose*`（现场诊断）只有 TurboDL 内核提供，默认返回"当前引擎不支持"；
 *   - `recoverInterruptedTasks` / `shutdown` 默认空实现（Legacy 没有引擎级资源要释放）。
 *
 * 这样接口是"可替换引擎"的真实边界，而不是把 TurboDL 的特有能力强加给所有实现。
 */
@Immutable
interface DownloadManager {

    /** 实时下载统计：任务 id → 速度/剩余时间/分片数 */
    val stats: StateFlow<Map<Long, DownloadStats>>

    /** 任务列表（Room Flow 直通，UI 直接 collect） */
    val tasks: Flow<List<DownloadTaskEntity>>

    /** 存储权限检查（UI 注入）；Android 10+ 或已授权返回 true */
    var storagePermissionProvider: suspend () -> Boolean

    /**
     * 入队并立即开始下载，返回 Room 任务 id。
     *
     * @param headers 请求头（Cookie/Referer/UA 等）；会被持久化，供进程重启后恢复下载使用
     * @param size 已知文件大小（字节）；-1 表示未知
     * @param onComplete 成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发
     */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        size: Long = -1L,
        onComplete: suspend () -> Unit = {},
    ): Long

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap())

    /** 暂停下载（保留断点分片） */
    fun pause(id: Long)

    /** 删除任务；[deleteLocal] 同时删除已保存的文件 */
    fun remove(id: Long, deleteLocal: Boolean = false)

    /**
     * 释放引擎持有的资源（连接池、线程池、常驻协程、WakeLock）。
     *
     * 默认空实现：不是所有引擎都有需要显式释放的资源。
     * 由持有方（ViewModel 的 `onCleared`）在生命周期结束时调用，且应幂等。
     */
    fun shutdown() { /* 默认无操作 */ }

    /**
     * 进程启动时，把上次被杀遗留的"下载中/等待中"任务标为已暂停。
     *
     * 默认空实现。不这么做的后果：状态永远卡在"下载中"，用户无法点恢复。
     */
    fun recoverInterruptedTasks() { /* 默认无操作 */ }

    // ---------- 引擎专有：现场诊断（默认不支持）----------

    /**
     * 上一次诊断的运行状态（null = 从未运行）。
     * 仅 TurboDL 内核提供；其他引擎返回 null。
     */
    val diagnoseStatus: String? get() = null

    /** 上一次诊断的结果全文（供界面查看/分享）；其他引擎返回 null */
    val diagnoseLastResult: String? get() = null

    /**
     * 连接数扫描：用任务的真实链接测「吞吐随连接数的变化」，判定服务端限速模型。
     *
     * @param tiers 连接数档位（默认 8/16/32/64；上限刻意压到 64 —— 连接数拉满容易触发网盘风控）
     * @param windowMs 每档测量窗口
     * @return 可读报告；引擎不支持时返回说明文本
     */
    suspend fun diagnoseConnections(
        tiers: List<Int> = listOf(8, 16, 32, 64),
        windowMs: Long = 15_000,
    ): String = "当前下载引擎不支持连接数诊断（该功能需要 TurboDL 内核）。"

    /**
     * 并发任务扫描：固定每任务连接数，测「多任务并行能否叠加速度」。
     *
     * @param taskCounts 并行任务数档位
     * @param connectionsPerTask 每任务连接数（固定，只让任务数变化）
     * @param windowMs 每档测量窗口
     * @return 可读报告；引擎不支持时返回说明文本
     */
    suspend fun diagnoseConcurrentTasks(
        taskCounts: List<Int> = listOf(1, 2, 3),
        connectionsPerTask: Int = 16,
        windowMs: Long = 15_000,
    ): String = "当前下载引擎不支持并发任务诊断（该功能需要 TurboDL 内核）。"
}

/**
 * 引擎可选实现清单 —— 供设置页展示与切换。
 *
 * 新增引擎时在此登记，UI 自动出现选项（避免"加了实现但忘了接 UI"）。
 */
enum class DownloadEngine(
    val id: String,
    val displayName: String,
    val description: String,
) {
    /** TurboDL 内核（默认）：多线程 Range 分片 + 动态分段 + 插件路由 + 现场诊断 */
    TURBODL(
        id = "turbodl",
        displayName = "TurboDL（默认）",
        description = "多线程 Range 分片、工作窃取、限流退避、插件路由（HLS）、断点续传与现场诊断。",
    ),

    /** 旧版自适应引擎：固定满并发 + 4MB 块 + 工作窃取；功能较少但独立实现 */
    LEGACY(
        id = "legacy",
        displayName = "内置引擎（兼容模式）",
        description = "项目早期的自适应下载引擎。保留作为 TurboDL 异常时的兜底；不支持现场诊断。",
    ),

    /**
     * aria2 原生引擎（实验性，仅 arm64）。
     *
     * 把下载交给 APK 内置的官方 aria2c 可执行文件（1.37.0 aarch64，全静态、仅需 1 个文件）。
     * 作为**独立实现**的第三方引擎参照，也是 TurboDL 出问题时的另一条路。
     *
     * 已知限制（都会在设置页提示）：
     *  - `-x` 硬上限 16（高连接数设置无法完全映射）；
     *  - 分片门槛 `-k` 默认 20M → 小于 40MB 的文件不劈分（本项目设 4M 规避）；
     *  - 产物需从临时目录再拷一次到目标（引擎合并契约所致）；
     *  - 依赖"执行 APK 内原生二进制"这一平台灰色地带，非 arm64 设备不可用。
     */
    ARIA2(
        id = "aria2",
        displayName = "aria2 原生引擎（实验）",
        description = "内置官方 aria2c（仅 arm64）。多协议、成熟稳定；不支持现场诊断，小文件不分片。",
    ),
    ;

    companion object {
        fun fromId(id: String?): DownloadEngine =
            entries.firstOrNull { it.id == id } ?: TURBODL
    }
}
