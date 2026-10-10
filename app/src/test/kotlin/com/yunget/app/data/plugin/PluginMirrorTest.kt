package com.yunget.app.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插件源镜像与回退（P5）的策略测试。
 *
 * ## 为什么这些断言值得写死
 *
 * 本功能最容易出错的不是"取不到就换下一个"（那是显然的），而是**候选顺序**：
 *
 *  - 直连必须永远在最后。一旦某次改动把直连排到了镜像前面，用户的流量就会开始
 *    默认过第三方 CDN，而且界面上看不出来；
 *  - 全局镜像只对 GitHub 系地址生效。把自建源的地址也推给镜像，是把用户自己的
 *    内容交给陌生第三方，而且没有任何好处；
 *  - 回退只对"取不到字节"生效，**不**对"校验不通过"生效。这条是安全边界，
 *    它靠代码顺序保证（取字节在 [PluginMirror.fetchFirst]、校验在其后），
 *    所以这里同时用一条读源码的静态测试把它钉住（见本文件末尾）。
 */
class PluginMirrorTest {

    private val rawGitHub = "https://raw.githubusercontent.com/owner/repo/main/plugins.json"
    private val rawSelfHosted = "https://plugins.example.com/plugins.json"

    // ---------------------------------------------- 候选顺序

    @Test
    fun `没有镜像时只有直连`() {
        val list = PluginMirror.candidates(rawGitHub)
        assertEquals(1, list.size)
        assertEquals(PluginMirror.Route.DIRECT, list[0].route)
        assertEquals(rawGitHub, list[0].url)
    }

    @Test
    fun `GitHub 地址配了全局镜像时是镜像在前直连在后`() {
        val list = PluginMirror.candidates(rawGitHub, globalPrefix = "https://gh.dpik.top/")
        assertEquals(2, list.size)
        assertEquals(PluginMirror.Route.GLOBAL_GITHUB_MIRROR, list[0].route)
        assertEquals("https://gh.dpik.top/$rawGitHub", list[0].url)
        // 这条是安全底线：直连永远排最后
        assertEquals(PluginMirror.Route.DIRECT, list[1].route)
        assertEquals(rawGitHub, list[1].url)
    }

    @Test
    fun `非 GitHub 地址不套全局镜像`() {
        val list = PluginMirror.candidates(rawSelfHosted, globalPrefix = "https://gh.dpik.top/")
        assertEquals(1, list.size)
        assertEquals(
            "自建源的地址不该被推到第三方镜像上（用户没要求，也对镜像服务没好处）",
            PluginMirror.Route.DIRECT,
            list[0].route,
        )
    }

    @Test
    fun `本源自定义镜像对任意主机生效`() {
        val list = PluginMirror.candidates(rawSelfHosted, sourceMirror = "https://my.mirror/")
        assertEquals(2, list.size)
        assertEquals(PluginMirror.Route.SOURCE_MIRROR, list[0].route)
        assertEquals("https://my.mirror/$rawSelfHosted", list[0].url)
        assertEquals(PluginMirror.Route.DIRECT, list[1].route)
    }

    @Test
    fun `本源镜像优先于全局镜像`() {
        val list = PluginMirror.candidates(
            rawGitHub,
            globalPrefix = "https://gh.dpik.top/",
            sourceMirror = "https://my.mirror/",
        )
        assertEquals(2, list.size)
        assertEquals(PluginMirror.Route.SOURCE_MIRROR, list[0].route)
        assertFalse(
            "配了本源镜像就不该再走全局镜像",
            list.any { it.route == PluginMirror.Route.GLOBAL_GITHUB_MIRROR },
        )
    }

    @Test
    fun `镜像前缀缺尾斜杠时补上`() {
        val list = PluginMirror.candidates(rawGitHub, globalPrefix = "https://gh.dpik.top")
        assertEquals("https://gh.dpik.top/$rawGitHub", list[0].url)
    }

    @Test
    fun `镜像与直连产出同一地址时只试一次`() {
        // 前缀写成原地址本身时会拼出一样的结果；重复一次只会白白多花一个超时
        val weird = "https://x.example/"
        val list = PluginMirror.candidates(rawGitHub, sourceMirror = weird)
        assertEquals(2, list.size)
        assertEquals(list.map { it.url }.distinct().size, list.size)
    }

    @Test
    fun `非 http 地址不产生候选`() {
        assertTrue(PluginMirror.candidates("").isEmpty())
        assertTrue(PluginMirror.candidates("file:///etc/passwd").isEmpty())
        assertTrue(PluginMirror.candidates("ftp://x/y").isEmpty())
        // 即便配了镜像也不该把非 http 的地址"映射"出去
        assertTrue(PluginMirror.candidates("file:///etc/passwd", sourceMirror = "https://m/").isEmpty())
    }

    // ---------------------------------------------- 回退真的会发生

    @Test
    fun `首选地址不可用时自动落到下一个`() {
        val tried = mutableListOf<String>()
        val candidates = PluginMirror.candidates(rawGitHub, globalPrefix = "https://gh.dpik.top/")
        val hit = PluginMirror.fetchFirst(candidates) { c ->
            tried += c.url
            if (c.route == PluginMirror.Route.DIRECT) "索引内容" else null
        }
        assertEquals(listOf("https://gh.dpik.top/$rawGitHub", rawGitHub), tried)
        assertEquals(PluginMirror.Route.DIRECT, hit?.candidate?.route)
        assertEquals("索引内容", hit?.value)
    }

    @Test
    fun `首选地址取到内容就不再看后面`() {
        val tried = mutableListOf<String>()
        val candidates = PluginMirror.candidates(rawGitHub, sourceMirror = "https://my.mirror/")
        PluginMirror.fetchFirst(candidates) { c ->
            tried += c.url
            "内容"
        }
        assertEquals(1, tried.size)
        assertEquals("https://my.mirror/$rawGitHub", tried[0])
    }

    @Test
    fun `单个地址抛异常不中断整条回退链`() {
        val candidates = PluginMirror.candidates(rawGitHub, sourceMirror = "https://my.mirror/")
        var seen = 0
        val hit = PluginMirror.fetchFirst(candidates) { c ->
            seen++
            if (c.route == PluginMirror.Route.SOURCE_MIRROR) throw java.io.IOException("连接超时") else "内容"
        }
        assertEquals(2, seen)
        assertEquals(PluginMirror.Route.DIRECT, hit?.candidate?.route)
    }

    @Test
    fun `全部候选都失败时返回 null`() {
        val candidates = PluginMirror.candidates(rawGitHub, sourceMirror = "https://my.mirror/")
        assertNull(PluginMirror.fetchFirst(candidates) { null })
        assertNull(PluginMirror.fetchFirst(emptyList()) { "内容" })
    }

    // ---------------------------------------------- 通道可见性

    @Test
    fun `每个通道都有可展示的名字`() {
        val names = PluginMirror.Route.entries.map { it.describe() }
        assertEquals(PluginMirror.Route.entries.size, names.distinct().size)
        assertTrue(names.all { it.isNotBlank() })
    }

    // ---------------------------------------------- 安全边界（静态核对）

    /**
     * **镜像不得获得信任权**：取字节与校验必须分开，且取字节的实现里不能出现任何校验动作。
     *
     * 为什么不写运行时测试而读源码：这条边界靠的是"哪一步在哪个函数里"的代码顺序，
     * 运行时断言要造一个"镜像返回了内容但摘要不符"的场景，然后断言"没有继续试直连"——
     * 那需要把真签名链整个架起来，代价高、覆盖面反而更窄。直接核对源码里
     * "取字节函数只做取字节"这一条，覆盖的是真正会被后人改坏的地方。
     */
    @Test
    fun `取字节与校验是两个函数_取字节里不出现任何校验动作`() {
        val src = File("src/main/kotlin/com/yunget/app/data/plugin/MarketClient.kt").readText()
        val fetchBytes = src.substringAfter("private fun httpGetBytes(").substringBefore("private fun mirrorPrefix(")
        val forbidden = listOf("sha256Hex", "PluginSignature", "resolvePublicKey", "verifySelfReported")
        val hits = forbidden.filter { fetchBytes.contains(it) }
        assertTrue(
            "httpGetBytes() 里出现了校验相关调用：$hits\n" +
                "取字节只负责'把内容拿回来'，所有通道拿到的字节必须走同一条校验链。\n" +
                "一旦在这里按通道区别对待，'换镜像'就会变成绕过校验的后门。",
            hits.isEmpty(),
        )
        // 反向：校验必须发生在取字节之后，且确实调用了取字节
        val verify = src.substringAfter("private suspend fun fetchAndVerify(")
            .substringBefore("private fun resolvePublicKey(")
        assertTrue(
            "fetchAndVerify() 应当通过 httpGetBytes 取字节（保证走同一条回退链）",
            verify.contains("httpGetBytes("),
        )
        assertTrue(
            "fetchAndVerify() 应当做摘要与验签",
            verify.contains("sha256Hex()") && verify.contains("PluginSignature.verify("),
        )
    }

    /**
     * 回退链的**全部**候选都必须过同一条校验：这里核对 `fetchAndVerify` 里的校验段
     * 没有任何"如果来自镜像就跳过"的分支（形如 `if (route == ...)` / `viaMirror`）。
     */
    @Test
    fun `校验段不按来源区别对待`() {
        val src = File("src/main/kotlin/com/yunget/app/data/plugin/MarketClient.kt").readText()
        val verify = src.substringAfter("private suspend fun fetchAndVerify(")
            .substringBefore("private fun resolvePublicKey(")
        assertFalse(
            "校验段里出现了按通道分支的痕迹 —— 镜像与直连的字节必须一视同仁",
            verify.contains("viaMirror") || verify.contains("route ==") || verify.contains("DIRECT"),
        )
    }
}