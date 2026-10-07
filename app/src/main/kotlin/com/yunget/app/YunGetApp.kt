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
    }

    /**
     * 内存压力回调：系统回收前先释放「可再生」的内存 —— 空闲 HTTP 连接及其 socket / TLS 缓冲。
     * 分片下载的数据全部流式落盘、不在堆上缓存，所以这里只丢弃空闲连接，不会影响进行中的下载。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.yunget.app.data.network.HttpClients.evictIdleConnections()
        }
    }
}

/**
 * 选了 Gopeed 引擎时，应用启动就把引擎加载起来。
 *
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
