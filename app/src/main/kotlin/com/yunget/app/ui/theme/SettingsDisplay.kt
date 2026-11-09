package com.yunget.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

val LocalShowSettingDescriptions = staticCompositionLocalOf { false }

/** Explanations can disappear; a selected value must remain visible. */
fun settingSupportingText(description: String, value: String?, showDescriptions: Boolean): String? =
    (if (showDescriptions) description else value)?.takeIf { it.isNotBlank() }
