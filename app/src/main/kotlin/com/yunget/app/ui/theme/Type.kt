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

package com.yunget.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material type roles with comfortable CJK line heights and no bundled font payload. */
private fun appText(size: Float, height: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = 0.sp,
)

fun buildAppTypography(scale: Float = 1f): Typography {
    fun size(value: Int) = (value * scale).coerceAtLeast(9f)
    return Typography(
        displayLarge = appText(size(40), size(48)),
        displayMedium = appText(size(34), size(42)),
        displaySmall = appText(size(29), size(36)),
        headlineLarge = appText(size(26), size(33), FontWeight.SemiBold),
        headlineMedium = appText(size(23), size(30), FontWeight.SemiBold),
        headlineSmall = appText(size(20), size(27), FontWeight.SemiBold),
        titleLarge = appText(size(22), size(28), FontWeight.SemiBold),
        titleMedium = appText(size(16), size(22), FontWeight.Medium),
        titleSmall = appText(size(14), size(20), FontWeight.Medium),
        bodyLarge = appText(size(16), size(23)),
        bodyMedium = appText(size(14), size(20)),
        bodySmall = appText(size(12), size(17)),
        labelLarge = appText(size(14), size(19), FontWeight.Medium),
        labelMedium = appText(size(12), size(16), FontWeight.Medium),
        labelSmall = appText(size(11), size(15), FontWeight.Medium),
    )
}

/** 2.7.26 经典界面的字阶；不参与新版自动密度缩放。 */
fun buildClassicTypography(): Typography = Typography(
    headlineMedium = appText(22f, 28f, FontWeight.SemiBold),
    titleLarge = appText(18f, 24f, FontWeight.SemiBold),
    titleMedium = appText(16f, 22f, FontWeight.Medium),
    titleSmall = appText(14f, 20f, FontWeight.Medium),
    bodyLarge = appText(15f, 22f),
    bodyMedium = appText(13f, 18f),
    bodySmall = appText(12f, 16f),
    labelLarge = appText(14f, 18f, FontWeight.Medium),
    labelMedium = appText(12f, 16f, FontWeight.Medium),
    labelSmall = appText(11f, 14f, FontWeight.Medium),
)

val Typography = buildAppTypography()
