package com.yunget.app.data.network.video

import com.yunget.app.data.repository.VideoParseRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM unit tests for shared video parsing primitives in `:core`.
 */
class VideoParserCoreTest {

    @After
    fun tearDown() {
        BrowserPageExtractorRegistry.reset()
    }

    // ---------------------------------------------------------------- VideoLinkParser

    @Test
    fun detectsDouyinShortAndLongLinks() {
        val shortLink = VideoLinkParser.parse("7.12 复制打开抖音 https://v.douyin.com/iRNBho6u/ 看看这个！")
        assertNotNull(shortLink)
        assertEquals(VideoPlatform.DOUYIN, shortLink!!.platform)
        assertEquals("short", shortLink.kind)
        assertTrue(shortLink.isShortLink)
        assertEquals("", shortLink.id)

        val videoLink = VideoLinkParser.parse("https://www.douyin.com/video/7345678901234567890")
        assertNotNull(videoLink)
        assertEquals(VideoPlatform.DOUYIN, videoLink!!.platform)
        assertEquals("video", videoLink.kind)
        assertEquals("7345678901234567890", videoLink.id)
        assertFalse(videoLink.isShortLink)

        val modalLink = VideoLinkParser.parse(
            "https://www.douyin.com/user/self?modal_id=7345678901234567891&showTab=like"
        )
        assertNotNull(modalLink)
        assertEquals(VideoPlatform.DOUYIN, modalLink!!.platform)
        assertEquals("7345678901234567891", modalLink.id)

        val shareLink = VideoLinkParser.parse("https://www.iesdouyin.com/share/note/7345678901234567892/")
        assertNotNull(shareLink)
        assertEquals(VideoPlatform.DOUYIN, shareLink!!.platform)
        assertEquals("7345678901234567892", shareLink.id)
    }

    @Test
    fun detectsBilibiliWeiboAcFunPixivXhsKuaishouLinks() {
        val biliShort = VideoLinkParser.parse("分享视频 https://b23.tv/abc1234。")
        assertEquals(VideoPlatform.BILIBILI, biliShort?.platform)
        assertTrue(biliShort?.isShortLink == true)

        val biliBv = VideoLinkParser.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=1")
        assertEquals(VideoPlatform.BILIBILI, biliBv?.platform)
        assertEquals("video", biliBv?.kind)
        assertEquals("BV1xx411c7mD", biliBv?.id)
        assertFalse(biliBv?.isShortLink == true)

        val biliAv = VideoLinkParser.parse("https://www.bilibili.com/video/av170001")
        assertEquals(VideoPlatform.BILIBILI, biliAv?.platform)
        assertEquals("av170001", biliAv?.id)

        val xhsShort = VideoLinkParser.parse("小红书笔记 http://xhslink.com/a/abcDEF123，快来看")
        assertEquals(VideoPlatform.XHS, xhsShort?.platform)
        assertTrue(xhsShort?.isShortLink == true)

        val xhsNote = VideoLinkParser.parse("https://www.xiaohongshu.com/explore/665a1b2c000000001e02abcd")
        assertEquals(VideoPlatform.XHS, xhsNote?.platform)
        assertEquals("note", xhsNote?.kind)
        assertEquals("665a1b2c000000001e02abcd", xhsNote?.id)

        val ksShort = VideoLinkParser.parse("https://v.kuaishou.com/abc123")
        assertEquals(VideoPlatform.KUAISHOU, ksShort?.platform)
        assertTrue(ksShort?.isShortLink == true)

        val ksPhoto = VideoLinkParser.parse("https://www.kuaishou.com/short-video/3x8a9b0c1d2e")
        assertEquals(VideoPlatform.KUAISHOU, ksPhoto?.platform)
        assertEquals("photo", ksPhoto?.kind)
        assertEquals("3x8a9b0c1d2e", ksPhoto?.id)

        val weiboTv = VideoLinkParser.parse("https://weibo.com/tv/show/1034:5012345678901234?mid=5012345678999999")
        assertEquals(VideoPlatform.WEIBO, weiboTv?.platform)
        assertEquals("tv", weiboTv?.kind)
        assertEquals("5012345678999999", weiboTv?.id)

        val weiboStatus = VideoLinkParser.parse("https://weibo.com/1234567890/OcAbCdEfG")
        assertEquals(VideoPlatform.WEIBO, weiboStatus?.platform)
        assertEquals("status", weiboStatus?.kind)
        assertEquals("OcAbCdEfG", weiboStatus?.id)

        val acfun = VideoLinkParser.parse("https://www.acfun.cn/v/ac43210987")
        assertEquals(VideoPlatform.ACFUN, acfun?.platform)
        assertEquals("ac", acfun?.kind)
        assertEquals("43210987", acfun?.id)

        val pixiv = VideoLinkParser.parse("https://www.pixiv.net/artworks/118234567")
        assertEquals(VideoPlatform.PIXIV, pixiv?.platform)
        assertEquals("artwork", pixiv?.kind)
        assertEquals("118234567", pixiv?.id)

        val pixivLegacy = VideoLinkParser.parse("https://www.pixiv.net/member_illust.php?mode=medium&illust_id=98765432")
        assertEquals(VideoPlatform.PIXIV, pixivLegacy?.platform)
        assertEquals("98765432", pixivLegacy?.id)
    }

    @Test
    fun rejectsNonVideoLinksAndPlainNumbers() {
        assertNull(VideoLinkParser.parse("plain text without url 7345678901234567890"))
        assertNull(VideoLinkParser.parse("https://pan.quark.cn/s/abcdef123456"))
        assertFalse(VideoLinkParser.looksLikeVideoLink("https://example.com/video/12345"))
        assertTrue(VideoLinkParser.looksLikeVideoLink("https://www.bilibili.com/video/BV1xx411c7mD"))
    }

    // ---------------------------------------------------------------- VideoParsers

    @Test
    fun registryContainsAllBuiltInPlatformsAndOrdersLoginRequiredLast() {
        assertEquals(7, VideoParsers.all.size)
        assertSame(DouyinParser, VideoParsers.of(VideoPlatform.DOUYIN))
        assertSame(KuaishouParser, VideoParsers.of(VideoPlatform.KUAISHOU))
        assertSame(WeiboParser, VideoParsers.of(VideoPlatform.WEIBO))
        assertSame(AcFunParser, VideoParsers.of(VideoPlatform.ACFUN))
        assertSame(BilibiliParser, VideoParsers.of(VideoPlatform.BILIBILI))
        assertSame(XhsParser, VideoParsers.of(VideoPlatform.XHS))
        assertSame(PixivParser, VideoParsers.of(VideoPlatform.PIXIV))
        assertNull(VideoParsers.of(VideoPlatform.OTHER))

        val ordered = VideoParsers.supportedPlatforms
        val firstLoginIdx = ordered.indexOfFirst { it.needsLogin }
        assertTrue("Expected at least one login-required platform", firstLoginIdx > 0)
        assertTrue(ordered.subList(0, firstLoginIdx).none { it.needsLogin })
        assertTrue(ordered.subList(firstLoginIdx, ordered.size).all { it.needsLogin })
    }

    // ---------------------------------------------------------------- VideoHttp helpers

    @Test
    fun videoHttpHelpersHandleInlineJsonUrlsAndFileNames() {
        val html = """<html><script>window.__INITIAL_STATE__ = {"note":{"id":"123"}}</script></html>"""
        assertEquals(
            """{"note":{"id":"123"}}""",
            VideoHttp.extractInlineJson(html, "window.__INITIAL_STATE__")
        )
        assertNull(VideoHttp.extractInlineJson("<html></html>", "window.MISSING"))

        assertEquals("""{"url":"https://a.com/b"}""", VideoHttp.unescapeQuotes("""{\"url\":\"https:\\/\\/a.com\\/b\"}"""))

        assertEquals("https://cdn.example.com/a.jpg", VideoHttp.secureUrl("//cdn.example.com/a.jpg"))
        assertEquals("https://cdn.example.com/a.jpg", VideoHttp.secureUrl("http://cdn.example.com/a.jpg"))
        assertEquals("https://cdn.example.com/a.jpg", VideoHttp.secureUrl("https://cdn.example.com/a.jpg"))
        assertEquals("", VideoHttp.secureUrl(""))

        assertEquals("a_b_c_d", VideoHttp.sanitizeFileName(" a/b:c*d "))
        val longName = "a".repeat(150) + ".mp4"
        val sanitized = VideoHttp.sanitizeFileName(longName)
        assertTrue(sanitized.length <= 120)
        assertTrue(sanitized.endsWith(".mp4"))

        assertEquals(
            "https://www.kuaishou.com",
            VideoHttp.redactUrl("https://www.kuaishou.com/fw/photo/abc?token=secret#frag")
        )
        assertEquals("http://example.com:8080", VideoHttp.redactUrl("http://example.com:8080/path"))
        assertEquals("<none>", VideoHttp.redactUrl(null))
    }

    @Test
    fun requireSuccessMapsHttpErrorsToExpectedKinds() {
        VideoHttp.requireSuccess(VideoHttp.RawResponse(200, "ok", emptyMap(), "https://example.com"), "测试")

        for (code in listOf(403, 418, 429)) {
            try {
                VideoHttp.requireSuccess(VideoHttp.RawResponse(code, "", emptyMap(), "https://example.com"), "测试")
                fail("Expected VideoParseException for HTTP $code")
            } catch (e: VideoParseException) {
                assertEquals(VideoParseErrorKind.BLOCKED, e.kind)
            }
        }

        for (code in listOf(404, 410)) {
            try {
                VideoHttp.requireSuccess(VideoHttp.RawResponse(code, "", emptyMap(), "https://example.com"), "测试")
                fail("Expected VideoParseException for HTTP $code")
            } catch (e: VideoParseException) {
                assertEquals(VideoParseErrorKind.NOT_FOUND, e.kind)
            }
        }

        try {
            VideoHttp.requireSuccess(VideoHttp.RawResponse(502, "", emptyMap(), "https://example.com"), "测试")
            fail("Expected VideoParseException for HTTP 502")
        } catch (e: VideoParseException) {
            assertEquals(VideoParseErrorKind.UNKNOWN, e.kind)
        }
    }

    // ---------------------------------------------------------------- ClientHints & BrowserPageExtractor

    @Test
    fun clientHintsDerivesConsistentSpecFromUserAgent() {
        val desktop = ClientHints.fromUserAgent(VideoHttp.UA_CHROME)
        assertEquals("Windows", desktop.platform)
        assertEquals("131.0.0.0", desktop.fullVersion)
        assertEquals("Win32", desktop.navigatorPlatform)
        assertFalse(desktop.mobile)
        assertEquals("x86", desktop.architecture)

        val ios = ClientHints.fromUserAgent(VideoHttp.UA_IPHONE)
        assertEquals("iOS", ios.platform)
        assertTrue(ios.mobile)
        assertNull(ios.navigatorPlatform)

        val android = ClientHints.fromUserAgent(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        )
        assertEquals("Android", android.platform)
        assertEquals("130.0.0.0", android.fullVersion)
        assertEquals("Pixel 8", android.model)
        assertTrue(android.mobile)
    }

    @Test
    fun browserPageExtractorRegistryDefaultsToNoopAndSupportsInstall() = runBlocking {
        BrowserPageExtractorRegistry.reset()
        assertFalse(BrowserPageExtractorRegistry.current.isAvailable)
        assertNull(
            BrowserPageExtractorRegistry.current.loadAndExtract(
                url = "https://www.douyin.com/video/123",
                userAgent = VideoHttp.UA_CHROME,
                readyCheckJs = "true",
                extractJs = "({})"
            )
        )

        val fake = object : BrowserPageExtractor {
            override val isAvailable: Boolean = true
            override suspend fun loadAndExtract(
                url: String,
                userAgent: String,
                readyCheckJs: String,
                extractJs: String,
                timeoutMs: Long,
                pollIntervalMs: Long,
                extraDocumentStartJs: List<String>,
            ): String = """{"url":"$url"}"""
        }
        BrowserPageExtractorRegistry.install(fake)
        assertTrue(BrowserPageExtractorRegistry.current.isAvailable)
        assertEquals(
            """{"url":"https://www.douyin.com/video/123"}""",
            BrowserPageExtractorRegistry.current.loadAndExtract(
                url = "https://www.douyin.com/video/123",
                userAgent = VideoHttp.UA_CHROME,
                readyCheckJs = "true",
                extractJs = "({})"
            )
        )
    }

    // ---------------------------------------------------------------- VideoParseRepository & Models

    @Test
    fun repositoryReturnsNullForNonVideoTextAndRejectsUnsupportedPlatform() = runBlocking {
        val repo = VideoParseRepository()
        assertNull(repo.parse("https://pan.baidu.com/s/1abcdefg"))

        try {
            repo.parse(DetectedVideoLink(VideoPlatform.OTHER, "video", "1", "https://example.com/1"))
            fail("Expected UNSUPPORTED VideoParseException")
        } catch (e: VideoParseException) {
            assertEquals(VideoParseErrorKind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun videoModelsExposeCountsAndUserMessages() {
        val result = VideoParseResult(
            platform = VideoPlatform.BILIBILI,
            title = "demo",
            medias = listOf(
                VideoMedia("https://cdn.example.com/v.mp4", VideoMediaKind.VIDEO),
                VideoMedia("https://cdn.example.com/1.jpg", VideoMediaKind.IMAGE),
                VideoMedia("https://cdn.example.com/2.jpg", VideoMediaKind.IMAGE),
            )
        )
        assertEquals(1, result.videoCount)
        assertEquals(2, result.imageCount)
        assertTrue(VideoParseException(VideoParseErrorKind.BLOCKED, "403").userMessage().contains("风控"))
        assertTrue(VideoParseException(VideoParseErrorKind.NOT_FOUND, "404").userMessage().contains("不存在"))
        assertEquals("custom hint", VideoParseException(VideoParseErrorKind.UNSUPPORTED, "custom hint").userMessage())
    }
}
