package com.yunget.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yunget.app.data.security.AndroidKeystoreCredentialCipher
import com.yunget.app.data.security.CredentialCipher

@Database(
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class, XunleiAccountEntity::class, BaiduAccountEntity::class, C139AccountEntity::class, Pan123AccountEntity::class, BookmarkEntity::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    // ---------- Room 直接生成的 DAO（**明文**，仅供 SecureAccountDaos 包装）----------
    //
    // 带 raw 前缀是刻意的：业务代码一律使用下面的包装版本，
    // 否则会绕过加密、把凭证以明文写进数据库。
    protected abstract fun rawQuarkAccountDao(): QuarkAccountDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    protected abstract fun rawUcAccountDao(): UCAccountDao

    protected abstract fun rawXunleiAccountDao(): XunleiAccountDao

    protected abstract fun rawBaiduAccountDao(): BaiduAccountDao

    protected abstract fun rawC139AccountDao(): C139AccountDao

    protected abstract fun rawPan123AccountDao(): Pan123AccountDao

    /** 网盘链接收藏（无凭证内容，无需加密装饰器）。 */
    abstract fun bookmarkDao(): BookmarkDao

    /** 凭证加密器，由 [get] 在构造后注入。 */
    private lateinit var credentialCipher: CredentialCipher

    // ---------- 对外 DAO：读写自动加解密 ----------
    //
    // 实现见 SecureAccountDaos（取自上游 YunX 的安全加固 PR #53）：
    //  - 落库前加密（AES-GCM，密钥存 Android Keystore 且不可导出）
    //  - 读取后解密，且解密在 Dispatchers.IO —— Keystore 是 Binder IPC（单次 30~75ms），
    //    在主线程解密会让网盘页并发刷新时整应用掉帧
    //  - **平滑迁移**：读到明文自动加密回写，老用户无需重新登录
    //  - 解密失败则清空该条凭证：宁可让用户重新登录，也不留下无法使用的脏数据
    fun quarkAccountDao(): QuarkAccountDao = SecureAccountDaos.quark(rawQuarkAccountDao(), credentialCipher)

    fun ucAccountDao(): UCAccountDao = SecureAccountDaos.uc(rawUcAccountDao(), credentialCipher)

    fun xunleiAccountDao(): XunleiAccountDao = SecureAccountDaos.xunlei(rawXunleiAccountDao(), credentialCipher)

    fun baiduAccountDao(): BaiduAccountDao = SecureAccountDaos.baidu(rawBaiduAccountDao(), credentialCipher)

    fun c139AccountDao(): C139AccountDao = SecureAccountDaos.c139(rawC139AccountDao(), credentialCipher)

    fun pan123AccountDao(): Pan123AccountDao = SecureAccountDaos.pan123(rawPan123AccountDao(), credentialCipher)

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
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                    // 早期开发版（1-8）无可靠 schema；从 v9 起必须保留凭证和下载任务
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                    .build()
                    .also {
                        it.credentialCipher = AndroidKeystoreCredentialCipher()
                        instance = it
                    }
            }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN requestHeadersJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE download_task ADD COLUMN chunkCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN plannedTotalSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN cleanupId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v11：新增网盘链接收藏表。
         *
         * 表结构与上游 YunX 一致（含 `IF NOT EXISTS`），便于两侧数据互通与后续同步。
         * 全部字段 `NOT NULL` + 默认值，避免历史行出现 null 导致读取崩溃。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmark` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`link` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`pwd` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`createTime` INTEGER NOT NULL)"
                )
            }
        }
    }
}
