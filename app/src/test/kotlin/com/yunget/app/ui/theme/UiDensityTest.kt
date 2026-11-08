package com.yunget.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class UiDensityTest {
    @Test fun tallPhoneGetsCompactMetrics() {
        val profile = autoUiDensity(412, 915)
        assertEquals(1f, profile.fontScale, 0f)
        assertTrue(profile.spacingScale < 1f)
    }

    @Test fun narrowMultiWindowGetsCompactMetrics() {
        assertTrue(autoUiDensity(320, 800).fontScale < 1f)
    }

    @Test fun tabletAndLandscapeKeepComfortableSpacing() {
        assertEquals(1f, autoUiDensity(840, 900).spacingScale, 0f)
        assertEquals(1f, autoUiDensity(915, 412).fontScale, 0f)
    }

    @Test fun localMetricsScaleDpWithoutAffectingTouchMinimum() {
        val metrics = UiMetrics(spacingScale = 0.8f)
        assertEquals(8f, metrics.space(10.dp).value, 0.001f)
    }

    @Test fun typographyScaleKeepsFractionalSpInsteadOfTruncating() {
        val typography = buildAppTypography(scale = 0.98f)
        assertEquals(13.72f, typography.bodyMedium.fontSize.value, 0.001f)
    }
}
