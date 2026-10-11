package com.yunget.app.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支持条目的净化与排序（P7）。
 *
 * 这一层是**宿主替插件完成的动作的最后一道关**：条目来自第三方代码，
 * 而渲染与"打开链接""读图片"是宿主做的。所以这里挡不住的东西，
 * 就变成了"插件让宿主替它越权"。
 *
 * 三类必须挡住：
 *  1. 非 http/https 链接（`intent:` / `file:` / `javascript:` 交给系统打开是危险动作）；
 *  2. 应用私有目录之外的图片路径（否则插件可以指示宿主去读它读不到的文件）；
 *  3. 空标题与超长文本（空壳条目看不懂、超长文本卡死页面）。
 */
class SupportEntriesTest {

    private val root = "/data/user/0/com.yunget.app/files"

    // ---------------------------------------------- 链接

    @Test
    fun `只接受 http 与 https 链接`() {
        assertTrue(SupportEntries.isHttpUrl("https://example.com/a"))
        assertTrue(SupportEntries.isHttpUrl("http://example.com"))
        assertFalse("intent 链接不该放行", SupportEntries.isHttpUrl("intent://evil#Intent;scheme=x;end"))
        assertFalse("file 链接不该放行", SupportEntries.isHttpUrl("file:///etc/passwd"))
        assertFalse("javascript 不该放行", SupportEntries.isHttpUrl("javascript:alert(1)"))
        assertFalse("只有 scheme 不算 URL", SupportEntries.isHttpUrl("https://"))
        assertFalse("空串不算", SupportEntries.isHttpUrl(""))
    }

    @Test
    fun `非法链接的条目被丢弃`() {
        val r = SupportEntries.sanitize(
            "p", listOf(SupportEntry("恶意", kind = SupportEntry.Kind.LINK, payload = "intent://x")), root,
        )
        assertTrue("非 http 链接必须被丢", r.isEmpty)
        assertEquals(1, r.dropped)
    }

    @Test
    fun `合法链接的条目保留`() {
        val r = SupportEntries.sanitize(
            "p", listOf(SupportEntry("官网", kind = SupportEntry.Kind.LINK, payload = "https://a.b")), root,
        )
        assertEquals(1, r.entries.size)
        assertEquals(0, r.dropped)
    }

    // ---------------------------------------------- 图片路径

    @Test
    fun `图片必须在应用私有目录内`() {
        assertTrue(SupportEntries.isInsidePrivateRoot("$root/plugins/x/qr.png", root))
        assertFalse(
            "私有目录外的不许读",
            SupportEntries.isInsidePrivateRoot("/sdcard/DCIM/secret.jpg", root),
        )
        assertFalse(
            "向上跳的路径必须拒（即便规范化后落在根内，意图也可疑）",
            SupportEntries.isInsidePrivateRoot("$root/../elsewhere/x.png", root),
        )
    }

    @Test
    fun `同前缀的兄弟目录不算在私有目录内`() {
        // 这一条是"只做 startsWith 会犯的错"：/files-secret 以 /files 开头
        assertFalse(
            "同前缀的兄弟目录必须被排除，否则前缀匹配会放它过去",
            SupportEntries.isInsidePrivateRoot("/data/user/0/com.yunget.app/files-secret/qr.png", root),
        )
    }

    @Test
    fun `根目录自身的路径判为在内`() {
        assertTrue(
            "根目录自身（没有后续段）应当算在内 —— 边界上不要多一个 if",
            SupportEntries.isInsidePrivateRoot(root, root),
        )
    }

    @Test
    fun `越界图片的条目被丢弃`() {
        val r = SupportEntries.sanitize(
            "p",
            listOf(SupportEntry("收款码", kind = SupportEntry.Kind.IMAGE, payload = "/sdcard/x.png")),
            root,
        )
        assertTrue("越界图片必须被丢", r.isEmpty)
    }

    @Test
    fun `https 图片 URL 的条目保留`() {
        // manifest 是纯文本带不了二进制 —— 远程 URL 是插件提供一张收款码的唯一途径。
        // 宿主用公共图片加载器取图（scheme 合法性已在 isHttpUrl 把关）。
        val r = SupportEntries.sanitize(
            "p",
            listOf(
                SupportEntry(
                    "微信赞赏码",
                    kind = SupportEntry.Kind.IMAGE,
                    payload = "https://raw.githubusercontent.com/jiayuxuan123/YunGet-Plugins/main/assets/weixin.png",
                ),
            ),
            root,
        )
        assertEquals(1, r.entries.size)
        assertEquals(0, r.dropped)
    }

    @Test
    fun `非 http 的图片地址被丢弃`() {
        // file:// 的图片等于让宿主去读本地文件 —— 原有的越权担忧对它完全成立，
        // 放开 URL 只放行 http/https，绝不能把本地 scheme 一起放进来。
        val r = SupportEntries.sanitize(
            "p",
            listOf(
                SupportEntry("本地文件", kind = SupportEntry.Kind.IMAGE, payload = "file:///etc/passwd"),
                SupportEntry("脚本注入", kind = SupportEntry.Kind.IMAGE, payload = "javascript:alert(1)"),
            ),
            root,
        )
        assertTrue("两个都要被丢", r.isEmpty)
        assertEquals(2, r.dropped)
    }

    // ---------------------------------------------- 文本与标题

    @Test
    fun `空标题的条目被丢弃`() {
        val r = SupportEntries.sanitize(
            "p", listOf(SupportEntry("   ", kind = SupportEntry.Kind.TEXT, payload = "内容")), root,
        )
        assertTrue("空标题会渲染成一个看不懂的空壳条目", r.isEmpty)
    }

    @Test
    fun `空的文本载荷被丢弃`() {
        val r = SupportEntries.sanitize(
            "p", listOf(SupportEntry("标题", kind = SupportEntry.Kind.TEXT, payload = "  ")), root,
        )
        assertTrue("只有标题没有内容的文本条目没有意义", r.isEmpty)
    }

    @Test
    fun `超长文本被截断而不是丢弃`() {
        // 截断比丢弃好：内容还在，只是被裁剪；丢弃会让插件作者莫名其妙
        val long = "x".repeat(SupportEntries.MAX_TEXT_CHARS + 1000)
        val r = SupportEntries.sanitize(
            "p", listOf(SupportEntry("长文", kind = SupportEntry.Kind.TEXT, payload = long)), root,
        )
        assertEquals(1, r.entries.size)
        assertEquals(
            "应当截到上限",
            SupportEntries.MAX_TEXT_CHARS,
            r.entries.first().payload.length,
        )
    }

    @Test
    fun `说明被截断到上限`() {
        val longNote = "n".repeat(SupportEntries.MAX_NOTE_CHARS + 100)
        val r = SupportEntries.sanitize(
            "p",
            listOf(SupportEntry("标题", note = longNote, kind = SupportEntry.Kind.TEXT, payload = "正文")),
            root,
        )
        assertEquals(SupportEntries.MAX_NOTE_CHARS, r.entries.first().note.length)
    }

    @Test
    fun `标题与说明的前后空白被去掉`() {
        val r = SupportEntries.sanitize(
            "p", listOf(SupportEntry("  标题  ", note = "  说明  ", kind = SupportEntry.Kind.TEXT, payload = " 正文 ")), root,
        )
        val e = r.entries.first()
        assertEquals("标题", e.title)
        assertEquals("说明", e.note)
    }

    // ---------------------------------------------- 数量上限

    @Test
    fun `单个插件的条数有上限`() {
        val many = (1..50).map { SupportEntry("条目$it", kind = SupportEntry.Kind.TEXT, payload = "x") }
        val r = SupportEntries.sanitize("p", many, root)
        assertEquals(
            "超出的应当被丢掉，否则一个插件能用大量条目刷屏",
            SupportEntries.MAX_ENTRIES_PER_PLUGIN,
            r.entries.size,
        )
        assertTrue("丢弃数要如实统计", r.dropped > 0)
    }

    // ---------------------------------------------- 排序稳定性

    @Test
    fun `同一插件内按标题排序`() {
        val r = SupportEntries.sanitize(
            "p",
            listOf(
                SupportEntry("zebra", kind = SupportEntry.Kind.TEXT, payload = "1"),
                SupportEntry("alpha", kind = SupportEntry.Kind.TEXT, payload = "2"),
            ),
            root,
        )
        assertEquals(listOf("alpha", "zebra"), r.entries.map { it.title })
    }

    @Test
    fun `跨插件顺序与注册顺序无关`() {
        // 用两种注册顺序，结果必须一致 —— 否则用户以为页面上东西在乱跳
        val a = "plugin-a" to listOf(SupportEntry("A 的条目", kind = SupportEntry.Kind.TEXT, payload = "x"))
        val b = "plugin-b" to listOf(SupportEntry("B 的条目", kind = SupportEntry.Kind.TEXT, payload = "y"))

        val first = SupportEntries.merge(listOf(a, b), root).map { it.ownerPluginId }
        val second = SupportEntries.merge(listOf(b, a), root).map { it.ownerPluginId }

        assertEquals("两种注册顺序必须得到同一个排列", first, second)
        assertEquals(listOf("plugin-a", "plugin-b"), first)
    }

    @Test
    fun `合并时保留来源插件 id`() {
        val r = SupportEntries.merge(
            listOf(
                "p1" to listOf(SupportEntry("一", kind = SupportEntry.Kind.TEXT, payload = "x")),
                "p2" to listOf(SupportEntry("二", kind = SupportEntry.Kind.TEXT, payload = "y")),
            ),
            root,
        )
        assertEquals(2, r.size)
        assertEquals(
            "界面要显示「由哪个插件提供」，所以来源不能丢",
            setOf("p1", "p2"),
            r.map { it.ownerPluginId }.toSet(),
        )
    }

    // ---------------------------------------------- 没有提供者

    @Test
    fun `没有条目时是空列表而不是崩溃`() {
        val r = SupportEntries.merge(emptyList(), root)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `某个插件返回空列表不影响别的插件`() {
        val r = SupportEntries.merge(
            listOf(
                "empty" to emptyList(),
                "has" to listOf(SupportEntry("有", kind = SupportEntry.Kind.TEXT, payload = "x")),
            ),
            root,
        )
        assertEquals(1, r.size)
        assertEquals("has", r.first().ownerPluginId)
    }

    // ---------------------------------------------- 依赖关系（P7 的机制验证）

    @Test
    fun `扩展点键符合命名空间规范`() {
        // P7 依赖 P3：扩展点的 id 也要守命名空间规则，
        // 否则它就是"跨插件契约没有归属"的反例。
        val id = SupportEntryProvider.KEY.id
        assertTrue(
            "扩展点 id 必须带命名空间：$id",
            id.contains(".cap."),
        )
        assertFalse("扩展点 id 不能是裸名", id.startsWith("."))
    }
}
