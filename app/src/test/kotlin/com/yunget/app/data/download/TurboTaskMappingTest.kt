package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurboTaskMappingTest {
    @Test
    fun `stable room key recovers the room id`() {
        assertEquals(42L, TurboTaskMapping.roomIdFromStableKey("room-42"))
    }

    @Test
    fun `other or invalid stable keys are ignored`() {
        assertNull(TurboTaskMapping.roomIdFromStableKey(null))
        assertNull(TurboTaskMapping.roomIdFromStableKey(""))
        assertNull(TurboTaskMapping.roomIdFromStableKey("task-42"))
        assertNull(TurboTaskMapping.roomIdFromStableKey("room-"))
        assertNull(TurboTaskMapping.roomIdFromStableKey("room-0"))
        assertNull(TurboTaskMapping.roomIdFromStableKey("room-not-a-number"))
    }
}
