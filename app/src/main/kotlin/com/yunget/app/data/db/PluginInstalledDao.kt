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

/** [PluginInstalledEntity] 的读写（无凭证内容，不需要加密装饰器）。 */
@Dao
interface PluginInstalledDao {

    @Query("SELECT * FROM plugin_installed ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<PluginInstalledEntity>>

    /** 只观察「启用」的插件（真正参与加载的那批） */
    @Query("SELECT * FROM plugin_installed WHERE enabled = 1 ORDER BY installedAt DESC")
    fun observeEnabled(): Flow<List<PluginInstalledEntity>>

    @Query("SELECT * FROM plugin_installed WHERE id = :id")
    suspend fun getById(id: String): PluginInstalledEntity?

    // ---------------------------------------------------------------- 阻塞版本（只给装载流程用）
    //
    // 装载插件时（PluginRuntime.start/load）必须先拿到一个**确定**的已装列表再决定加载谁，
    // 而那段代码已经在 IO 线程上、并且被一把 mutex 保护着。用 suspend 版本会让"读快照"和
    // "据此装载"之间横跨一次调度，期间用户的启停操作可能已经改了库 —— 快照就不再是快照。
    // 所以这里给两个阻塞查询；调用方必须已在 IO 线程上（PluginRuntime 保证了）。
    // 同样的查询写两遍看着冗余，但 Room 不允许同一个函数既 suspend 又阻塞。

    @Query("SELECT * FROM plugin_installed WHERE id = :id")
    fun getByIdBlocking(id: String): PluginInstalledEntity?

    @Query("SELECT * FROM plugin_installed ORDER BY installedAt DESC")
    fun getAllBlocking(): List<PluginInstalledEntity>

    /**
     * 安装 / 更新：同一个 id 再次导入按**覆盖**处理（REPLACE），
     * 否则重复导入同一个插件会因主键冲突直接抛异常。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginInstalledEntity)

    /** 启用 / 停用；同时推进 updatedAt */
    @Query("UPDATE plugin_installed SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean, updatedAt: Long)

    /** 记录最近一次失败原因（供列表展示"为什么没加载"） */
    @Query("UPDATE plugin_installed SET lastError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLastError(id: String, error: String, updatedAt: Long)

    /** 同 [updateLastError]，给已在 IO 线程上的装载流程用（见上方说明）。 */
    @Query("UPDATE plugin_installed SET lastError = :error, updatedAt = :updatedAt WHERE id = :id")
    fun updateLastErrorBlocking(id: String, error: String, updatedAt: Long)

    @Query("DELETE FROM plugin_installed WHERE id = :id")
    suspend fun delete(id: String)
}
