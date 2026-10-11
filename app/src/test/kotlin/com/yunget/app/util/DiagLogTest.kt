package com.yunget.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志的分级、分类与**脱敏兜底**。
 *
 * ## 脱敏为什么必须测
 *
 * 这份日志的设计目的是"整份回传给开发者"，而调用方**一定会**有忘记脱敏的时候
 * （30 多个调用点，将来更多）。一次疏忽就等于把用户的 cookie 贴到聊天记录里。
 * 所以这里验的不是"脱敏很完备"（它不完备，也不打算完备），而是
 * **最常见的那几种写法确实被挡住了**，以及**它不会误伤普通句子**
 * （过度脱敏会让日志变得没法读，那等于把日志系统废掉）。
 */
class DiagLogTest {

    // ---------------------------------------------- 脱敏

    @Test
    fun `cookie 的常见写法被脱敏`() {
        val cases = listOf(
            "Cookie: SESSIONID=abcdefghijklmnop",
            "cookie=abcdefghijklmnop",
            "\"cookie\":\"abcdefghijklmnop\"",
            "请求头 cookie: abcdefghijklmnop;",
        )
        for (c in cases) {
            val out = DiagLog.redact(c)
            assertFalse("`$c` 里的值必须被遮住，实际输出：$out", out.contains("abcdefghijklmnop"))
            assertTrue("脱敏后要保留键名，否则看不出这行原本是什么：$out", out.contains("ookie", ignoreCase = true))
        }
    }

    @Test
    fun `token 与密码被脱敏`() {
        for (c in listOf(
            "access_token=abcdefghijklmnop",
            "password: hunter2hunter2",
            "api_key=sk-abcdefghijklmnop",
            "Authorization: Bearer abcdefghijklmnop",
        )) {
            val out = DiagLog.redact(c)
            assertTrue("`$c` 里的长值必须被遮住：$out", !out.contains("abcdefghijklmnop") && !out.contains("hunter2hunter2"))
        }
    }

    @Test
    fun `脱敏保留值的长度`() {
        // 保留长度是有用的：排查时"长度不对"本身就是线索（比如 token 被截断了）
        val out = DiagLog.redact("token=abcdefghijklmnop")
        assertTrue("应当能看出原值有多长：$out", out.contains("16"))
    }

    @Test
    fun `短值不脱敏`() {
        // 8 字符以下不当凭据：否则 `auth=1`、`sign=ok` 这类正常内容会被遮掉
        val out = DiagLog.redact("auth=1 sign=ok token=short")
        assertEquals("短值应当原样保留，过度脱敏会让日志读不了", "auth=1 sign=ok token=short", out)
    }

    @Test
    fun `普通句子不被误伤`() {
        val plain = "开始下载 https://example.com/a.zip，线程数 16，文件大小 1024 字节"
        assertEquals("不含敏感键名的句子必须原样保留", plain, DiagLog.redact(plain))
    }

    @Test
    fun `一句话里多个凭据都被处理`() {
        val out = DiagLog.redact("headers: cookie=abcdefghijklmnop, authorization=zyxwvutsrqponml")
        assertFalse("第一个凭据要遮住", out.contains("abcdefghijklmnop"))
        assertFalse("第二个凭据也要遮住", out.contains("zyxwvutsrqponml"))
    }

    // ---------------------------------------------- 级别

    @Test
    fun `级别顺序即严重程度`() {
        // 过滤逻辑靠 ordinal 比较，所以顺序不能乱 —— 这条测试就是那个约束的守卫
        assertTrue(DiagLog.Level.VERBOSE.ordinal < DiagLog.Level.DEBUG.ordinal)
        assertTrue(DiagLog.Level.DEBUG.ordinal < DiagLog.Level.INFO.ordinal)
        assertTrue(DiagLog.Level.INFO.ordinal < DiagLog.Level.WARN.ordinal)
        assertTrue(DiagLog.Level.WARN.ordinal < DiagLog.Level.ERROR.ordinal)
    }

    @Test
    fun `默认落盘级别是 INFO`() {
        // VERBOSE/DEBUG 默认不落盘：否则每次进度回调一次写盘，IO 放大到不可接受
        assertEquals(
            "默认应当只落盘 INFO 及以上",
            DiagLog.Level.INFO,
            DiagLog.minLevel(),
        )
    }

    @Test
    fun `级别标记唯一`() {
        val marks = DiagLog.Level.entries.map { it.mark }
        assertEquals("级别标记是 grep 的锚点，必须唯一", marks.size, marks.distinct().size)
    }

    // ---------------------------------------------- 分类

    @Test
    fun `分类文件名唯一`() {
        val names = DiagLog.Category.entries.map { it.fileName }
        assertEquals(
            "两个分类共用一个文件会让它们的日志混在一起",
            names.size,
            names.distinct().size,
        )
    }

    @Test
    fun `每个分类都有中文标签`() {
        for (c in DiagLog.Category.entries) {
            assertTrue("${c.name} 缺少可读标签", c.label.isNotBlank())
            assertTrue("${c.name} 的文件名应当以 .log 结尾", c.fileName.endsWith(".log"))
        }
    }

    @Test
    fun `关键子系统都有独立分类`() {
        // 这几个是用户报问题时最常涉及的，必须有地方落 ——
        // 缺一个就意味着那个子系统出问题时没有证据
        val labels = DiagLog.Category.entries.map { it.label }
        for (required in listOf("启动", "网络", "下载", "解析", "账号", "插件", "存储")) {
            assertTrue("缺少「$required」分类 —— 那个子系统出问题时将没有落盘证据", required in labels)
        }
    }

    // ---------------------------------------------- 旧调用点兼容

    @Test
    fun `字符串标签能映射到分类`() {
        // 库里原有 30 多个调用点用的是字符串标签。映射对了，
        // 它们的日志才会出现在正确的文件里（而不是全挤进 other）
        assertEquals(
            DiagLog.Category.PLUGIN,
            DiagLog.categoryFor("插件"),
        )
        assertEquals(DiagLog.Category.DOWNLOAD, DiagLog.categoryFor("aria2检测"))
        assertEquals(DiagLog.Category.BOOT, DiagLog.categoryFor("启动阶段"))
        assertEquals(
            "映射不到就进 OTHER，不能抛异常 —— 旧调用点不该因为日志改动而崩",
            DiagLog.Category.OTHER,
            DiagLog.categoryFor("某个没见过的标签"),
        )
    }
}
