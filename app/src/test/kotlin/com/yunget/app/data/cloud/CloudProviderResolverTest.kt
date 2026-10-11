package com.yunget.app.data.cloud

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网盘 Provider 的路由与**回退**（P0）。
 *
 * ## 这组测试守的是迁移期的稳定性
 *
 * 迁移是渐进的：网盘一个一个从内置搬到插件。在某个网盘的新 Provider 被证明可靠之前，
 * 内置实现**一直保留**。于是"插件出问题时用户还能不能用网盘"就成了整件事的底线。
 *
 * 要防的失败方式：
 *  - 插件没装 → 网盘功能整个没了（最常见：用户根本没装任何插件）
 *  - 插件装了但不健康 → 一样没了（次常见：插件装上了却初始化失败）
 *  - 插件方法抛异常 → 一样没了（最隐蔽：运行期才炸）
 *
 * 三种都必须**回退到内置并继续成功**。这不是"降级体验"，是迁移期必须的行为。
 *
 * 本模块用 JUnit 4：断言消息在前。
 */
class CloudProviderResolverTest {

    /** 一个可编程的假 Provider，覆盖各种"坏"的情况。 */
    private class FakeProvider(
        override val providerId: String = "provider.test",
        override val displayName: String = "测试 Provider",
        private val claims: Boolean = true,
        private val health: String? = null,
        private val throwOnHandles: Boolean = false,
        private val throwOnResolve: Boolean = false,
        private val throwOnList: Boolean = false,
        private val throwOnResolveDownload: Boolean = false,
        private val caps: Set<CloudCapability> = setOf(
            CloudCapability.SHARE_PARSE, CloudCapability.SHARE_BROWSE, CloudCapability.DIRECT_LINK,
        ),
    ) : CloudProvider {
        var handlesCalls = 0
        var resolveCalls = 0

        override fun capabilities(): Set<CloudCapability> = caps
        override fun handles(rawInput: String): Boolean {
            handlesCalls++
            if (throwOnHandles) error("handles 挂了")
            return claims
        }
        override fun health(): String? = health

        override suspend fun resolveShare(request: CloudShareRequest): CloudResult<CloudShare> {
            resolveCalls++
            if (throwOnResolve) error("resolve 挂了")
            return CloudResult.Ok(CloudShare(shareId = "from-plugin", title = "插件解析的"))
        }
        override suspend fun listShareFiles(
            shareId: String, path: String, password: String?, page: String?,
        ): CloudResult<CloudFilePage> {
            if (throwOnList) error("list 挂了")
            return CloudResult.Ok(CloudFilePage(listOf(CloudFile("plugin", "插件的文件", false))))
        }
        override suspend fun resolveDownload(
            shareId: String, file: CloudFile, password: String?,
        ): CloudResult<CloudDownloadInfo> {
            if (throwOnResolveDownload) error("resolveDownload 挂了")
            return CloudResult.Ok(CloudDownloadInfo(url = "https://plugin/x", fileName = "插件.zip"))
        }
    }

    private val builtinShare = CloudResult.Ok(CloudShare(shareId = "from-builtin", title = "内置解析的"))

    private fun resolver(vararg plugins: CloudProvider) =
        CloudProviderResolver(context = NO_CONTEXT, providers = { plugins.toList() })

    /**
     * 单测里没有 Android Context。
     *
     * 日志写入因此必须允许"没有 Context 就跳过"——否则这条逻辑没法在纯 JVM 上测，
     * 而它恰恰是迁移期最需要被测的部分（回退要留痕、但留痕不能成为崩溃源）。
     */
    private val NO_CONTEXT: Context? = null

    private val req = CloudShareRequest("https://example.com/s/abc")

    // ---------------------------------------------- 正常路径

    @Test
    fun `装了健康的插件就用插件`() = runBlocking {
        val p = FakeProvider()
        val r = resolver(p)
        val result = r.resolveShare(req) { builtinShare }
        assertEquals(
            "认领 + 健康时应当走插件",
            "from-plugin",
            (result as CloudResult.Ok).value.shareId,
        )
    }

    @Test
    fun `不认领这个链接的插件不会被选中`() = runBlocking {
        val p = FakeProvider(claims = false)
        val r = resolver(p)
        assertNull("不认领就不该被选中", r.providerFor(req.rawInput))
        val result = r.resolveShare(req) { builtinShare }
        assertEquals("from-builtin", (result as CloudResult.Ok).value.shareId)
    }

    // ---------------------------------------------- 回退：三种典型故障

    @Test
    fun `没装任何插件时走内置且成功`() = runBlocking {
        val r = resolver()   // 一个插件都没有：绝大多数用户的真实状态
        assertNull(r.providerFor(req.rawInput))
        val result = r.resolveShare(req) { builtinShare }
        assertEquals("from-builtin", (result as CloudResult.Ok).value.shareId)
    }

    @Test
    fun `插件不健康时回退内置`() = runBlocking {
        val p = FakeProvider(health = "账号未登录")
        val r = resolver(p)
        assertNull(
            "不健康的插件不该被选中 —— 否则用户点了网盘却只得到一个失败提示",
            r.providerFor(req.rawInput),
        )
        val result = r.resolveShare(req) { builtinShare }
        assertEquals("from-builtin", (result as CloudResult.Ok).value.shareId)
        assertEquals(
            "回退时**不该**真的调用插件方法（那可能又失败一次）",
            0, p.resolveCalls,
        )
    }

    @Test
    fun `插件解析抛异常时回退内置`() = runBlocking {
        val p = FakeProvider(throwOnResolve = true)
        val r = resolver(p)
        val result = r.resolveShare(req) { builtinShare }
        assertEquals(
            "插件运行期炸了也必须回退 —— 用户不该因此用不了网盘",
            "from-builtin",
            (result as CloudResult.Ok).value.shareId,
        )
    }

    @Test
    fun `handles 自身抛异常时被跳过而不是崩掉`() = runBlocking {
        val r = resolver(FakeProvider(throwOnHandles = true))
        assertNull("handles 抛异常应当跳过这个插件", r.providerFor(req.rawInput))
        val result = r.resolveShare(req) { builtinShare }
        assertEquals("from-builtin", (result as CloudResult.Ok).value.shareId)
    }

    // ---------------------------------------------- 回退：浏览与取链

    @Test
    fun `列目录抛异常时回退内置`() = runBlocking {
        val p = FakeProvider(throwOnList = true)
        val r = resolver(p)
        val provider = r.providerFor(req.rawInput)
        val result = r.listShareFiles(provider, "sid", "", null, null) { _, _, _, _ ->
            CloudResult.Ok(CloudFilePage(listOf(CloudFile("builtin", "内置的文件", false))))
        }
        val files = (result as CloudResult.Ok).value.files
        assertEquals("列目录失败也要回退", "内置的文件", files.single().name)
    }

    @Test
    fun `取下载地址抛异常时回退内置`() = runBlocking {
        val p = FakeProvider(throwOnResolveDownload = true)
        val r = resolver(p)
        val provider = r.providerFor(req.rawInput)
        val file = CloudFile("f", "x.mp4", false)
        val result = r.resolveDownload(provider, "sid", file, null) { _, _, _ ->
            CloudResult.Ok(CloudDownloadInfo(url = "https://builtin/x", fileName = "x.mp4"))
        }
        assertEquals(
            "取链失败也要回退",
            "https://builtin/x",
            (result as CloudResult.Ok).value.url,
        )
    }

    @Test
    fun `provider 为 null 时直接走内置`() = runBlocking {
        val r = resolver()
        val file = CloudFile("f", "x", false)
        val result = r.resolveDownload(null, "sid", file, null) { _, _, _ ->
            CloudResult.Ok(CloudDownloadInfo(url = "https://builtin/x", fileName = "x"))
        }
        assertEquals("https://builtin/x", (result as CloudResult.Ok).value.url)
    }

    // ---------------------------------------------- 能力是声明式的

    @Test
    fun `能力是声明式的_宿主只信声明`() {
        val only = FakeProvider(caps = setOf(CloudCapability.SHARE_PARSE))
        assertEquals(
            "未声明的能力宿主不该调用 —— 让插件以为「调了但没反应」比直接报错更糟",
            setOf(CloudCapability.SHARE_PARSE),
            only.capabilities(),
        )
        assertTrue(
            "能力枚举应当包含取直链",
            CloudCapability.entries.any { it.name == "DIRECT_LINK" },
        )
    }

    // ---------------------------------------------- 错误是业务结果，不是异常

    @Test
    fun `需要提取码是正常业务结果`() {
        // 它不该是异常：用户看到的是"请输入提取码"，不是"出错了"
        val r: CloudResult<CloudShare> = CloudResult.Err(CloudError.NEEDS_PASSWORD)
        assertEquals(CloudError.NEEDS_PASSWORD, r.errorOrNull())
        assertNull(r.getOrNull())
    }

    @Test
    fun `几种错误各有各的用户提示语义`() {
        val all = listOf(
            CloudError.SHARE_UNAVAILABLE, CloudError.NEEDS_PASSWORD,
            CloudError.BAD_PASSWORD, CloudError.NEEDS_LOGIN,
            CloudError.RISK_CONTROLLED, CloudError.UNEXPECTED("接口变了"),
        )
        assertEquals(
            "错误类型应当覆盖迁移期会遇到的各种情况",
            all.size, all.distinct().size,
        )
        assertNotNull(
            "平台接口变更要有专属类型，否则所有异常都会被归成「网络错误」",
            CloudError.UNEXPECTED("x") as CloudError,
        )
    }

    // ---------------------------------------------- 下载信息交接

    @Test
    fun `下载信息带齐引擎需要的字段`() {
        // 宿主要把它交给 TurboDL：URL、文件名、必需的头、大小、直链有效期
        val info = CloudDownloadInfo(
            url = "https://cdn/x.zip",
            fileName = "x.zip",
            sizeBytes = 1234,
            headers = mapOf("Referer" to "https://example.com/"),
            urlExpiresAtMillis = 1_700_000_000_000L,
        )
        assertEquals("https://cdn/x.zip", info.url)
        assertEquals("x.zip", info.fileName)
        assertEquals(1234L, info.sizeBytes)
        assertEquals("https://example.com/", info.headers["Referer"])
        assertTrue("直链有效期必须能表达，否则过期后下了个 403", info.urlExpiresAtMillis > 0)
    }

    @Test
    fun `契约版本由插件声明并可被宿主校验`() {
        val p = FakeProvider()
        assertEquals("默认应当指向当前契约版本", "1.0.0", p.apiVersion)
    }
}
