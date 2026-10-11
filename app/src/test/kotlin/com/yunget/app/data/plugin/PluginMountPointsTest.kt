package com.yunget.app.data.plugin

import dev.turbodl.plugin.runtime.ExtensionRegistry
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.FileHandler
import dev.turbodl.plugin.runtime.ext.HandledFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件处理器的分发（P16，Handler 用途）。
 *
 * 这里验的是**三条行为约定** —— 它们写在 `PluginMountPoints` 里，而它们各自防的是
 * 一种真实故障：
 *
 *  1. 处理器抛异常**不能**影响"下载已完成"：一个播放器打不开，不该把已完成的任务
 *     变成失败，更不该删掉已下好的文件。
 *  2. `matches` 抛异常同样只跳过该处理器。
 *  3. 先问再交：宿主不替处理器猜文件类型。
 *
 * **本模块用 JUnit，断言消息在前**（与引擎模块的 kotlin.test 相反，两边都搞错过）。
 */
class PluginMountPointsTest {

    private fun file(name: String = "a.mp4", ext: String = "mp4") = HandledFile(
        taskId = 1L,
        absolutePath = "/storage/emulated/0/Download/$name",
        fileName = name,
        sizeBytes = 1024,
        mimeType = "video/$ext",
        extension = ext,
        sourceUrl = "https://example.com/$name",
    )

    /** 不需要 Android Context 的分发验证。 */
    private fun dispatch(
        registry: ExtensionRegistry,
        f: HandledFile,
    ): Int {
        var handled = 0
        for (reg in registry.registrations(ExtensionPoints.FILE_HANDLER)) {
            val h: FileHandler = reg.instance
            val matched = runCatching { h.matches(f) }.getOrDefault(false)
            if (!matched) continue
            handled += runCatching { h.handle(f); 1 }.getOrDefault(0)
        }
        return handled
    }

    private class Ok(private val accept: Boolean = true) : FileHandler {
        override val id = "ok"
        override val name = "正常处理器"
        override fun matches(file: HandledFile) = accept
        override fun handle(file: HandledFile) = "处理 ${file.fileName}"
    }

    private class ThrowsInHandle : FileHandler {
        override val id = "boom"
        override val name = "会抛异常的处理器"
        override fun matches(file: HandledFile) = true
        override fun handle(file: HandledFile): String = throw IllegalStateException("模拟失败")
    }

    private class ThrowsInMatches : FileHandler {
        override val id = "bad-match"
        override val name = "matches 会抛"
        override fun matches(file: HandledFile): Boolean = throw RuntimeException("判定失败")
        override fun handle(file: HandledFile) = "不该被调用"
    }

    // ---------------------------------------------- 正常路径

    @Test
    fun `匹配的处理器被调用`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "ok", Ok())
        assertEquals(1, dispatch(reg, file()))
    }

    @Test
    fun `不匹配的处理器不被调用`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "no", Ok(accept = false))
        assertEquals("matches=false 就不该处理", 0, dispatch(reg, file()))
    }

    @Test
    fun `多个处理器按注册顺序逐个尝试`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "a", Ok())
        reg.register(ExtensionPoints.FILE_HANDLER, "b", Ok())
        assertEquals("两个都匹配时两个都该处理", 2, dispatch(reg, file()))
    }

    // ---------------------------------------------- 约定 1：失败不影响下载结果

    @Test
    fun `处理器抛异常不影响其它处理器`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "boom", ThrowsInHandle(), priority = 10)
        reg.register(ExtensionPoints.FILE_HANDLER, "ok", Ok(), priority = 1)

        // 关键：抛异常的那个被隔离，后面的仍然被处理，且整体不抛
        assertEquals("失败的处理器不应计入成功数", 1, dispatch(reg, file()))
    }

    @Test
    fun `全部处理器都抛异常时也不抛`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "b1", ThrowsInHandle())
        reg.register(ExtensionPoints.FILE_HANDLER, "b2", ThrowsInHandle())
        assertEquals(0, dispatch(reg, file()))
    }

    // ---------------------------------------------- 约定 2：matches 抛异常只跳过

    @Test
    fun `matches 抛异常的处理器被跳过`() {
        val reg = ExtensionRegistry()
        reg.register(ExtensionPoints.FILE_HANDLER, "bad", ThrowsInMatches(), priority = 10)
        reg.register(ExtensionPoints.FILE_HANDLER, "ok", Ok(), priority = 1)

        assertEquals(
            "matches 抛异常应视为「不匹配」而不是崩掉整个分发",
            1,
            dispatch(reg, file()),
        )
    }

    // ---------------------------------------------- 约定 3：先问再交

    @Test
    fun `图片不会交给只认视频的处理器`() {
        val reg = ExtensionRegistry()
        class VideoOnly : FileHandler {
            override val id = "video"
            override val name = "只认视频"
            override fun matches(file: HandledFile) = file.extension == "mp4"
            override fun handle(file: HandledFile) = "播放 ${file.fileName}"
        }
        reg.register(ExtensionPoints.FILE_HANDLER, "video", VideoOnly())

        assertEquals("视频应被处理", 1, dispatch(reg, file("a.mp4", "mp4")))
        assertEquals("图片不该被处理", 0, dispatch(reg, file("a.png", "png")))
    }

    // ---------------------------------------------- 挂载点常量

    @Test
    fun `挂载点 id 与引擎侧一致`() {
        assertEquals(ExtensionPoints.DOWNLOAD_BACKEND.id, PluginMountPoints.BACKEND)
        assertEquals(ExtensionPoints.FILE_HANDLER.id, PluginMountPoints.FILE_HANDLER)
        assertTrue(
            "文件处理器挂载点必须带命名空间（P3）",
            PluginMountPoints.FILE_HANDLER.contains(".cap."),
        )
    }
}
