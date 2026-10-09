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
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class, XunleiAccountEntity::class, BaiduAccountEntity::class, C139AccountEntity::class, Pan123AccountEntity::class, BookmarkEntity::class, Pan115AccountEntity::class, GuangYaAccountEntity::class, ILanzouAccountEntity::class, LanzouAccountEntity::class, PluginInstalledEntity::class, PluginSourceEntity::class],
    version = 20,
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

    protected abstract fun rawPan115AccountDao(): Pan115AccountDao

    protected abstract fun rawGuangYaAccountDao(): GuangYaAccountDao

    protected abstract fun rawILanzouAccountDao(): ILanzouAccountDao

    protected abstract fun rawLanzouAccountDao(): LanzouAccountDao

    /** 网盘链接收藏（无凭证内容，无需加密装饰器）。 */
    abstract fun bookmarkDao(): BookmarkDao

    /** 已安装的 JS 插件（无凭证内容，无需加密装饰器）。 */
    abstract fun pluginInstalledDao(): PluginInstalledDao

    /** 插件源 / 市场索引源。 */
    abstract fun pluginSourceDao(): PluginSourceDao

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

    fun pan115AccountDao(): Pan115AccountDao = SecureAccountDaos.pan115(rawPan115AccountDao(), credentialCipher)

    fun guangYaAccountDao(): GuangYaAccountDao = SecureAccountDaos.guangYa(rawGuangYaAccountDao(), credentialCipher)

    fun iLanzouAccountDao(): ILanzouAccountDao = SecureAccountDaos.iLanzou(rawILanzouAccountDao(), credentialCipher)

    fun lanzouAccountDao(): LanzouAccountDao = SecureAccountDaos.lanzou(rawLanzouAccountDao(), credentialCipher)

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
                    .addMigrations(
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                    )
                    // 早期开发版（1-8）无可靠 schema；从 v9 起必须保留凭证和下载任务
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                    .build()
                    .also {
                        // 用**全进程唯一**实例（不是 new 一个）：密钥重建的通知
                        // （provisionedListener）只有构造者那一个实例能收到，
                        // 各 new 一份会让「密钥刚被重建」的判断在实例之间错位。
                        it.credentialCipher = AndroidKeystoreCredentialCipher.shared
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

        /**
         * v12：下载任务记录平均速度。
         *
         * 与上游 YunX 的迁移链**对齐版本号**：上游 v12 是这个字段、v13 才是收藏表，
         * 而本项目此前把收藏直接做在 v11。这里补齐 v12/v13 两跳，
         * 使后续版本号与上游一致 —— 否则同一个 `version = N` 在两边的表结构不同，
         * 将来若要合并或参考上游迁移会踩错。
         *
         * `addColumn` 对已存在的列会抛异常，故先探测。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("download_task", "avgSpeed")) {
                    db.execSQL("ALTER TABLE download_task ADD COLUMN avgSpeed INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /** v13：收藏表（本项目在 v11 已建，这里做存在性保护，老库走到这跳时不会重复建表）。 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
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

        /** v14：收藏支持「主页快捷方式」标记（0/1）；老收藏默认不在主页显示。 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("bookmark", "homePinned")) {
                    db.execSQL("ALTER TABLE bookmark ADD COLUMN homePinned INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /** v15：主页快捷方式色块的自定义文字；空串 = 自动取标题前几个字。 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("bookmark", "homeLabel")) {
                    db.execSQL("ALTER TABLE bookmark ADD COLUMN homeLabel TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /** v16：115 网盘登录凭证（Cookie 落库前在 SecureAccountDaos 里加密）。 */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pan115_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`cookie` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v17：下载任务记录外部引擎任务 ID（空串 = 内置分片下载器）。 */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("download_task", "engineTaskId")) {
                    db.execSQL(
                        "ALTER TABLE `download_task` ADD COLUMN `engineTaskId` TEXT NOT NULL DEFAULT ''"
                    )
                }
            }
        }

        /** v18：新增光鸭云盘 / 蓝奏云优享版 / 蓝奏云登录凭证表（落库前加密）。 */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `guangya_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`accessToken` TEXT NOT NULL, " +
                        "`refreshToken` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, " +
                        "`deviceSign` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ilanzou_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`appToken` TEXT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`password` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lanzou_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`cookie` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v19：迅雷登录方式标记（区分 App 通道与网页登录）。
         *
         * 两种登录方式拿到的 refresh token **必须用各自的 OAuth 客户端去刷新**，
         * 混用必然失败 —— 表现为「刚登录就提示过期」。
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("xunlei_account", "authType")) {
                    db.execSQL(
                        "ALTER TABLE `xunlei_account` ADD COLUMN `authType` TEXT NOT NULL DEFAULT ''"
                    )
                }
            }
        }

        /**
         * v20：插件管理两张表 —— 已安装插件 [PluginInstalledEntity] 与插件源 [PluginSourceEntity]。
         *
         * 都是**新建表**（不像 v18/v19 那样给老表加列），所以每列显式写 `NOT NULL` + 默认值：
         * 表一旦有历史行，加列缺默认值就会失败；这里的默认值同时要与实体上的
         * `@ColumnInfo(defaultValue = ...)` 逐字一致，否则 Room 打开库时校验表结构会抛
         * `Migration didn't properly handle`（本轮由 `DatabaseMigrationContractTest` 静态核对）。
         *
         * 脚本本体不入库：`plugin_installed.scriptPath` 存应用私有目录下的绝对路径。
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plugin_installed` (" +
                        "`id` TEXT NOT NULL DEFAULT '', " +
                        "`name` TEXT NOT NULL DEFAULT '', " +
                        "`version` TEXT NOT NULL DEFAULT '', " +
                        "`sourceUri` TEXT NOT NULL DEFAULT '', " +
                        "`sourceKind` TEXT NOT NULL DEFAULT '', " +
                        "`manifestJson` TEXT NOT NULL DEFAULT '', " +
                        "`scriptPath` TEXT NOT NULL DEFAULT '', " +
                        "`scriptSha256` TEXT NOT NULL DEFAULT '', " +
                        "`enabled` INTEGER NOT NULL DEFAULT 0, " +
                        "`declaredPermissions` TEXT NOT NULL DEFAULT '', " +
                        "`trustLevel` TEXT NOT NULL DEFAULT '', " +
                        "`installedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastError` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plugin_source` (" +
                        "`id` TEXT NOT NULL DEFAULT '', " +
                        "`displayName` TEXT NOT NULL DEFAULT '', " +
                        "`indexUrl` TEXT NOT NULL DEFAULT '', " +
                        "`trustLevel` TEXT NOT NULL DEFAULT '', " +
                        "`addedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastFetchedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`enabled` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** 列是否存在：迁移里加列前先探测，避免「重复加列」把升级路径炸掉。 */
        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            runCatching {
                query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
                    }
                }
                false
            }.getOrDefault(false)
    }
}
