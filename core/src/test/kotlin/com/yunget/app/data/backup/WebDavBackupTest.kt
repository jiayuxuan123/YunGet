package com.yunget.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class WebDavBackupTest {
    @Test
    fun `shared crypto round trips a synthetic snapshot with fresh salts`() {
        val snapshot = """{"app":"yunget_auth_backup","version":1,"accounts":[]}"""
        val first = AuthCrypto.encrypt(snapshot, "example-passphrase")
        val second = AuthCrypto.encrypt(snapshot, "example-passphrase")
        assertNotEquals(first, second)
        assertTrue(AuthCrypto.isEncrypted(first))
        assertFalse(first.contains("accounts"))
        assertEquals(snapshot, AuthCrypto.decrypt(first, "example-passphrase"))
        assertThrows(AEADBadTagException::class.java) { AuthCrypto.decrypt(first, "different-passphrase") }
    }

    @Test
    fun `requires a complete HTTPS file URL with credentials supplied separately`() {
        assertThrows(IllegalArgumentException::class.java) { WebDavBackup("http://example.com/file", "", "") }
        assertThrows(IllegalArgumentException::class.java) { WebDavBackup("https://user@example.com/file", "", "") }
        assertThrows(IllegalArgumentException::class.java) { WebDavBackup("https://example.com/folder/", "", "") }
        WebDavBackup("https://example.com/folder/backup", "user", "test")
    }
}
