package com.yunget.app.data.network

import java.security.SecureRandom

/**
 * Shared persistent device-identifier hooks for `:core` APIs.
 * Android and Desktop hosts install their respective persistent stores at startup.
 */
object CoreDeviceIds {
    @Volatile
    private var pan123Provider: () -> String = { randomHex32() }

    fun installPan123LoginUuid(provider: () -> String) {
        pan123Provider = provider
    }

    fun pan123LoginUuid(): String = pan123Provider()

    fun randomHex32(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val hex = CharArray(32)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }
}
