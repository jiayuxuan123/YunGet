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
        displayLarge = appText(size(40), size(50)),
        displayMedium = appText(size(34), size(43)),
        displaySmall = appText(size(28), size(36)),
        headlineLarge = appText(size(24), size(32), FontWeight.SemiBold),
        headlineMedium = appText(size(20), size(28), FontWeight.SemiBold),
        headlineSmall = appText(size(17), size(24), FontWeight.SemiBold),
        titleLarge = appText(size(20), size(27), FontWeight.SemiBold),
        titleMedium = appText(size(16), size(24), FontWeight.Medium),
        titleSmall = appText(size(13), size(19), FontWeight.Medium),
        bodyLarge = appText(size(16), size(24)),
        bodyMedium = appText(size(14), size(20)),
        bodySmall = appText(size(12), size(17)),
        labelLarge = appText(size(14), size(20), FontWeight.Medium),
        labelMedium = appText(size(12), size(16), FontWeight.Medium),
        labelSmall = appText(size(11), size(15), FontWeight.Medium),
    )
}

/** 2.7.26 经典界面的字阶；不参与新版自动密度缩放。 */
fun buildClassicTypography(): Typography = com.yunget.app.ui.classic.classicTypography

val Typography = buildAppTypography()
