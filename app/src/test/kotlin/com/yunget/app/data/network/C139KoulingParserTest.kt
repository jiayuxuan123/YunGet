package com.yunget.app.data.network

import org.junit.Assert.*
import org.junit.Test

class C139KoulingParserTest {
    @Test
    fun recognizesCopiedCommandWithPassword() {
        val parsed = C139KoulingParser.parse("分享文件 /*Abc_123:/ 提取码：a1B2")!!
        assertEquals("Abc_123", parsed.code)
        assertEquals(1, parsed.codeType)
        assertEquals("a1B2", parsed.password)
    }

    @Test
    fun recognizesChineseCommand() {
        assertEquals(2, C139KoulingParser.parse("/*测试口令:/")!!.codeType)
    }

    @Test
    fun ordinaryTextAndMalformedCommandsAreIgnored() {
        listOf("提取码：1234", "普通分享说明", "/*broken", "/*has space:/", "/*${"a".repeat(65)}:/")
            .forEach { assertNull(C139KoulingParser.parse(it)) }
    }

    @Test
    fun passwordRequestSetsProtocolFlagAndEscapesAccount() {
        val request = buildC139KoulingInfoRequest(C139Kouling("abc", 1), "a&b", "1234")
        assertTrue(request.contains("<isPasswd>1</isPasswd>"))
        assertTrue(request.contains("<account>a&amp;b</account>"))
        assertTrue(request.contains("<passwd><![CDATA[1234]]></passwd>"))
    }
}
