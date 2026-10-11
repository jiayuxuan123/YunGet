package com.yunget.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎注册表（P9）。
 *
 * ## 这一项要验的是什么
 *
 * 总纲的验收标准是"**新增一个下载引擎不再需要改 Host 的条件分支**"。
 * 所以最关键的测试是 `新增引擎只需注册_不需要改Host`：它在运行时往注册表里
 * 加一个第四种引擎，断言注册表能查到它 —— 而调用方一行没改。
 *
 * ## 为什么不测构造（create）
 *
 * 构造一个真实的 [DownloadManager] 需要 `Context`、Room、OkHttpClient，
 * 那是集成测试的范围。本模块的**纯 JVM 单测跑不了 Android 运行时**
 * （项目里其他测试同样不碰 Android 类），所以这里只验**注册与查找**——
 * 而这恰好是 P9 的实质：把"有哪些引擎"从 Host 的 `when` 分支搬进一张表。
 *
 * 注意断言参数顺序：本模块用 JUnit 4，签名是
 * `assertX(String message, T actual)` —— **消息在前**。
 */
class DownloadEngineRegistryTest {

    /** 一个"第三方引擎"，只实现 provider 接口，不碰 Host 的任何代码。 */
    private class FakeEngineProvider(
        override val id: String,
        private val available: Boolean = true,
    ) : DownloadEngineRegistry.EngineProvider {
        override val displayName = "假引擎 $id"
        override val description = "测试用的外部引擎"
        override fun isAvailable() = available
        override fun unavailableReason() = "测试环境下被标为不可用"
        override fun create(context: android.content.Context): DownloadManager =
            throw UnsupportedOperationException("本测试只验注册与查找，不构造真实管理器")
    }

    // ---------------------------------------------- 内置清单

    @Test
    fun `内置三个引擎都已注册`() {
        val ids = DownloadEngineRegistry.all().map { it.id }
        assertTrue("TurboDL 必须在注册表里", "turbodl" in ids)
        assertTrue("兼容模式的旧引擎必须在注册表里", "legacy" in ids)
        assertTrue("aria2 必须在注册表里", "aria2" in ids)
    }

    @Test
    fun `默认引擎是清单里第一个`() {
        assertSame(
            "默认引擎应当就是清单第一项",
            DownloadEngineRegistry.all().first(),
            DownloadEngineRegistry.default(),
        )
        assertEquals("默认应当是 TurboDL", "turbodl", DownloadEngineRegistry.default().id)
    }

    @Test
    fun `每个内置引擎的文案都齐全`() {
        for (p in DownloadEngineRegistry.all()) {
            assertTrue("引擎 id 不能为空", p.id.isNotBlank())
            assertTrue("${p.id} 缺少展示名", p.displayName.isNotBlank())
            assertTrue("${p.id} 缺少说明", p.description.isNotBlank())
        }
    }

    @Test
    fun `引擎 id 不重复`() {
        val ids = DownloadEngineRegistry.all().map { it.id }
        assertEquals("id 重复会让设置页出现两个同名项", ids.size, ids.distinct().size)
    }

    // ---------------------------------------------- 核心验收：可扩展性

    @Test
    fun `新增引擎只需注册_不需要改Host`() {
        val before = DownloadEngineRegistry.all().size
        DownloadEngineRegistry.registerExternal(FakeEngineProvider("plugin-engine"))
        try {
            val after = DownloadEngineRegistry.all()
            assertEquals("注册一个新引擎应当只增加一项", before + 1, after.size)
            assertNotNull(
                "注册之后必须查得到 —— 这就是「新增引擎不用改 Host 分支」",
                DownloadEngineRegistry.find("plugin-engine"),
            )
            assertEquals(
                "外部注册的排在末尾，内置的保持在前（顺序稳定，设置页不会跳）",
                "plugin-engine",
                after.last().id,
            )
        } finally {
            DownloadEngineRegistry.unregisterExternal("plugin-engine")
        }
    }

    @Test
    fun `外部引擎可以注销`() {
        DownloadEngineRegistry.registerExternal(FakeEngineProvider("temp-engine"))
        assertNotNull("注册后应当查得到", DownloadEngineRegistry.find("temp-engine"))

        assertTrue("首次注销应当成功", DownloadEngineRegistry.unregisterExternal("temp-engine"))
        assertNull(
            "插件卸载后它的引擎不该继续出现在选择列表里",
            DownloadEngineRegistry.find("temp-engine"),
        )
        assertFalse(
            "重复注销返回 false（而不是抛异常）",
            DownloadEngineRegistry.unregisterExternal("temp-engine"),
        )
    }

    @Test
    fun `同id注册按覆盖处理`() {
        // 插件更新时是"换掉"而不是"并列"，否则设置页出现两个同名项
        DownloadEngineRegistry.registerExternal(FakeEngineProvider("dup-engine", available = true))
        DownloadEngineRegistry.registerExternal(FakeEngineProvider("dup-engine", available = false))
        try {
            val matches = DownloadEngineRegistry.all().filter { it.id == "dup-engine" }
            assertEquals("同 id 只能有一项", 1, matches.size)
            assertFalse("后注册的应当覆盖先前的", matches.first().isAvailable())
        } finally {
            DownloadEngineRegistry.unregisterExternal("dup-engine")
        }
    }

    @Test
    fun `注册不会影响内置清单`() {
        val builtinIds = DownloadEngineRegistry.all().map { it.id }
        DownloadEngineRegistry.registerExternal(FakeEngineProvider("another"))
        try {
            val now = DownloadEngineRegistry.all().map { it.id }
            assertEquals(
                "外部注册不能改变内置引擎的顺序或内容",
                builtinIds,
                now.take(builtinIds.size),
            )
        } finally {
            DownloadEngineRegistry.unregisterExternal("another")
        }
    }

    // ---------------------------------------------- id 解析

    @Test
    fun `按id查找`() {
        assertNotNull("内置的应当查得到", DownloadEngineRegistry.find("turbodl"))
        assertNull(DownloadEngineRegistry.find("nonexistent"))
        assertNull("null 应当安全返回 null", DownloadEngineRegistry.find(null))
        assertNull("空串应当安全返回 null", DownloadEngineRegistry.find(""))
        assertNull("空白串应当安全返回 null", DownloadEngineRegistry.find("   "))
    }

    @Test
    fun `可用性默认是true`() {
        // 纯 Kotlin 实现总是可用；只有依赖外部资源的引擎才需要覆盖。
        val plain = object : DownloadEngineRegistry.EngineProvider {
            override val id = "plain"
            override val displayName = "普通引擎"
            override val description = "不依赖外部资源"
            override fun create(context: android.content.Context): DownloadManager =
                throw UnsupportedOperationException()
        }
        assertTrue("没覆盖 isAvailable 的引擎应当默认为可用", plain.isAvailable())
    }

    @Test
    fun `不可用时能给出原因`() {
        val p = FakeEngineProvider("never", available = false)
        assertFalse(p.isAvailable())
        assertTrue(
            "标为不可用就必须能说清原因 —— 只显示「不可用」用户不知道怎么办",
            p.unavailableReason().isNotBlank(),
        )
    }
}
