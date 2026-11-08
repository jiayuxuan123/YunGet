package com.yunget.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale

/** Shared by every animation coroutine, including third-party Material components. */
internal class AppMotionDurationScale(
    private val motionLevel: () -> Int = { ThemeController.motionLevel },
) : MotionDurationScale {
    var systemScale by mutableFloatStateOf(1f)

    override val scaleFactor: Float
        get() = if (motionLevel() == 2) 0f
        else systemScale.takeIf { it.isFinite() && it >= 0f } ?: 1f
}
