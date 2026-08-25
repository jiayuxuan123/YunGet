package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XunleiAccountDao {

    @Query("SELECT * FROM xunlei_account WHERE id = 'xunlei'")
    fun observeAccount(): Flow<XunleiAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: XunleiAccountEntity)

    @Query("SELECT * FROM xunlei_account WHERE id = 'xunlei'")
    suspend fun getAccount(): XunleiAccountEntity?

    @Query("DELETE FROM xunlei_account WHERE id = 'xunlei'")
    suspend fun clear()
}