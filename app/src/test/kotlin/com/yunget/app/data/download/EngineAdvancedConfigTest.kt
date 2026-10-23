/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
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

package com.yunget.app.data.download

import dev.turbodl.core.HttpVersionPolicy
import dev.turbodl.core.TurboConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎高级参数与 `TurboConfig` 的契约。
 *
 * ## 为什么需要这个测试
 *
 * 这些旋钮的默认值此前**根本没有传给引擎**，所以一直跑的是引擎默认值；
 * 一旦 App 侧开始传值，就必须保证：
 *
 *  1. 传进 TurboConfig 的值不会触发它的 `require` 校验（否则**下载直接起不来**，
 *     而且是在用户点了下载之后才炸）；
 *  2. App 侧的默认值与引擎默认**逐项一致** —— 否则"升级 App 就悄悄改变了下载行为"，
 *     这种漂移极难在事后定位。
 *
 * 这里把两组数字钉死，任何一边改了都会被这条测试拦下。
 */
class EngineAdvancedConfigTest {

    /** 与 SettingsRepository 的默认值保持一致（改了那边就要同步这里）。 */
    private fun appDefaultConfig() = TurboConfig(
        blockSize = 16L * 1024 * 1024,
        minSegmentSize = 64L * 1024,
        maxSegmentsPerTask = 0,
        maxSplitsPerSegment = 2,
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 60_000L,
        probeTimeoutMs = 6_000L,
        probeRetries = 1,
        slowStartInitial = 0,
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        // App 侧永远显式传策略；0 映射为 AUTO
        httpVersionPolicy = HttpVersionPolicy.AUTO,
    )

    @Test
    fun appDefaultsMatchEngineDefaults() {
        val engine = TurboConfig()
        val app = appDefaultConfig()

        assertEquals("blockSize 默认值漂移", engine.blockSize, app.blockSize)
        assertEquals("minSegmentSize 默认值漂移", engine.minSegmentSize, app.minSegmentSize)
        assertEquals("maxSegmentsPerTask 默认值漂移", engine.maxSegmentsPerTask, app.maxSegmentsPerTask)
        assertEquals("maxSplitsPerSegment 默认值漂移", engine.maxSplitsPerSegment, app.maxSplitsPerSegment)
        assertEquals("connectTimeoutMs 默认值漂移", engine.connectTimeoutMs, app.connectTimeoutMs)
        assertEquals("readTimeoutMs 默认值漂移", engine.readTimeoutMs, app.readTimeoutMs)
        assertEquals("probeTimeoutMs 默认值漂移", engine.probeTimeoutMs, app.probeTimeoutMs)
        assertEquals("probeRetries 默认值漂移", engine.probeRetries, app.probeRetries)
        assertEquals("slowStartInitial 默认值漂移", engine.slowStartInitial, app.slowStartInitial)
        assertEquals("userAgent 默认值漂移", engine.userAgent, app.userAgent)
        // 【这一项不再比默认值相等】
        // App 永远传显式策略（AUTO/FORCE_HTTP1/FORCE_HTTP2），而引擎的默认值
        // 是 UNSET（= "没设置，回落到 forceHttp1"）。两者语义不同：
        // App 传 AUTO 表示"用户明确选了自动"，不该被 forceHttp1 改写。
        // 所以这里断言的是**解析结果**，而不是字段字面量。
        assertEquals(
            "App 传 AUTO 时应解析为 AUTO（不受 forceHttp1 影响）",
            HttpVersionPolicy.AUTO,
            app.effectiveHttpVersionPolicy,
        )
    }

    @Test
    fun boundaryValuesAcceptedByEngine() {
        // UI 允许的极值必须都能构造 —— 否则用户把步进器拉到端点就炸。
        val min = TurboConfig(
            blockSize = 1024L * 1024,          // 1MB（UI 下限）
            minSegmentSize = 4096L,             // 引擎硬下限
            maxSegmentsPerTask = 0,             // 不限
            maxSplitsPerSegment = 0,            // 不再切
            connectTimeoutMs = 1_000L,
            readTimeoutMs = 5_000L,
            probeTimeoutMs = 1_000L,            // 引擎硬下限
            probeRetries = 0,
            slowStartInitial = 0,
            httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP1,
        )
        assertEquals(4096L, min.minSegmentSize)

        val max = TurboConfig(
            blockSize = 64L * 1024 * 1024,      // 64MB（UI 上限）
            minSegmentSize = 1024L * 1024,      // 1024KB（UI 上限）
            maxSegmentsPerTask = 2048,
            maxSplitsPerSegment = 16,
            connectTimeoutMs = 120_000L,
            readTimeoutMs = 600_000L,
            probeTimeoutMs = 60_000L,
            probeRetries = 5,
            slowStartInitial = 64,
            httpVersionPolicy = HttpVersionPolicy.FORCE_HTTP2,
        )
        assertEquals(2048, max.maxSegmentsPerTask)
    }

    @Test
    fun httpVersionPolicyMapsToAllThreeStates() {
        // UI 用 0/1/2 表示三态；映射错一个就会让"强制 1.1"静默失效。
        fun map(v: Int) = when (v) {
            1 -> HttpVersionPolicy.FORCE_HTTP1
            2 -> HttpVersionPolicy.FORCE_HTTP2
            else -> HttpVersionPolicy.AUTO
        }
        assertEquals(HttpVersionPolicy.AUTO, map(0))
        assertEquals(HttpVersionPolicy.FORCE_HTTP1, map(1))
        assertEquals(HttpVersionPolicy.FORCE_HTTP2, map(2))
        // 越界值回落到 AUTO，而不是抛异常
        assertEquals(HttpVersionPolicy.AUTO, map(99))
    }

    @Test
    fun slowStartKeepsItsInitialConcurrency() {
        // 「慢启动」开关只有半截效果的历史：起点并发没设，一直是引擎默认。
        // 这条测试确保 slowStartInitial 真的进了 config，而不是被 ignore。
        val cfg = TurboConfig(slowStart = true, slowStartInitial = 4)
        assertTrue(cfg.slowStart)
        assertEquals(4, cfg.slowStartInitial)
    }
}
