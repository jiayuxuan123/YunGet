package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BaiduAccountDao {

    @Query("SELECT * FROM baidu_account WHERE id = 'baidu'")
    fun observeAccount(): Flow<BaiduAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: BaiduAccountEntity)

    @Query("SELECT * FROM baidu_account WHERE id = 'baidu'")
    suspend fun getAccount(): BaiduAccountEntity?

    @Query("DELETE FROM baidu_account WHERE id = 'baidu'")
    suspend fun clear()
}