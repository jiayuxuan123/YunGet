package com.yunget.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.Locale

/**
 * Windows 电源与挂机辅助：
 * 1. 下载进行中通过 `SetThreadExecutionState` 阻止系统进入睡眠（不影响熄屏省电）；
 * 2. 全部任务完成后支持可取消的 60 秒倒计时关机。
 */
internal object DesktopSystemPower {
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    private interface Kernel32Power : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    private val kernel32: Kernel32Power? by lazy {
        if (!isWindows) null
        else runCatching { Native.load("kernel32", Kernel32Power::class.java) }.getOrNull()
    }

    @Volatile
    private var lastKeepAwake: Boolean = false

    fun setKeepAwake(active: Boolean) {
        if (active == lastKeepAwake) return
        lastKeepAwake = active
        val api = kernel32 ?: return
        runCatching {
            val flags = if (active) ES_CONTINUOUS or ES_SYSTEM_REQUIRED else ES_CONTINUOUS
            api.SetThreadExecutionState(flags)
        }
    }

    fun scheduleShutdownSeconds(seconds: Int = 60): Boolean {
        if (!isWindows) return false
        val delay = seconds.coerceIn(15, 3600)
        return runCatching {
            ProcessBuilder(
                "shutdown", "/s", "/t", delay.toString(),
                "/c", "云取全部下载任务已完成，将在 $delay 秒后关机（可在云取窗口中取消）",
            ).start().waitFor() == 0
        }.getOrDefault(false)
    }

    fun cancelScheduledShutdown(): Boolean {
        if (!isWindows) return false
        return runCatching {
            ProcessBuilder("shutdown", "/a").start().waitFor() == 0
        }.getOrDefault(false)
    }
}
