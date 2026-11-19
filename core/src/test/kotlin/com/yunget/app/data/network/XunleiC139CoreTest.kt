package com.yunget.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XunleiC139CoreTest {

    // ---------------------------------------------------------------- Xunlei link & kouling

    @Test
    fun xunleiShareLinkAndKoulingRoundTrip() {
        val location =
            "https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1" +
                "?channel=complete_birdkey_by_plate&pwd=nw45&from=BHO"
        val shareUrl = XunleiKouling.shareUrlFromLocation(location)
        assertEquals("https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?pwd=nw45", shareUrl)

        val parsed = ShareLinkParser.parse(shareUrl!!)
        assertNotNull(parsed)
        assertEquals(SharePlatform.XUNLEI, parsed!!.platform)
        assertEquals("VOEs0DLEAfUV9o-JOAqrzgZmA1", parsed.shareId)
        assertEquals("nw45", parsed.pwd)

        val bare = XunleiKouling.shareUrlFromLocation(
            "https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?from=BHO"
        )
        assertEquals("https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1", bare)
        assertNull(XunleiKouling.shareUrlFromLocation("https://www.example.com/search?q=test"))
        assertNull(XunleiKouling.shareUrlFromLocation(""))
        assertNull(XunleiKouling.shareUrlFromLocation(null))
    }

    @Test
    fun xunleiKoulingNormalizationAndJumpUrl() {
        assertEquals("张三丰资源", XunleiKouling.normalize("  「张三丰资源」  "))
        assertTrue(XunleiKouling.looksLikeKouling("张三丰资源"))
        assertTrue(XunleiKouling.looksLikeKouling("【测试123】"))
        assertFalse(XunleiKouling.looksLikeKouling("abc123"))
        assertFalse(XunleiKouling.looksLikeKouling("有 空格"))
        assertFalse(XunleiKouling.looksLikeKouling(""))

        val jumpUrl = XunleiKouling.buildJumpUrl("张三丰资源")
        assertTrue(jumpUrl.startsWith(XunleiKouling.JUMP_API))
        assertTrue(jumpUrl.contains("wd=%E5%BC%A0%E4%B8%89%E4%B8%B0%E8%B5%84%E6%BA%90"))
    }

    // ---------------------------------------------------------------- Xunlei fingerprint

    @Test
    fun xunleiDeviceFingerprintGeneratesValidSignAndPersistsThroughStore() {
        val hex32 = Regex("^[0-9a-f]{32}$")
        val idA = "0123456789abcdef0123456789abcdef"
        val idB = "fedcba9876543210fedcba9876543210"

        val signA = XunleiDeviceFingerprint.buildDeviceSign(idA)
        assertEquals(signA, XunleiDeviceFingerprint.buildDeviceSign(idA))
        assertTrue(signA.startsWith("div101.$idA"))
        val suffix = signA.removePrefix("div101.$idA")
        assertTrue(hex32.matches(suffix))
        assertNotEquals(signA, XunleiDeviceFingerprint.buildDeviceSign(idB))

        val generated = XunleiDeviceFingerprint.generate()
        assertTrue(hex32.matches(generated.deviceId))
        assertTrue(hex32.matches(generated.peerId))
        assertEquals(XunleiDeviceFingerprint.buildDeviceSign(generated.deviceId), generated.deviceSign)

        try {
            XunleiDeviceFingerprint.resetForTest()
            assertEquals(XunleiConstants.DEVICE_ID, XunleiDeviceFingerprint.deviceId())

            val store = InMemoryXunleiDeviceFingerprintStore()
            XunleiDeviceFingerprint.init(store)
            val saved = store.load()
            assertNotNull(saved)
            assertEquals(saved!!.deviceId, XunleiDeviceFingerprint.deviceId())
            assertEquals(saved.peerId, XunleiDeviceFingerprint.peerId())
            assertEquals(saved.deviceSign, XunleiDeviceFingerprint.deviceSign())

            // Idempotent: second init does not overwrite existing identity
            val preset = XunleiDeviceIdentity(idA, idB, signA)
            XunleiDeviceFingerprint.init(InMemoryXunleiDeviceFingerprintStore(preset))
            assertEquals(saved.deviceId, XunleiDeviceFingerprint.deviceId())

            // Fresh init with pre-populated store loads saved identity
            XunleiDeviceFingerprint.resetForTest()
            XunleiDeviceFingerprint.init(InMemoryXunleiDeviceFingerprintStore(preset))
            assertEquals(idA, XunleiDeviceFingerprint.deviceId())
            assertEquals(idB, XunleiDeviceFingerprint.peerId())
            assertEquals(signA, XunleiDeviceFingerprint.deviceSign())
        } finally {
            XunleiDeviceFingerprint.resetForTest()
        }
    }

    // ---------------------------------------------------------------- Xunlei web credential & API helpers

    @Test
    fun xunleiWebCredentialTrustedUrlsAndParsing() {
        assertTrue(XunleiWebCredential.isTrustedUrl(null))
        assertTrue(XunleiWebCredential.isTrustedUrl(""))
        assertTrue(XunleiWebCredential.isTrustedUrl("about:blank"))
        assertTrue(XunleiWebCredential.isTrustedUrl("https://pan.xunlei.com/"))
        assertTrue(XunleiWebCredential.isTrustedUrl("https://i.xunlei.com:443/login?next=pan"))
        assertTrue(XunleiWebCredential.isTrustedUrl("https://xunlei.com/path?q=hello world"))

        assertFalse(XunleiWebCredential.isTrustedUrl("http://pan.xunlei.com/"))
        assertFalse(XunleiWebCredential.isTrustedUrl("https://pan.xunlei.com:8443/"))
        assertFalse(XunleiWebCredential.isTrustedUrl("https://user:pass@pan.xunlei.com/"))
        assertFalse(XunleiWebCredential.isTrustedUrl("https://@pan.xunlei.com/"))
        assertFalse(XunleiWebCredential.isTrustedUrl("https://pan.xunlei.com.evil.com/"))
        assertFalse(XunleiWebCredential.isTrustedUrl("https://notxunlei.com/"))
        assertFalse(XunleiWebCredential.isTrustedUrl("javascript:alert(1)"))

        val access = "eyJhbGciOiJIUzI1NiJ9.payload_token_123456"
        val refresh = "refresh_token_abcdef1234567890"
        val jsonInput = """{"data":{"credentials":{"access_token":"$access","refresh_token":"$refresh","device_id":"dev123","sub":"42"}}}"""
        val parsedJson = XunleiWebCredential.parse(jsonInput)
        assertNotNull(parsedJson)
        assertEquals(access, parsedJson!!.accessToken)
        assertEquals(refresh, parsedJson.refreshToken)
        assertEquals("dev123", parsedJson.deviceId)
        assertEquals("42", parsedJson.userId)

        val quotedJson = "\"{\\\"accessToken\\\":\\\"$access\\\",\\\"refreshToken\\\":\\\"$refresh\\\"}\""
        val parsedQuoted = XunleiWebCredential.parse(quotedJson)
        assertEquals(access, parsedQuoted?.accessToken)
        assertEquals(refresh, parsedQuoted?.refreshToken)

        assertEquals(access, XunleiWebCredential.parse("Bearer $access")?.accessToken)
        assertNull(XunleiWebCredential.parse("null"))

        assertEquals(
            "1.4181a24198c7edfbbba2afbcf1f4fab0",
            XunleiWebCredential.computeCaptchaSign("550cfe136e9d74e95be5393738639de7", 1792243618981L)
        )
    }

    @Test
    fun xunleiApiJwtExpAndReviewUrl() {
        val header = CoreBase64.encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray(), urlSafe = true, padding = false)
        val payload = CoreBase64.encode("""{"sub":"99","exp":1900000000}""".toByteArray(), urlSafe = true, padding = false)
        val api = XunleiApi()
        assertEquals(1900000000L, api.jwtExp("$header.$payload.sig"))
        assertEquals(0L, api.jwtExp("not-a-jwt"))

        val review = XunleiApi.parseReviewUrl(
            "https://xluser-ssl.xunlei.com/verify?creditkey=abc%2B1&token=xyz"
        )
        assertEquals("abc+1", review["creditkey"])
        assertEquals("xyz", review["token"])
    }

    // ---------------------------------------------------------------- C139 link, kouling & XML

    @Test
    fun c139ShareLinkAndKoulingParsing() {
        val share = ShareLinkParser.parse("https://yun.139.com/shareweb/#/w/i/2i5C123456789 提取码：ab12")
        assertNotNull(share)
        assertEquals(SharePlatform.C139, share!!.platform)
        assertEquals("2i5C123456789", share.shareId)
        assertEquals("ab12", share.pwd)

        val cmd = C139KoulingParser.parse("分享文件 /*Abc_123:/ 提取码：a1B2")
        assertNotNull(cmd)
        assertEquals("Abc_123", cmd!!.code)
        assertEquals(1, cmd.codeType)
        assertEquals("a1B2", cmd.password)

        val cnCmd = C139KoulingParser.parse("/*测试口令:/")
        assertEquals(2, cnCmd?.codeType)
        assertEquals("", cnCmd?.password)

        assertNull(C139KoulingParser.parse("普通分享说明"))
        assertNull(C139KoulingParser.parse("/*has space:/"))
    }

    @Test
    fun c139KoulingXmlBuildAndParse() {
        val reqWithPwd = buildC139KoulingInfoRequest(C139Kouling("abc", 1), "a&b", "1234")
        assertTrue(reqWithPwd.contains("<isPasswd>1</isPasswd>"))
        assertTrue(reqWithPwd.contains("<account>a&amp;b</account>"))
        assertTrue(reqWithPwd.contains("<passwd><![CDATA[1234]]></passwd>"))

        val reqNoPwd = buildC139KoulingInfoRequest(C139Kouling("abc", 1), "13800138000", "")
        assertTrue(reqNoPwd.contains("<isPasswd>0</isPasswd>"))

        val genReq = buildC139KoulingGeneralRequest(C139Kouling("测试", 2), "13800138000", "abcd")
        assertTrue(genReq.contains("<linkCodeType>2</linkCodeType>"))
        assertTrue(genReq.contains("<linkCode><![CDATA[测试]]></linkCode>"))

        val info = parseC139KoulingInfo(
            """<root resultCode="0"><getOutLinkInfoRes><lkName>演示资料</lkName><password>abcd</password></getOutLinkInfoRes></root>"""
        )
        assertEquals("演示资料", info.title)
        assertEquals("abcd", info.password)

        val general = parseC139KoulingGeneral(
            """<root resultCode="0"><outLink><url>https://yun.139.com/shareweb/#/w/i/0A1B2C</url><linkID>0A1B2C</linkID><lkName>演示资料</lkName></outLink></root>"""
        )
        assertEquals("https://yun.139.com/shareweb/#/w/i/0A1B2C", general.shareUrl)
        assertEquals("0A1B2C", general.linkId)
        assertEquals("演示资料", general.title)

        try {
            parseC139KoulingInfo("""<root resultCode="9188" desc="提取码错误"></root>""")
            fail("Expected exception on non-zero resultCode")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("9188"))
            assertTrue(expected.message!!.contains("提取码错误"))
        }
    }

    // ---------------------------------------------------------------- C139 policy, constants, device info & API helpers

    @Test
    fun c139DownloadPolicyAndConstantsHelpers() {
        val headers = C139DownloadPolicy.headers()
        assertEquals(C139Constants.PC_UA, headers["User-Agent"])
        assertEquals(C139Constants.DOWNLOAD_REFERER, headers["Referer"])

        val encryptedPhone = CoreBase64.encode("13800138000".toByteArray())
        val authB64 = CoreBase64.encode("pc:13800138000:secretTok".toByteArray())

        val merged = C139Constants.extractCookies { domain ->
            when (domain) {
                C139Constants.COOKIE_DOMAIN -> "Os_SSo_Sid=sid1; RMKEY=rm1; ignored=x"
                C139Constants.COOKIE_DOMAIN_BACKUP -> "authorization=Basic $authB64; ORCHES-I-ACCOUNT-ENCRYPT=$encryptedPhone"
                else -> null
            }
        }
        assertTrue(merged.contains("Os_SSo_Sid=sid1"))
        assertTrue(merged.contains("RMKEY=rm1"))
        assertTrue(merged.contains("authorization=Basic $authB64"))
        assertFalse(merged.contains("ignored="))

        assertTrue(C139Constants.isValidCookie(merged))
        assertTrue(C139Constants.isValidCookie("authorization=Basic $authB64"))
        assertTrue(C139Constants.isValidCookie("Os_SSo_Sid=a; RMKEY=b"))
        assertFalse(C139Constants.isValidCookie("Os_SSo_Sid=a"))
        assertFalse(C139Constants.isValidCookie(""))

        assertEquals("Basic $authB64", C139Constants.extractAuthorization(merged))
        assertEquals("13800138000", C139Constants.extractAccountFull("ORCHES-I-ACCOUNT-ENCRYPT=$encryptedPhone"))
        assertEquals("13800138000", C139Constants.extractAccountFull("authorization=Basic $authB64"))
        assertEquals("138****8000", C139Constants.extractAccount("ORCHES-I-ACCOUNT-SIMPLIFY=138****8000; ORCHES-I-ACCOUNT-ENCRYPT=$encryptedPhone"))
        assertEquals("13800138000", C139Constants.extractAccount("ORCHES-I-ACCOUNT-ENCRYPT=$encryptedPhone"))

        val api = C139Api()
        assertEquals("13800138000", api.accountFromAuthorization("Basic $authB64"))
        val sign = api.calSign("""{"a":1,"b":"hello"}""", "2026-11-19 12:00:00", "AbCdEfGhIjKlMnOp")
        assertTrue(Regex("^[0-9A-F]{32}$").matches(sign))
        assertEquals(sign, api.calSign("""{"a":1,"b":"hello"}""", "2026-11-19 12:00:00", "AbCdEfGhIjKlMnOp"))
    }

    @Test
    fun c139DeviceInfoDefaultsAndProviderHook() {
        assertEquals("A B C", C139DeviceInfo.sanitizeField("A|B\nC\r"))

        val fallback = C139DeviceInfo.fallback()
        assertTrue(fallback.appDeviceInfo().contains("|00000000000000000000000000000000|"))
        assertTrue(fallback.appUserAgent().startsWith("android|Android Device|android Unknown|"))

        val jvmDefault1 = C139DeviceInfo.defaultJvm()
        val jvmDefault2 = C139DeviceInfo.defaultJvm()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(jvmDefault1.deviceId))
        assertEquals(jvmDefault1.deviceId, jvmDefault2.deviceId)

        try {
            val custom = fallback.copy(manufacturer = "TestBrand", model = "TestModel", deviceId = "a".repeat(32))
            C139DeviceInfo.installProvider { custom }
            assertEquals(custom, C139DeviceInfo.current())
            assertTrue(C139DeviceInfo.current().appDeviceInfo().contains("|TestBrand|TestModel|"))
        } finally {
            C139DeviceInfo.resetProviderForTest()
        }
    }
}
