package com.yunget.app.data.repository

import com.yunget.app.data.db.XunleiAccountDao
import com.yunget.app.data.db.XunleiAccountEntity
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.XunleiConstants
import com.yunget.app.data.network.XunleiWebCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class XunleiSessionRefreshTest {

    @Test
    fun fallsBackToWebAuthTypeForLegacyBackupAndReusesRecentRefreshForConcurrentCallers() = runBlocking {
        val dao = InMemoryXunleiDao(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = "old_access",
                refreshToken = "old_refresh",
                deviceId = "dev_123",
                authType = "",
                nickname = "legacy",
            )
        )
        var webCalls = 0
        var appCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val isWeb = req.header("X-Client-Id") == XunleiWebCredential.CLIENT_ID
                val body = if (isWeb) {
                    webCalls++
                    """{"access_token":"new_access","refresh_token":"new_refresh"}"""
                } else {
                    appCalls++
                    """{"error":"invalid_client"}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isWeb) 200 else 400)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()
        val api = XunleiApi(clientProvider = { client })
        val repo = XunleiAccountRepository(dao, api)

        val first = repo.refreshSession("old_access")
        assertNotNull(first)
        assertEquals("new_access" to "new_refresh", first)
        assertEquals(1, appCalls)
        assertEquals(1, webCalls)

        val stored = dao.getAccount()!!
        assertEquals("new_access", stored.accessToken)
        assertEquals("new_refresh", stored.refreshToken)
        assertEquals(XunleiWebCredential.AUTH_TYPE, stored.authType)

        // 第二个并发/延迟到达的调用方仍持旧 accessToken，应直接复用刚刷出的结果而非报错
        val second = repo.refreshSession("old_access")
        assertEquals(first, second)
        assertEquals(1, webCalls)
    }

    private class InMemoryXunleiDao(initial: XunleiAccountEntity) : XunleiAccountDao {
        private val state = MutableStateFlow(listOf(initial))

        override fun observeAccount(): Flow<XunleiAccountEntity?> = state.map { sorted(it).firstOrNull() }
        override fun observeAll(): Flow<List<XunleiAccountEntity>> = state.map { sorted(it) }
        override suspend fun upsert(account: XunleiAccountEntity) {
            state.value = state.value.filterNot { it.id == account.id } + account
        }
        override suspend fun getAccount(): XunleiAccountEntity? = sorted(state.value).firstOrNull()
        override suspend fun getById(id: String): XunleiAccountEntity? = state.value.firstOrNull { it.id == id }
        override suspend fun replaceTokens(
            id: String, previousAccess: String, previousRefresh: String, access: String, refresh: String, now: Long,
        ): Int {
            val existing = getById(id) ?: return 0
            if (existing.accessToken != previousAccess || existing.refreshToken != previousRefresh) return 0
            upsert(existing.copy(accessToken = access, refreshToken = refresh, updatedAt = now))
            return 1
        }
        override suspend fun count(): Int = state.value.size
        override suspend fun clear() { state.value = emptyList() }
        override suspend fun deleteById(id: String) { state.value = state.value.filterNot { it.id == id } }
        override suspend fun updateRemark(id: String, remark: String) = Unit
        override suspend fun demoteAll() { state.value = state.value.map { it.copy(isPrimary = false) } }
        override suspend fun promote(id: String) { state.value = state.value.map { if (it.id == id) it.copy(isPrimary = true) else it } }
        override suspend fun promoteLatest() = Unit

        private fun sorted(list: List<XunleiAccountEntity>) =
            list.sortedWith(compareByDescending<XunleiAccountEntity> { it.isPrimary }.thenByDescending { it.updatedAt })
    }
}
