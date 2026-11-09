package com.yunget.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLuminance = foreground.luminance()
    val backgroundLuminance = background.luminance()
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
        (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
}

internal fun readableAccent(accent: Color, foreground: Color, surfaces: List<Color>): Color {
    for (step in 0..20) {
        val candidate = lerp(accent, foreground, step / 20f)
        if (surfaces.all { contrastRatio(candidate, it) >= 4.5f }) return candidate
    }
    return foreground
}

internal val ColorScheme.accentText: Color
    get() = readableAccent(primary, onSurface, listOf(surface, background, surfaceContainerHigh))
