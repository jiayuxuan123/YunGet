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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 速度采样环测试。
 *
 * 这些用例守的是速度曲线的**数据前提**，不是绘制效果（绘制需要真机）：
 * 曲线看着不对时，先在这里排除"是不是数据本身就是错的"。
 */
class SpeedHistoryTest {

    @Test
    fun `容量满后丢最旧的，保留最近 N 个`() {
        val h = SpeedHistory(capacity = 3)
        h.record(1)
        h.record(2)
        h.record(3)
        h.record(4)

        assertEquals("超出容量必须丢最旧的一个", listOf(2L, 3L, 4L), h.snapshot())
        assertEquals(3, h.size)
    }

    @Test
    fun `负速度钳到 0`() {
        val h = SpeedHistory(capacity = 4)
        h.record(-100)
        assertEquals("速度不可能为负，负值会画到坐标轴下方", listOf(0L), h.snapshot())
    }

    @Test
    fun `snapshot 是副本，外部改动不影响内部`() {
        val h = SpeedHistory(capacity = 4)
        h.record(5)
        val snap = h.snapshot()
        h.record(6)
        assertEquals("已取出的快照不应随后续采样变化", listOf(5L), snap)
        assertEquals(listOf(5L, 6L), h.snapshot())
    }

    @Test
    fun `peak 取最大值，空时为零`() {
        val h = SpeedHistory(capacity = 4)
        assertEquals("无数据时峰值为 0（纵轴上限不能是负数）", 0L, h.peak())

        h.record(100)
        h.record(900)
        h.record(300)
        assertEquals(900L, h.peak())
    }

    @Test
    fun `clear 清空`() {
        val h = SpeedHistory(capacity = 4)
        h.record(1)
        h.record(2)
        h.clear()
        assertEquals(0, h.size)
        assertTrue(h.snapshot().isEmpty())
    }

    @Test
    fun `默认容量是一分钟采样`() {
        assertEquals(
            "默认容量应与采样间隔配合成「最近一分钟」",
            60_000L,
            SpeedHistory.DEFAULT_CAPACITY * SpeedHistory.SAMPLE_INTERVAL_MS
        )
    }
}
