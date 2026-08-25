package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UCAccountDao {

    @Query("SELECT * FROM uc_account WHERE id = 'uc'")
    fun observeAccount(): Flow<UCAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: UCAccountEntity)

    @Query("SELECT * FROM uc_account WHERE id = 'uc'")
    suspend fun getAccount(): UCAccountEntity?

    @Query("DELETE FROM uc_account WHERE id = 'uc'")
    suspend fun clear()
}