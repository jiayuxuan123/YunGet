package com.yunget.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.download.DownloadStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 下载页 ViewModel：任务列表（Room Flow → StateFlow）+ 实时统计 + 速度采样 + 操作转发。
 */
class DownloadViewModel(private val manager: DownloadManager) : ViewModel() {

    val tasks: StateFlow<List<DownloadTaskEntity>> = manager.tasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 实时下载统计：任务 id → 速度/剩余时间/线程数 */
    val stats: StateFlow<Map<Long, DownloadStats>> = manager.stats

    // ---------------------------------------------------------------- 速度曲线采样

    private val histories = mutableMapOf<Long, SpeedHistory>()

    private val _speedSeries = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())

    /**
     * 每个任务的速度序列（最近 [SpeedHistory.DEFAULT_CAPACITY] 个采样点），供速度曲线绘制。
     *
     * 为什么在 ViewModel 里采样而不是在 Composable 里：切到别的 Tab 再切回来，
     * 曲线要从"刚才那段"接着画，不能清零。Composable 随页面销毁，ViewModel 与 Activity 同寿。
     */
    val speedSeries: StateFlow<Map<Long, List<Long>>> = _speedSeries.asStateFlow()

    /**
     * 常驻采样协程：固定 1 秒一次，把当前速度写进各任务的历史环。
     *
     * 采样范围是**所有有统计或有历史的任务**，不只是"正在下载"的：
     * 任务暂停后仍继续记 0，曲线才会自然回落 —— 立刻停采的话曲线会僵在暂停前那一瞬，
     * 看起来像"暂停了速度还那么高"。
     *
     * 【为什么不在这里按任务表清理历史】[tasks] 是 `WhileSubscribed` 的，下载页没显示时
     * 上游停止收集、它的 value 会**冻结在最后一次列表**上。若拿这份可能过期的列表做
     * "不在列表里就删历史"，会把刚在解析页新加的任务的历史每秒删一次，曲线永远攒不起来。
     * 因此清理一律走显式路径：[clearSpeedHistory]（删除任务时）与 [resetSpeedHistory]（重新开始时）。
     */
    init {
        viewModelScope.launch {
            while (isActive) {
                delay(SpeedHistory.SAMPLE_INTERVAL_MS)
                sampleOnce()
            }
        }
    }

    private fun sampleOnce() {
        // manager.stats 是常驻 MutableStateFlow（不依赖订阅者），这份快照永远是新鲜的
        val currentStats = manager.stats.value

        // 只采样"当前有统计"的任务；历史一旦建立就继续记 0（见类注释）
        val ids = currentStats.keys + histories.keys
        if (ids.isEmpty()) return

        ids.forEach { id ->
            val history = histories.getOrPut(id) { SpeedHistory() }
            history.record(currentStats[id]?.speed ?: 0L)
        }

        _speedSeries.value = histories.mapValues { it.value.snapshot() }
    }

    /**
     * 删除任务时清掉它的曲线。
     *
     * 【为什么必须显式清】Room 的任务 id 会被复用（删掉再新建可能拿到同一个 id）。
     * 不清的话新任务会**继承上一个已删任务的曲线**，一打开就是一段来历不明的高速波形。
     *
     * 【为什么不在"重新开始"时清】暂停→继续是这类应用最常见的动作，
     * 曲线中间那段"掉到 0 再爬升"正是用户想看的（说明确实断过、续传从 0 起）。
     * 每次继续都清空的话，这条曲线永远只有几秒钟长，等于没有。
     */
    fun clearSpeedHistory(id: Long) {
        histories.remove(id)
        _speedSeries.value = histories.mapValues { it.value.snapshot() }
    }

    /** 添加下载任务（headers 可携带 Referer/Cookie 等） */
    fun enqueue(url: String, fileName: String, headers: Map<String, String> = emptyMap()) {
        viewModelScope.launch { manager.enqueue(url, fileName, headers) }
    }

    fun pause(id: Long) = manager.pause(id)

    fun resume(id: Long) = manager.start(id)

    fun remove(id: Long, deleteLocal: Boolean = false) {
        clearSpeedHistory(id)
        manager.remove(id, deleteLocal)
    }

    /** 全部暂停：暂停所有正在下载（含等待中）的任务 */
    fun pauseAll() {
        tasks.value.filter {
            it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                it.status == DownloadTaskEntity.STATUS_PENDING
        }.forEach { manager.pause(it.id) }
    }

    /** 全部开始：恢复所有已暂停/失败的任务（断点续传） */
    fun resumeAll() {
        tasks.value.filter {
            it.status == DownloadTaskEntity.STATUS_PAUSED ||
                it.status == DownloadTaskEntity.STATUS_FAILED
        }.forEach { manager.start(it.id) }
    }

    /** 删除全部任务（可同时删除已保存到本地的文件） */
    fun removeAll(deleteLocal: Boolean = false) {
        tasks.value.toList().forEach { task ->
            clearSpeedHistory(task.id)
            manager.remove(task.id, deleteLocal)
        }
        histories.clear()
        _speedSeries.value = emptyMap()
    }

    class Factory(private val manager: DownloadManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadViewModel::class.java))
            return DownloadViewModel(manager) as T
        }
    }
}