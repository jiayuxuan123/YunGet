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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 115 网盘登录凭证（115.com cookie 落库，后续 API 请求携带）。
 *
 * 115 的登录态完全由 Cookie 表达（UID/CID/SEID/KID 四个键）：
 * - `UID` 同时也是提取分享时的 `user_id`（`share/snap` 的请求参数），缺它连分享都打不开；
 * - `CID`/`SEID`/`KID` 是个人盘与转存接口的鉴权串；
 * - 下载直链还要求请求头带上这份 Cookie（115 CDN 无 Cookie 会 403）。
 */
@Entity(tableName = "pan115_account")
data class Pan115AccountEntity(
    @PrimaryKey
    val id: String = "pan115",
    val cookie: String = "",
    val nickname: String = "",
    /**
     * 用户自定义备注（可空）。用于区分同一个平台下的多个账号，
     * 或在只登录一个账号时给一个"能认出来"的名字。
     */
    val remark: String = "",
    
    /**
     * 是否该平台的主账号。
     *
     * 需要**单一身份**的操作（列目录、转存、上传、创建分享）走主账号；
     * 同平台应当只有一行为 true。目前每个平台只有一行账号，
     * 但字段先立起来，后续接入多账号时不必再迁移一次。
     */
    val isPrimary: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
