package com.yunget.app.data.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件分级（P20）在索引解析与兼容性上的行为。
 *
 * 三件事必须同时成立，缺一不可：
 *
 *  1. **缺 `level` 的存量索引照常能装** —— 分级是后加的字段，让它变成一次全量失效
 *     是不可接受的（存量插件与存量索引都没这个字段）；
 *  2. **写了不认识的值要拒** —— 不能"猜一个默认值"，猜错会把 L2 当 L1 装上，
 *     而界面正在告诉用户"即时生效"，那是实打实的误导；
 *  3. **级别 → 需不需要重启** 的映射要跟着级别走，不能在某处硬编码。
 */
class PluginLevelTest {

    /**
     * 造一条只有一个插件的索引。[levelField] 为空串 = 索引里**没有** level 字段。
     *
     * 用 JSONObject 拼而不是字符串模板：模板里那几个逗号很容易在加字段时拼错，
     * 而 JSON 拼错时 org.json 报的是行号，报错信息与真正的原因隔着好几行。
     */
    private fun indexJson(levelField: String): String {
        val version = JSONObject()
            .put("version", "1.0.0")
            .put("downloadUrl", "https://example.com/demo.js")
            .put("sha256", "a".repeat(64))
            .put("signature", "AAAA")
            .put("keyId", "official-2026")
        val plugin = JSONObject()
            .put("id", "demo.plugin")
            .put("name", "示例插件")
            .put("versions", org.json.JSONArray().put(version))
        if (levelField.isNotEmpty()) plugin.put("level", levelField)
        return JSONObject()
            .put("schemaVersion", 1)
            .put("sourceName", "测试源")
            .put("plugins", org.json.JSONArray().put(plugin))
            .toString()
    }

    // ---------------------------------------------- 解析

    @Test
    fun `旧索引没有 level 字段时按 L1 读`() {
        val index = MarketIndex.parse(indexJson(levelField = "")).getOrThrow()
        assertEquals(1, index.plugins.size)
        assertEquals(
            "缺 level 的存量条目必须按 L1 兼容 —— 分级是后加的字段，不能让它变成一次全量失效",
            PluginLevel.JS,
            index.plugins[0].level,
        )
    }

    @Test
    fun `写了合法 level 时按它读`() {
        assertEquals(PluginLevel.NATIVE, parseOne("native").level)
        assertEquals(PluginLevel.JS, parseOne("js").level)
        // 大小写与空白不该是拒绝的理由：JSON 是人写的
        assertEquals(PluginLevel.NATIVE, parseOne("  Native ").level)
    }

    @Test
    fun `写了非法 level 时整条被拒_但要留痕`() {
        val index = MarketIndex.parse(indexJson(levelField = "wasm")).getOrThrow()
        assertTrue("不认识的值必须拒（不能猜默认值）", index.plugins.isEmpty())
        // 光拒不够：用户看到列表里没有这个插件时，合理推测是"源里没有"，
        // 于是永远不会想到去升级应用。所以必须把丢掉的原因留在索引里。
        assertEquals("demo.plugin", index.skippedEntries.single().id)
        assertTrue(
            "丢弃原因要说清是级别不受支持：${index.skippedEntries.single().reason}",
            index.skippedEntries.single().reason.contains("级别") &&
                index.skippedEntries.single().reason.contains("wasm"),
        )
    }

    @Test
    fun `同一条索引里别的字段照常解析`() {
        // 确认"拒绝非法 level"是按条目粒度的，不会把整个索引判废
        val json = """
        {
          "schemaVersion": 1,
          "sourceName": "测试源",
          "plugins": [
            {"id":"ok.one","name":"正常的",
             "versions":[{"version":"1.0.0","downloadUrl":"https://e.com/a.js",
                          "sha256":"${"b".repeat(64)}","signature":"AAAA","keyId":"k"}]},
            {"id":"bad.two","name":"级别非法的","level":"wasm",
             "versions":[{"version":"1.0.0","downloadUrl":"https://e.com/b.js",
                          "sha256":"${"c".repeat(64)}","signature":"AAAA","keyId":"k"}]}
          ]
        }
        """.trimIndent()
        val index = MarketIndex.parse(json).getOrThrow()
        assertEquals(listOf("ok.one"), index.plugins.map { it.id })
    }

    // ---------------------------------------------- 级别 → 生效方式

    @Test
    fun `只有原生插件需要重启`() {
        assertFalse(PluginLevel.JS.requiresRestart)
        assertTrue(PluginLevel.NATIVE.requiresRestart)
    }

    @Test
    fun `解析默认值只用于缺字段`() {
        assertEquals(PluginLevel.DEFAULT, PluginLevel.parseOrDefault(null))
        assertEquals(PluginLevel.DEFAULT, PluginLevel.parseOrDefault("   "))
        assertNull("写了非法值不能退回默认", PluginLevel.parseOrDefault("wasm"))
    }

    // ---------------------------------------------- 措辞

    @Test
    fun `两个级别的说明都不许出现安全二选一的结论`() {
        for (level in PluginLevel.entries) {
            val note = level.capabilityNote() + level.hotLoadNote()
            for (forbidden in listOf("绝对安全", "安全无虞", "保证安全", "100% 安全")) {
                assertFalse(
                    "${level.title} 的说明里出现了结论式的措辞「$forbidden」：$note",
                    note.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `原生插件的说明必须承认它没有沙箱`() {
        val note = PluginLevel.NATIVE.capabilityNote()
        assertTrue("L2 的说明必须写明能力与宿主等同", note.contains("与宿主等同"))
        assertTrue("L2 的说明必须写明没有沙箱", note.contains("没有沙箱"))
        assertTrue("L2 的说明必须说清重启才生效", PluginLevel.NATIVE.hotLoadNote().contains("重启"))
    }

    @Test
    fun `JS 插件的说明要说清它能访问什么而不是笼统说受限`() {
        val note = PluginLevel.JS.capabilityNote()
        assertTrue("L1 的说明要具体说网络范围", note.contains("网络"))
        assertTrue("L1 的说明要具体说存储范围", note.contains("存储"))
        assertFalse(
            "不能给 L1 贴上'没有沙箱'的标签 —— 事实相反，措辞会误导",
            note.contains("没有沙箱"),
        )
    }

    private fun parseOne(level: String): MarketPlugin {
        val index = MarketIndex.parse(indexJson(levelField = level)).getOrThrow()
        assertEquals("应当只解析出一条", 1, index.plugins.size)
        return index.plugins[0]
    }

    /**
     * 源仓库里那份**真实**索引必须能被解析，且每个条目都带级别。
     *
     * 这条挡的是"文档与工具都写了 level、唯独实际发布的那份忘了加" ——
     * 那会让线上索引与 App 的能力对不上，而单测全绿（因为单测用的是自己造的 JSON）。
     */
    @Test
    fun `仓库里的真实索引能被解析且每条都有级别`() {
        val file = java.io.File("../../YunGet-Plugins/plugins.json")
        if (!file.isFile) return // 只拷了 app 模块时跳过
        val index = MarketIndex.parse(file.readText()).getOrThrow()
        assertTrue("真实索引里应当有插件", index.plugins.isNotEmpty())
        assertTrue(
            "真实索引里有条目被跳过：${index.skippedEntries}",
            index.skippedEntries.isEmpty(),
        )
        assertTrue(
            "每个条目都要有级别字段（由 sign_plugin.py index 从 entry.language 推导）",
            index.plugins.all { it.level == PluginLevel.JS },
        )
    }

    @Suppress("unused")
    private fun jsonObjectOf(text: String): JSONObject = JSONObject(text)
}