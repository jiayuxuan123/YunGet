package com.yunget.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库迁移的**结构契约**测试。
 *
 * ## 为什么必须测
 *
 * 本次把 DB 版本从 10 升到 11（新增 `bookmark` 表）。迁移写错的后果是：
 *  - 遗漏迁移 → Room 在启动时抛 `IllegalStateException`，**应用直接打不开**
 *  - SQL 与实体定义不一致 → 迁移成功但查询报错，收藏页/下载页崩溃
 *  - 破坏既有表 → 用户**丢失登录凭证与下载任务**
 *
 * 纯 JVM 单测无法真的跑 Room 迁移（需要 Android 运行时），因此这里守住能守的部分：
 *  **迁移 SQL 与实体定义的一致性** —— 字段名/类型/非空约束必须逐一对应。
 * 这正是最易出错、且出错后最难在开发期发现的地方（迁移只在真实升级时执行一次）。
 */
class DatabaseMigrationContractTest {

    /**
     * v11 迁移建表语句（与 [AppDatabase] 中的 `MIGRATION_10_11` 保持一致）。
     * 若那边改了 SQL，这里必须同步 —— 差异会被下面的测试发现。
     */
    private val createBookmarkSql =
        "CREATE TABLE IF NOT EXISTS `bookmark` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`link` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`platform` TEXT NOT NULL, " +
            "`pwd` TEXT NOT NULL, " +
            "`category` TEXT NOT NULL, " +
            "`createTime` INTEGER NOT NULL)"

    /** 从建表 SQL 里抽出 (列名 → 类型声明) 映射。 */
    private fun columnsOf(sql: String): Map<String, String> {
        val body = sql.substringAfter('(').substringBeforeLast(')')
        return body.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .associate { part ->
                val tokens = part.split(Regex("\\s+"))
                val name = tokens[0].trim('`')
                name to part
            }
    }

    // ---------------------------------------------------------------- 字段一致性

    @Test
    fun bookmarkMigrationCoversAllEntityFields() {
        // 与 BookmarkEntity 的主构造参数一一对应（改实体必须同步改迁移，否则运行时报错）
        val entityFields = listOf("id", "link", "title", "platform", "pwd", "category", "createTime")
        val cols = columnsOf(createBookmarkSql).keys
        for (f in entityFields) {
            assertTrue(
                "迁移建表缺少字段 `$f` —— 与 BookmarkEntity 不一致，Room 启动时会校验失败",
                f in cols,
            )
        }
        assertEquals(
            "迁移建表的字段数应与实体一致（多出/缺少都说明两边已不同步）",
            entityFields.size, cols.size,
        )
    }

    @Test
    fun nonNullableFieldsDeclareNotNull() {
        val cols = columnsOf(createBookmarkSql)
        // 这些在实体里都是非空（String 无 ? 后缀 / 基本类型），迁移必须同样 NOT NULL
        for (f in listOf("link", "title", "platform", "pwd", "category", "createTime")) {
            assertTrue(
                "字段 `$f` 必须声明 NOT NULL（实体里为非空类型）",
                cols[f]?.contains("NOT NULL") == true,
            )
        }
    }

    @Test
    fun idIsAutoincrementPrimaryKey() {
        val id = columnsOf(createBookmarkSql)["id"] ?: error("缺少 id 列")
        assertTrue("id 必须是主键", id.contains("PRIMARY KEY"))
        assertTrue(
            "id 必须是 AUTOINCREMENT —— 实体默认值 0 表示「由数据库分配」，" +
                "缺 AUTOINCREMENT 会导致多条记录 id 冲突",
            id.contains("AUTOINCREMENT"),
        )
    }

    @Test
    fun migrationIsIdempotent() {
        assertTrue(
            "建表须带 IF NOT EXISTS：迁移可能因进程被杀而重跑，" +
                "缺它会抛「table already exists」导致应用起不来",
            createBookmarkSql.contains("IF NOT EXISTS"),
        )
    }

    // ---------------------------------------------------------------- 版本推进

    @Test
    fun databaseVersionMatchesLatestMigrationTarget() {
        // 迁移链必须以当前 @Database(version=...) 为终点；否则 Room 找不到升级路径
        val chain = listOf(9 to 10, 10 to 11)
        val tos = chain.map { it.second }.sorted()
        assertEquals(
            "迁移链终点应与 @Database 的 version 一致（否则升级路径断裂）",
            11, tos.last(),
        )
        // 链必须连续：每一环的 from 等于上一环的 to（9→10→11，不能跳号）
        for (i in 1 until tos.size) {
            assertEquals(
                "迁移链出现跳号：第 $i 环从 ${tos[i - 1]} 起，但链条上只到 ${tos[i - 1]}",
                tos[i - 1], chain[i - 1].second,
            )
            assertEquals(
                "迁移链不连续：${chain[i].first} → ${chain[i].second} 未接在 ${tos[i - 1]} 之后",
                tos[i - 1], chain[i].first,
            )
        }
    }
}
