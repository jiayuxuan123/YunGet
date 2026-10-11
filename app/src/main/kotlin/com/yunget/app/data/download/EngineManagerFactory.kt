/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.download

import android.content.Context
import com.yunget.app.data.db.AppDatabase
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.prefs.SettingsRepository

/**
 * 各下载引擎的构造入口（P9）。
 *
 * ## 为什么把它从 ViewModel 里搬出来
 *
 * 这些工厂原先写在 `DownloadManagerViewModel` 的私有方法里。那让"引擎有多少种"
 * 这件事**绑死在 UI 层**：一个引擎存在与否，要看某个 ViewModel 里有没有对应的
 * `createXxxManager()`；而 `DownloadEngineRegistry` 想按 id 构造时，
 * 又必须反过来依赖 UI 层，形成不该有的依赖方向。
 *
 * 搬到 data 层之后方向就正了：注册表 → 工厂 → 管理器，UI 只负责"用哪个 id"。
 *
 * ## 为什么每个引擎单独一个方法而不是一个带参数的通用方法
 *
 * 四个引擎的构造参数**确实不同**：
 *
 *  - TurboDL 要多传 `ignoreSsl` / `dohUrl` / `warmUp` / `slowStart`；
 *  - aria2 要多传一个 `runner`；
 *  - Legacy 不持久化请求头、也没有现场诊断。
 *
 * 总纲的风险提示是"宁可让接口宽一点，也不要为了统一削掉某个引擎的能力"。
 * 一个通用构造方法会强迫所有引擎接受同一组参数（其中大半对它是无意义的），
 * 或者退化成"传一个 Map 然后各自解析"——两种都比现在这样直白地写四个方法更容易出错。
 */
internal object EngineManagerFactory {

    /** 共享设置：所有引擎都要的下载目录、并发、限速、重试、通知开关。 */
    private fun SettingsRepository.commonThreads() = downloadThreads
    private fun SettingsRepository.commonConcurrency() = maxConcurrentDownloads
    private fun SettingsRepository.commonSpeedLimit() = downloadSpeedLimit
    private fun SettingsRepository.commonRetry() = downloadRetryCount
    private fun SettingsRepository.commonKeepWhenLocked() = keepDownloadWhenLocked
    private fun SettingsRepository.commonShowSpeed() = notificationShowSpeed

    /** TurboDL 内核（默认引擎）。 */
    fun createTurboDl(context: Context): DownloadManager {
        val settings = SettingsRepository(context)
        val dao = AppDatabase.get(context).downloadTaskDao()
        return TurboDownloadManager(
            context = context,
            dao = dao,
            threadProvider = { settings.commonThreads() },
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.commonConcurrency() },
            speedLimitProvider = { settings.commonSpeedLimit() },
            retryCountProvider = { settings.commonRetry() },
            keepWhenLockedProvider = { settings.commonKeepWhenLocked() },
            showSpeedProvider = { settings.commonShowSpeed() },
            ignoreSslProvider = { settings.ignoreSslCert },
            dohUrlProvider = { settings.dohUrl },
            warmUpProvider = { settings.warmUpConnections },
            slowStartProvider = { settings.slowStart },
        )
    }

    /**
     * 内置兼容引擎（兜底）。
     *
     * 它需要 [ChunkDownloader]，其 OkHttpClient 走 [HttpClients.downloadClient]
     * （带缓存，且忽略 SSL 等开关变化时会 `rebuildAll` 重建）。
     *
     * 注意：Legacy 不持久化请求头、也不提供现场诊断 —— 这正是它只作兜底、不作默认的原因。
     */
    fun createLegacy(context: Context): DownloadManager {
        val settings = SettingsRepository(context)
        val dao = AppDatabase.get(context).downloadTaskDao()
        return LegacyDownloadManager(
            context = context,
            dao = dao,
            downloader = chunkDownloader(context, settings),
            threadProvider = { settings.commonThreads() },
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.commonConcurrency() },
            speedLimitProvider = { settings.commonSpeedLimit() },
            retryCountProvider = { settings.commonRetry() },
            keepWhenLockedProvider = { settings.commonKeepWhenLocked() },
            showSpeedProvider = { settings.commonShowSpeed() },
        )
    }

    /**
     * aria2 原生引擎（实验性）。
     *
     * **复用 [LegacyDownloadManager] 的全部上层逻辑**（Room 持久化 / 进度 / 前台服务 /
     * 合并 / 大小校验 / 保存到 MediaStore），只把"下载字节"这一层换成 [Aria2Runner]。
     *
     * 若设备不可用，[Aria2Runner] 会如实报错，`runTask` 随即回退到单流整文件下载 ——
     * 不会卡死或产出坏文件。
     */
    fun createAria2(context: Context): DownloadManager {
        val settings = SettingsRepository(context)
        val dao = AppDatabase.get(context).downloadTaskDao()
        return LegacyDownloadManager(
            context = context,
            dao = dao,
            downloader = chunkDownloader(context, settings),
            threadProvider = { settings.commonThreads() },
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.commonConcurrency() },
            speedLimitProvider = { settings.commonSpeedLimit() },
            retryCountProvider = { settings.commonRetry() },
            keepWhenLockedProvider = { settings.commonKeepWhenLocked() },
            showSpeedProvider = { settings.commonShowSpeed() },
            runner = Aria2Runner(context),
        )
    }

    /**
     * 构造兜底引擎用的 [ChunkDownloader]，缓冲按「线程数 × 并发任务数」摊薄。
     *
     * 【为什么必须摊薄】旧行为是每连接固定 256KB。看着不大，乘上连接数就大了：
     * 线程上限 256 × 256KB = 64MB，多任务并行还要再乘。
     * 默认引擎（TurboDL）那边同期是 1MB/连接 → 256 × 1MB = 256MB = Android 默认整堆，
     * 2026-10-10 真机 OOM 就是这么来的。这里用同一套口径把兜底引擎一并管住。
     *
     * 两个引擎的构造点共用本函数，避免"改一处漏一处"。
     */
    private fun chunkDownloader(
        context: Context,
        settings: SettingsRepository,
    ) = ChunkDownloader(
        clientProvider = { HttpClients.downloadClient() },
        bufferSizeProvider = {
            ioBufferSizeFor(settings.downloadThreads, settings.maxConcurrentDownloads)
        },
    )
}
