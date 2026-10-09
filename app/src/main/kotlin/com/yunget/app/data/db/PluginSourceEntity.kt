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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 插件源（市场索引源，Room 持久化）。
 *
 * 一个插件源就是一个返回 `plugins.json` 的地址（如 GitHub raw 上的索引文件）。
 * 本轮只做「登记 + 拉取并缓存索引」：**不自动安装**里面的任何脚本，
 * 索引内容缓存在应用私有目录（`filesDir/plugins/sources/<id>.json`），
 * 由上层 UI 展示后再逐个走 [PluginInstalledEntity] 那条安装链路。
 *
 * 表结构由迁移 `MIGRATION_19_20` 建立；字段与迁移 SQL 必须逐字对应
 * （见 `DatabaseMigrationContractTest`）。
 */
@Entity(tableName = "plugin_source")
data class PluginSourceEntity(
    /** 源 id（由索引地址摘要派生，同一个 url 重复添加会得到同一个 id） */
    @PrimaryKey
    @ColumnInfo(defaultValue = "''")
    val id: String,
    /** 展示名（用户可改） */
    @ColumnInfo(defaultValue = "''")
    val displayName: String = "",
    /** 索引地址（`https://.../plugins.json`） */
    @ColumnInfo(defaultValue = "''")
    val indexUrl: String = "",
    /** 该源的信任级别，取值同 [PluginInstalledEntity.TRUST_LEVELS]；安装时写入插件记录 */
    @ColumnInfo(defaultValue = "''")
    val trustLevel: String = "",
    /** 添加时间 */
    @ColumnInfo(defaultValue = "0")
    val addedAt: Long = 0L,
    /** 最近一次成功拉取索引的时间；0 = 从未拉取 */
    @ColumnInfo(defaultValue = "0")
    val lastFetchedAt: Long = 0L,
    /** 是否启用（关掉后不参与「检查更新」，但保留登记） */
    @ColumnInfo(defaultValue = "0")
    val enabled: Boolean = true,
)
