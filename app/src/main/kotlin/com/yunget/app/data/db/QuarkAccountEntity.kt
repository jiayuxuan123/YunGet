package com.yunget.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 夸克网盘登录凭证（cookie 落库，后续所有 API 请求携带）。
 */
@Entity(tableName = "quark_account")
data class QuarkAccountEntity(
    @PrimaryKey
    val id: String = "quark",
    val cookie: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)