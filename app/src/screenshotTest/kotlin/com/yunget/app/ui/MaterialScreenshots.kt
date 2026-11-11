package com.yunget.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.yunget.app.ui.navigation.MainTab
import com.yunget.app.ui.screens.ResolveLandingPreviewContent
import com.yunget.app.ui.screens.AnalyticsDisclosureDialog
import com.yunget.app.ui.screens.SettingsPreviewContent
import com.yunget.app.ui.screens.DrivePreviewContent
import com.yunget.app.ui.screens.DownloadPreviewContent
import com.yunget.app.ui.screens.DownloadCompletedPreviewContent
import com.yunget.app.ui.screens.ThemeScreen
import com.yunget.app.ui.screens.SupportScreen
import com.yunget.app.ui.screens.SupportBuiltinPreviewContent
import com.yunget.app.ui.theme.ComposeEmptyActivityTheme
import com.yunget.app.ui.theme.AppUiStyle

/** Host-rendered production Compose components; these are not device screenshots. */
@Composable
private fun PreviewShell(
    tab: MainTab,
    dark: Boolean = false,
    wide: Boolean = false,
    uiStyle: AppUiStyle = AppUiStyle.MODERN,
    descriptions: Boolean = false,
    content: @Composable () -> Unit,
) {
    ComposeEmptyActivityTheme(
        darkTheme = dark,
        dynamicColor = false,
        uiStyleOverride = uiStyle,
        showDescriptionsOverride = descriptions,
    ) {
        Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                if (wide) MainNavigationRail(tab) {}
                Column(Modifier.weight(1f)) {
                    MainAppBar(tab)
                    Box(Modifier.weight(1f)) { content() }
                    if (!wide) MainBottomBar(tab) {}
                }
            }
        }
    }
}

@Composable
private fun FullScreenPreview(
    dark: Boolean = false,
    uiStyle: AppUiStyle = AppUiStyle.MODERN,
    descriptions: Boolean = false,
    content: @Composable () -> Unit,
) {
    ComposeEmptyActivityTheme(
        darkTheme = dark,
        dynamicColor = false,
        uiStyleOverride = uiStyle,
        showDescriptionsOverride = descriptions,
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) { content() }
    }
}

@PreviewTest
@Preview(name = "resolve-phone", widthDp = 412, heightDp = 892)
@Composable
fun ResolvePhone() = PreviewShell(MainTab.Resolve) { ResolveLandingPreviewContent() }

@PreviewTest
@Preview(name = "resolve-dark", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ResolveDark() = PreviewShell(MainTab.Resolve, dark = true) { ResolveLandingPreviewContent() }

@PreviewTest
@Preview(name = "resolve-large-text", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
fun ResolveLargeText() = PreviewShell(MainTab.Resolve) { ResolveLandingPreviewContent() }

@PreviewTest
@Preview(name = "resolve-tablet", widthDp = 840, heightDp = 900)
@Composable
fun ResolveTablet() = PreviewShell(MainTab.Resolve, wide = true) { ResolveLandingPreviewContent() }

@PreviewTest
@Preview(name = "settings-phone", widthDp = 412, heightDp = 892)
@Composable
fun SettingsPhone() = PreviewShell(MainTab.Settings) { SettingsPreviewContent() }

@PreviewTest
@Preview(name = "settings-dark", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsDark() = PreviewShell(MainTab.Settings, dark = true) { SettingsPreviewContent() }

@PreviewTest
@Preview(name = "settings-large-text", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
fun SettingsLargeText() = PreviewShell(MainTab.Settings) { SettingsPreviewContent() }

@PreviewTest
@Preview(name = "settings-full", widthDp = 412, heightDp = 3200)
@Composable
fun SettingsFull() = PreviewShell(MainTab.Settings) { SettingsPreviewContent() }

@PreviewTest
@Preview(name = "theme-phone", widthDp = 412, heightDp = 892)
@Composable
fun ThemePhone() = FullScreenPreview { ThemeScreen(onBack = {}) }

@PreviewTest
@Preview(name = "theme-full", widthDp = 412, heightDp = 2600)
@Composable
fun ThemeFull() = FullScreenPreview { ThemeScreen(onBack = {}) }

@PreviewTest
@Preview(name = "theme-dark", widthDp = 412, heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThemeDark() = FullScreenPreview(dark = true) { ThemeScreen(onBack = {}) }

@PreviewTest
@Preview(name = "theme-large-text", widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
fun ThemeLargeText() = FullScreenPreview { ThemeScreen(onBack = {}) }

@PreviewTest
@Preview(name = "downloads-narrow", widthDp = 320, heightDp = 820, fontScale = 1.3f)
@Composable
fun DownloadsNarrow() = PreviewShell(MainTab.Download) { DownloadPreviewContent() }

@PreviewTest
@Preview(name = "support-wechat", widthDp = 412, heightDp = 1000)
@Composable
fun SupportWechat() = FullScreenPreview { SupportBuiltinPreviewContent(initialPage = 0) }

@PreviewTest
@Preview(name = "support-alipay", widthDp = 412, heightDp = 1000)
@Composable
fun SupportAlipay() = FullScreenPreview { SupportBuiltinPreviewContent(initialPage = 1) }

@PreviewTest
@Preview(name = "drive-phone", widthDp = 412, heightDp = 892)
@Composable
fun DrivePhone() = PreviewShell(MainTab.Drive) { DrivePreviewContent() }

@PreviewTest
@Preview(name = "downloads-phone", widthDp = 412, heightDp = 892)
@Composable
fun DownloadsPhone() = PreviewShell(MainTab.Download) { DownloadPreviewContent() }

@PreviewTest
@Preview(name = "downloads-dark", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DownloadsDark() = PreviewShell(MainTab.Download, dark = true) { DownloadPreviewContent() }

@PreviewTest
@Preview(name = "downloads-completed", widthDp = 412, heightDp = 892)
@Composable
fun DownloadsCompleted() = PreviewShell(MainTab.Download) { DownloadCompletedPreviewContent() }

@PreviewTest
@Preview(name = "analytics-disclosure", widthDp = 412, heightDp = 892)
@Composable
fun AnalyticsDisclosure() = FullScreenPreview {
    AnalyticsDisclosureDialog(onFinished = {})
}

@PreviewTest
@Preview(name = "classic-resolve", widthDp = 412, heightDp = 892)
@Composable
fun ClassicResolve() = PreviewShell(MainTab.Resolve, uiStyle = AppUiStyle.CLASSIC) {
    com.yunget.app.ui.classic.ClassicResolvePreview()
}

@PreviewTest
@Preview(name = "classic-settings", widthDp = 412, heightDp = 892)
@Composable
fun ClassicSettings() = PreviewShell(MainTab.Settings, uiStyle = AppUiStyle.CLASSIC) {
    com.yunget.app.ui.classic.ClassicSettingsPreview()
}

@PreviewTest
@Preview(name = "classic-downloads", widthDp = 412, heightDp = 892)
@Composable
fun ClassicDownloads() = PreviewShell(MainTab.Download, uiStyle = AppUiStyle.CLASSIC) {
    com.yunget.app.ui.classic.ClassicDownloadPreview()
}

@PreviewTest
@Preview(name = "settings-descriptions", widthDp = 412, heightDp = 892)
@Composable
fun SettingsDescriptions() = PreviewShell(MainTab.Settings, descriptions = true) { SettingsPreviewContent() }

@PreviewTest
@Preview(name = "classic-downloads-dark", widthDp = 412, heightDp = 892)
@Composable
fun ClassicDownloadsDark() = PreviewShell(MainTab.Download, dark = true, uiStyle = AppUiStyle.CLASSIC) {
    com.yunget.app.ui.classic.ClassicDownloadPreview()
}
