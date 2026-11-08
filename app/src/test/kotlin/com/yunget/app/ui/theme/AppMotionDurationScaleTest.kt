package com.yunget.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppMotionDurationScaleTest {
    @Test fun disabledMotionStopsEveryAnimation() {
        val scale = AppMotionDurationScale { 2 }
        assertEquals(0f, scale.scaleFactor, 0f)
    }
    @Test fun expressiveRespectsSystemAccessibilityScale() {
        val scale = AppMotionDurationScale { 0 }
        scale.systemScale = 0f
        assertEquals(0f, scale.scaleFactor, 0f)
        scale.systemScale = 0.5f
        assertEquals(0.5f, scale.scaleFactor, 0f)
    }
    @Test fun settingTakesEffectWithoutRecreatingActivity() {
        var mode = 0
        val scale = AppMotionDurationScale { mode }
        assertEquals(1f, scale.scaleFactor, 0f)
        mode = 2
        assertEquals(0f, scale.scaleFactor, 0f)
        mode = 1
        assertEquals(1f, scale.scaleFactor, 0f)
    }
    @Test fun invalidSystemScaleDoesNotPoisonAnimationClock() {
        val scale = AppMotionDurationScale { 1 }
        scale.systemScale = Float.NaN
        assertEquals(1f, scale.scaleFactor, 0f)
    }
}
