package com.yunget.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun primaryActionPairsMeetWcagAa() {
        assertTrue(contrast(onPrimaryLight, primaryLight) >= 4.5f)
        assertTrue(contrast(onPrimaryDark, primaryDark) >= 4.5f)
    }

    @Test
    fun primaryContainersKeepReadableContent() {
        assertTrue(contrast(onPrimaryContainerLight, primaryContainerLight) >= 4.5f)
        assertTrue(contrast(onPrimaryContainerDark, primaryContainerDark) >= 4.5f)
    }

    @Test
    fun groupedSurfacesKeepReadableText() {
        assertTrue(contrast(onSurfaceLight, surfaceContainerLowLight) >= 4.5f)
        assertTrue(contrast(onSurfaceDark, surfaceContainerLowDark) >= 4.5f)
        assertTrue(contrast(onSurfaceVariantLight, surfaceContainerLowLight) >= 4.5f)
        assertTrue(contrast(onSurfaceVariantDark, surfaceContainerLowDark) >= 4.5f)
    }
}
