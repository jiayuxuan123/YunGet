package com.yunget.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuarkAccountDao {

    @Query("SELECT * FROM quark_account WHERE id = 'quark'")
    fun observeAccount(): Flow<QuarkAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: QuarkAccountEntity)

    @Query("SELECT * FROM quark_account WHERE id = 'quark'")
    suspend fun getAccount(): QuarkAccountEntity?

    @Query("DELETE FROM quark_account WHERE id = 'quark'")
    suspend fun clear()
}