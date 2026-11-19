package com.yunget.desktop

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

/**
 * Windows DPAPI (CurrentUser) credential encryption for desktop account persistence.
 *
 * Values protected by DPAPI are stored with prefix [DPAPI_PREFIX] followed by Base64 bytes.
 * Legacy plaintext values remain readable for seamless migration and are re-encrypted on save.
 */
internal object DesktopCredentialCrypto {
    private const val DPAPI_PREFIX = "dpapi:v1:"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun isEncrypted(value: String): Boolean = value.startsWith(DPAPI_PREFIX)

    fun protect(plain: String): String {
        if (plain.isEmpty()) return ""
        if (isEncrypted(plain)) return plain
        if (!isWindows) return plain
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val encrypted = Crypt32Util.cryptProtectData(bytes)
        return DPAPI_PREFIX + Base64.getEncoder().encodeToString(encrypted)
    }

    fun unprotect(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return stored
        if (!isWindows) {
            error("DPAPI-encrypted credentials can only be decrypted on Windows")
        }
        val payload = stored.removePrefix(DPAPI_PREFIX)
        val raw = Base64.getDecoder().decode(payload)
        val plain = Crypt32Util.cryptUnprotectData(raw)
        return String(plain, Charsets.UTF_8)
    }
}
