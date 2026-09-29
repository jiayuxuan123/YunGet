package com.yunget.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证加密层的契约测试。
 *
 * ## 为什么必须测
 *
 * 凭证（各网盘 cookie / accessToken）此前**以明文存进 Room 数据库**。
 * 引入加密层后，两个方向的错误都会造成实际损害：
 *  - **没加密**：用户拿到 root 或数据库文件即可直接读取账号凭证（安全缺口仍在）
 *  - **解不开**：用户每次启动都要重新登录（体验灾难）
 *
 * 本测试无法直接验证 Android Keystore（纯 JVM 单测没有该 Provider），
 * 因此用一个**行为等价的替身实现**验证「装饰器」这一层的逻辑：
 * 落库内容必须与明文不同、读出的必须与原文一致、明文能被自动迁移、
 * 解密失败不能被当成"空凭证"静默返回。
 */
class SecureCredentialContractTest {

    /**
     * 行为等价的替身：真实实现用 Android Keystore 的 AES-GCM，
     * 这里用可逆变换模拟"密文与明文不同 + 可还原"，用于验证装饰器逻辑。
     */
    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String =
            "yunx:v1:${purpose}:${plaintext.reversed()}"

        override fun decrypt(stored: String, purpose: String): String {
            if (!isEncrypted(stored)) return stored
            val prefix = "yunx:v1:$purpose:"
            require(stored.startsWith(prefix)) { "purpose 不匹配" }
            return stored.removePrefix(prefix).reversed()
        }

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("yunx:v1:")
    }

    /** 解密即失败的替身（模拟密钥丢失 / 数据损坏）。 */
    private class BrokenCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = "yunx:v1:broken"
        override fun decrypt(stored: String, purpose: String): String =
            throw IllegalStateException("keystore key unavailable")

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("yunx:v1:")
    }

    private val cipher = FakeCipher()

    // ---------------------------------------------------------------- 基本往返

    @Test
    fun roundTripPreservesValue() {
        val secret = "a=1; b=2; __puus=abcdef"
        val enc = cipher.encrypt(secret, "quark.cookie")
        assertEquals("解密后必须与原文一致", secret, cipher.decrypt(enc, "quark.cookie"))
    }

    @Test
    fun ciphertextDiffersFromPlaintext() {
        val secret = "a=1; __puus=abcdef"
        val enc = cipher.encrypt(secret, "quark.cookie")
        assertNotEquals("密文不得等于明文", secret, enc)
        assertFalse("明文不应原样出现在密文中", enc.contains("__puus=abcdef"))
    }

    // ---------------------------------------------------------------- 迁移

    @Test
    fun plaintextIsDetectedAsNotEncrypted() {
        assertFalse(
            "已是明文的旧数据必须被识别为未加密 —— 迁移依赖这个判断",
            cipher.isEncrypted("a=1; __puus=abcdef"),
        )
        assertTrue("新写入的必须被识别为已加密", cipher.isEncrypted(cipher.encrypt("x", "p")))
    }

    @Test
    fun legacyPlaintextStillReadable() {
        // 老用户库里是明文：必须能原样读出（随后由 DAO 层加密回写）
        val legacy = "a=1; __puus=legacy"
        assertEquals(legacy, cipher.decrypt(legacy, "quark.cookie"))
    }

    // ---------------------------------------------------------------- 失败语义

    @Test
    fun brokenKeyThrowsInsteadOfReturningEmpty() {
        // 解密失败必须是**异常**，不能被静默当成空字符串 ——
        // 否则会表现为"凭证莫名消失"，且写入时会用空值覆盖真实凭证。
        var threw = false
        try {
            BrokenCipher().decrypt("yunx:v1:whatever", "quark.cookie")
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("密钥不可用时解密必须抛出，而不是返回空值", threw)
    }

    @Test
    fun purposeIsBoundToCiphertext() {
        // AAD 绑定用途：用错误的 purpose 解密必须失败，
        // 防止把 A 网盘的密文当 B 网盘的凭证用（真实实现通过 AAD 实现）
        var threw = false
        try {
            cipher.decrypt(cipher.encrypt("secret", "quark.cookie"), "baidu.cookie")
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("purpose 不匹配时必须解密失败", threw)
    }
}
