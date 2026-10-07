package com.yunget.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据库迁移的**结构契约**测试。
 *
 * ## 为什么必须测（这里有一次真实事故的记录）
 *
 * 2.6.17 的事件：为了让版本号与上游对齐，`AppDatabase` 里补了 `MIGRATION_11_12`
 * （给 `download_task` 加 `avgSpeed`）与 `MIGRATION_18_19`（给 `xunlei_account` 加 `authType`），
 * **但忘了把这两个字段加进对应的 Entity**。
 *
 * 结果：Room 在迁移后校验表结构，发现库里**多出一列**，抛
 * `IllegalStateException: Migration didn't properly handle: download_task` ——
 * **所有从旧版本升级上来的用户，启动即崩**。而开发机的库是新装的（走的是建表路径，
 * 不经过迁移），所以本地一点问题都没有 —— 这类错误**只在真实升级时暴露**，
 * 出事时用户已经装不回去了。
 *
 * ## 本测试守什么
 *
 * 迁移 SQL 与实体定义必须**双向一致**：
 *  ① 迁移里 ALTER 出来的列，实体里必须有同名字段（本次事故就是这条）；
 *  ② 迁移建的表，列集合要与实体字段集合一致（多/少都算不同步）；
 *  ③ 迁移链连续，且终点等于 `@Database(version = ...)`（否则 Room 找不到升级路径）。
 *
 * 纯 JVM 单测跑不了真的 Room 迁移（需要 Android 运行时），所以这里**直接读源码**做静态核对 ——
 * 对"实体与迁移不一致"这一类问题是完备的，而这正是最易错、最难在开发期发现的地方。
 */
class DatabaseMigrationContractTest {

    /**
     * 源码位置：单测的工作目录是模块根（`app/`），故从 `src/main/kotlin/...` 起找。
     *
     * 为什么读源码而不是把 SQL 抄一份进测试：抄一份的话，**改迁移忘改测试**与
     * **改迁移忘改实体**是同一类错误 —— 测试会跟着一起错，等于没测。
     */
    private val dbDir = File("src/main/kotlin/com/yunget/app/data/db")

    private val appDatabaseSrc: String by lazy {
        File(dbDir, "AppDatabase.kt").readText()
    }

    /** 从迁移源码里抽出「表 → 新增列集合」（所有 `ALTER TABLE x ADD COLUMN y`）。 */
    private fun columnsAddedByMigrations(): Map<String, Set<String>> {
        val re = Regex("""ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?""", RegexOption.IGNORE_CASE)
        val out = mutableMapOf<String, MutableSet<String>>()
        for (m in re.findAll(appDatabaseSrc)) {
            out.getOrPut(m.groupValues[1]) { mutableSetOf() }.add(m.groupValues[2])
        }
        return out
    }

    /** 从迁移源码里抽出「表 → 建表语句」。 */
    private fun tablesCreatedByMigrations(): Map<String, String> {
        // 形如 "CREATE TABLE IF NOT EXISTS `bookmark` (" + "..." + "..."
        val re = Regex("""CREATE TABLE IF NOT EXISTS `(\w+)` \(""", RegexOption.IGNORE_CASE)
        val out = mutableMapOf<String, String>()
        for (m in re.findAll(appDatabaseSrc)) {
            val start = m.range.last + 1
            // 抓到该表建表语句的结尾：下一个 "CREATE TABLE" 或迁移声明结束
            val rest = appDatabaseSrc.substring(start)
            val end = listOf(
                rest.indexOf("CREATE TABLE"),
                rest.indexOf("private val MIGRATION"),
                rest.indexOf("/**"),
            ).filter { it > 0 }.minOrNull() ?: rest.length
            out[m.groupValues[1]] = rest.substring(0, end)
        }
        return out
    }

    /** 从实体源码里抽出字段名集合（@ColumnInfo 未改名时，属性名即列名）。 */
    private fun entityFieldsOf(table: String): Set<String>? {
        val file = dbDir.listFiles { f -> f.name.endsWith("Entity.kt") }
            ?.firstOrNull { f ->
                Regex("""@Entity\(tableName\s*=\s*"${Regex.escape(table)}"""").containsMatchIn(f.readText())
            } ?: return null
        val src = file.readText()
        // 只取主构造参数区（@Entity data class XxxEntity( ... )）
        val ctor = src.substringAfter("data class", "").substringAfter('(', "")
            .substringBeforeLast(')')
        return Regex("""^\s*val\s+(\w+)\s*:""", RegexOption.MULTILINE)
            .findAll(ctor)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** 建表 SQL 的列名集合（跳过 PRIMARY KEY(...) 之类的表级约束）。 */
    private fun sqlColumns(createSql: String): Set<String> =
        Regex("""`(\w+)`\s+(TEXT|INTEGER|REAL|BLOB)""")
            .findAll(createSql)
            .map { it.groupValues[1] }
            .toSet()

    // ---------------------------------------------- ① 迁移加列必须有实体字段（本次事故）

    @Test
    fun everyColumnAddedByMigrationExistsInEntity() {
        val added = columnsAddedByMigrations()
        assertTrue(
            "没有解析到任何 ALTER TABLE —— 说明本测试的正则与 AppDatabase 的写法已脱节，测试形同虚设",
            added.isNotEmpty(),
        )
        val problems = mutableListOf<String>()
        for ((table, cols) in added) {
            val fields = entityFieldsOf(table)
            if (fields == null) {
                problems += "表 `$table`：迁移给它加了列，但找不到对应 Entity 数据类"
                continue
            }
            val missing = cols - fields
            if (missing.isNotEmpty()) {
                problems += "表 `$table`：迁移加了列 $missing，但实体里没有这些字段"
            }
        }
        assertTrue(
            "【2.6.17 启动即崩的根因】迁移里 ALTER 出来的列，实体必须声明同名字段。\n" +
                "否则 Room 迁移后校验表结构发现多出一列，抛 " +
                "`Migration didn't properly handle`，**升级用户启动即崩**。\n" +
                "修法：给对应 Entity 补 `val x: T = 默认值`（默认值要与迁移的 DEFAULT 一致）。\n" +
                "问题：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ---------------------------------------------- ② 迁移建表必须与实体字段一致

    @Test
    fun everyTableCreatedByMigrationMatchEntityFields() {
        val created = tablesCreatedByMigrations()
        assertTrue("没有解析到任何 CREATE TABLE", created.isNotEmpty())
        // 建表只是起点：后续迁移会继续 ALTER 加列，所以要比对的是**最终形态**
        // （建表列 ∪ 该表被 ALTER 加过的列）。否则「迁移分两步加列」会被误判为不一致。
        val altered = columnsAddedByMigrations()
        val problems = mutableListOf<String>()
        for ((table, sql) in created) {
            val fields = entityFieldsOf(table)
            if (fields == null) {
                problems += "表 `$table`：迁移建了表，但找不到对应 Entity"
                continue
            }
            val finalCols = sqlColumns(sql) + altered.getOrDefault(table, emptySet())
            val missing = fields - finalCols
            val extra = finalCols - fields
            if (missing.isNotEmpty()) problems += "表 `$table`：实体有字段 $missing，迁移链里没有对应列"
            if (extra.isNotEmpty()) problems += "表 `$table`：迁移链里有列 $extra，实体里没有"
        }
        assertTrue(
            "迁移建表 SQL 与实体字段必须一一对应（多一列/少一列 Room 都会在启动时校验失败）。\n" +
                "问题：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ---------------------------------------------- ③ 迁移链必须连续且抵达当前版本

    @Test
    fun migrationChainIsContinuousAndReachesDatabaseVersion() {
        val pairs = Regex("""Migration\((\d+),\s*(\d+)\)""")
            .findAll(appDatabaseSrc)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        assertTrue("没有解析到 Migration(...) 声明", pairs.isNotEmpty())

        val declaredVersion = Regex("""version\s*=\s*(\d+)""")
            .find(appDatabaseSrc)?.groupValues?.get(1)?.toInt()
            ?: error("未找到 @Database(version = ...)")

        // 每条迁移都必须是 from -> from+1 的单步推进
        for ((from, to) in pairs) {
            assertEquals("迁移必须单步推进（$from → $to 跳号了）", from + 1, to)
        }
        // 按 from 排序后必须首尾相连
        val sorted = pairs.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            assertEquals(
                "迁移链不连续：${sorted[i - 1].second} 之后应接 ${sorted[i - 1].second}，实际接 ${sorted[i].first}",
                sorted[i - 1].second, sorted[i].first,
            )
        }
        // 终点必须等于 @Database version —— 否则 Room 找不到升级路径，同样启动即崩
        assertEquals(
            "迁移链终点与 @Database(version=) 不一致 —— Room 会因找不到升级路径而抛异常",
            declaredVersion, sorted.last().second,
        )
    }

    @Test
    fun migrationsAreRegisteredInBuilder() {
        // 声明了迁移却没 addMigrations 也是常见疏漏（迁移永远不会执行）
        for (name in Regex("""private val (MIGRATION_\d+_\d+)""").findAll(appDatabaseSrc)
            .map { it.groupValues[1] }.toList()) {
            assertTrue(
                "迁移 $name 已声明但没有注册到 addMigrations —— 升级时不会执行，用户库结构停在旧版本",
                appDatabaseSrc.contains("$name,") || appDatabaseSrc.contains("$name\n"),
            )
        }
    }

    @Test
    fun addedColumnsDeclareDefaultValues() {
        // ALTER TABLE ... ADD COLUMN 对已有行必须能给值：NOT NULL 就一定要带 DEFAULT
        val re = Regex("""ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?\s+([^"]*)""", RegexOption.IGNORE_CASE)
        val problems = re.findAll(appDatabaseSrc).mapNotNull { m ->
            val (table, col, decl) = m.destructured
            if (decl.contains("NOT NULL", true) && !decl.contains("DEFAULT", true)) {
                "表 `$table` 的列 `$col` 声明了 NOT NULL 却没有 DEFAULT —— 老行无法填充，迁移会失败"
            } else null
        }.toList()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
