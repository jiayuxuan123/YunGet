package com.yunget.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 139 网盘（和彩云）登录凭证（mail.10086.cn / yun.139.com cookie 落库，后续 API 请求携带）。
 * @param authorization 网页版直接下发的 Authorization（§3.5.5，形如 "Basic cGM6..."），解析时直接用
 */
@Entity(tableName = "c139_account")
data class C139AccountEntity(
    @PrimaryKey
    val id: String = "c139",
    val cookie: String = "",
    val nickname: String = "",
    val authorization: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
