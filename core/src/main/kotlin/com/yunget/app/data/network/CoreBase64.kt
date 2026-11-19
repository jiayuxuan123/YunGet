package com.yunget.app.data.network

/**
 * Pure-Kotlin Base64 encoder/decoder compatible with Android minSdk 24 and JVM 17.
 */
object CoreBase64 {
    private const val STANDARD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in STANDARD_ALPHABET.indices) this[STANDARD_ALPHABET[i].code] = i
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun encode(bytes: ByteArray, urlSafe: Boolean = false, padding: Boolean = true): String {
        if (bytes.isEmpty()) return ""
        val alphabet = if (urlSafe) URL_ALPHABET else STANDARD_ALPHABET
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i++].toInt() and 0xFF
            val hasB1 = i < bytes.size
            val b1 = if (hasB1) bytes[i++].toInt() and 0xFF else 0
            val hasB2 = i < bytes.size
            val b2 = if (hasB2) bytes[i++].toInt() and 0xFF else 0

            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (hasB1) {
                out.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            } else if (padding) {
                out.append('=')
            }
            if (hasB2) {
                out.append(alphabet[b2 and 0x3F])
            } else if (padding) {
                out.append('=')
            }
        }
        return out.toString()
    }

    fun decode(input: String): ByteArray {
        val clean = IntArray(input.length)
        var len = 0
        for (ch in input) {
            val code = ch.code
            if (ch == '=' || code <= 32) continue
            if (code >= 256) throw IllegalArgumentException("Invalid Base64 character")
            val value = DECODE_TABLE[code]
            if (value < 0) throw IllegalArgumentException("Invalid Base64 character: $ch")
            clean[len++] = value
        }
        if (len == 0) return ByteArray(0)
        val outLen = (len * 3) / 4
        val out = ByteArray(outLen)
        var oi = 0
        var i = 0
        while (i < len) {
            val c0 = clean[i++]
            val c1 = if (i < len) clean[i++] else break
            if (oi < outLen) out[oi++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (i >= len) break
            val c2 = clean[i++]
            if (oi < outLen) out[oi++] = (((c1 and 0x0F) shl 4) or (c2 ushr 2)).toByte()
            if (i >= len) break
            val c3 = clean[i++]
            if (oi < outLen) out[oi++] = (((c2 and 0x03) shl 6) or c3).toByte()
        }
        return if (oi == outLen) out else out.copyOf(oi)
    }
}
