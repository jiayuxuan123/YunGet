package com.yunget.app.data.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityNotificationDataTest {
    private val token = "a".repeat(64)
    private val cookieName = listOf("__Host", "yunget", "community").joinToString("-")

    @Test
    fun `selects only the exact community session cookie`() {
        assertEquals(
            "$cookieName=$token",
            CommunityNotificationData.sessionCookie("theme=dark; $cookieName=$token; other=value"),
        )
        assertNull(CommunityNotificationData.sessionCookie("not-$cookieName=$token"))
        assertNull(CommunityNotificationData.sessionCookie("$cookieName=${"a".repeat(63)}"))
    }

    @Test
    fun `unread count is bounded and rejects malformed values`() {
        assertEquals(17, CommunityNotificationData.unread("""{"unread":17}"""))
        assertEquals(999, CommunityNotificationData.unread("""{"unread":5000}"""))
        assertEquals(0, CommunityNotificationData.unread("""{"unread":-1}"""))
        assertEquals(0, CommunityNotificationData.unread("""{"unread":1.5}"""))
        assertEquals(0, CommunityNotificationData.unread("not json"))
    }
}
