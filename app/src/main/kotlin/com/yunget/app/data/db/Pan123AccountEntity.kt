package com.yunget.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 123 云盘登录凭证（JWT token 落库，后续 API 请求携带 Authorization: Bearer <token>）。
 * 依据《123网盘API文档_面向Agent.md》§3.3：凭证形态为 JWT（Bearer Token），由登录接口 data.token 返回；
 * JWT exp 约 90 天后过期，token 失效（code 非 0 或 401）时重新走登录。
 */
@Entity(tableName = "pan123_account")
data class Pan123AccountEntity(
    @PrimaryKey
    val id: String = "pan123",
    /** Bearer JWT（ResolveViewModel.currentCredential 返回，作为 repository 的 cookie 参数） */
    val accessToken: String = "",
    /** 登录账号（手机号，展示用） */
    val account: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)