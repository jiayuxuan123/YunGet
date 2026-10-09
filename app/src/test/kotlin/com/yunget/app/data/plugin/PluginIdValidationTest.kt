package com.yunget.app.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件 id 校验与脚本文件名的规则测试（纯 JVM，不依赖 Android 运行时）。
 *
 * ## 为什么这是安全测试，不是"工具函数测试"
 *
 * 插件 id 直接参与拼接脚本的落盘路径（`filesDir/plugins/scripts/<id>_<sha8>.js`）。
 * 脚本来自剪贴板与网络，其自报 id 是**不可信输入**：
 *   · 允许 `..` / `/` / `\` → 可写应用私有目录里任意文件（覆盖 prefs、数据库、其它插件）；
 *   · 允许大写或非 ASCII → 在大小写不敏感 / 非 UTF-8 的文件系统上，
 *     "两个不同 id"可能落到同一个文件，卸载 A 会删掉 B 的脚本；
 *   · 允许空/超长 → 文件名退化或超出文件系统上限，安装"成功"但脚本永远加载不了。
 *
 * 这些错误都不会立刻崩，只会**安静地**留下越权写文件或互相覆盖的后果，
 * 所以必须把规则钉死在这里。文件名规则另外要求：无论输入多脏，
 * 输出的文件名都不能含路径分隔符或 `..`（[PluginIds.fileNameFor] 是最后一道纯函数防线）。
 */
class PluginIdValidationTest {

    /** 所有出现在断言消息里的输入都要能一眼看懂：加引号 + 显示长度。 */
    private fun show(raw: String): String = "\"$raw\"（长度 ${raw.length}）"

    private val safeFileName = Regex("[A-Za-z0-9._-]+")

    // ---------------------------------------------------------------- validate：合法

    @Test
    fun validIdsPass() {
        for (id in listOf("parser.example", "a.b-c1", "abc", "a-b", "plugin.v1.2.3", "a".repeat(64))) {
            assertEquals(
                "合法 id 必须原样通过（不做任何改写，用户看到的与库里存的要一致）：$id",
                id,
                PluginIds.validate(id),
            )
        }
    }

    @Test
    fun validIdIsNotRewritten() {
        // 归一化（去空格/转小写）会让"用户以为装了 A、实际装了 B"，
        // 所以校验只有通过/拒绝两种结果，没有"修正"
        val id = "parser.example"
        assertSame(id, PluginIds.validate(id))
    }

    // ---------------------------------------------------------------- validate：路径穿越与分隔符

    @Test
    fun traversalAttemptsAreRejected() {
        val attacks = listOf(
            "../etc/passwd",
            "..",
            "..%2f..%2fetc",
            "a/../../b",
            "a/b",
            "a\\b",
            "..\\..\\evil",
            "C:\\Windows\\System32\\evil",
            "a\u0000b",          // NUL 截断（老文件系统上会截断路径）
            "a\nb",
            "a\tb",
            "a b",
            "/abs",
            "./rel",
            "~/home",
            "a:b",
            "a*b",
            "a?b",
            "a\"b",
        )
        for (raw in attacks) {
            assertNull("含路径语义/非法字符的 id 必须被拒：${show(raw)}", PluginIds.validate(raw))
        }
    }

    @Test
    fun doubleDotIsRejectedEvenWithoutSeparators() {
        assertNull("`..` 是路径语义，任何位置都不允许", PluginIds.validate("a..b"))
        assertNull(PluginIds.validate("..."))
        assertNull(PluginIds.validate("..a"))
        assertNull(PluginIds.validate("a.."))
    }

    // ---------------------------------------------------------------- validate：首尾与长度

    @Test
    fun leadingOrTrailingSeparatorRejected() {
        for (raw in listOf(".abc", "-abc", "abc.", "abc-", ".", "-", ".-", "-.")) {
            assertNull("首尾为 `.`/`-` 的 id 必须被拒：${show(raw)}", PluginIds.validate(raw))
        }
    }

    @Test
    fun lengthBounds() {
        assertNull("空串必须被拒", PluginIds.validate(""))
        assertNull("少于下限必须被拒", PluginIds.validate("a".repeat(PluginIds.MIN_LENGTH - 1)))
        assertNull("超过上限必须被拒", PluginIds.validate("a".repeat(PluginIds.MAX_LENGTH + 1)))
        assertNull("超长（200 字符）必须被拒", PluginIds.validate("a".repeat(200)))

        // 边界本身是合法的：改 MIN/MAX 时这两个断言会立刻提醒
        assertEquals("a".repeat(PluginIds.MIN_LENGTH), PluginIds.validate("a".repeat(PluginIds.MIN_LENGTH)))
        assertEquals("a".repeat(PluginIds.MAX_LENGTH), PluginIds.validate("a".repeat(PluginIds.MAX_LENGTH)))
    }

    @Test
    fun uppercaseAndNonAsciiRejected() {
        for (raw in listOf("Parser.Example", "PARSER", "parser.Example", "我的插件", "插件.parser", "parser 示例", "café.js", "😀".repeat(3))) {
            assertNull("大写/中文/空格/emoji 一律拒绝（大小写不敏感文件系统会互相覆盖）：${show(raw)}", PluginIds.validate(raw))
        }
    }

    // ---------------------------------------------------------------- 从文件名派生 id

    @Test
    fun derivedIdIsAlwaysValid() {
        val junkNames = listOf(
            "../../evil.js",
            "..\\..\\evil.js",
            "/etc/passwd",
            "C:\\Windows\\System32\\evil.js",
            "我的插件.js",
            "我的 插件 v2.JS",
            "a".repeat(200) + ".js",
            "a".repeat(200),
            "",
            "   ",
            "...",
            "!!!",
            "/",
            "\\",
            "a/b/c/../../d.js",
            ".hidden",
            "-weird-",
            "UPPER.CASE.JS",
            "emoji😀.js",
            "..",
            "con.js",
            "文件",
            "%2e%2e%2fevil.js",
            "plugin\u0000.js",
        )
        for (name in junkNames) {
            val id = PluginIds.deriveFromFileName(name)
            assertEquals(
                "派生出的 id 必须自身合法（文件名：${show(name)} → id=$id）",
                id,
                PluginIds.validate(id),
            )
            assertTrue("派生 id 不得超长：$id", id.length <= PluginIds.MAX_LENGTH)
        }
    }

    @Test
    fun deriveStripsDirectoriesAndExtension() {
        assertEquals("只取最后一段路径 + 去掉 .js", "evil", PluginIds.deriveFromFileName("../../evil.js"))
        assertEquals("反斜杠路径同样只取最后一段", "evil", PluginIds.deriveFromFileName("..\\..\\evil.js"))
        assertEquals("正常文件名原样使用", "parser.example", PluginIds.deriveFromFileName("parser.example.js"))
        assertEquals("没有扩展名也能用", "parser-example", PluginIds.deriveFromFileName("parser-example"))
    }

    @Test
    fun deriveFallsBackToStableHashForUnusableNames() {
        // 中文名：字母/数字全被过滤掉，只能走摘要兜底
        val a = PluginIds.deriveFromFileName("我的插件.js")
        val b = PluginIds.deriveFromFileName("我的插件.js")
        assertEquals("同一个文件名必须派生出同一个 id（否则每次导入都变成新插件）", a, b)
        assertTrue("兜底 id 要以 plugin. 开头：$a", a.startsWith(PluginIds.FALLBACK_PREFIX + "."))
        assertEquals(a, PluginIds.validate(a))

        // 不同文件名不得碰撞（否则后导入的会覆盖先导入的）
        assertNotEquals(a, PluginIds.deriveFromFileName("我的另一个插件.js"))
    }

    @Test
    fun deriveWithoutFileNameStillUsable() {
        val id = PluginIds.deriveFromFileName(null)
        assertEquals("没有文件名也要给出合法 id", id, PluginIds.validate(id))
        assertEquals("空输入必须是确定性的", id, PluginIds.deriveFromFileName(null))
    }

    // ---------------------------------------------------------------- 文件名

    @Test
    fun fileNameIsAlwaysPathSafe() {
        val dirtyIds = listOf(
            "parser.example",
            "a".repeat(200),
            "../../etc/passwd",
            "..",
            "",
            "...",
            "我的插件",
            "/",
            "a/b",
            "a\\b",
            "-.-",
            "UPPER.CASE",
        )
        for (id in dirtyIds) {
            for (sha in listOf("1a2b3c4d", "", "ZZZZ", "../../etc")) {
                val name = PluginIds.fileNameFor(id, sha)
                assertTrue("文件名只允许 [A-Za-z0-9._-]：$name（id=${show(id)}）", safeFileName.matches(name))
                assertFalse("文件名不得含路径分隔符：$name", name.contains('/'))
                assertFalse("文件名不得含反斜杠：$name", name.contains('\\'))
                assertFalse("文件名不得含 `..`：$name", name.contains(".."))
                assertTrue("文件名必须以 .js 结尾：$name", name.endsWith(PluginIds.SCRIPT_SUFFIX))
            }
        }
    }

    @Test
    fun fileNameCarriesIdAndDigest() {
        assertEquals(
            "文件名 = <id>_<sha8>.js",
            "parser.example_1a2b3c4d.js",
            PluginIds.fileNameFor("parser.example", "1a2b3c4d"),
        )
        // 摘要段只留十六进制：传进来脏字符也不能进文件名
        assertFalse(PluginIds.fileNameFor("parser.example", "../../x").contains(".."))
        // 同一插件不同版本 → 不同文件（升级时能原子切换，不会覆盖正在使用的脚本）
        assertNotEquals(
            PluginIds.fileNameFor("parser.example", "11111111"),
            PluginIds.fileNameFor("parser.example", "22222222"),
        )
    }

    // ---------------------------------------------------------------- 脚本自报 id

    @Test
    fun declaredIdIsReadFromDefineMeta() {
        val script = """
            // 示例插件
            plugin.defineMeta({ id: 'parser.example', name: "示例", version: '1.2.0' });
            plugin.requires({ permissions: ['http', "storage"], abiMajor: 1 });
        """.trimIndent()

        assertEquals("parser.example", PluginIds.extractDeclaredId(script))
        assertEquals("示例", PluginIds.stringField(PluginIds.defineMetaBody(script), "name"))
        assertEquals("1.2.0", PluginIds.stringField(PluginIds.defineMetaBody(script), "version"))
        assertEquals(
            listOf("http", "storage"),
            PluginIds.stringListField(PluginIds.requiresBody(script), "permissions"),
        )
    }

    @Test
    fun declaredIdIsReadFromPluginIdAssignment() {
        val script = """
            plugin.id = "parser.example";
        """.trimIndent()
        assertEquals("parser.example", PluginIds.extractDeclaredId(script))
    }

    @Test
    fun noDeclaredIdMeansDeriveFromFileName() {
        val script = """
            plugin.defineMeta({ name: '无 id 插件', version: '1.0.0' });
        """.trimIndent()
        assertNull("没声明 id 时必须返回 null，交给文件名派生", PluginIds.extractDeclaredId(script))
        assertNull("空脚本不得凭空造出 id", PluginIds.extractDeclaredId(""))
    }

    @Test
    fun invalidDeclaredIdIsReturnedVerbatimSoCallerCanReject() {
        val script = """plugin.defineMeta({ id: '../evil' });"""
        val declared = PluginIds.extractDeclaredId(script)
        assertEquals("读取层不做过滤，原样返回", "../evil", declared)
        assertNull("过滤器必须拒绝它 —— 调用方据此让安装失败，而不是退回文件名派生", PluginIds.validate(declared!!))
    }

    @Test
    fun permissionsAsPlainStringIsAlsoAccepted() {
        val script = """plugin.requires({ permissions: "http crypto" });"""
        assertEquals(
            listOf("http", "crypto"),
            PluginIds.stringListField(PluginIds.requiresBody(script), "permissions"),
        )
        assertTrue("没有 requires 时返回空列表，不抛异常", PluginIds.stringListField(null, "permissions").isEmpty())
    }
}
