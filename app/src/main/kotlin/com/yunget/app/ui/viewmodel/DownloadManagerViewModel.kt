package com.yunget.app.ui.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import com.yunget.app.data.db.DownloadTaskDao
import com.yunget.app.data.download.Aria2Runner
import com.yunget.app.data.download.Aria2Executor
import com.yunget.app.data.download.ChunkDownloader
import com.yunget.app.data.download.DownloadEngine
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.download.LegacyDownloadManager
import com.yunget.app.data.download.TurboDownloadManager
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 持有下载管理器的 ViewModel —— 让管理器的生命周期与 **Activity 真正结束**对齐。
 *
 * ## 为什么不能放在 composable 的 `remember {}` 里
 *
 * 管理器原来在 `MainScreen` 用 `remember { DownloadManager(...) }` 创建。`remember` 无 key，
 * 而 composable 会随 Activity 重建而重新组合，于是：
 *
 * 1. **配置变更（旋转屏幕 / 改主题 / 改图标）→ 新管理器**。但 7 个业务 ViewModel
 *    （`QuarkCloudViewModel` / `DownloadViewModel` …）是在构造时**强引用**管理器的，
 *    它们绑定在 Activity 的 `ViewModelStore` 上、配置变更时会被**保留** ——
 *    于是旧的业务 ViewModel 仍指着旧管理器，而界面用的是新管理器：**两套引擎同时活着**。
 * 2. **旧管理器永不释放**：全项目原先没有任何 `shutdown()` 调用点，旧实例的
 *    `TurboClient`（两个 OkHttpClient 的线程池 + 连接池）与事件收集协程永久残留。
 *
 * ## 放在 ViewModel 里为什么能同时解决两件事
 *
 * - `ViewModel` 在**配置变更时被保留** → 不会重复创建管理器，业务 ViewModel 与管理器始终配套；
 * - `onCleared()` 只在 **Activity 真正结束**（用户退出 / 系统回收）时调用 →
 *   正好是释放引擎与连接池的正确时机，也不会出现"ViewModel 还活着、管理器已被关"的悬垂引用。
 *
 * 用 Application Context 建管理器：管理器会被长期持有，传 Activity 就会泄漏它。
 */
class DownloadManagerViewModel(
    private val settings: SettingsRepository,
    private val dao: DownloadTaskDao,
    private val appContext: Context,
) : ViewModel() {

    /**
     * 下载管理器（惰性创建并缓存）。
     *
     * 惰性：登录引导页 / 首次启动引导等场景可能不会用到下载，
     * 没必要为此提前建 OkHttpClient 与插件运行时。
     */
    private var managerOrNull: DownloadManager? = null

    /**
     * 取管理器；未创建则创建。
     */
    val manager: DownloadManager
        get() = managerOrNull ?: synchronized(this) {
            managerOrNull ?: createManager().also {
                managerOrNull = it
                // 管理器首次创建时（= App 正常使用下载功能的起点）后台探一次 aria2，
                // 结果落盘到诊断日志。这样"这台设备能不能跑 aria2"不用用户手动点按钮就能留痕，
                // 排查时直接看导出日志即可。
                probeAria2InBackground()
            }
        }

    /**
     * 后台静默探测 aria2 可用性并落盘（不弹任何 UI、不影响前台）。
     *
     * 为什么放在这里而不是设置页按钮里：设置页按钮需要用户主动点，
     * 而"exec 能不能用"是平台灰色地带、随系统版本可能变化 —— 每次启动留一条记录，
     * 出问题时才有现场（否则只能靠用户复现）。
     */
    private fun probeAria2InBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val r = Aria2Executor.probe(appContext)
                DiagLog.i(
                    appContext, "aria2自检",
                    "abi=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                        if (r.available) "可用: ${r.detail}" else "不可用: ${r.detail}"
                )
            }
        }
    }

    /**
     * 按当前设置创建对应引擎的管理器。
     *
     * 引擎选择在**创建时**读取一次（不随设置热切换）：
     * 各业务 ViewModel 在构造时强引用管理器，热切换会让旧 ViewModel 继续指向旧管理器
     * （两套引擎同时活着）。设置页因此明确提示"重启 App 生效"。
     *
     * 两个设置项是**嵌套关系**，不要混为一谈：
     *  - 新设置 `downloadEngine`（builtin / gopeed）决定「这一层用哪套下载器」；
     *  - 旧设置 `downloadEngineId`（turbodl / legacy / aria2）只在 `builtin` 时决定**内置实现**用哪个。
     *
     * 选了 Gopeed 时固定由 [TurboDownloadManager] 承载：引擎路由（建任务 / 进度同步 / 暂停删除转发，
     * 见 TurboDownloadManager 的「外部下载引擎（Gopeed）路由」一节）就写在它里面 —— 它在这里的角色
     * 是「任务登记 + 引擎路由」的宿主，具体字节由 GopeedEngine 下载，TurboDL 完全不参与。
     * 这样旧 engineId 选什么都不影响 Gopeed 生效，也就不会出现「设置说在用引擎、实际跑的是旧引擎」。
     */
    private fun createManager(): DownloadManager {
        if (settings.downloadEngine == SettingsRepository.ENGINE_GOPEED) return createTurboManager()
        val engine = DownloadEngine.fromId(settings.downloadEngineId)
        return when (engine) {
            DownloadEngine.TURBODL -> createTurboManager()
            DownloadEngine.LEGACY -> createLegacyManager()
            DownloadEngine.ARIA2 -> createAria2Manager()
        }
    }

    private fun createTurboManager(): DownloadManager = TurboDownloadManager(
        context = appContext,
        dao = dao,
        threadProvider = settings::downloadThreads,
        saveDirProvider = { settings.downloadDirUri },
        concurrencyProvider = { settings.maxConcurrentDownloads },
        speedLimitProvider = { settings.downloadSpeedLimit },
        retryCountProvider = { settings.downloadRetryCount },
        keepWhenLockedProvider = { settings.keepDownloadWhenLocked },
        showSpeedProvider = { settings.notificationShowSpeed },
        ignoreSslProvider = { settings.ignoreSslCert },
        dohUrlProvider = { settings.dohUrl },
        warmUpProvider = { settings.warmUpConnections },
        slowStartProvider = { settings.slowStart },
    )

    /**
     * 内置兼容引擎（兜底）。
     *
     * 它需要 [ChunkDownloader]，其 OkHttpClient 走 [HttpClients.downloadClient]（带缓存，
     * 且忽略SSL 等开关变化时会 `rebuildAll` 重建）。
     *
     * 注意：Legacy 不持久化请求头（`requestHeadersJson`）、也不提供现场诊断 ——
     * 这正是它只作兜底、不作默认的原因。
     */
    private fun createLegacyManager(): DownloadManager = LegacyDownloadManager(
        context = appContext,
        dao = dao,
        downloader = ChunkDownloader { HttpClients.downloadClient() },
        threadProvider = settings::downloadThreads,
        saveDirProvider = { settings.downloadDirUri },
        concurrencyProvider = { settings.maxConcurrentDownloads },
        speedLimitProvider = { settings.downloadSpeedLimit },
        retryCountProvider = { settings.downloadRetryCount },
        keepWhenLockedProvider = { settings.keepDownloadWhenLocked },
        showSpeedProvider = { settings.notificationShowSpeed },
    )

    /**
     * aria2 原生引擎（实验性）。
     *
     * **复用 [LegacyDownloadManager] 的全部上层逻辑**（Room 持久化 / 进度 / 前台服务 /
     * 合并 / 大小校验 / 保存到 MediaStore），只把"下载字节"这一层换成 [Aria2Runner]。
     *
     * 若设备不可用（非 arm64，或 APK 未以 useLegacyPackaging=true 打包导致 so 未落盘），
     * [Aria2Runner] 会如实报错，`runTask` 随即回退到单流整文件下载 —— 不会卡死或产出坏文件。
     * 设置页提供 [Aria2Executor.probe] 的显式检测入口，让用户能先确认再切。
     */
    private fun createAria2Manager(): DownloadManager = LegacyDownloadManager(
        context = appContext,
        dao = dao,
        downloader = ChunkDownloader { HttpClients.downloadClient() },
        threadProvider = settings::downloadThreads,
        saveDirProvider = { settings.downloadDirUri },
        concurrencyProvider = { settings.maxConcurrentDownloads },
        speedLimitProvider = { settings.downloadSpeedLimit },
        retryCountProvider = { settings.downloadRetryCount },
        keepWhenLockedProvider = { settings.keepDownloadWhenLocked },
        showSpeedProvider = { settings.notificationShowSpeed },
        runner = Aria2Runner(appContext),
    )

    /**
     * Activity 真正结束时释放引擎。
     *
     * 必须释放：管理器的 TurboClient 持有两个 OkHttpClient 的 Dispatcher 线程池与连接池，
     * 以及一个 `client.events.collect` 常驻协程；不释放就是进程级泄漏。
     *
     * `onCleared()` 在配置变更时**不会**被调用（ViewModel 被保留），
     * 所以旋转屏幕不会误关正在下载的引擎 —— 这正是选 ViewModel 而非 DisposableEffect 的原因。
     */
    override fun onCleared() {
        super.onCleared()
        // 只释放"确实创建过"的实例：没创建过就没什么可关，也不该在这里把它唤醒。
        managerOrNull?.let { runCatching { it.shutdown() } }
        managerOrNull = null
    }

    class Factory(
        private val settings: SettingsRepository,
        private val dao: DownloadTaskDao,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadManagerViewModel(settings, dao, appContext) as T
    }
}
