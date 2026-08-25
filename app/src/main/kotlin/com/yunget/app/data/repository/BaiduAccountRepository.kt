package com.yunget.app.data.repository

import android.webkit.CookieManager
import com.yunget.app.data.db.BaiduAccountDao
import com.yunget.app.data.db.BaiduAccountEntity
import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.BaiduConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 百度账号数据仓库：Room 持久化 + 网络验证（gettemplatevariable 拿昵称）。
 */
class BaiduAccountRepository(
    private val dao: BaiduAccountDao,
    private val api: BaiduApi
) {

    fun observeAccount(): Flow<BaiduAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): BaiduAccountEntity? = dao.getAccount()

    /** 退出登录：清理 WebView Cookie + 清除本地记录 */
    suspend fun logoutBaidu() {
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        dao.clear()
    }

    /**
     * 校验 Cookie 有效性（需含 BDUSS）；有效则拉取昵称并落库，返回 true；无效返回 false。
     */
    suspend fun saveBaiduAccount(cookie: String): Boolean {
        if (!BaiduConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "百度用户"
        dao.upsert(
            BaiduAccountEntity(
                id = "baidu",
                cookie = cookie,
                nickname = nickname
            )
        )
        return true
    }
}