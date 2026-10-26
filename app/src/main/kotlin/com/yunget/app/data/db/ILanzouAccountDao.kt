/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * 本文件取自上游 YunX (https://github.com/CYQawa/YunX)
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
interface ILanzouAccountDao {

    @Query(
        "SELECT * FROM ilanzou_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1"
    )
    fun observeAccount(): Flow<ILanzouAccountEntity?>

    /** 观察全部账号（多账号列表）。主账号排最前。 */
    @Query("SELECT * FROM ilanzou_account ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ILanzouAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: ILanzouAccountEntity)

    @Query(
        "SELECT * FROM ilanzou_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1"
    )
    suspend fun getAccount(): ILanzouAccountEntity?

    @Query("SELECT * FROM ilanzou_account WHERE id = :id")
    suspend fun getById(id: String): ILanzouAccountEntity?

    /** 该平台现有账号数。 */
    @Query("SELECT COUNT(*) FROM ilanzou_account")
    suspend fun count(): Int

    @Query("DELETE FROM ilanzou_account")
    suspend fun clear()

    /** 只删一个账号。 */
    @Query("DELETE FROM ilanzou_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 更新备注。空串 = 清掉备注、界面回退显示昵称。 */
    @Query("UPDATE ilanzou_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    /**
     * 把某个账号设为主账号，其余降为普通账号。
     *
     * 两条语句必须同一事务：只做第一条会短暂出现"零个主账号"，
     * 那时任何读主账号的请求都会拿到 null。
     */
    @Transaction
    suspend fun setPrimary(id: String) {
        demoteAll()
        promote(id)
    }

    @Query("UPDATE ilanzou_account SET isPrimary = 0")
    suspend fun demoteAll()

    @Query("UPDATE ilanzou_account SET isPrimary = 1 WHERE id = :id")
    suspend fun promote(id: String)

    /**
     * 把"更新时间最新的那个账号"升为主账号。
     *
     * 用于「删掉主账号后还剩其它账号」：不选一个上来，该平台就没有主账号了。
     * 选最新的而不是最旧的 —— 多账号场景下最近登录的通常正是用户在用的那个。
     */
    @Query(
        "UPDATE ilanzou_account SET isPrimary = 1 WHERE id = " +
            "(SELECT id FROM ilanzou_account ORDER BY updatedAt DESC LIMIT 1)"
    )
    suspend fun promoteLatest()

}
