/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
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

import dev.turbodl.core.TurboConfig

/**
 * 引擎参数的**应用侧默认值**，只在这里写一次。
 *
 * 为什么要有这个文件：同一组默认值原来散在三处 —— 设置仓库的 getter、设置页的"恢复默认"、
 * 以及 `EngineAdvancedConfigTest` 里手抄的一份。引擎改默认值（本轮读超时 60s → 30s）时，
 * 三处不会同时跟着动：测试里那份抄本继续用旧值，于是"App 默认值漂移"这条用例报错，
 * 但用户手上真正生效的默认值到底是哪个，谁也说不上来。
 *
 * 现在默认值只有这一份：仓库 getter 读它，设置页恢复默认用它，测试直接拿 [config] 和
 * `TurboConfig()` 比 —— 引擎一改默认值，测试立刻告诉我们应用侧是否需要跟着改。
 */
object EngineDefaults {

    const val BLOCK_SIZE: Long = 16L * 1024 * 1024
    const val MIN_SEGMENT_SIZE: Long = 64L * 1024
    const val MAX_SEGMENTS_PER_TASK: Int = 0
    const val MAX_SPLITS_PER_SEGMENT: Int = 2
    const val CONNECT_TIMEOUT_MS: Long = 15_000L

    /** 与引擎默认值保持一致：[TurboConfig.readTimeoutMs]。 */
    const val READ_TIMEOUT_MS: Long = 30_000L

    const val PROBE_TIMEOUT_MS: Long = 6_000L
    const val PROBE_RETRIES: Int = 1
    const val SLOW_START_INITIAL: Int = 0

    /** 空串表示"用引擎自带的 UA"，与 `TurboConfig.userAgent` 的默认值一致。 */
    const val USER_AGENT: String = ""

    /** 应用侧完整默认配置（测试用它和 `TurboConfig()` 对齐）。 */
    fun config(): TurboConfig = TurboConfig(
        blockSize = BLOCK_SIZE,
        minSegmentSize = MIN_SEGMENT_SIZE,
        maxSegmentsPerTask = MAX_SEGMENTS_PER_TASK,
        maxSplitsPerSegment = MAX_SPLITS_PER_SEGMENT,
        connectTimeoutMs = CONNECT_TIMEOUT_MS,
        readTimeoutMs = READ_TIMEOUT_MS,
        probeTimeoutMs = PROBE_TIMEOUT_MS,
        probeRetries = PROBE_RETRIES,
        slowStartInitial = SLOW_START_INITIAL,
        userAgent = USER_AGENT,
    )
}
