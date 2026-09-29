package com.yunget.app.data.repository

import com.yunget.app.data.db.Pan123AccountDao
import com.yunget.app.data.db.Pan123AccountEntity
import com.yunget.app.data.network.Pan123Api
import kotlinx.coroutines.flow.Flow

/**
 * 123 云盘账号仓库。
 *
 * **凭证**：Bearer JWT（约 90 天过期），无 refresh 接口，失效后需重新登录。
 *
 * **登录方式**：以**网页登录**为主（用户在官方页面 `yun.123pan.cn` 完成认证，
 * App 从 localStorage 的 `authorToken` 取结果凭证），账号密码接口保留作兼容兜底。
 *
 * 【为什么改用网页登录】账号密码登录需向 `user.123pan.cn/api/user/sign_in` 提交
 * **明文密码**，这一行为容易被官方判定为异常客户端而触发风控。
 * 网页登录让 App 不再接触密码，且取到的 token 与旧接口的 `data.token`
 * **同源同形**（都是 Bearer JWT），后续校验与业务请求无需改动。
 */
class Pan123AccountRepository(
    private val dao: Pan123AccountDao,
    private val api: Pan123Api
) {

    fun observeAccount(): Flow<Pan123AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan123AccountEntity? = dao.getAccount()

    /** 账号密码登录（POST user.123pan.cn/api/user/sign_in，无需签名）→ token 落库，返回 true */
    suspend fun login(account: String, password: String): Boolean {
        val token = api.login(account.trim(), password)
        if (token.isBlank()) return false
        val nickname = api.fetchNickname(token)?.takeIf { it.isNotBlank() } ?: account.trim()
        dao.upsert(
            Pan123AccountEntity(
                id = "pan123",
                accessToken = token,
                account = account.trim(),
                nickname = nickname
            )
        )
        return true
    }

    /**
     * 保存网页登录得到的 token（从 `yun.123pan.cn` 的 localStorage `authorToken` 提取）。
     *
     * 必须先用 `user/info` 校验再落库：localStorage 里可能残留过期值，
     * 或页面尚未完成登录时读到无关内容 —— 直接落库会让用户以为登录成功、实际全部请求 401。
     *
     * @return 校验通过并已落库返回 true；token 无效返回 false（不落库）
     */
    suspend fun saveToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return false
        val nickname = api.fetchNickname(trimmed)?.takeIf { it.isNotBlank() } ?: return false
        dao.upsert(
            Pan123AccountEntity(
                id = "pan123",
                accessToken = trimmed,
                account = nickname,
                nickname = nickname
            )
        )
        return true
    }

    /** 校验当前 token 是否仍有效（失败自动清库，下次重新登录） */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = api.fetchNickname(acc.accessToken) != null
        if (!ok) dao.clear()
        return ok
    }

    /**
     * 退出登录。
     *
     * 【为什么必须同时清 WebView 存储】网页登录的凭证存在 WebView 的
     * localStorage（`authorToken`）与 Cookie 里。只清数据库的话，
     * 用户重新打开登录页时网页仍是登录态，会被自动检测逻辑**立刻登回旧账号** ——
     * 表现为「退出登录无效」或「换了账号却还是旧账号」。
     */
    suspend fun logout() {
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        dao.clear()
    }
}