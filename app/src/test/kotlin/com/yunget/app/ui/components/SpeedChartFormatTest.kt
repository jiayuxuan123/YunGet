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

package com.yunget.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 速度曲线纵轴格式化测试。
 *
 * ## 为什么专门为这个函数写测试
 *
 * 2.7.0 发布后崩过一次，崩因就在轴标签上：Vico 的 `formatForAxis()` 在格式化器返回
 * **空串**时直接抛 `IllegalStateException`，而当时的横轴格式化器就是 `{ _, _, _ -> "" }`。
 * 纵轴的 `bytes <= 0 -> ""` 是同一类错误，只是恰好没被触发到。
 *
 * 也就是说这里守的不是"数字好不好看"，而是**一条会让应用崩溃的契约**：
 * 任何输入都必须返回非空标签。这条契约在 Vico 的源码里，不在调用方的类型签名里 ——
 * 编译器不会拦，只能靠测试。
 */
class SpeedChartFormatTest {

    @Test
    fun `任何输入都不返回空串`() {
        // 覆盖会出现在真实纵轴上的取值：0、负数（异常数据）、各量级边界与边界两侧
        val inputs = listOf(
            0.0, -1.0, -1024.0,                       // 零与异常负值
            1.0, 512.0, 1023.0, 1024.0,               // B 档与 B→K 边界
            1024.0 * 1024 - 1, 1024.0 * 1024,         // K→M 边界
            1024.0 * 1024 * 1024 - 1, 1024.0 * 1024 * 1024,  // M→G 边界
            5.0 * 1024 * 1024 * 1024,                 // G 档
            Double.MAX_VALUE,
        )
        inputs.forEach { v ->
            val label = formatAxisSpeed(v)
            assertTrue("输入 $v 的轴标签不能为空（Vico 会因此抛 IllegalStateException）", label.isNotEmpty())
        }
    }

    @Test
    fun `零与负值都给 0`() {
        assertEquals("0", formatAxisSpeed(0.0))
        assertEquals("0", formatAxisSpeed(-1.0))
        assertEquals("0", formatAxisSpeed(-1024.0))
    }

    @Test
    fun `各量级换算与进位边界`() {
        assertEquals("512B", formatAxisSpeed(512.0))
        assertEquals("1K", formatAxisSpeed(1024.0))
        assertEquals("1M", formatAxisSpeed(1024.0 * 1024))
        assertEquals("1G", formatAxisSpeed(1024.0 * 1024 * 1024))
    }

    @Test
    fun `整数不带小数点，非整数保留一位`() {
        assertEquals("2M", formatAxisSpeed(2.0 * 1024 * 1024))
        assertEquals("1.5M", formatAxisSpeed(1.5 * 1024 * 1024))
    }

    @Test
    fun `极大值不溢出成空或异常`() {
        // Double.MAX_VALUE 超出 G 档，仍要给出可读标签（哪怕数字很大）
        val label = formatAxisSpeed(Double.MAX_VALUE)
        assertTrue("极大值也要有标签，实际=$label", label.isNotEmpty())
        assertTrue("极大值应落在 G 档", label.endsWith("G"))
    }
}
