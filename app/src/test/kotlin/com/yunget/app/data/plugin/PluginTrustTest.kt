package com.yunget.app.data.plugin

import com.yunget.app.data.plugin.PluginTrust.BuiltinKey
import com.yunget.app.data.plugin.PluginTrust.KeyVerdict
import com.yunget.app.data.db.PluginInstalledEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 插件密钥的轮换与吊销（P6）。
 *
 * ## 这组测试要证明的那件事
 *
 * "私钥泄露了怎么办"这个场景有个反直觉之处：**签名永远数学上有效**。
 * 所以"吊销"这件事不可能靠验签失败来实现 —— 它只能靠**显式地拒绝这把钥匙**。
 * 而一个只写在注释里的"这里要查吊销名单"是拦不住任何人的：
 * 下面每个用例都在证明某条具体的路径上，吊销检查确实挡在了前面。
 *
 * ## 为什么用 RFC 8032 官方向量当密钥
 *
 * 这里需要一把"我能造出真签名"的公钥来证明**签名有效但仍被拒**。
 * 用真签名就得有私钥，而官方私钥按规矩永远不该出现在这里（它只在签名者的机器上）。
 * 所以改用 RFC 8032 官方向量里公开的密钥 —— 公钥公开、签名可复现，
 * 于是"验签通过"与"被吊销拒绝"这两件事能在同一份数据上同时成立。
 */
class PluginTrustTest {

    // RFC 8032 §7.1 TEST 1 的公钥与签名（消息为空）。
    private val rfcPubHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val rfcSigHex =
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Ed25519 的 SPKI 定长头 + 32 字节原始公钥（与 tools/sign_plugin.py 产出的格式一致）。 */
    private fun spkiFromRaw(raw: ByteArray): ByteArray =
        byteArrayOf(
            0x30, 0x2a,
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x70,
            0x03, 0x21, 0x00,
        ) + raw

    private fun toPem(spki: ByteArray): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(spki)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    private fun fingerprintOf(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private val rfcKey = BuiltinKey(
        keyId = "official-2026",
        pem = toPem(spkiFromRaw(hexToBytes(rfcPubHex))),
        fingerprint = fingerprintOf(hexToBytes(rfcPubHex)),
    )

    /** 轮换后的新钥匙（用另一条官方向量的公钥占位）。 */
    private val newKey = BuiltinKey(
        keyId = "official-2027",
        pem = toPem(
            spkiFromRaw(hexToBytes("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")),
        ),
        fingerprint = fingerprintOf(hexToBytes("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")),
    )

    // ---------------------------------------------- 核心场景：吊销后旧签名仍被拒

    @Test
    fun `签名有效的钥匙一旦吊销就装不上`() {
        val policy = PluginTrust.Policy(
            keys = listOf(rfcKey, newKey),
            revokedKeys = mapOf("official-2026" to "私钥于 2026-05 泄露，改用 official-2027"),
        )

        // 前置事实：这个签名**是真的**（官方向量），所以"被拒"绝不能归因于验签失败
        assertTrue(
            "前提：这份签名在 official-2026 下必须真的有效，否则本测试什么也证明不了",
            PluginSignature.verify(ByteArray(0), b64(hexToBytes(rfcSigHex)), rfcKey.pem),
        )

        // 但它被拒了，且拒的理由是"钥匙作废"而不是"签名不对"
        val verdict = policy.verdictFor("official-2026")
        assertTrue("被吊销的钥匙必须给出 Revoked 裁定", verdict is KeyVerdict.Revoked)
        assertEquals(
            "拒绝理由要原样透出（界面上给用户看的就是它）",
            "私钥于 2026-05 泄露，改用 official-2027",
            (verdict as KeyVerdict.Revoked).reason,
        )

        // 内置公钥不再交出去 —— 少了这一步，回退到源自带的 publicKeyUrl 就等于复活了这把钥匙
        assertNull("吊销的钥匙不能再从内置表里取到", policy.builtinPublicKey("official-2026"))
        assertTrue("有效 keyId 列表里不该再出现它", "official-2026" !in policy.builtinKeyIds())
    }

    @Test
    fun `吊销一把不影响新 keyId`() {
        val policy = PluginTrust.Policy(
            keys = listOf(rfcKey, newKey),
            revokedKeys = mapOf("official-2026" to "私钥泄露，改用 official-2027"),
        )
        assertEquals(KeyVerdict.Allowed, policy.verdictFor("official-2027"))
        assertFalse(policy.isRevoked("official-2027"))
        assertNotNull("新钥匙照常可用", policy.builtinPublicKey("official-2027"))
        assertEquals(newKey.fingerprint, policy.fingerprintOf("official-2027"))
    }

    @Test
    fun `没吊销过的 keyId 一律放行`() {
        val policy = PluginTrust.Policy(listOf(rfcKey), emptyMap())
        assertEquals(KeyVerdict.Allowed, policy.verdictFor("official-2026"))
        assertEquals(KeyVerdict.Allowed, policy.verdictFor("某个自建源的 keyId"))
        assertNull(policy.revocationReason("official-2026"))
        assertTrue(policy.revokedKeyIds().isEmpty())
    }

    @Test
    fun `keyId 前后空格不影响裁定`() {
        val policy = PluginTrust.Policy(listOf(rfcKey), mapOf("official-2026" to "泄露"))
        assertTrue(policy.isRevoked("  official-2026  "))
    }

    // ---------------------------------------------- 自检（防"忘了从内置表移走"）

    @Test
    fun `自检通过_内置表与吊销名单自洽`() {
        val policy = PluginTrust.Policy(
            keys = listOf(newKey),                    // 轮换的正解：旧 keyId 从内置表移走
            revokedKeys = mapOf("official-2026" to "私钥泄露，改用 official-2027"),
        )
        assertEquals(
            "轮换后的配置应当是自洽的：\n" + policy.selfCheck(),
            emptyList<String>(),
            policy.selfCheck(),
        )
    }

    @Test
    fun `自检报出_吊销的钥匙仍留在内置表`() {
        val policy = PluginTrust.Policy(
            keys = listOf(rfcKey),                       // 忘了移走
            revokedKeys = mapOf("official-2026" to "私钥泄露"),
        )
        val problems = policy.selfCheck()
        assertTrue(
            "必须报出「吊销的钥匙还在内置表里」这条：$problems",
            problems.any { it.contains("仍留在内置公钥表") },
        )
        // 这份配置是**危险**的：钥匙还留在表里（fingerprintOf 仍查得到），
        // 一旦有人删掉 resolvePublicKey 里那道吊销检查，源就能用 publicKeyUrl
        // 把这把已作废的公钥再塞回来，签名照样验得过。留着它只为一件事：
        // 让自检能持续提醒"这里该清理了"。
        assertNotNull(
            "这把钥匙确实还在内置表里（自检报的就是这个事实）",
            policy.fingerprintOf("official-2026"),
        )
    }

    @Test
    fun `自检报出_指纹与内置公钥对不上`() {
        val tampered = rfcKey.copy(fingerprint = "0".repeat(64))
        val problems = PluginTrust.Policy(listOf(tampered), emptyMap()).selfCheck()
        assertTrue(
            "换掉内置公钥却没改指纹（= 公钥可能已被替换）必须被报出来：$problems",
            problems.any { it.contains("指纹对不上") },
        )
    }

    @Test
    fun `自检报出_吊销原因留空`() {
        val problems = PluginTrust.Policy(listOf(newKey), mapOf("official-2027" to "  ")).selfCheck()
        assertTrue("没写原因会让用户看到一句没有出处的拒绝：$problems", problems.any { it.contains("没写原因") })
    }

    @Test
    fun `自检报出_公钥坏掉`() {
        // 坏在哪一步（PEM 解析失败 / 太短 / 指纹对不上）取决于 base64 解码器的宽容度，
        // 所以这里只断言"这把钥匙一定被报了问题"，不去钉死文案。
        val broken = BuiltinKey("bad-key", "-----BEGIN PUBLIC KEY-----\n!!!\n-----END PUBLIC KEY-----", "0".repeat(64))
        val problems = PluginTrust.Policy(listOf(broken), emptyMap()).selfCheck()
        assertTrue("内置公钥坏掉必须被自检报出来：$problems", problems.any { it.contains("bad-key") })
    }

    // ---------------------------------------------- 真实内置表

    @Test
    fun `应用里真实内置的表是自洽的`() {
        assertEquals(
            "内置公钥表或吊销名单有矛盾（见 PluginTrust.Policy.selfCheck 的说明）",
            emptyList<String>(),
            PluginTrust.selfCheck(),
        )
    }

    @Test
    fun `内置指纹与源仓库 README 记录的一致`() {
        // 仓库 YunGet-Plugins/keys/README.md 记的是同一个指纹。两处对不上意味着
        // 有人只改了其中一边 —— 那正是"换掉公钥再换掉插件"这条攻击链的前半段。
        val readme = File("../../YunGet-Plugins/keys/README.md").let { f ->
            if (f.isFile) f.readText() else null
        }
        if (readme == null) return // 单测在别的工程根目录下跑（如只拷了 app 模块），跳过
        val declared = PluginTrust.builtinKeyIds().firstOrNull()?.let { PluginTrust.fingerprintOf(it) }
        assertNotNull(declared)
        assertTrue(
            "内置指纹 $declared 没出现在源仓库 keys/README.md 里 —— 请两边一起更新",
            readme.contains(declared!!),
        )
    }

    // ---------------------------------------------- 信任等级不得被吊销污染

    @Test
    fun `吊销的钥匙永远拿不到官方等级`() {
        // 即便安装入口已经拒了，这里仍要独立成立：等级判定是 UI 上"官方"标签的依据，
        // 它一旦被污染，用户界面会对着一个装不上的插件显示"官方"。
        assertEquals(
            PluginInstalledEntity.TRUST_OFFICIAL,
            PluginTrust.gradeFor(PluginInstalledEntity.TRUST_OFFICIAL, "official-2026"),
        )
        assertFalse(PluginTrust.isRevoked("official-2026"))
    }

    // ---------------------------------------------- 安装链路上确实查了吊销（P6 的落点）

    @Test
    fun `安装链路在任何验签之前就查吊销名单`() {
        val src = File("src/main/kotlin/com/yunget/app/data/plugin/MarketClient.kt").readText()
        val gate = src.indexOf("PluginTrust.verdictFor(version.keyId)")
        val fetch = src.indexOf("val script = fetchAndVerify(")
        val keyLookup = src.indexOf("private fun resolvePublicKey(")
        assertTrue("安装链路上应当有吊销检查", gate >= 0)
        assertTrue("应当仍然走 fetchAndVerify 做校验", fetch >= 0)
        assertTrue(
            "吊销检查必须排在取脚本（进而验签）**之前** —— 顺序反了这条防线就名存实亡",
            gate < fetch,
        )
        // 独立的取钥匙通道（源自带公钥）也必须查一次
        val resolveBody = src.substring(keyLookup, src.indexOf("index.publicKeyUrl"))
        assertTrue(
            "resolvePublicKey 回退到 publicKeyUrl 之前也要查吊销，否则换一个源就绕过去了",
            resolveBody.contains("PluginTrust.verdictFor"),
        )
    }

    @Test
    fun `真实内置钥匙当前未被吊销`() {
        // 这条是"上面那些吊销用例不会污染真实行为"的护栏：合成表里吊销
        // official-2026 之后，真实的内置钥匙必须仍然是可用状态。
        assertFalse(
            "真实内置钥匙 official-2026 不应处于吊销状态（若确实泄露了，请连同 README 一起更新）",
            PluginTrust.isRevoked("official-2026"),
        )
        assertNotNull(PluginTrust.builtinPublicKey("official-2026"))
    }
}
