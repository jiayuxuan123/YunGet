package com.yunget.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkParserTest {

    @Test
    fun parsesAllSupportedPlatforms() {
        val cases = listOf(
            "https://pan.quark.cn/s/Abc123?pwd=a1B2" to (SharePlatform.QUARK to "Abc123"),
            "https://drive.uc.cn/s/Abc123" to (SharePlatform.UC to "Abc123"),
            "https://pan.xunlei.com/s/Abc_123-xy" to (SharePlatform.XUNLEI to "Abc_123-xy"),
            "https://pan.baidu.com/s/1Abc_123-xy?pwd=9xYz" to (SharePlatform.BAIDU to "Abc_123-xy"),
            "https://yun.139.com/shareweb/#/w/i/Abc_123" to (SharePlatform.C139 to "Abc_123"),
            "https://www.123pan.com/s/2785Vv-T4Ded" to (SharePlatform.PAN123 to "2785Vv-T4Ded")
        )

        cases.forEach { (text, expected) ->
            val parsed = ShareLinkParser.parse(text)!!
            assertEquals(expected.first, parsed.platform)
            assertEquals(expected.second, parsed.shareId)
        }
    }

    @Test
    fun explicitTextPasswordIsExtracted() {
        val parsed = ShareLinkParser.parse("链接 https://drive.uc.cn/s/Abc123 提取码：a1B2")!!
        assertEquals("a1B2", parsed.pwd)
    }

    @Test
    fun rejectsUnrelatedUrl() {
        assertNull(ShareLinkParser.parse("https://example.com/s/Abc123"))
    }

    // --------------------------------------------- 新增平台（同步上游 v1.2.8/v1.2.9）

    @Test
    fun parsesPan115LinksInAllForms() {
        // 标准分享链接（三种域名）
        for (host in listOf("115.com", "115cdn.com", "115rc.com")) {
            val parsed = ShareLinkParser.parse("https://$host/s/sws8lxs36jf")!!
            assertEquals("115 的 $host 应识别", SharePlatform.PAN115, parsed.platform)
            assertEquals("sws8lxs36jf", parsed.shareId)
        }
        // 提取码在 URL 参数里
        val withPwd = ShareLinkParser.parse("https://115.com/s/sws8lxs36jf?password=n307")!!
        assertEquals(SharePlatform.PAN115, withPwd.platform)
        assertEquals("n307", withPwd.pwd)
        // 提取码只在文案里（链接不带 password 不代表不需要码）
        val fromText = ShareLinkParser.parse("https://115.com/s/sws8lxs36jf 访问码：n307")!!
        assertEquals("n307", fromText.pwd)
        // 口令形式：https://115.com/sws8lxs36jf-n307/
        val command = ShareLinkParser.parse("https://115.com/sws8lxs36jf-n307/")!!
        assertEquals(SharePlatform.PAN115, command.platform)
        assertEquals("sws8lxs36jf", command.shareId)
        assertEquals("n307", command.pwd)
    }

    @Test
    fun parsesGuangYaLink() {
        val parsed = ShareLinkParser.parse("https://www.guangyapan.com/s/Abc-123_xy")!!
        assertEquals(SharePlatform.GUANGYA, parsed.platform)
        assertEquals("Abc-123_xy", parsed.shareId)

        val withPwd = ShareLinkParser.parse("https://www.guangyapan.com/s/Abc123?pwd=a1B2")!!
        assertEquals("a1B2", withPwd.pwd)
    }

    @Test
    fun ilanzouIsNotConfusedWithLanzou() {
        // ★ 顺序敏感性：`www.ilanzou.com` 里也含 "lanzou.com" 子串，
        //   若蓝奏云正则先匹配，优享版会被误判成蓝奏云（两者接口完全不同）。
        val premium = ShareLinkParser.parse("https://www.ilanzou.com/s/Abc123")!!
        assertEquals("优享版必须识别为 ILANZOU", SharePlatform.ILANZOU, premium.platform)
        assertEquals("Abc123", premium.shareId)

        val plain = ShareLinkParser.parse("https://www.lanzou.com/iAbc123")!!
        assertEquals("蓝奏云必须识别为 LANZOU", SharePlatform.LANZOU, plain.platform)
        assertEquals("iAbc123", plain.shareId)
    }

    @Test
    fun parsesLanzouDomainFamily() {
        // 域名族：lanzou* / lanzoui / lanzoux / lanzouw 等，后缀 com/net/org/cn
        for (host in listOf("lanzou.com", "lanzoui.com", "lanzoux.com", "www.lanzou.net", "lanzou.org")) {
            val parsed = ShareLinkParser.parse("https://$host/iAbc123")!!
            assertEquals("$host 应识别为蓝奏云", SharePlatform.LANZOU, parsed.platform)
            assertEquals("iAbc123", parsed.shareId)
        }
        // 提取码参数名有多种写法
        for (param in listOf("pwd", "pass", "passcode", "password")) {
            val parsed = ShareLinkParser.parse("https://lanzou.com/iAbc123?$param=a1B2")!!
            assertEquals("参数名 $param 应被识别", "a1B2", parsed.pwd)
        }
    }
}
