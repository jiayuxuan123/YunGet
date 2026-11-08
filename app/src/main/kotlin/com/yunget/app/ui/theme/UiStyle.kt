package com.yunget.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppUiStyle {
    CLASSIC,
    MODERN,
}

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.MODERN }
