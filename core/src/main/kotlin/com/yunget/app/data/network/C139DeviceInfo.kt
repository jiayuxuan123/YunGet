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

package com.yunget.app.data.network

import java.util.Locale

/**
 * 139 个人云盘口令 XML 接口使用的运行时设备信息。
 *
 * **不要在常量里写死厂商、型号、分辨率或设备 ID**：这些字段既因设备而异，也可能暴露
 * 协作者/抓包者的真实设备。Android 端由宿主适配层从系统 API 与安装期随机 ID 注入，
 * 桌面端/纯 JVM 环境下走进程级随机默认实现，不读取任何硬件标识。
 *
 * 该动态生成只用于本次新增的口令 XML 请求；`fbcbd0b` 及以前已有的普通分享/个人盘固定
 * 设备头保持原样，避免改变线上协议行为。
 */
data class C139DeviceInfo(
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val deviceId: String,
    val osVersion: String,
    val screen: String,
    val language: String,
    val channel: String,
    val mmSource: String
) {
    /** 口令 XML 接口的 `x-DeviceInfo` / `x-yun-client-info` 共用的 App 格式。 */
    fun appDeviceInfo(): String =
        "1|127.0.0.1|1|$appVersion|$manufacturer|$model|$deviceId|" +
            "02-00-00-00-00-00|android $osVersion|$screen|$language||||$mmSource|0|"

    /** 口令 XML 接口的 `x-yun-user-agent`。 */
    fun appUserAgent(): String = "android|$model|android $osVersion|mCloud$appVersion-$mmSource"

    companion object {
        private const val FALLBACK_ID = "00000000000000000000000000000000"

        private val defaultJvmDeviceId: String by lazy { CoreDeviceIds.randomHex32() }

        @Volatile
        private var provider: () -> C139DeviceInfo = { defaultJvm() }

        /** 由宿主（Android 或桌面端）在启动时装配设备信息提供者。 */
        fun installProvider(deviceInfoProvider: () -> C139DeviceInfo) {
            provider = deviceInfoProvider
        }

        /** 获取当前宿主的设备信息；未装配时返回纯 JVM 默认值。 */
        fun current(): C139DeviceInfo = provider()

        /** 纯 JVM 默认实现（桌面端无需 Android 上下文即可直接使用）。 */
        fun defaultJvm(deviceId: String = defaultJvmDeviceId): C139DeviceInfo = C139DeviceInfo(
            appVersion = C139Constants.SHARE_CODE_APP_VERSION,
            manufacturer = "Android",
            model = "Android Device",
            deviceId = sanitizeField(deviceId).ifBlank { FALLBACK_ID },
            osVersion = "13",
            screen = "1080X2400",
            language = sanitizeField(Locale.getDefault().language.ifBlank { "zh" }),
            channel = C139Constants.SHARE_CODE_APP_CHANNEL,
            mmSource = C139Constants.SHARE_CODE_MM_SOURCE
        )

        /** 仅供未注入 Context 的测试/异常路径；不会读取或伪造真实硬件信息。 */
        fun fallback(): C139DeviceInfo = C139DeviceInfo(
            appVersion = C139Constants.SHARE_CODE_APP_VERSION,
            manufacturer = "Android",
            model = "Android Device",
            deviceId = FALLBACK_ID,
            osVersion = "Unknown",
            screen = "0X0",
            language = "zh",
            channel = C139Constants.SHARE_CODE_APP_CHANNEL,
            mmSource = C139Constants.SHARE_CODE_MM_SOURCE
        )

        internal fun resetProviderForTest() {
            provider = { defaultJvm() }
        }

        fun sanitizeField(value: String): String =
            value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    }
}
