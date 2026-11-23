/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.repository

import kotlinx.coroutines.flow.first



import com.yunget.app.data.db.XunleiAccountDao
import com.yunget.app.data.db.XunleiAccountEntity
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.XunleiLoginStep
import com.yunget.app.data.network.XunleiWebCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 迅雷账号仓库：账号+密码登录（可能触发短信验证）→ 换 token 落库。
 */
class XunleiAccountRepository(
    private val dao: XunleiAccountDao,
    private val api: XunleiApi
) {

    fun observeAccount(): Flow<XunleiAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): XunleiAccountEntity? = dao.getAccount()

    /**
     * 修改账号备注（空串 = 清除）。
     *
     * 备注是多账号的基础字段：平台昵称常常是一串数字或与账号无关的网名，
     * 同一平台有多个账号时完全分不出谁是谁。
     */
    suspend fun updateRemark(id: String, remark: String) = dao.updateRemark(id, remark)

    /** 观察全部账号（多账号列表）。主账号排最前。 */
    fun observeAll() = dao.observeAll()

    /** 切换主账号（同一事务内先降后升）。 */
    suspend fun setPrimary(id: String) {
        dao.setPrimary(id)
        refreshLock.withLock { recentlyRefreshed.clear() }
        api.resetSessionCache()
    }
    /**
     * 从列表里移除一个账号。
     *
     * 删的若是主账号，必须把剩下的某一个升为主账号 —— 否则该平台就没有主账号，
     * 列目录/转存/上传全部失效。选"最新登录的那个"：多账号场景下
     * 最近登录的通常正是用户当下在用的。
     */
    suspend fun removeAccount(id: String) {
        val wasPrimary = dao.getById(id)?.isPrimary == true
        dao.deleteById(id)
        refreshLock.withLock { recentlyRefreshed.clear() }
        api.resetSessionCache()
        if (!wasPrimary) return
        if (dao.getAccount() != null) return      // 还有别的主账号，无需处理
        dao.demoteAll()
        dao.promoteLatest()
    }


    /** 账号密码登录；返回登录步骤（needSms=true 表示需短信验证，携带 smsCreditKey/smsToken） */
    suspend fun loginWithPassword(
        username: String,
        password: String
    ): XunleiLoginStep {
        // 必须用官方真实设备 ID（devicesign 配套），否则 userinfo_expired
        val deviceId = XunleiApi.newDeviceId()
        // 官方首次登录：v3/login 不带任何 creditkey/captcha，返回 review_panel(1007) 触发短信验证
        return api.loginWithPassword(username, password, deviceId)
    }

    /** 发送短信验证码（登录触发 review_panel 后调用） */
    suspend fun sendSms(mobile: String): XunleiLoginStep {
        val deviceId = XunleiApi.newDeviceId()
        return api.sendSms(mobile, deviceId)
    }

    /** 短信验证码登录并换取 token，成功落库返回 true */
    suspend fun loginWithSms(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String
    ): Boolean {
        val deviceId = XunleiApi.newDeviceId()
        val step = api.smsLogin(mobile, smsCode, creditKey, smsToken, deviceId)
        if (step.sessionId.isBlank()) return false

        // 换 token 前先 initCaptcha 拿 captcha_token（官方时序：smslogin → captcha/init → signin/token）。

        // userID 取 smslogin 响应里的真实值：首次登录时进程里没有已缓存的用户 ID，传空会拿降级 token

        val captchaToken = api.initCaptcha(deviceId, mobile, userId = step.userID) ?: ""

        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" }
            )
        )
        return true
    }

    /** 密码登录成功后用 sessionID 换 token 落库 */
    suspend fun finishLogin(
        step: XunleiLoginStep,
        username: String
    ): Boolean {
        if (step.sessionId.isBlank()) return false
        val deviceId = XunleiApi.newDeviceId()

        // 同 loginWithSms：meta.user_id 用 v3/login 响应里的 userID，不能等 currentUserId 缓存

        val captchaToken = api.initCaptcha(deviceId, username, userId = step.userID) ?: ""

        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" }
            )
        )
        return true
    }

    private val refreshLock = Mutex()
    // 并发请求同时拿同一过期 token 进来时，第二个请求复用刚刷出的结果，避免查不到旧 token 误报过期。
    private val recentlyRefreshed = LinkedHashMap<String, Pair<String, String>>(8, 0.75f, false)

    suspend fun refreshSession(token: String, requestDeviceId: String = ""): Pair<String, String>? =
        refreshLock.withLock {
            recentlyRefreshed[token]?.let { return@withLock it }
            val account = dao.observeAll().first().firstOrNull { it.accessToken == token } ?: return@withLock null
            if (account.refreshToken.isBlank()) return@withLock null
            val deviceId = account.deviceId.ifBlank { requestDeviceId }.ifBlank { XunleiApi.newDeviceId() }
            if (requestDeviceId.isNotBlank() && deviceId != requestDeviceId) return@withLock null
            val primaryAttempt = api.refreshToken(account.refreshToken, deviceId, account.authType)
            // 旧版备份未导出 authType：默认 App 客户端刷新失败时，自动回退尝试网页 OAuth 客户端。
            val (refreshed, resolvedAuthType) = if (primaryAttempt != null) {
                primaryAttempt to account.authType
            } else if (account.authType.isBlank()) {
                val webAttempt = api.refreshToken(account.refreshToken, deviceId, XunleiWebCredential.AUTH_TYPE)
                    ?: return@withLock null
                webAttempt to XunleiWebCredential.AUTH_TYPE
            } else {
                return@withLock null
            }
            val refresh = refreshed.second.ifBlank { account.refreshToken }
            val now = System.currentTimeMillis()
            val persisted = if (resolvedAuthType != account.authType || deviceId != account.deviceId) {
                val current = dao.getById(account.id)
                if (current != null && current.accessToken == account.accessToken && current.refreshToken == account.refreshToken) {
                    dao.upsert(
                        current.copy(
                            accessToken = refreshed.first,
                            refreshToken = refresh,
                            deviceId = deviceId,
                            authType = resolvedAuthType,
                            updatedAt = now,
                        )
                    )
                    true
                } else false
            } else {
                dao.replaceTokens(
                    account.id, account.accessToken, account.refreshToken, refreshed.first, refresh, now,
                ) == 1
            }
            if (!persisted) return@withLock null
            val result = refreshed.first to refresh
            if (recentlyRefreshed.size >= 8) {
                recentlyRefreshed.entries.firstOrNull()?.let { recentlyRefreshed.remove(it.key) }
            }
            recentlyRefreshed[token] = result
            result
        }

    suspend fun logout() {
        dao.clear()
        refreshLock.withLock { recentlyRefreshed.clear() }
        api.resetSessionCache()
    }

    suspend fun allCredentials(): List<String> = dao.observeAll().first().map { it.accessToken }
}