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
 * 已安装的 JS 插件（Room 持久化）。
 *
 * 只存**元数据与脚本位置**，脚本本体落在应用私有目录（`filesDir/plugins/scripts/`），
 * 库里的 [scriptPath] 是绝对路径。这样卸载/更新脚本不必把正文塞进数据库，
 * 也避免把用户脚本当作普通文本字段到处传递。
 *
 * 表结构由迁移 `MIGRATION_19_20` 建立。**每个字段都写了
 * `@ColumnInfo(defaultValue = ...)` 且与迁移 SQL 逐字对应** ——
 * 实体与迁移不一致会让 Room 在打开库时抛 `Migration didn't properly handle`，
 * 表现为升级用户启动即崩（2.6.17 的真实事故，见 `DatabaseMigrationContractTest`）。
 */
@Entity(tableName = "plugin_installed")
data class PluginInstalledEntity(
    /** 插件 id（如 `parser.example`）。取值规则见 `PluginIds.validate`，同时也是脚本文件名前缀。 */
    @PrimaryKey
    @ColumnInfo(defaultValue = "''")
    val id: String,
    /** 展示名：优先取脚本 `defineMeta({name})`，缺省回退为 id */
    @ColumnInfo(defaultValue = "''")
    val name: String = "",
    /** 插件自报版本（`defineMeta({version})`）；脚本没写就是空串 */
    @ColumnInfo(defaultValue = "''")
    val version: String = "",
    /** 来源：SAF 文件路径 / `inline:<文件名>` / 市场条目 url */
    @ColumnInfo(defaultValue = "''")
    val sourceUri: String = "",
    /** 来源类别：[SOURCE_FILE] / [SOURCE_PASTE] / [SOURCE_MARKET] */
    @ColumnInfo(defaultValue = "''")
    val sourceKind: String = "",
    /** 清单原文（`turbodl-plugin.json`）。粘贴/文件导入通常为空串，市场安装时才有 */
    @ColumnInfo(defaultValue = "''")
    val manifestJson: String = "",
    /** 脚本文件在应用私有目录的**绝对路径** */
    @ColumnInfo(defaultValue = "''")
    val scriptPath: String = "",
    /** 脚本内容摘要（sha256 全量十六进制）。市场更新时据此判断脚本是否真的变了 */
    @ColumnInfo(defaultValue = "''")
    val scriptSha256: String = "",
    /** 用户是否启用（关掉 = 不加载，但保留脚本与配置） */
    @ColumnInfo(defaultValue = "0")
    val enabled: Boolean = true,
    /** 脚本声明的能力（`plugin.requires({permissions})`），逗号分隔；空串 = 未声明 */
    @ColumnInfo(defaultValue = "''")
    val declaredPermissions: String = "",
    /** 信任级别：[TRUST_OFFICIAL] / [TRUST_VERIFIED] / [TRUST_COMMUNITY] / [TRUST_UNTRUSTED] */
    @ColumnInfo(defaultValue = "''")
    val trustLevel: String = "",
    /** 首次安装时间 */
    @ColumnInfo(defaultValue = "0")
    val installedAt: Long = 0L,
    /** 最近一次变更时间（导入/启停/更新） */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L,
    /** 最近一次加载/校验失败的原因，成功或未运行过为空串 */
    @ColumnInfo(defaultValue = "''")
    val lastError: String = "",
) {
    companion object {
        /** 来源类别：从 SAF 文件导入 */
        const val SOURCE_FILE = "file"

        /** 来源类别：粘贴脚本文本导入 */
        const val SOURCE_PASTE = "paste"

        /** 来源类别：从插件源（市场）安装 */
        const val SOURCE_MARKET = "market"

        /** 信任级别：官方内置 */
        const val TRUST_OFFICIAL = "official"

        /** 信任级别：已核验（来源可追溯，签名/摘要已确认） */
        const val TRUST_VERIFIED = "verified"

        /** 信任级别：社区来源（默认，未做任何核验） */
        const val TRUST_COMMUNITY = "community"

        /** 信任级别：不可信（用户明确导入的来路不明脚本，加载前要额外提示） */
        const val TRUST_UNTRUSTED = "untrusted"

        /** 全部合法信任级别（写库前归一化用，非法值一律按 [TRUST_COMMUNITY] 处理） */
        val TRUST_LEVELS = listOf(TRUST_OFFICIAL, TRUST_VERIFIED, TRUST_COMMUNITY, TRUST_UNTRUSTED)
    }
}
