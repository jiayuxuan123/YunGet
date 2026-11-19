package com.yunget.app.data.network

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * 让 WebView 的客户端提示（Sec-CH-UA / Sec-CH-UA-Mobile / Sec-CH-UA-Platform）与
 * [XunleiWebCredential.DESKTOP_UA] 保持一致。
 *
 * 依赖 WebView 的 `USER_AGENT_METADATA` 特性；不支持时静默跳过。
 */
fun XunleiWebCredential.applyDesktopClientHints(settings: WebSettings) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    val brandVersions = listOf(
        Triple("Google Chrome", "131", "131.0.0.0"),
        Triple("Chromium", "131", "131.0.0.0"),
        Triple("Not_A Brand", "24", "24.0.0.0")
    ).map { (brand, major, full) ->
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand(brand)
            .setMajorVersion(major)
            .setFullVersion(full)
            .build()
    }
    val metadata = UserAgentMetadata.Builder()
        .setBrandVersionList(brandVersions)
        .setFullVersion("131.0.0.0")
        .setPlatform("Windows")
        .setPlatformVersion("10.0.0")
        .setArchitecture("x86")
        .setModel("")
        .setMobile(false)
        .setWow64(false)
        .build()
    WebSettingsCompat.setUserAgentMetadata(settings, metadata)
}
