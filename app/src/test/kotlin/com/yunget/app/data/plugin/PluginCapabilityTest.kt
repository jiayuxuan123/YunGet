package com.yunget.app.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力标识规范（P3，设计文档 §12.1–12.4）。
 *
 * 这些规则是**插件之间的契约**，所以真正要验的是"错的判断会不会让一个插件冒充另一个"：
 * 裸名被放过 → 两个不同来源的能力混为一谈；版本被当成可互换 → 不兼容的契约对上号；
 * 保留命名空间不设防 → 任何人发一个 `yung.cap.*` 就被当成宿主的。
 *
 * 注意断言参数顺序：本模块用 JUnit 4，它的签名是
 * `assertTrue(String message, boolean condition)` —— **消息在前**。
 */
class PluginCapabilityTest {

    // ---------------------------------------------- §12.1 命名空间

    @Test
    fun `接受带命名空间与版本的标识`() {
        val r = PluginCapability.parse("yung.cap.file.handler.video@1.2.0")
        assertTrue("合规范的三段式应当被接受", r is PluginCapability.Result.Ok)
        val p = (r as PluginCapability.Result.Ok).parsed
        assertEquals("yung", p.namespace)
        assertEquals("file.handler.video", p.name)
        assertEquals("yung.cap.file.handler.video@1.2.0", p.canonical)
    }

    @Test
    fun `接受反向域名风格的多段命名空间`() {
        val r = PluginCapability.parse("dev.turbodl.cap.download.engine@1.3.0")
        assertTrue("多段命名空间应当被接受", r is PluginCapability.Result.Ok)
        assertEquals("dev.turbodl", (r as PluginCapability.Result.Ok).parsed.namespace)
    }

    @Test
    fun `拒绝裸名`() {
        // 设计文档点名的三个例子
        for (bare in listOf("download.engine", "cloud.parser", "ai.provider")) {
            assertTrue(
                "裸名 `$bare` 必须被拒：没有命名空间就无法判断这是谁的契约",
                PluginCapability.parse(bare) is PluginCapability.Result.Invalid,
            )
        }
    }

    @Test
    fun `未登记的裸名不算旧格式`() {
        // 这一条是整套规则能不能立住的关键：
        // 若"没见过的裸名"也被当旧格式放过，那随便起个新裸名就绕过了命名空间要求。
        assertTrue(
            "未登记的裸名不能当 legacy 放过",
            PluginCapability.parse("myBrandNewThing") is PluginCapability.Result.Invalid,
        )
    }

    @Test
    fun `已登记的裸名按旧格式读`() {
        assertTrue(
            "迁移期内已登记的旧名字仍可读",
            PluginCapability.parse("http") is PluginCapability.Result.Legacy,
        )
    }

    @Test
    fun `缺少 cap 中缀被拒`() {
        val r = PluginCapability.parse("dev.turbodl.download.engine@1.0.0")
        assertTrue("少了 .cap. 应当被拒", r is PluginCapability.Result.Invalid)
        assertTrue(
            "拒绝理由要指出缺的是 .cap.，作者才知道怎么改",
            (r as PluginCapability.Result.Invalid).reason.contains(".cap."),
        )
    }

    @Test
    fun `形状不合法的标识被拒`() {
        for (bad in listOf(
            "", "  ",
            "dev..cap.x@1.0.0", ".cap.x@1.0.0",
            "dev.turbodl.cap.@1.0.0", "9dev.cap.x@1.0.0",
            "dev.TurboDL.cap.storage@1.0.0",
        )) {
            assertTrue("`$bad` 应当被拒", PluginCapability.parse(bad) is PluginCapability.Result.Invalid)
        }
    }

    // ---------------------------------------------- §12.2 版本

    @Test
    fun `版本必须是三段式`() {
        for (bad in listOf(
            "yung.cap.x@1.2", "yung.cap.x@1", "yung.cap.x@v1.2.3",
            "yung.cap.x@1.2.3-rc1", "yung.cap.x@", "yung.cap.storage",
        )) {
            assertTrue("`$bad` 的版本不合法，应当被拒", PluginCapability.parse(bad) is PluginCapability.Result.Invalid)
        }
    }

    @Test
    fun `前导零的版本被拒`() {
        // "1.2.03" 若与 "1.2.3" 等价，两个不同契约会互相冒充
        assertTrue(
            "前导零会被当成同一个版本，必须拒",
            PluginCapability.parse("yung.cap.x@1.2.03") is PluginCapability.Result.Invalid,
        )
        assertTrue(
            "同上",
            PluginCapability.parse("yung.cap.x@01.2.3") is PluginCapability.Result.Invalid,
        )
    }

    // ---------------------------------------------- §12.4 保留命名空间

    @Test
    fun `第三方声明保留命名空间被拒`() {
        val r = PluginCapability.check(
            reservedNamespaceOwner = false,
            id = "yung.cap.task.executor@1.0.0",
        )
        assertTrue("保留命名空间不可被第三方占用", r is PluginCapability.Result.Invalid)
        assertTrue(
            "拒绝理由要说清是命名空间被保留，作者才知道该怎么改",
            (r as PluginCapability.Result.Invalid).reason.contains("保留命名空间"),
        )
    }

    @Test
    fun `官方声明保留命名空间被接受`() {
        assertTrue(
            "官方可以声明自己的命名空间",
            PluginCapability.check(reservedNamespaceOwner = true, id = "yung.cap.storage@1.0.0")
                is PluginCapability.Result.Ok,
        )
    }

    @Test
    fun `第三方用自己的命名空间被接受`() {
        assertTrue(
            "第三方用自己的反向域名命名空间应当被接受",
            PluginCapability.check(
                reservedNamespaceOwner = false,
                id = "com.example.cap.my.parser@1.0.0",
            ) is PluginCapability.Result.Ok,
        )
    }

    @Test
    fun `引擎命名空间同样受保护`() {
        assertFalse(
            "dev.turbodl 也是保留命名空间，第三方不能声明",
            PluginCapability.check(false, "dev.turbodl.cap.download.engine@1.0.0")
                is PluginCapability.Result.Ok,
        )
    }

    // ---------------------------------------------- canonical 稳定性

    @Test
    fun `canonical 形式稳定且可重复解析`() {
        val first = (PluginCapability.parse("  dev.turbodl.cap.storage@1.0.0 ") as PluginCapability.Result.Ok).parsed
        assertEquals("dev.turbodl.cap.storage@1.0.0", first.canonical)
        val again = (PluginCapability.parse(first.canonical) as PluginCapability.Result.Ok).parsed
        assertEquals("canonical 形式再解析一次必须得到同样的结果", first, again)
    }
}
