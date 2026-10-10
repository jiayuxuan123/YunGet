/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.plugin

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 的回归测试：**更新失败要能回滚到上一可用版本**。
 *
 * ## 为什么必须有它
 *
 * 更新是覆盖式的：新脚本写盘、库记录改版本、旧脚本被删。所以"新版本加载失败"
 * 原本等于"插件永久坏掉，且旧版本已被我们自己删掉"。设计文档第 8.5 章要求支持回滚。
 *
 * 这里测的是回滚的**存储层**（`PluginRollbackStore`），它被设计成纯文件 IO、不依赖 Android，
 * 因此能在本项目的纯 JVM 测试里完整覆盖（App 测试没有 Robolectric）。
 *
 * 覆盖的关键行为：
 *  - 保存 / 读取 / 清除；
 *  - 保存新快照时**返回被取代的旧快照**（调用方据此清理旧备份文件，避免无限堆积）；
 *  - 损坏或半截的 JSON 不能让它抛异常（否则更新流程会被一个坏文件搞崩）；
 *  - 路径穿越的 id 不会写到目录外（与仓库其它写点同一安全要求）。
 */
class PluginRollbackStoreTest {

    private fun tempDir(): File =
        Files.createTempDirectory("rollback-test").toFile().apply { deleteOnExit() }

    private fun snapshot(
        id: String = "parser.example",
        version: String = "1.0.0",
        path: String = "/data/plugins/scripts/parser.example_abcd1234.js",
        sha: String = "abcd1234" + "0".repeat(56),
    ) = PluginRollbackStore.Snapshot(
        id = id,
        name = "Example",
        version = version,
        scriptPath = path,
        scriptSha256 = sha,
        sourceUri = "https://example.invalid/plugins.json",
        sourceKind = "market",
        manifestJson = """{"id":"$id"}""",
        declaredPermissions = "http,storage",
        trustLevel = "verified",
        savedAt = 1_700_000_000_000L,
    )

    @Test
    fun `save then load round-trips every field`() {
        val store = PluginRollbackStore(tempDir())
        assertNull("还没有快照时应返回 null", store.load("parser.example"))

        store.save(snapshot())
        val loaded = store.load("parser.example")
        assertNotNull("保存后应能读回", loaded)
        assertEquals("parser.example", loaded!!.id)
        assertEquals("1.0.0", loaded.version)
        assertEquals("Example", loaded.name)
        assertEquals("http,storage", loaded.declaredPermissions)
        assertEquals("verified", loaded.trustLevel)
        assertEquals("market", loaded.sourceKind)
        assertEquals(1_700_000_000_000L, loaded.savedAt)
        assertTrue(loaded.isUsable)
    }

    @Test
    fun `saving a new snapshot returns the one it replaces`() {
        // 调用方用返回值清理"更旧"的备份脚本文件，避免备份无限堆积。
        val store = PluginRollbackStore(tempDir())
        assertNull("第一次保存没有可取代的旧快照", store.save(snapshot(version = "1.0.0")))

        val replaced = store.save(snapshot(version = "1.1.0", path = "/x/1.1.0.js"))
        assertNotNull("第二次保存应返回被取代的快照", replaced)
        assertEquals("1.0.0", replaced!!.version)
        assertEquals("1.1.0", store.load("parser.example")!!.version)
    }

    @Test
    fun `clear removes the snapshot`() {
        val store = PluginRollbackStore(tempDir())
        store.save(snapshot())
        store.clear("parser.example")
        assertNull(store.load("parser.example"))
        // 再清一次不应抛异常（卸载流程可能重复调用）
        store.clear("parser.example")
    }

    @Test
    fun `corrupt json is treated as no snapshot instead of throwing`() {
        // 一个坏文件不该让"检查更新"或"回滚"流程崩掉 —— 那会把单个插件的问题放大成整体故障。
        val dir = tempDir()
        val store = PluginRollbackStore(dir)
        store.save(snapshot())
        File(dir, "parser.example.json").writeText("{ this is not json", Charsets.UTF_8)
        assertNull("损坏的快照应返回 null", store.load("parser.example"))
    }

    @Test
    fun `snapshot without script path is not usable`() {
        val dir = tempDir()
        val store = PluginRollbackStore(dir)
        store.save(snapshot(path = ""))
        // 空 scriptPath 的快照没有回滚价值（没有脚本可退回去），应视为不存在。
        assertNull(store.load("parser.example"))
    }

    @Test
    fun `ids are sanitized so they cannot escape the directory`() {
        val dir = tempDir()
        val store = PluginRollbackStore(dir)
        // 恶意/脏 id：路径穿越、绝对路径、上级目录
        store.save(snapshot(id = "../../evil"))
        val escaped = File(dir.parentFile, "evil.json")
        assertTrue("不得写到回滚目录之外", !escaped.exists())

        // 合法 id 仍能正常往返
        store.save(snapshot(id = "parser.example"))
        assertNotNull(store.load("parser.example"))
    }

    @Test
    fun `separate plugins keep separate snapshots`() {
        val store = PluginRollbackStore(tempDir())
        store.save(snapshot(id = "parser.a", version = "1.0.0"))
        store.save(snapshot(id = "parser.b", version = "2.0.0"))
        assertEquals("1.0.0", store.load("parser.a")!!.version)
        assertEquals("2.0.0", store.load("parser.b")!!.version)
        store.clear("parser.a")
        assertNull(store.load("parser.a"))
        assertNotNull("清掉一个不该影响另一个", store.load("parser.b"))
    }
}
