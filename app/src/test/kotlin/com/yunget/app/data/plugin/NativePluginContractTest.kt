package com.yunget.app.data.plugin

import dev.turbodl.plugin.native.NativePluginLoadException
import dev.turbodl.plugin.native.NativePluginRisk
import dev.turbodl.plugin.native.describe
import dev.turbodl.plugin.native.toPluginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2（原生）插件的契约层（P16）。
 *
 * 引擎侧只定契约、不实现加载（加载需要 Android 的 dex 机制），所以这里能测的是
 * **契约本身**：属性映射对不对、风险有没有被如实描述、kind 有没有和 JS 分开。
 *
 * 真正"装得上、卸得掉"的验证需要 Android 运行时（`DexClassLoader`），
 * 在纯 JVM 单测里做不到 —— 那部分由 App 的集成路径覆盖。
 */
class NativePluginContractTest {

    // ---------------------------------------------- kind 与属性

    @Test
    fun `L2 的 kind 与 JS 分开`() {
        assertEquals("native", dev.turbodl.plugin.native.KIND)
        assertFalse(
            "L2 不能复用 js 的 kind —— 否则两个 loader 会抢同一个来源",
            dev.turbodl.plugin.native.KIND == "js",
        )
    }

    @Test
    fun `来源被映射成引擎认识的 PluginSource`() {
        val jar = java.io.File("/data/user/0/com.yunget.app/files/plugins/native/demo.jar")
        val src = dev.turbodl.plugin.native.NativePluginSource(
            pluginId = "adapter.demo",
            jarFile = jar,
            entryClass = "com.example.Adapter",
            displayName = "演示适配器",
        ).toPluginSource()

        assertEquals(dev.turbodl.plugin.native.KIND, src.kind)
        assertEquals(jar.absolutePath, src.uri)
        assertEquals("adapter.demo", src.attributes[dev.turbodl.plugin.native.ATTR_PLUGIN_ID])
        assertEquals("com.example.Adapter", src.attributes[dev.turbodl.plugin.native.ATTR_ENTRY_CLASS])
        assertEquals("演示适配器", src.attributes[dev.turbodl.plugin.native.ATTR_DISPLAY_NAME])
        assertTrue(
            "必须带上 API 版本要求，否则宿主不会做版本校验",
            src.attributes[dev.turbodl.plugin.native.ATTR_API_VERSION]?.contains('.') == true,
        )
    }

    @Test
    fun `属性键是公开可读的`() {
        // 这些常量曾经是 internal，结果 App 侧连读都读不到 ——
        // 编译期才发现，L2 在真机上根本装不上。回归测试钉住这个边界。
        assertEquals("pluginId", dev.turbodl.plugin.native.ATTR_PLUGIN_ID)
        assertEquals("entryClass", dev.turbodl.plugin.native.ATTR_ENTRY_CLASS)
        assertEquals("displayName", dev.turbodl.plugin.native.ATTR_DISPLAY_NAME)
        assertEquals("requiredApiVersion", dev.turbodl.plugin.native.ATTR_API_VERSION)
    }

    // ---------------------------------------------- 失败分类

    @Test
    fun `加载异常带可展示的原因与分类`() {
        val e = NativePluginLoadException(
            "jar 里没有入口类 X", NativePluginLoadException.Kind.ENTRY_CLASS_MISSING,
        )
        assertTrue("原因必须能直接显示给用户", e.message!!.contains("入口类"))
        assertEquals(NativePluginLoadException.Kind.ENTRY_CLASS_MISSING, e.kind)
    }

    @Test
    fun `失败分类覆盖了需要不同处置的场景`() {
        // 分类的意义在于宿主能给出不同的处置建议：
        // "重新安装" / "更新应用" / "换设备" 说的是三件不同的事。
        val kinds = NativePluginLoadException.Kind.entries.map { it.name }
        for (required in listOf(
            "JAR_UNREADABLE", "ENTRY_CLASS_MISSING", "ENTRY_CLASS_INVALID",
            "API_VERSION_TOO_LOW", "LOAD_FAILED",
        )) {
            assertTrue("缺少失败分类 $required —— 那类问题将拿不到对应的处置建议", required in kinds)
        }
    }

    // ---------------------------------------------- 风险如实描述

    @Test
    fun `风险清单覆盖四条事实`() {
        // 设计文档第 8.4 章要求"不能伪装成完全沙箱"。
        // 这四条是 L2 的客观事实，逐条都要能被说出来。
        val risks = NativePluginRisk.entries
        assertEquals("风险清单的条数", 4, risks.size)
        assertNotNull(NativePluginRisk.SAME_PRIVILEGE_AS_HOST)
        assertNotNull(NativePluginRisk.CAN_REGISTER_EXTENSIONS)
        assertNotNull(NativePluginRisk.HOLDS_IRREVERSIBLE_RESOURCES)
        assertNotNull(NativePluginRisk.CRASH_NOT_ISOLATED)
    }

    @Test
    fun `风险描述不含沙箱或安全的暗示`() {
        for (r in NativePluginRisk.entries) {
            val text = r.describe()
            assertTrue("描述不能为空", text.isNotBlank())
            for (banned in listOf("沙箱", "隔离运行", "安全", "受控", "受限环境")) {
                assertFalse(
                    "${r.name} 的描述里出现了「$banned」—— L2 没有沙箱，" +
                        "写这类词就是给用户虚假的安全感（设计文档第 8.4 章明确禁止）：$text",
                    text.contains(banned),
                )
            }
        }
    }

    @Test
    fun `每条风险描述都是具体的陈述而非套话`() {
        // 逐条看一眼：任何一条如果只剩"请注意安全"这种话，就等于没说。
        assertTrue(
            NativePluginRisk.SAME_PRIVILEGE_AS_HOST.describe().contains("进程内"),
        )
        assertTrue(
            NativePluginRisk.CAN_REGISTER_EXTENSIONS.describe().contains("下载"),
        )
        assertTrue(
            NativePluginRisk.HOLDS_IRREVERSIBLE_RESOURCES.describe().contains("原生库"),
        )
        assertTrue(
            NativePluginRisk.CRASH_NOT_ISOLATED.describe().contains("应用"),
        )
    }

    // ---------------------------------------------- 重启语义

    @Test
    fun `L2 标记为需要重启`() {
        // 类加载器不卸载类，已加载的类无法替换 —— 这是结构性的，
        // 不是"暂时没做热替换"。所以界面必须提示重启，且不能含糊。
        assertTrue("L2 插件必须标记为需重启", PluginLevel.NATIVE.requiresRestart)
        assertFalse("L1 插件可以热加载，不应提示重启", PluginLevel.JS.requiresRestart)
    }

    @Test
    fun `L2 的说明写明它没有沙箱且能力等同宿主`() {
        val note = PluginLevel.NATIVE.capabilityNote()
        assertTrue("必须写明能力与宿主等同（事实陈述）：$note", note.contains("等同"))
        assertTrue("必须写明没有沙箱：$note", note.contains("沙箱"))
        assertTrue("必须写明声明的能力只是展示、不是限制：$note", note.contains("不是限制"))
    }

    @Test
    fun `L1 的说明不说没有沙箱`() {
        // L1 确实在沙箱里，给它写"没有沙箱"是事实错误
        assertFalse(
            PluginLevel.JS.capabilityNote().contains("没有沙箱"),
        )
    }
}
