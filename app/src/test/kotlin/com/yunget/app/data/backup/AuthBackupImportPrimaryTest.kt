package com.yunget.app.data.backup

import com.yunget.app.data.db.BaiduAccountDao
import com.yunget.app.data.db.BaiduAccountEntity
import com.yunget.app.data.db.C139AccountDao
import com.yunget.app.data.db.C139AccountEntity
import com.yunget.app.data.db.GuangYaAccountDao
import com.yunget.app.data.db.GuangYaAccountEntity
import com.yunget.app.data.db.ILanzouAccountDao
import com.yunget.app.data.db.ILanzouAccountEntity
import com.yunget.app.data.db.LanzouAccountDao
import com.yunget.app.data.db.LanzouAccountEntity
import com.yunget.app.data.db.Pan115AccountDao
import com.yunget.app.data.db.Pan115AccountEntity
import com.yunget.app.data.db.Pan123AccountDao
import com.yunget.app.data.db.Pan123AccountEntity
import com.yunget.app.data.db.QuarkAccountDao
import com.yunget.app.data.db.QuarkAccountEntity
import com.yunget.app.data.db.UCAccountDao
import com.yunget.app.data.db.UCAccountEntity
import com.yunget.app.data.db.XunleiAccountDao
import com.yunget.app.data.db.XunleiAccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AuthBackupImportPrimaryTest {

    @Test
    fun importedBackupAccountBecomesPrimaryEvenWhenOlderThanExistingMultiAccountRow() = runBlocking {
        val quarkRows = MutableStateFlow(
            listOf(
                QuarkAccountEntity(
                    id = "uuid-stale-primary",
                    cookie = "stale_cookie",
                    nickname = "旧账号",
                    isPrimary = true,
                    updatedAt = 9_999_999_999L,
                )
            )
        )
        val quarkDao = object : QuarkAccountDao {
            override fun observeAccount(): Flow<QuarkAccountEntity?> = quarkRows.map { sorted(it).firstOrNull() }
            override fun observeAll(): Flow<List<QuarkAccountEntity>> = quarkRows.map { sorted(it) }
            override suspend fun upsert(account: QuarkAccountEntity) {
                quarkRows.value = quarkRows.value.filterNot { it.id == account.id } + account
            }
            override suspend fun getAccount(): QuarkAccountEntity? = sorted(quarkRows.value).firstOrNull()
            override suspend fun getById(id: String): QuarkAccountEntity? = quarkRows.value.firstOrNull { it.id == id }
            override suspend fun count(): Int = quarkRows.value.size
            override suspend fun clear() { quarkRows.value = emptyList() }
            override suspend fun deleteById(id: String) { quarkRows.value = quarkRows.value.filterNot { it.id == id } }
            override suspend fun updateRemark(id: String, remark: String) = Unit
            override suspend fun demoteAll() { quarkRows.value = quarkRows.value.map { it.copy(isPrimary = false) } }
            override suspend fun promote(id: String) {
                quarkRows.value = quarkRows.value.map { if (it.id == id) it.copy(isPrimary = true) else it }
            }
            override suspend fun promoteLatest() = Unit
            private fun sorted(list: List<QuarkAccountEntity>) =
                list.sortedWith(compareByDescending<QuarkAccountEntity> { it.isPrimary }.thenByDescending { it.updatedAt })
        }

        val manager = AuthBackupManager(
            quarkDao = quarkDao,
            ucDao = noopDao(),
            xunleiDao = noopDao(),
            baiduDao = noopDao(),
            c139Dao = noopDao(),
            pan123Dao = noopDao(),
            pan115Dao = noopDao(),
            guangyaDao = noopDao(),
            ilanzouDao = noopDao(),
            lanzouDao = noopDao(),
        )

        val backupJson = """
            {
              "app": "yunget_auth_backup",
              "version": 1,
              "accounts": [
                {"platform": "quark", "cookie": "fresh_backup_cookie", "nickname": "恢复账号", "updatedAt": 1000}
              ]
            }
        """.trimIndent()

        val restored = manager.importJson(backupJson)
        assertEquals(1, restored)

        val primary = quarkDao.getAccount()!!
        assertEquals("fresh_backup_cookie", primary.cookie)
        assertTrue(primary.isPrimary)
        assertFalse(quarkDao.getById("uuid-stale-primary")!!.isPrimary)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> noopDao(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Int::class.javaPrimitiveType -> 0
            Boolean::class.javaPrimitiveType -> false
            else -> null
        }
    } as T
}
