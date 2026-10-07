package com.yunget.app.data.db

import com.yunget.app.data.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecureAccountDaos] 装饰器的行为测试。
 *
 * ## 为什么必须测这一层（而不只是测加密器）
 *
 * 加密器自身正确，不代表凭证真的被加密了 —— 真正的风险在**装饰器与 DAO 的接线**：
 *  - 写入时忘了加密 → 数据库里仍是明文（安全缺口照旧，且没有任何报错）
 *  - 读取时忘了解密 → 业务层拿到密文去请求接口 → 全部 401
 *  - 老数据是明文 → 若不迁移，要么读取失败，要么永远保持明文
 *  - 解密失败 → 若静默返回空，会被当成"未登录"，更糟的是后续写入会用空值覆盖
 *
 * 这些都不会崩溃、不会报错，只会"安静地不生效"。因此用内存假 DAO 直接验证。
 */
class SecureAccountDaosTest {

    /** 可逆替身：密文与明文必然不同，模拟真实 AES-GCM 的关键性质。 */
    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String =
            "enc($purpose)[" + plaintext.reversed() + "]"

        override fun decrypt(stored: String, purpose: String): String {
            if (!isEncrypted(stored)) return stored
            val prefix = "enc($purpose)["
            require(stored.startsWith(prefix) && stored.endsWith("]")) { "格式/用途不匹配" }
            return stored.removePrefix(prefix).removeSuffix("]").reversed()
        }

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("enc(")

        override fun onKeyProvisioned(listener: () -> Unit) = Unit
    }

    /** 内存 DAO：记录"数据库里"实际存了什么，供断言检查。 */
    private class FakeQuarkDao : QuarkAccountDao {
        var stored: QuarkAccountEntity? = null
        private val flow = MutableStateFlow<QuarkAccountEntity?>(null)
        var clearCount = 0

        override fun observeAccount(): Flow<QuarkAccountEntity?> = flow
        override suspend fun upsert(account: QuarkAccountEntity) {
            stored = account
            flow.value = account
        }

        override suspend fun getAccount(): QuarkAccountEntity? = stored
        override suspend fun clear() {
            stored = null
            flow.value = null
            clearCount++
        }
    }

    private val cipher = FakeCipher()

    private fun dao(raw: FakeQuarkDao) = SecureAccountDaos.quark(raw, cipher)

    // ---------------------------------------------------------------- 写入加密

    @Test
    fun upsertEncryptsBeforeStoring() = runBlocking {
        val raw = FakeQuarkDao()
        val secret = "a=1; __puus=supersecret"
        dao(raw).upsert(QuarkAccountEntity(cookie = secret))

        assertNotEquals("落库内容不得等于明文", secret, raw.stored?.cookie)
        assertTrue(
            "落库内容必须是密文",
            cipher.isEncrypted(raw.stored?.cookie ?: ""),
        )
        assertTrue(
            "明文不得以任何形式出现在库里",
            raw.stored?.cookie?.contains("supersecret") != true,
        )
    }

    // ---------------------------------------------------------------- 读取解密

    @Test
    fun getDecryptsAfterReading() = runBlocking {
        val raw = FakeQuarkDao()
        val secret = "a=1; __puus=supersecret"
        dao(raw).upsert(QuarkAccountEntity(cookie = secret))

        assertEquals(
            "业务层读到的必须是明文，否则所有接口请求都会 401",
            secret,
            dao(raw).getAccount()?.cookie,
        )
    }

    // ---------------------------------------------------------------- 平滑迁移

    @Test
    fun legacyPlaintextIsMigratedOnRead() = runBlocking {
        val raw = FakeQuarkDao()
        val legacy = "a=1; __puus=fromOldVersion"
        // 模拟升级前留下的明文记录
        raw.stored = QuarkAccountEntity(cookie = legacy)

        val read = dao(raw).getAccount()

        assertEquals("迁移期间读到的值应仍是明文（不能损坏数据）", legacy, read?.cookie)
        assertTrue(
            "明文记录读一次后应被加密回写 —— 否则老用户永远停在明文状态",
            cipher.isEncrypted(raw.stored?.cookie ?: ""),
        )
    }

    @Test
    fun alreadyEncryptedRecordIsNotReEncrypted() = runBlocking {
        val raw = FakeQuarkDao()
        dao(raw).upsert(QuarkAccountEntity(cookie = "x=1"))
        val afterFirst = raw.stored?.cookie

        // 再读一次：不应产生二次加密（嵌套加密会导致永久无法解密）
        dao(raw).getAccount()
        assertEquals("已加密的记录不应被再次加密", afterFirst, raw.stored?.cookie)
    }

    // ---------------------------------------------------------------- 失败语义

    @Test
    fun decryptFailureClearsCredentialInsteadOfReturningGarbage() = runBlocking {
        val raw = FakeQuarkDao()
        // 库里是"看起来像密文、但无法解开"的数据（模拟密钥丢失/数据损坏）
        raw.stored = QuarkAccountEntity(cookie = "enc(quark.cookie)[垃圾数据")

        val read = dao(raw).getAccount()

        assertNull("解密失败应返回 null（视为未登录），而不是抛错或返回乱码", read)
        assertEquals("同时应清空该条凭证，避免反复失败", 1, raw.clearCount)
    }

    @Test
    fun observeDecryptsValues() = runBlocking {
        val raw = FakeQuarkDao()
        val secret = "a=1; __puus=observed"
        dao(raw).upsert(QuarkAccountEntity(cookie = secret))

        // 只取首个值即退出：observeAccount() 的 Flow 是**永不结束**的（数据库变更流），
        // 直接 collect{} 会永久挂起 —— 必须用 first() 限时取出。
        val seen = kotlinx.coroutines.withTimeout(5_000) {
            dao(raw).observeAccount().first()
        }
        assertEquals("Flow 路径同样必须解密（用户从网盘页读到的是它）", secret, seen?.cookie)
    }
}
