package com.yunget.app.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 切网 / 断网自动重连监视器（Wi-Fi ↔ 移动网络切换后自动续传被网络中断的任务）。
 *
 * 只记录真正因瞬时网络中断失败的任务，绝不自动恢复用户手动暂停、限流冷却或 401/403/404 失败的任务。
 */
internal class NetworkReconnectMonitor(
    context: Context,
    private val onReconnectReady: (List<Long>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val interruptedTasks = ConcurrentHashMap<Long, Long>()
    private val registered = AtomicBoolean(false)
    @Volatile
    private var lastNetwork: Network? = null
    @Volatile
    private var networkLost: Boolean = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val switched = lastNetwork != null && lastNetwork != network
            val recovered = networkLost || switched
            lastNetwork = network
            networkLost = false
            if (recovered) {
                val candidates = drainEligibleTasks()
                if (candidates.isNotEmpty()) onReconnectReady(candidates)
            }
        }

        override fun onLost(network: Network) {
            if (lastNetwork == null || lastNetwork == network) {
                networkLost = true
            }
        }
    }

    fun start() {
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(callback)
        }.onFailure {
            registered.set(false)
        }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.unregisterNetworkCallback(callback)
        }
    }

    fun recordFailure(taskId: Long, httpStatus: Int?, reason: String, nowMs: Long = System.currentTimeMillis()) {
        if (isTransientNetworkFailure(httpStatus, reason)) {
            interruptedTasks[taskId] = nowMs
        } else {
            interruptedTasks.remove(taskId)
        }
    }

    fun clearTask(taskId: Long) {
        interruptedTasks.remove(taskId)
    }

    internal fun drainEligibleTasks(nowMs: Long = System.currentTimeMillis()): List<Long> {
        val eligible = mutableListOf<Long>()
        val iter = interruptedTasks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            iter.remove()
            if (nowMs - entry.value <= RECONNECT_WINDOW_MS) {
                eligible += entry.key
            }
        }
        return eligible
    }

    companion object {
        private const val RECONNECT_WINDOW_MS = 3 * 60_000L

        fun isTransientNetworkFailure(httpStatus: Int?, reason: String): Boolean {
            if (httpStatus != null && httpStatus > 0) return false
            val lower = reason.lowercase(Locale.ROOT)
            if (reason.contains("存储空间不足") || reason.contains("限流") || reason.contains("授权/时效")) {
                return false
            }
            return lower.contains("timeout") ||
                lower.contains("timed out") ||
                lower.contains("reset") ||
                lower.contains("unreachable") ||
                lower.contains("resolve") ||
                lower.contains("broken pipe") ||
                lower.contains("eof") ||
                lower.contains("socket") ||
                lower.contains("network") ||
                lower.contains("connect") ||
                reason.contains("网络") ||
                reason.contains("连接") ||
                reason.contains("超时") ||
                reason.contains("断开")
        }
    }
}
