package com.yunget.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class, XunleiAccountEntity::class, BaiduAccountEntity::class, C139AccountEntity::class, Pan123AccountEntity::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun quarkAccountDao(): QuarkAccountDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun ucAccountDao(): UCAccountDao

    abstract fun xunleiAccountDao(): XunleiAccountDao

    abstract fun baiduAccountDao(): BaiduAccountDao

    abstract fun c139AccountDao(): C139AccountDao

    abstract fun pan123AccountDao(): Pan123AccountDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yunget.db"
                )
                    .addMigrations(MIGRATION_9_10)
                    // 早期开发版（1-8）无可靠 schema；从 v9 起必须保留凭证和下载任务
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN requestHeadersJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE download_task ADD COLUMN chunkCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN plannedTotalSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN cleanupId TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
