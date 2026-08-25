package com.yunget.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * UC 网盘登录凭证（cookie 落库，后续所有 API 请求携带）。
 */
@Entity(tableName = "uc_account")
data class UCAccountEntity(
    @PrimaryKey
    val id: String = "uc",
    val cookie: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)