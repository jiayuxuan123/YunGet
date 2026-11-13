package com.yunget.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Normalizes historical download schemas without clearing tasks or any account tables. */
internal object DownloadTaskTableRepair {
    private data class Column(val name: String, val type: String, val default: String?, val fallback: String)
    private val columns = listOf(
        Column("id", "INTEGER PRIMARY KEY AUTOINCREMENT", null, "NULL"),
        Column("url", "TEXT", null, "''"), Column("fileName", "TEXT", null, "''"),
        Column("totalSize", "INTEGER", null, "0"), Column("downloadedSize", "INTEGER", null, "0"),
        Column("status", "INTEGER", null, "2"), Column("errorMsg", "TEXT", null, "''"),
        Column("savePath", "TEXT", null, "''"), Column("requestHeadersJson", "TEXT", "'{}'", "'{}'"),
        Column("chunkCount", "INTEGER", "0", "0"), Column("plannedTotalSize", "INTEGER", "0", "0"),
        Column("cleanupId", "TEXT", "''", "''"), Column("engineTaskId", "TEXT", "''", "''"),
        Column("avgSpeed", "INTEGER", "0", "0"), Column("platform", "TEXT", "''", "''"),
        Column("fallbackUrl", "TEXT", "''", "''"), Column("altSourcesJson", "TEXT", "'[]'", "'[]'"),
        Column("mergeAudioUrl", "TEXT", "''", "''"), Column("mergeAudioHeadersJson", "TEXT", "'{}'", "'{}'"),
        Column("motionUrl", "TEXT", "''", "''"), Column("audioOnly", "INTEGER", "0", "0"),
        Column("createTime", "INTEGER", null, "0"), Column("sourceAccountId", "TEXT", "''", "''"),
        Column("refreshReferenceJson", "TEXT", "'{}'", "'{}'"), Column("transferPolicyJson", "TEXT", "'{}'", "'{}'"),
        Column("pipelineStage", "TEXT", "'NETWORK'", "'NETWORK'"), Column("completedCachePath", "TEXT", "''", "''"),
        Column("completedCacheSize", "INTEGER", "0", "0"), Column("attemptGeneration", "INTEGER", "0", "0"),
    )

    internal fun createSql(table: String): String {
        require(table == "download_task" || table == "download_task_repaired")
        return "CREATE TABLE `$table` (" + columns.joinToString(", ") {
            "`${it.name}` ${it.type} NOT NULL" + (it.default?.let { value -> " DEFAULT $value" } ?: "")
        } + ")"
    }

    internal fun copySql(existing: Set<String>): String {
        require(existing.containsAll(setOf("id", "url", "fileName"))) { "Download table is missing its identity columns; original data was retained" }
        val names = columns.joinToString(", ") { "`${it.name}`" }
        val values = columns.joinToString(", ") {
            if (it.name !in existing) it.fallback
            else if (it.name == "id") "`id`" else "COALESCE(`${it.name}`, ${it.fallback})"
        }
        return "INSERT INTO `download_task_repaired` ($names) SELECT $values FROM `download_task`"
    }

    fun migrate(db: SupportSQLiteDatabase) {
        // Room owns the transaction. Any copy/validation error rolls back before the original is dropped.
        val existing = db.query("PRAGMA table_info(`download_task`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val copy = copySql(existing)
        db.execSQL(createSql("download_task_repaired"))
        db.execSQL(copy)
        fun count(table: String) = db.query("SELECT count(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
        check(count("download_task") == count("download_task_repaired")) { "Task migration count mismatch; original data was retained" }
        db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name='download_task'), 0)) WHERE name='download_task_repaired'")
        db.execSQL("DROP TABLE `download_task`")
        db.execSQL("ALTER TABLE `download_task_repaired` RENAME TO `download_task`")
    }
}
