/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
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
import kotlinx.coroutines.flow.Flow

/** [PluginSourceEntity] 的读写。 */
@Dao
interface PluginSourceDao {

    @Query("SELECT * FROM plugin_source ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<PluginSourceEntity>>

    @Query("SELECT * FROM plugin_source WHERE id = :id")
    suspend fun getById(id: String): PluginSourceEntity?

    // ---------------------------------------------------------------- 阻塞版本（检查更新用）
    //
    // 检查更新要先拿到一个**确定**的启用源列表，再去并行拉索引。用 suspend 版本的话
    // "读列表"与"据此拉取"之间会横跨一次调度，期间用户可能刚关掉某个源。
    // 与 PluginInstalledDao 里的两个阻塞查询同一理由：调用方必须已在 IO 线程上。

    @Query("SELECT * FROM plugin_source WHERE enabled = 1 ORDER BY addedAt DESC")
    fun getEnabledBlocking(): List<PluginSourceEntity>

    /** 与插件表一致：同一个 id（同一个索引地址）重复添加按覆盖处理。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: PluginSourceEntity)

    /** 拉取成功：记录时间戳（拉取失败不写，保证「从未成功拉取」与「拉过但失败」可区分） */
    @Query("UPDATE plugin_source SET lastFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateFetchedAt(id: String, fetchedAt: Long)

    @Query("UPDATE plugin_source SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM plugin_source WHERE id = :id")
    suspend fun delete(id: String)
}
