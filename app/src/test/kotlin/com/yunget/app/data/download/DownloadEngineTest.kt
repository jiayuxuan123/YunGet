package com.yunget.app.data.download

import com.yunget.app.data.prefs.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载引擎注册表与接口契约测试。
 *
 * 单测跑在纯 JVM 上（无 Android 运行时），因此**不能**实例化两个 manager
 * （它们的构造需要 Context/Room）。这里守住的是另一类真实缺陷：
 *
 * 1. **引擎 id 映射**：设置里存的是字符串，解析失败必须安全回落到默认引擎 ——
 *    否则用户改了设置、App 升级后 id 失效，会得到 null 或崩溃；
 * 2. **实现完整性**：两个实现类必须真的 implements [DownloadManager]。
 *    这是"引擎可切换"能否成立的静态前提：若哪天有人把某个实现改回具体类型、
 *    或漏了接口成员，这里会立刻红，而不是等到运行时报 ClassCastException；
 * 3. **默认引擎是 TurboDL**：避免误把兜底引擎变成默认。
 *
 * 端到端等价性（同 URL 下两引擎产物字节一致）需要真机，不在单测范围。
 */
class DownloadEngineTest {

    @Test
    fun `engine id round-trips`() {
        DownloadEngine.entries.forEach { engine ->
            assertEquals(
                "引擎 id 必须能往返解析（设置里存的就是这个字符串）",
                engine,
                DownloadEngine.fromId(engine.id),
            )
        }
    }

    @Test
    fun `unknown or blank engine id falls back to TurboDL`() {
        // 未知 id（例如未来版本删掉的引擎）不能抛异常，也不能返回 null：
        // 用户升级后设置里可能残留旧 id，必须安全回落到默认引擎。
        assertEquals(DownloadEngine.TURBODL, DownloadEngine.fromId("no-such-engine"))
        assertEquals(DownloadEngine.TURBODL, DownloadEngine.fromId(null))
        assertEquals(DownloadEngine.TURBODL, DownloadEngine.fromId(""))
    }

    @Test
    fun `default engine is TurboDL`() {
        assertEquals(
            "默认引擎必须是 TurboDL —— 内置兼容引擎只作兜底",
            DownloadEngine.TURBODL,
            DownloadEngine.fromId(SettingsRepository.DEFAULT_DOWNLOAD_ENGINE),
        )
    }

    @Test
    fun `both implementations satisfy the DownloadManager interface`() {
        // 静态前提：两个实现都必须真的实现接口（而不是靠 typealias 冒充）。
        // 用 isAssignableFrom 检查，无需构造实例。
        assertTrue(
            "TurboDownloadManager 必须实现 DownloadManager 接口",
            DownloadManager::class.java.isAssignableFrom(TurboDownloadManager::class.java),
        )
        assertTrue(
            "LegacyDownloadManager 必须实现 DownloadManager 接口（否则引擎无法切换）",
            DownloadManager::class.java.isAssignableFrom(LegacyDownloadManager::class.java),
        )
    }

    @Test
    fun `every registered engine id is non-blank and unique`() {
        val ids = DownloadEngine.entries.map { it.id }
        assertTrue("引擎 id 不能为空", ids.all { it.isNotBlank() })
        assertEquals("引擎 id 必须唯一（设置按 id 持久化）", ids.size, ids.toSet().size)
    }
}
