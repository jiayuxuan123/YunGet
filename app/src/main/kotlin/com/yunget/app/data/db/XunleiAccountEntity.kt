package com.yunget.app.data.db

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
    val updatedAt: Long = System.currentTimeMillis()
)