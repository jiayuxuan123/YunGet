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

    /** 观察全部账号（多账号列表）。主账号排最前。 */
    fun observeAll() = dao.observeAll()

    /** 切换主账号（同一事务内先降后升）。 */
    suspend fun setPrimary(id: String) = dao.setPrimary(id)
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
        if (!wasPrimary) return
        if (dao.getAccount() != null) return      // 还有别的主账号，无需处理
        dao.demoteAll()
        dao.promoteLatest()
    }


    /** 修改账号备注（空串 = 清除）。备注不是凭证，不参与加解密。 */
    suspend fun updateRemark(id: String, remark: String) = dao.updateRemark(id, remark)

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