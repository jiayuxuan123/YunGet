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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.text.DecimalFormat

/**
 * 下载速度曲线（最近约一分钟）。
 *
 * ## 为什么用图表库而不是手画 Canvas
 *
 * 一条"看起来对"的曲线要处理坐标轴刻度、数值格式化、空数据、极端值拉伸、深浅色适配 ——
 * 手写能画出线，但画不出这些边界，最后就是一个"能跑但不好用"的控件。
 * Vico 是 Compose 原生的图表库，这些默认行为都现成。
 *
 * ## 版本锁
 *
 * `gradle/libs.versions.toml` 里 Vico 锁在 2.4.4，注释说明了原因（更高版本会把
 * androidx.core / compose-ui 拉到需要 AGP 9.1 的版本，而本项目在 AGP 8.13）。
 *
 * ## 数据来源
 *
 * [samples] 是字节/秒的序列（由 [com.yunget.app.ui.viewmodel.SpeedHistory] 攒）。
 * **点数 < 2 时整块不绘制**：一个点连不成线，画出来只是一条贴底的直线，
 * 反而让人以为"速度为 0"。
 *
 * ## 纵轴单位
 *
 * 纵轴刻度走 [formatAxisSpeed]，与任务卡上的速度文字**共用同一套单位换算口径**
 * （1024 进制、KB/s 起）—— 否则会出现"曲线上标着 2.0M，卡片上写着 1.9 MB/s"这种对不上的情况。
 */
@Composable
fun SpeedChart(
    samples: List<Long>,
    modifier: Modifier = Modifier
) {
    // 少于两个点连不成线，直接留白（由调用方决定要不要显示整块区域）
    if (samples.size < 2) return

    val modelProducer = remember { CartesianChartModelProducer() }

    // 每次采样变化重新灌一次数据。Vico 内部对同一份数据有缓存，重复灌相同序列不会重绘。
    LaunchedEffect(samples) {
        modelProducer.runTransaction {
            lineSeries { series(samples) }
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // M3 主题：坐标轴/文字颜色跟随当前配色（含动态色彩与深浅色）
    val vicoTheme = rememberM3VicoTheme(
        lineColor = primary,
        textColor = surfaceVariant
    )

    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(primary)),
        areaFill = LineCartesianLayer.AreaFill.single(
            fill(primary.copy(alpha = 0.18f)),
            // 面积填充的"基线"：曲线下方填到纵轴 0，看起来才像"速度面积"
            { 0f }
        )
    )

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(line)
        ),
        startAxis = VerticalAxis.rememberStart(
            // 纵轴只留速度刻度，不画网格线（网格线在小尺寸卡片里比曲线还抢眼）
            valueFormatter = yAxisFormatter
        ),
        // 横轴不传：它的刻度是采样点序号，对用户没有意义。
        //
        // 【为什么不传一个"返回空串的格式化器"】Vico 明确禁止这么做 ——
        // formatForAxis() 在格式化器返回空串时直接抛 IllegalStateException
        // （"Use HorizontalAxis.ItemPlacer and VerticalAxis.ItemPlacer, not empty strings,
        // to control which x and y values are labeled."）。控制"哪些刻度显示标签"要用
        // ItemPlacer，不是让格式化器返回空串。这里干脆整条轴都不要 ——
        // 少一条轴比"有轴但没字"更干净，也顺带省掉一行高度。
        bottomAxis = null
    )

    Box(modifier = modifier) {
        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT),
            // 这是"最近一分钟概览"，不是可交互图表：关掉缩放与滚动，避免误触把曲线拖走
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false)
        )
    }
}

/** 曲线区高度。48dp 足够看出起伏，又不会把任务卡撑得太高。 */
private val CHART_HEIGHT = 48.dp

/**
 * 纵轴数值格式：把字节/秒按 1024 进制换算成 B/K/M/G。
 *
 * ★ **绝不能返回空串**。Vico 的 `formatForAxis()` 在格式化器返回空串时直接抛
 *   `IllegalStateException`（见 `CartesianValueFormatter.kt`）—— 它把"空标签"当成用法错误：
 *   想控制哪些刻度显示，应该用 `ItemPlacer`，而不是让格式化器返回空串。
 *   所以 0 也要给一个真实标签（"0"），不能返回 ""。这条约束由 `SpeedChartFormatTest` 守住。
 *
 * 与 [com.yunget.app.ui.screens.formatSpeed] 是同一套换算，但**不共用函数**：
 * 那个函数输出带空格的完整单位（"1.5 MB/s"），纵轴宽度有限，这里要的是最紧凑的形式（"1.5M"）。
 * 两处口径（进制、进位阈值）必须一致，改一处要改另一处 —— 这是有意的重复，
 * 因为"轴标签"和"正文文字"本来就有不同的排版约束。
 *
 * 声明为 internal 而非 private：单测要能直接调它（见 SpeedChartFormatTest）。
 * 界面上只经 [yAxisFormatter] 使用。
 */
internal fun formatAxisSpeed(bytesPerSec: Double): String {
    val bytes = bytesPerSec.toLong()
    return when {
        bytes <= 0 -> "0"
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        bytes < 1024L * 1024 * 1024 -> compact(bytes / 1024.0 / 1024.0, "M")
        else -> compact(bytes / 1024.0 / 1024.0 / 1024.0, "G")
    }
}

private val yAxisFormatter = CartesianValueFormatter { _, value, _ -> formatAxisSpeed(value) }

/** 保留一位小数，但整数不显示 ".0"（纵轴标签越短越好）。 */
private fun compact(value: Double, suffix: String): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}$suffix"
    else "${DecimalFormat("0.0").format(rounded)}$suffix"
}
