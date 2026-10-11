package com.yunget.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 表格降级（防崩溃）。
 *
 * ## 这条修复为什么必须有测试
 *
 * markdown-renderer 0.35.0 是按 compose-bom 2025.12 编译的，而本项目用的是
 * 2026.06 —— 它的 `MarkdownTableHeader` 调用了一个已被移除的 `RowScope.weight`
 * 签名。后果是：**任何含表格的 Markdown 渲染都会抛 NoSuchMethodError**。
 *
 * 而插件描述里表格是常见写法（字段对照表、取值说明），官方示例插件的描述里就有。
 * 真机 2.7.5 上打开插件详情直接崩。
 *
 * 这类"运行时才炸、且一炸就是白屏"的缺陷，单靠编译和普通单测发现不了 ——
 * 所以必须有测试把"表格被降级、其它内容不受影响"钉住。
 *
 * 本模块用 JUnit 4：断言消息在前。
 */
class StripMarkdownTablesTest {

    @Test
    fun `表格被降级为等宽文本行`() {
        val md = """
            ## 这是什么

            一个演示插件。

            | 输入 | 结果 |
            |---|---|
            | a://x | https://x |
        """.trimIndent()

        val out = stripMarkdownTables(md)
        assertFalse("不该再出现表格分隔行", out.contains("|---|"))
        assertFalse("不该再出现行首的竖线", out.lines().any { it.trim().startsWith("|") })
    }

    @Test
    fun `表格的行列内容与顺序都保留`() {
        // 降级不能丢信息：用户要能读懂那张表说了什么
        val out = stripMarkdownTables("| 网盘 | 登录方式 |\n|---|---|\n| 夸克 | 网页登录 |")
        assertTrue("表头要保留", out.contains("网盘"))
        assertTrue("表头要保留", out.contains("登录方式"))
        assertTrue("数据行要保留", out.contains("夸克"))
        assertTrue("数据行要保留", out.contains("网页登录"))
        // 顺序：夸克应当在 网页登录 之前
        assertTrue(
            "行序不能乱，否则读起来是错的",
            out.indexOf("夸克") < out.indexOf("网页登录"),
        )
    }

    @Test
    fun `非表格内容原样保留`() {
        val md = """
            ## 标题

            一段说明文字。

            - 列表项一
            - 列表项二

            `code`
        """.trimIndent()

        val out = stripMarkdownTables(md)
        assertTrue("标题", out.contains("## 标题"))
        assertTrue("说明文字", out.contains("一段说明文字。"))
        assertTrue("列表项", out.contains("- 列表项一"))
        assertTrue("行内代码", out.contains("`code`"))
    }

    @Test
    fun `没有表格时不做多余改动`() {
        val md = "# 标题\n\n正文一段。"
        val out = stripMarkdownTables(md)
        assertTrue(out.contains("# 标题"))
        assertTrue(out.contains("正文一段。"))
        assertFalse(
            "没有表格就不该凭空加缩进",
            out.lines().any { it.startsWith("    ") && it.isNotBlank() },
        )
    }

    @Test
    fun `多张表格都能处理`() {
        val md = """
            | a | b |
            |---|---|
            | 1 | 2 |

            中间说明

            | c | d |
            |---|---|---|
            | 3 | 4 |
        """.trimIndent()

        val out = stripMarkdownTables(md)
        assertFalse(out.contains("|---|"))
        assertTrue(out.contains("中间说明"))
        assertTrue("第一张表", out.contains("1"))
        assertTrue("第二张表", out.contains("3"))
    }

    @Test
    fun `单元格里含竖线也能处理`() {
        // 表格单元格里的转义竖线不能把行拆坏
        val out = stripMarkdownTables("| 名称 | 说明 |\n|---|---|\n| a | 含 | 竖线 |")
        assertTrue(out.contains("含"))
        assertTrue(out.contains("竖线"))
    }

    @Test
    fun `空输入不炸`() {
        assertEquals("", stripMarkdownTables(""))
        assertEquals("", stripMarkdownTables("   "))
    }
}
