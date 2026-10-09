/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 插件市场的**纯逻辑**层：Ed25519 验签、索引解析、更新判据、信任分级。
 *
 * 这些都是纯 JVM 可测的（不依赖 Android），而且它们恰好是"错了就很严重"的地方：
 *  - 验签：错了等于把别人的脚本当官方装进来；
 *  - 更新判据：错了等于"永远不提示更新"或"把降级当更新"；
 *  - 信任分级：错了等于给未验证的插件贴上"官方"。
 *
 * 测试数据用的是**真实**的：公钥与签名取自 `YunGet-Plugins` 仓库（`keys/official-2026.pub`
 * 与 `plugins.json` 里那条），脚本内容也按同一条 sig 的原文逐字使用。这样验签用例验的不是
 * "我写了一个自洽的实现"，而是"它能不能验真仓库发出来的那份签名"。
 */
class PluginMarketLogicTest {

    // ---------------------------------------------------------------- 真实测试数据

    /** 官方源公钥（与 `PluginTrust` 内置的那把一致）。 */
    private val officialPublicKey = """
        -----BEGIN PUBLIC KEY-----
        MCowBQYDK2VwAyEAreoDyAm377RaS2C91NsidyEFwLO8KDjvW3WLkRx7DqE=
        -----END PUBLIC KEY-----
    """.trimIndent()

    /** 示例插件脚本的**摘要**（真实值，见 YunGet-Plugins/plugins.json）。 */
    private val sampleSha256 = "b1aa85197621be29befd0f84d0144326d13e8b156c42c1f170d7845455de0fd7"

    private val sampleSignature =
        "MefY8mdHpgss7UJw8kxN2XY7BV4sOwEF62Wp0+R1TXCV5gp78RFFAvv0mv2omU9Fg0gKdHK6HWUUAmsq5l0dDg=="

    /** 真实脚本的**字节**：验签必须对原始字节做，所以这里按 SHA-256 反推不可行 —— 直接用内容。 */
    private val sampleScript: ByteArray by lazy { readSampleScriptBytes() }

    /**
     * 从仓库里读示例脚本。
     *
     * 找不到就**跳过**依赖它的用例（只克隆了 App 仓库、没有同级 `YunGet-Plugins` 的环境）。
     * 用 JUnit4 的 assumption 而不是 `return`：没跑的事不该被记成通过 ——
     * 这一点在 TurboDL 那边的 JS 用例里有同样的约定（那里用 JUnit5 的 assumeTrue）。
     */
    private fun readSampleScriptBytes(): ByteArray {
        val candidates = listOf(
            java.io.File("../YunGet-Plugins/plugins/parser.example-cloud/parser.example-cloud.js"),
            java.io.File("../../YunGet-Plugins/plugins/parser.example-cloud/parser.example-cloud.js"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: return ByteArray(0)
        return file.readBytes()
    }

    private fun assumeSample() {
        org.junit.Assume.assumeTrue(
            "同级目录下没有 YunGet-Plugins 仓库，跳过依赖真实签名数据的用例",
            sampleScript.isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------- Ed25519 验签

    /**
     * RFC 8032 §7.1 的官方测试向量 1（空消息）。
     *
     * 用官方向量而不是自己生成的密钥对：自己签自己验只能证明"实现自洽"，
     * 而 Ed25519 最容易错的地方（点解压的奇偶分支、标量小端序、S < l 的检查）
     * 恰恰要靠**外部**向量才能暴露。
     */
    @Test
    fun `verifies the RFC 8032 test vector`() {
        val pub = hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hexToBytes(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        val spki = spkiFromRaw(pub)
        val pem = toPem(spki)
        assertTrue("RFC 8032 向量 1 应当通过", PluginSignature.verify(ByteArray(0), b64(sig), pem))
    }

    /** RFC 8032 向量 2：1 字节消息 `0x72`。 */
    @Test
    fun `verifies the RFC 8032 test vector with one byte message`() {
        val pub = hexToBytes("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val sig = hexToBytes(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )
        val msg = hexToBytes("72")
        assertTrue(PluginSignature.verify(msg, b64(sig), toPem(spkiFromRaw(pub))))
    }

    /** 同一个签名换一条消息必须失败 —— 防"验签只看长度/格式"这类假实现。 */
    @Test
    fun `rejects a signature over a different message`() {
        val pub = hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hexToBytes(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        assertFalse(
            "签名是给空消息的，验另一条消息必须失败",
            PluginSignature.verify("not empty".toByteArray(), b64(sig), toPem(spkiFromRaw(pub))),
        )
    }

    /** 换一把公钥必须失败。 */
    @Test
    fun `rejects when the public key does not match`() {
        val otherPub = hexToBytes("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val sig = hexToBytes(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        assertFalse(PluginSignature.verify(ByteArray(0), b64(sig), toPem(spkiFromRaw(otherPub))))
    }

    /** 坏输入（PEM 垃圾 / base64 垃圾 / 长度不对的签名）一律 false，不抛异常。 */
    @Test
    fun `malformed input returns false instead of throwing`() {
        assertFalse(PluginSignature.verify(ByteArray(0), "!!!not base64!!!", officialPublicKey))
        assertFalse(PluginSignature.verify(ByteArray(0), b64(ByteArray(10)), officialPublicKey))
        assertFalse(PluginSignature.verify(ByteArray(0), sampleSignature, "not a pem"))
        assertFalse(PluginSignature.verify(ByteArray(0), "", officialPublicKey))
    }

    /**
     * **真仓库的签名**：这条是"端到端"的 —— 公钥与签名都来自 `YunGet-Plugins`，
     * 脚本字节从仓库文件直接读。它同时钉住三件事：验签实现、公钥内置值、以及
     * "签名覆盖原始字节"这个约定（任何一处被改，这条就会红）。
     */
    @Test
    fun `verifies the real signature published in the plugins repository`() {
        assumeSample()
        assertEquals(
            "脚本内容的 sha256 应当与索引里的一致（这条用例的前提）",
            sampleSha256,
            sampleScript.sha256Hex(),
        )
        assertTrue(
            "应该能验过仓库发布的那份签名",
            PluginSignature.verify(sampleScript, sampleSignature, officialPublicKey),
        )
    }

    /** 脚本改一个字节 → 验签失败（这是"防篡改"的正面证明）。 */
    @Test
    fun `tampering with one byte invalidates the real signature`() {
        assumeSample()
        val tampered = sampleScript.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(
            "改了字节还必须验过，说明验签没在验内容",
            PluginSignature.verify(tampered, sampleSignature, officialPublicKey),
        )
    }

    /** 内置公钥与仓库里的公钥必须是同一把（否则官方源插件全装不上）。 */
    @Test
    fun `builtin public key matches the one in the repository`() {
        val builtin = PluginTrust.builtinPublicKey("official-2026")
        assertNotNull("official-2026 必须是内置公钥", builtin)
        assertEquals(
            "内置公钥与仓库公钥必须逐字相同",
            officialPublicKey.replace(Regex("""\s+"""), ""),
            builtin!!.replace(Regex("""\s+"""), ""),
        )
    }

    // ---------------------------------------------------------------- 信任分级

    @Test
    fun `official requires both the official source and a builtin key`() {
        assertEquals(
            "官方源 + 内置公钥 = 官方",
            "official",
            PluginTrust.gradeFor("official", "official-2026"),
        )
        assertEquals(
            "第三方源即使用了同一把公钥也不能自称官方",
            "verified",
            PluginTrust.gradeFor("verified", "official-2026"),
        )
        assertEquals(
            "社区源用内置公钥时取**保守方向**：低于源等级的那个，所以是 community 而不是 verified",
            "community",
            PluginTrust.gradeFor("community", "official-2026"),
        )
    }

    @Test
    fun `no signature is always untrusted`() {
        assertEquals("untrusted", PluginTrust.gradeFor("official", ""))
        assertEquals("untrusted", PluginTrust.gradeFor("verified", "   "))
    }

    @Test
    fun `unknown key on an unknown source stays at the sources level`() {
        assertEquals("community", PluginTrust.gradeFor("community", "someone-else-2026"))
    }

    // ---------------------------------------------------------------- 索引解析

    private fun indexJson(version: String, sha: String = sampleSha256, sig: String = sampleSignature): String = """
        {
          "schemaVersion": 1,
          "source": { "id": "official", "name": "官方源", "trustLevel": "official" },
          "plugins": [
            {
              "id": "parser.example-cloud",
              "name": "Example Cloud",
              "versions": [
                {
                  "version": "$version",
                  "downloadUrl": "https://example.invalid/p.js",
                  "sha256": "$sha",
                  "sizeBytes": 8111,
                  "minHostVersion": "2.6.22",
                  "signature": "$sig",
                  "keyId": "official-2026"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses an index and exposes the newest version by version order`() {
        val json = """
            {
              "schemaVersion": 1,
              "source": { "id": "official", "name": "官方源", "trustLevel": "official" },
              "plugins": [
                {
                  "id": "p.x", "name": "X",
                  "versions": [
                    { "version": "1.0.10", "downloadUrl": "u", "sha256": "$sampleSha256", "signature": "s", "keyId": "k" },
                    { "version": "1.0.9",  "downloadUrl": "u", "sha256": "$sampleSha256", "signature": "s", "keyId": "k" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val index = MarketIndex.parse(json).getOrThrow()
        val plugin = index.find("p.x")!!
        assertEquals("最新的应当是 1.0.10（按 semver 而不是数组顺序）", "1.0.10", plugin.newestVersion()!!.version)
    }

    @Test
    fun `rejects an index whose schema is newer than supported`() {
        val json = """{ "schemaVersion": 99, "source": {}, "plugins": [] }"""
        val result = MarketIndex.parse(json)
        assertTrue("格式版本更高时必须拒绝读取", result.isFailure)
        assertTrue(
            "原因要说清是版本太高",
            result.exceptionOrNull()!!.message!!.contains("高于"),
        )
    }

    @Test
    fun `skips bad entries but keeps the good ones`() {
        val json = """
            {
              "schemaVersion": 1,
              "plugins": [
                { "name": "缺 id" },
                { "id": "no-versions" },
                { "id": "ok", "versions": [ { "version": "1.0.0", "downloadUrl": "u", "sha256": "$sampleSha256", "signature": "s", "keyId": "k" } ] }
              ]
            }
        """.trimIndent()
        val index = MarketIndex.parse(json).getOrThrow()
        assertEquals("坏条目跳过、好条目留下", listOf("ok"), index.plugins.map { it.id })
    }

    // ---------------------------------------------------------------- 更新判据

    @Test
    fun `older or equal versions are not offered as updates`() {
        val index = MarketIndex.parse(indexJson("1.0.0")).getOrThrow()
        assertNull("同版本不算更新", index.findUpdateFor("parser.example-cloud", "1.0.0"))
        // 更小的版本：索引里是 1.0.0，本地是 1.1.0
        assertNull("更小不算更新（那是降级）", index.findUpdateFor("parser.example-cloud", "1.1.0"))
    }

    @Test
    fun `newer version is offered as an update`() {
        val index = MarketIndex.parse(indexJson("1.1.0")).getOrThrow()
        val update = index.findUpdateFor("parser.example-cloud", "1.0.0")
        assertNotNull("更高版本应当出现更新", update)
        assertEquals("1.1.0", update!!.version)
    }

    /** 没有签名/摘要的版本**不算可更新** —— 宁可装作没更新，也不让用户盲装。 */
    @Test
    fun `a version without verification material is never offered`() {
        val noSig = MarketIndex.parse(indexJson("1.1.0", sig = "")).getOrThrow()
        assertNull("缺签名不算可更新", noSig.findUpdateFor("parser.example-cloud", "1.0.0"))

        val badSha = MarketIndex.parse(indexJson("1.1.0", sha = "abc")).getOrThrow()
        assertNull("摘要格式不对不算可更新", badSha.findUpdateFor("parser.example-cloud", "1.0.0"))
    }

    @Test
    fun `unknown plugin has no update`() {
        val index = MarketIndex.parse(indexJson("1.1.0")).getOrThrow()
        assertNull(index.findUpdateFor("not.installed", "1.0.0"))
    }

    /** 预发布后缀：1.1.0-rc1 高于 1.0.0，但低于 1.1.0。 */
    @Test
    fun `prerelease versions order correctly`() {
        val rc = MarketIndex.parse(indexJson("1.1.0-rc1")).getOrThrow()
        assertNotNull("1.0.0 → 1.1.0-rc1 是更新", rc.findUpdateFor("parser.example-cloud", "1.0.0"))

        val stable = MarketIndex.parse(indexJson("1.1.0")).getOrThrow()
        assertEquals("1.1.0-rc1 → 1.1.0 是更新", "1.1.0", stable.findUpdateFor("parser.example-cloud", "1.1.0-rc1")!!.version)

        val rcFromStable = MarketIndex.parse(indexJson("1.1.0-rc1")).getOrThrow()
        assertNull("1.1.0 → 1.1.0-rc1 不是更新（正式版更高）", rcFromStable.findUpdateFor("parser.example-cloud", "1.1.0"))
    }

    /** 源的信任等级不能由索引自称超过用户给的等级。 */
    @Test
    fun `index cannot claim a higher trust than the user granted`() {
        val index = MarketIndex.parse(indexJson("1.1.0")).getOrThrow()
        assertEquals("索引自称 official，但用户只给了 community → 取 community", "community", MarketIndex.effectiveTrust(index, "community"))
        assertEquals("用户给了 official，索引也自称 official → official", "official", MarketIndex.effectiveTrust(index, "official"))
    }

    // ---------------------------------------------------------------- 自报身份

    @Test
    fun `reads self reported id and version from a script`() {
        assumeSample()
        val meta = PluginIds.readSelfReportedMeta(sampleScript.toString(Charsets.UTF_8))
        assertEquals("parser.example-cloud", meta.id)
        assertEquals("1.0.0", meta.version)
    }

    @Test
    fun `missing self reported meta is not an error`() {
        val meta = PluginIds.readSelfReportedMeta("plugin.registerParser({ parse: function(){ return null; } });")
        assertNull(meta.id)
        assertNull(meta.version)
    }

    // ---------------------------------------------------------------- 工具

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** 把 32 字节原始 Ed25519 公钥包成 SPKI DER（前缀固定 12 字节）。 */
    private fun spkiFromRaw(raw: ByteArray): ByteArray {
        val prefix = hexToBytes("302a300506032b6570032100")
        return prefix + raw
    }

    private fun toPem(der: ByteArray): String =
        "-----BEGIN PUBLIC KEY-----\n" + b64(der) + "\n-----END PUBLIC KEY-----"
}
