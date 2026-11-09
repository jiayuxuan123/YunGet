package com.yunget.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentColorsTest {
    @Test fun staticActionPairsRemainReadableInBothThemes() {
        listOf(
            AstryxPalette.onAccentLight to AstryxPalette.accentLight,
            AstryxPalette.onAccentDark to AstryxPalette.accentDark,
            AstryxPalette.onAccentContainerLight to AstryxPalette.accentContainerLight,
            AstryxPalette.onAccentContainerDark to AstryxPalette.accentContainerDark,
            AstryxPalette.onSuccessContainerLight to AstryxPalette.successContainerLight,
            AstryxPalette.onSuccessContainerDark to AstryxPalette.successContainerDark,
        ).forEach { (foreground, background) ->
            assertTrue(contrastRatio(foreground, background) >= 4.5f)
        }
    }

    @Test fun darkButtonBlueIsAdjustedForSmallTextOnDarkSurfaces() {
        val surfaces = listOf(Color(0xFF111112), Color(0xFF303237))
        val adjusted = readableAccent(Color(0xFF0171E3), Color(0xFFDFE2E5), surfaces)
        assertTrue(surfaces.all { contrastRatio(adjusted, it) >= 4.5f })
    }

    @Test fun readableSeedIsPreserved() {
        val seed = Color(0xFF2B62AD)
        assertEquals(seed, readableAccent(seed, Color.Black, listOf(Color.White)))
    }
}
