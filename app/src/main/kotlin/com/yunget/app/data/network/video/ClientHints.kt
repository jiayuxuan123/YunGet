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

package com.yunget.app.data.network.video

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * 让 WebView 的 **client hints** 与自定义 UA 保持一致。
 *
 * ## 为什么必须做
 *
 * 只改 `settings.userAgentString` 会留下自相矛盾的指纹：UA 说自己是
 * Chrome 131 / Windows，而 client hints（`Sec-CH-UA` 系列）仍报
 * "Android WebView / 设备型号"。抖音、微博这类站点会拿两处对不上直接判机器人。
 *
 * 这里按 UA 里的版本重建一份 metadata：品牌、主版本、平台、架构、是否移动端
 * 全部跟着 UA 走，两处不再冲突。
 *
 * ## 依赖
 *
 * 用 `androidx.webkit` 的 `setUserAgentMetadata`（1.13+，要求 minSdk 24）。
 * 本项目 minSdk 已提到 24，可以直接用官方 API，不必反射。
 * `USER_AGENT_METADATA` 特性在旧 WebView 上不存在，此时**静默跳过** ——
 * 页面退回只靠 UA 判定，弱一些但不会崩。
 */
internal fun WebSettings.alignClientHintsWith(userAgent: String) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return

    val major = Regex("Chrome/(\\d+)").find(userAgent)?.groupValues?.getOrNull(1) ?: "131"
    val full = "$major.0.0.0"

    val brandVersions = listOf(
        Triple("Google Chrome", major, full),
        Triple("Chromium", major, full),
        // Chrome 官方 UA-CH 固定带这一项，缺了它反而比不带更可疑
        Triple("Not_A Brand", "24", "24.0.0.0"),
    ).map { (brand, brandMajor, brandFull) ->
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand(brand)
            .setMajorVersion(brandMajor)
            .setFullVersion(brandFull)
            .build()
    }

    val isAndroid = userAgent.contains("Android")
    val isIos = userAgent.contains("iPhone") || userAgent.contains("iPad")

    val metadata = UserAgentMetadata.Builder()
        .setBrandVersionList(brandVersions)
        .setFullVersion(full)
        .setPlatform(if (isAndroid) "Android" else if (isIos) "iOS" else "Windows")
        .setPlatformVersion(if (isAndroid) "14.0.0" else if (isIos) "17.0.0" else "10.0.0")
        .setArchitecture(if (isAndroid || isIos) "" else "x86")
        // 机型跟着 UA 走：UA 写了 Pixel 8，这里也写 Pixel 8
        .setModel(if (isAndroid) "Pixel 8" else "")
        .setMobile(isAndroid || isIos || userAgent.contains("Mobile"))
        .setWow64(false)
        .build()

    WebSettingsCompat.setUserAgentMetadata(this, metadata)
}
