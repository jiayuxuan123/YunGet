package com.yunget.app

import android.app.Application
import com.yunget.app.crash.CrashHandler

class YunGetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        // 迅雷动态设备指纹：首次启动生成并持久化（开源分发后每台设备独立指纹）
        com.yunget.app.data.network.XunleiDeviceFingerprint.init(this)
    }
}