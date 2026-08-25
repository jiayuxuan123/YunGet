package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface C139AccountDao {

    @Query("SELECT * FROM c139_account WHERE id = 'c139'")
    fun observeAccount(): Flow<C139AccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: C139AccountEntity)

    @Query("SELECT * FROM c139_account WHERE id = 'c139'")
    suspend fun getAccount(): C139AccountEntity?

    @Query("DELETE FROM c139_account WHERE id = 'c139'")
    suspend fun clear()
}
