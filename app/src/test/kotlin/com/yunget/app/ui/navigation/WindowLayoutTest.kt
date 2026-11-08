package com.yunget.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test fun phoneUsesBottomNavigation() { assertFalse(usesNavigationRail(412, 915)) }
    @Test fun portraitTabletUsesRail() { assertTrue(usesNavigationRail(800, 1280)) }
    @Test fun landscapePhoneWithEnoughRoomUsesRail() { assertTrue(usesNavigationRail(840, 412)) }
    @Test fun shortMultiWindowKeepsBottomNavigation() { assertFalse(usesNavigationRail(840, 320)) }
}
