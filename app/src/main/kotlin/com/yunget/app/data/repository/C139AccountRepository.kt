package com.yunget.app.data.repository

import android.webkit.CookieManager
import com.yunget.app.data.db.C139AccountDao
import com.yunget.app.data.db.C139AccountEntity
import com.yunget.app.data.network.C139Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 139 网盘账号数据仓库：Room 持久化 + Cookie 校验。
 * 登录态 = mail.10086.cn 的 Os_SSo_Sid + RMKEY（WebView 登录后提取）。
 */
class C139AccountRepository(
    private val dao: C139AccountDao
) {

    fun observeAccount(): Flow<C139AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): C139AccountEntity? = dao.getAccount()

    /** 退出登录：清理 WebView Cookie + 清除本地记录 */
    suspend fun logoutC139() {
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        dao.clear()
    }

    /**
     * 校验 139 Cookie 有效性（Os_SSo_Sid+RMKEY 或 authorization 任一成立）；
     * 有效则提取账号与 authorization 并落库，返回 true。
     */
    suspend fun saveC139Account(cookie: String): Boolean {
        if (!C139Constants.isValidCookie(cookie)) return false
        val nickname = C139Constants.extractAccount(cookie) ?: "139用户"
        val authorization = C139Constants.extractAuthorization(cookie).orEmpty()
        dao.upsert(
            C139AccountEntity(
                id = "c139",
                cookie = cookie,
                nickname = nickname,
                authorization = authorization
            )
        )
        return true
    }
}