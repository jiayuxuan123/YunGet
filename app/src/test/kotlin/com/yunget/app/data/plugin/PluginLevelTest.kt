package com.yunget.app.data.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件分级字段在索引解析与兼容性上的行为。
 *
 * ## Android 只剩 JS 一档，为什么这些用例还留着
 *
 * 索引是**外部格式**（源仓库里的 `plugins.json`），它会比应用先存在、也会比应用后变化。
 * 所以三件事仍然必须成立：
 *
 *  1. **缺 `level` 的存量索引照常能装** —— 分级是后加的字段，让它变成一次全量失效
 *     是不可接受的（存量插件与存量索引都没这个字段）；
 *  2. **写了不认识的值要拒** —— 不能"猜一个默认值"；
 *  3. **`native` 要有专门的说明** —— 它不是"未知的新级别"，而是"Android 版不再支持的东西"。
 *     笼统报"请更新应用"会让用户去升级一个永远不会支持它的版本（原生插件在 Android 上
 *     已被放弃，见 [PluginLevel] 的类注释）。
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
    fun `旧索引没有 level 字段时按 JS 读`() {
        val index = MarketIndex.parse(indexJson(levelField = "")).getOrThrow()
        assertEquals(1, index.plugins.size)
        assertEquals(
            "缺 level 的存量条目必须按 JS 兼容 —— 分级是后加的字段，不能让它变成一次全量失效",
            PluginLevel.JS,
            index.plugins[0].level,
        )
    }

    @Test
    fun `写了合法 level 时按它读`() {
        assertEquals(PluginLevel.JS, parseOne("js").level)
        // 大小写与空白不该是拒绝的理由：JSON 是人写的
        assertEquals(PluginLevel.JS, parseOne("  JS ").level)
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

    /**
     * `native` 必须被拒，且**不能**用"请更新应用"那套说辞。
     *
     * 这条钉的是手机端的产品决定：原生插件在这一侧暂时停用（后续 Windows 端启用），
     * 用户升级 Android 应用也没用。写成"级别不受支持，请更新应用"会把人送去一个
     * 不存在的答案。
     */
    @Test
    fun `native 条目被拒且给出专门的原因`() {
        val index = MarketIndex.parse(indexJson(levelField = "native")).getOrThrow()
        assertTrue("手机端不加载原生插件，条目必须被丢掉", index.plugins.isEmpty())
        val reason = index.skippedEntries.single().reason
        assertTrue("原因里要点明这是原生（L2）插件：$reason", reason.contains("原生"))
        assertTrue("原因里要说清这是暂时的、以后在 Windows 端启用：$reason", reason.contains("Windows"))
        assertFalse(
            "不能说'请更新应用' —— 升级也不会支持，那是在骗用户去找一个不存在的答案：$reason",
            reason.contains("更新应用"),
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

    // ---------------------------------------------- 生效方式

    @Test
    fun `解析默认值只用于缺字段`() {
        assertEquals(PluginLevel.DEFAULT, PluginLevel.parseOrDefault(null))
        assertEquals(PluginLevel.DEFAULT, PluginLevel.parseOrDefault("   "))
        assertNull("写了非法值不能退回默认", PluginLevel.parseOrDefault("wasm"))
        assertNull("native 也不能退回默认（那会把它当 JS 装上）", PluginLevel.parseOrDefault("native"))
    }

    @Test
    fun `现在只有 JS 一档`() {
        assertEquals(
            "Android 只保留 JS 插件；多出一档就意味着有人把 L2 又接回来了",
            listOf(PluginLevel.JS),
            PluginLevel.entries.toList(),
        )
        assertEquals("js", PluginLevel.DEFAULT.id)
    }

    // ---------------------------------------------- 措辞

    @Test
    fun `说明里不许出现安全二选一的结论`() {
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
    fun `JS 插件的说明要说清它能访问什么而不是笼统说受限`() {
        val note = PluginLevel.JS.capabilityNote()
        assertTrue("说明要具体说网络范围", note.contains("网络"))
        assertTrue("说明要具体说存储范围", note.contains("存储"))
        assertFalse(
            "不能给 JS 插件贴上'没有沙箱'的标签 —— 事实相反，措辞会误导",
            note.contains("没有沙箱"),
        )
        assertFalse(
            "也不能说'能力与宿主等同' —— 它在沙箱里，这句是事实错误",
            note.contains("与宿主等同"),
        )
    }

    @Test
    fun `JS 插件的说明要说清即时生效`() {
        assertTrue(PluginLevel.JS.hotLoadNote().contains("立即生效"))
    }

    private fun parseOne(level: String): MarketPlugin {
        val index = MarketIndex.parse(indexJson(levelField = level)).getOrThrow()
        assertEquals("应当只解析出一条", 1, index.plugins.size)
        return index.plugins[0]
    }

    /**
     * 源仓库里那份**真实**索引必须能被解析。
     *
     * 这条挡的是"文档与工具都写了 level、唯独实际发布的那份忘了加" ——
     * 那会让线上索引与 App 的能力对不上，而单测全绿（因为单测用的是自己造的 JSON）。
     *
     * ## 这里原先断言"L2 条目数 = 4"
     *
     * 那条断言随原生插件一起失效了：**索引里可能仍有 `level: "native"` 的条目**
     * （源仓库是 PC 与 Android 共用的，PC 端还会用它们）。手机端的正确行为是
     * **把它们全部跳过并说明原因**，而不是假装它们不存在 —— 所以现在断言的是这件事。
     */
    @Test
    fun `仓库里的真实索引能被解析_原生条目被跳过并留痕`() {
        val file = java.io.File("../../YunGet-Plugins/plugins.json")
        if (!file.isFile) return // 只拷了 app 模块时跳过
        val index = MarketIndex.parse(file.readText()).getOrThrow()
        assertTrue("真实索引里应当有插件", index.plugins.isNotEmpty())
        assertTrue(
            "Android 只剩 JS 一档，解析出来的条目都必须是 JS：${index.plugins.map { it.id to it.level }}",
            index.plugins.all { it.level == PluginLevel.JS },
        )
        // 被跳过的条目只能是原生插件，且每条都要有可展示的原因
        for (skipped in index.skippedEntries) {
            assertTrue(
                "跳过 ${skipped.id} 的原因要说清是原生插件在手机端停用：${skipped.reason}",
                skipped.reason.contains("原生"),
            )
        }
    }

    @Suppress("unused")
    private fun jsonObjectOf(text: String): JSONObject = JSONObject(text)
}
