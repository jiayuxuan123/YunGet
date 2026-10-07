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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 迅雷网盘登录凭证（access_token 落库，pan API 请求携带 Bearer）。
 */
@Entity(tableName = "xunlei_account")
data class XunleiAccountEntity(
    @PrimaryKey
    val id: String = "xunlei",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    val nickname: String = "",
    /**
     * 登录方式：空串 = App 通道（账号密码 / 短信，用 App OAuth 客户端刷新）；
     * `"web"` = 网页登录，刷新必须换成网页 OAuth 客户端
     * （否则刷新必失败，用户会看到「刚登录就过期」）。
     *
     * 【必须与迁移 `MIGRATION_18_19` 保持一致】该迁移会给老库加这一列，
     * 若实体里没有对应字段，Room 打开库时校验表结构**多出一列**就会直接抛
     * `Migration didn't properly handle`，**应用启动即崩**（2.6.17 的真实事故）。
     * 加列时"实体 + 迁移 + 版本号"三者必须同时改，缺一不可。
     */
    @ColumnInfo(defaultValue = "''")
    val authType: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)