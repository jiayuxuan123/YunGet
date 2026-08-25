package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface Pan123AccountDao {

    @Query("SELECT * FROM pan123_account WHERE id = 'pan123'")
    fun observeAccount(): Flow<Pan123AccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: Pan123AccountEntity)

    @Query("SELECT * FROM pan123_account WHERE id = 'pan123'")
    suspend fun getAccount(): Pan123AccountEntity?

    @Query("DELETE FROM pan123_account WHERE id = 'pan123'")
    suspend fun clear()
}