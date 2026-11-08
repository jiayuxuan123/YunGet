package com.yunget.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class UiDensityProfile(
    val fontScale: Float,
    val spacingScale: Float,
    val reason: String,
)

@Immutable
data class UiMetrics(
    val fontScale: Float = 1f,
    val spacingScale: Float = 1f,
) {
    fun space(value: Dp): Dp = (value.value * spacingScale).dp
}

val LocalUiMetrics = staticCompositionLocalOf { UiMetrics() }

/** Uses the current window ratio, so split-screen and rotation are handled automatically. */
fun autoUiDensity(widthDp: Int, heightDp: Int): UiDensityProfile {
    val shortSide = minOf(widthDp, heightDp).coerceAtLeast(1)
    val longSide = maxOf(widthDp, heightDp).coerceAtLeast(1)
    val ratio = longSide.toFloat() / shortSide
    return when {
        widthDp >= 720 || ratio <= 1.35f -> UiDensityProfile(
            fontScale = 1f,
            spacingScale = 1f,
            reason = "宽屏或平板：标准间距",
        )
        shortSide < 340 -> UiDensityProfile(
            fontScale = 0.98f,
            spacingScale = 0.88f,
            reason = "超窄窗口：略收紧字号与间距",
        )
        ratio >= 2.1f -> UiDensityProfile(
            fontScale = 1f,
            spacingScale = 0.90f,
            reason = "高屏：保留舒适字号，仅收紧间距",
        )
        else -> UiDensityProfile(
            fontScale = 1f,
            spacingScale = 0.95f,
            reason = "常规屏幕：舒适字号与轻量间距",
        )
    }
}
