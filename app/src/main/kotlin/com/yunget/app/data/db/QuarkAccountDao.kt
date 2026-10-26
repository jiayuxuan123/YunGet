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

package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface QuarkAccountDao {

    /**
     * 观察**主账号**。
     *
     * 多账号之后这张表可能有多行，而"当前使用哪个账号"由 `isPrimary` 决定。
     * 排序规则：主账号优先，其次按更新时间倒序 —— 保证即使数据里意外出现
     * 两条 `isPrimary=1`（并发写入），返回的也是确定的那一条，而不是随机的。
     */
    @Query(
        "SELECT * FROM quark_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1"
    )
    fun observeAccount(): Flow<QuarkAccountEntity?>

    /** 观察全部账号（多账号列表用）。主账号排最前。 */
    @Query("SELECT * FROM quark_account ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAll(): Flow<List<QuarkAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: QuarkAccountEntity)

    /** 同 [observeAccount]：取主账号。 */
    @Query(
        "SELECT * FROM quark_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1"
    )
    suspend fun getAccount(): QuarkAccountEntity?

    @Query("SELECT * FROM quark_account WHERE id = :id")
    suspend fun getById(id: String): QuarkAccountEntity?

    /** 该平台现有账号数（登录时判断"这是第一个账号还是再加一个"）。 */
    @Query("SELECT COUNT(*) FROM quark_account")
    suspend fun count(): Int

    /** 清空该平台所有账号（"退出全部"用）。 */
    @Query("DELETE FROM quark_account")
    suspend fun clear()

    /** 只删一个账号。 */
    @Query("DELETE FROM quark_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 更新备注。空串 = 清掉备注、界面回退显示昵称。 */
    @Query("UPDATE quark_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    /**
     * 把某个账号设为主账号，其余降为普通账号。
     *
     * 两条语句必须一起执行（同一个 `@Transaction`）：
     * 只做第一条会短暂出现"零个主账号"，那时任何读主账号的请求都会拿到 null。
     */
    @Transaction
    suspend fun setPrimary(id: String) {
        demoteAll()
        promote(id)
    }

    @Query("UPDATE quark_account SET isPrimary = 0")
    suspend fun demoteAll()

    @Query("UPDATE quark_account SET isPrimary = 1 WHERE id = :id")
    suspend fun promote(id: String)
}