/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * Copyright (C) 2026 CYQawa
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

package com.yunget.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.yunget.app.crash.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YunGetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        // 迅雷动态设备指纹：首次启动生成并持久化（开源分发后每台设备独立指纹）
        com.yunget.app.data.network.XunleiDeviceFingerprint.init(this)
        // 本机密钥失效自愈：必须在**任何**凭证读写之前装配。改锁屏密码/指纹会让 Android Keystore
        // 里的密钥被系统永久作废（KeyPermanentlyInvalidatedException / Key not found / Invalid
        // key blob 三种形态）。装配后这类事件只会记下「登录态已失效」的一次性提示，
        // 而不会把 Keystore 异常抛到主线程把应用崩掉。
        com.yunget.app.data.security.CredentialStore.installRecovery(this)
        // 下载引擎自愈 + 预热（选了 Gopeed 才加载内核，失败只记日志，绝不影响应用启动）
        autoStartGopeedIfSelected(this)
        // 插件健康检查（P4）：把"已启用但加载不起来"的插件在启动时就记回库里。
        // 刻意放后台线程且吞掉异常 —— 插件坏了不该影响应用启动，也不该阻塞首屏。
        healthCheckPlugins(this)
    }

    /**
     * 内存压力回调：系统回收前先释放「可再生」的内存 —— 空闲 HTTP 连接及其 socket / TLS 缓冲。
     * 分片下载的数据全部流式落盘、不在堆上缓存，所以这里只丢弃空闲连接，不会影响进行中的下载。
     *
     * 【本回调在主线程】所以 [com.yunget.app.data.network.HttpClients.evictIdleConnections]
     * 内部自己切到后台线程 —— 关 socket 是网络 I/O，在这里直接做会抛
     * `NetworkOnMainThreadException`（2.6.18 的真实崩溃）。调用方无需再做线程处理。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.yunget.app.data.network.HttpClients.evictIdleConnections()
        }
    }
}

/**
 * 插件健康检查（P4，设计文档第 8.5 章）：启动时把"已启用但加载不起来"的插件记回库里。
 *
 * ## 为什么需要
 *
 * 插件坏掉（脚本被系统清理、更新后不兼容、调用了未授予的能力）**只在加载时才暴露**，
 * 而加载原先只在用户打开插件页时发生 —— 不进那个页面就永远发现不了，
 * 表现为"下载功能莫名失效，但插件列表看起来一切正常"。
 *
 * ## 边界（刻意保守）
 *
 * - **后台协程**：要读库、要读脚本文件、要建 QuickJS 运行时，绝不能占主线程（首屏会被拖慢）。
 * - **失败只记日志**：插件坏了不该影响应用启动 —— 与 [autoStartGopeedIfSelected] 同一原则。
 * - **不自动回滚**：回滚是用户的决定（也许他只是想补一个权限）。这里只把状态记清楚，
 *   详情页会显示"回退到上一版本"入口供他选。
 * - **没有插件时直接返回**：绝大多数用户没装插件，不该为此付任何启动成本。
 */
private fun healthCheckPlugins(context: android.content.Context) {
    // 用 IO 调度器而不是 Thread：下面三步全是 suspend（读库、装插件、写回错误状态），
    // 裸线程里调 suspend 是编译不过的。各步骤本身已 flowOn/withContext(IO)，
    // 这里只是给它们一个启动用的作用域。
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
            val repo = com.yunget.app.data.plugin.PluginRepository(context)
            if (!repo.hasEnabledPlugins()) return@runCatching
            // 与插件管理页 / 编辑器共用**进程内唯一**的运行时，不能各建一个 PluginHost。
            val runtime = com.yunget.app.data.plugin.sharedPluginRuntime(context)
            // start() 会把已启用的插件装进引擎；随后 healthCheck() 判定装载结果是否健康。
            val started = runtime.start()
            val unhealthy = runtime.healthCheck()
            if (unhealthy.isNotEmpty()) {
                android.util.Log.w(
                    "YunGet",
                    "插件健康检查：${unhealthy.size} 个插件不健康 → $unhealthy（已记录，可在插件页查看）"
                )
            } else if (started.isNotEmpty()) {
                android.util.Log.i("YunGet", "插件健康检查：${started.size} 个插件全部正常")
            }
        }.onFailure {
            android.util.Log.e("YunGet", "插件健康检查失败（不影响启动）：${it.message}", it)
        }
    }
}

/**
 * 选了 Gopeed 引擎时，应用启动就把引擎加载起来。
 * 引擎是进程内单例，首次加载要 `System.load` 几十 MB 的 .so 并初始化 Go runtime，提前加载能让
 * 第一个下载任务不必等它。**任何失败都只记日志** —— 引擎起不来不能影响应用启动，下载侧会按失败任务处理。
 *
 * 另外无论选的是哪个下载器，都在这里把引擎的内存状态与真实文件对齐一次（`GopeedEngine.syncInstalledState`）：
 * 选内置下载器时下面会直接 return、整条下载流程都不会碰引擎，不对齐的话「下载引擎」页会以为「未导入内核」。
 */
private fun autoStartGopeedIfSelected(context: android.content.Context) {
    val settingsRepo = com.yunget.app.data.prefs.SettingsRepository(context)
    com.yunget.app.data.gopeed.GopeedEngine.syncInstalledState(context)
    if (settingsRepo.downloadEngine != com.yunget.app.data.prefs.SettingsRepository.ENGINE_GOPEED) return
    val engine = com.yunget.app.data.gopeed.GopeedEngine
    if (!engine.isInstalled(context)) {
        // 内核被删了（或从没导入过）但设置还停在 Gopeed：自愈回内置下载器，避免「设置说在用引擎、
        // 实际跑的是内置下载器」的错位（与「下载引擎」页删内核时的处理保持一致）。
        settingsRepo.downloadEngine = com.yunget.app.data.prefs.SettingsRepository.ENGINE_BUILTIN
        return
    }
    Thread {
        runCatching {
            engine.start(context, engine.resolveDownloadDir(context))
        }.onFailure {
            android.util.Log.e("YunGet", "启动时自动加载 Gopeed 引擎失败：${it.message}", it)
        }
    }.start()
}
