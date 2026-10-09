/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * 本文件取自上游 YunX (https://github.com/CYQawa/YunX)
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

package com.yunget.app.ui.viewmodel

/**
 * 下载速度采样环（每个任务一份），供速度曲线绘制使用。
 *
 * ## 为什么要自己攒历史
 *
 * 引擎给出的 [com.yunget.app.data.download.DownloadStats] 是**瞬时值**：它每秒更新一次，
 * 但只保留"当前这一刻"的速度。曲线要的是过去一段时间的**序列**，这个序列引擎不管，
 * 得由界面侧攒。
 *
 * ## 为什么放在 ViewModel 而不是 Composable 里
 *
 * 采样必须**跨页面切换存活**：切到别的 Tab 再切回来，曲线不能从零开始重画 ——
 * 用户想看的是"刚才那一分钟速度稳不稳"，切个页面就清空等于没这个功能。
 * Composable 随页面离开就销毁，`remember` 的状态跟着没了；ViewModel 与 Activity 同寿，
 * 正好是这条序列该有的生命周期。
 *
 * ## 采样节奏
 *
 * [record] 由 ViewModel 的常驻协程按固定间隔调用，**与引擎更新速度的频率无关**：
 * 引擎偶尔漏报一次也不会在曲线上留个洞（这里填 0 或不填，见下）。固定间隔还有个好处 ——
 * 横轴就是"第 N 个采样点"，不必再为每个点存时间戳。
 *
 * ## 空档怎么处理
 *
 * 任务暂停/结束时速度归零，但**不立刻清空历史**：清空会让曲线瞬间变空、看不出刚发生了什么。
 * 调用方在任务彻底停止（暂停/失败/完成）时传 `speed = 0` 继续采样，曲线自然回落到 0 并保留
 * 之前的形状；只有任务被删除或重新开始时才 [clear]。
 */
class SpeedHistory(
    /** 保留多少个采样点。默认 60 个 —— 采样间隔 1 秒时正好是"最近一分钟"。 */
    private val capacity: Int = DEFAULT_CAPACITY
) {

    private val samples = ArrayDeque<Long>(capacity)

    /** 当前序列（按时间先后）。返回的是快照副本，调用方可安全持有。 */
    fun snapshot(): List<Long> = samples.toList()

    /**
     * 记一个速度采样（字节/秒）。
     *
     * 超出容量时丢最旧的一个 —— 这是环形的意义：曲线永远显示"最近 N 个点"，
     * 而不是越攒越长最后把横轴压成一团。
     */
    fun record(speed: Long) {
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(speed.coerceAtLeast(0L))
    }

    /** 清空（任务删除或重新开始时调用）。 */
    fun clear() = samples.clear()

    /** 当前点数。 */
    val size: Int get() = samples.size

    /**
     * 这一段里出现过的峰值速度（字节/秒）；无数据返回 0。
     *
     * 用途：纵轴上限。用峰值而不是"当前速度"定上限，曲线不会因为速度掉下来而突然被拉伸 ——
     * 那样看起来像速度回升了，其实是坐标轴变了。
     */
    fun peak(): Long = samples.maxOrNull() ?: 0L

    companion object {
        /**
         * 默认容量 = 60。
         *
         * 采样间隔 1 秒 → 显示最近一分钟。这个长度是权衡过的：再短看不出趋势，
         * 再长在手机屏宽上每个点不到 2dp，曲线糊成一条带。
         */
        const val DEFAULT_CAPACITY = 60

        /** 采样间隔（毫秒）。1 秒：与引擎的统计更新同频，再多采只是重复同一个值。 */
        const val SAMPLE_INTERVAL_MS = 1_000L
    }
}
