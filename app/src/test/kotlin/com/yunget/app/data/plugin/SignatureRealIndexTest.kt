package com.yunget.app.data.plugin

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实发布的索引 + 真实插件文件**验证验签的两条路径。
 *
 * ## 为什么需要这条
 *
 * 真机上出现"sha256 与索引一致、签名字段一致、keyId 一致，验签却不过"的现象
 * （lanzou 过、pan123/quark/support.official/example 不过）。三者都对的前提下
 * 验签必须通过 —— 所以问题只可能在验签实现本身。
 *
 * [PluginSignature.verify] 有两条路径：
 *  1. 平台 `Signature("Ed25519")`（API 33+）；
 *  2. 失败时回退到纯 Kotlin 的 [PluginSignature.Ed25519]。
 *
 * 真机两条都不过才会报失败，所以两条都要**对着真实字节**验一遍 ——
 * 只测 RFC 向量的通用实现可能漏掉"特定长度/特定首字节的输入"这类相关性缺陷。
 */
class SignatureRealIndexTest {

    private val indexFile = File("../../YunGet-Plugins/plugins.json")

    /**
     * 插件文件的路径。
     *
     * **只认 `.js`**：Android 端只剩 JS 插件（原生/L2 已移除，见 `PluginLevel` 的类注释），
     * 所以这条回归测试只覆盖"这台设备真的能装上的东西"。索引里那 4 条 `level: "native"`
     * 在 Android 上会被市场直接跳过，验它们等于验一份永远不会被安装的字节。
     * （Ed25519 实现本身没有变，仍是 PC 与 Android 共用的那一份；将来若需要覆盖 jar 条目，
     * 这里放开后缀即可。）
     */
    private fun payloadPath(pluginId: String): File =
        File("../../YunGet-Plugins/plugins/$pluginId/$pluginId.js")

    private fun entries(): List<Entry> {
        if (!indexFile.isFile) return emptyList()
        val index = JSONObject(indexFile.readText())
        val plugins = index.getJSONArray("plugins")
        val out = mutableListOf<Entry>()
        for (i in 0 until plugins.length()) {
            val p = plugins.getJSONObject(i)
            val id = p.getString("id")
            val level = p.optString("level", "js")
            if (level != "js") continue   // 原生（L2）条目在 Android 上不会被安装，见 payloadPath
            val v = p.getJSONArray("versions").getJSONObject(0)
            val file = payloadPath(id)
            if (!file.isFile) continue
            val bytes = file.readBytes()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val pem = PluginTrust.builtinPublicKey(v.getString("keyId")) ?: continue
            out += Entry(id, bytes, v.getString("signature"), pem, digest, v.getString("sha256"))
        }
        return out
    }

    private data class Entry(
        val id: String,
        val bytes: ByteArray,
        val signature: String,
        val pem: String,
        val actualSha256: String,
        val indexSha256: String,
    )

    @Test
    fun `真实插件的摘要与索引一致`() {
        val entries = entries()
        if (entries.isEmpty()) return
        val bad = entries.filterNot { it.actualSha256.equals(it.indexSha256, ignoreCase = true) }
            .map { "${it.id}：实际 ${it.actualSha256.take(12)} / 索引 ${it.indexSha256.take(12)}" }
        assertTrue("摘要不符：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `真实插件能被内置公钥验签通过（平台路径）`() {
        val entries = entries()
        if (entries.isEmpty()) return
        val bad = entries.filterNot { e ->
            val der = PluginSignature.decodePublicKey(e.pem)!!
            val sig = java.util.Base64.getDecoder().decode(e.signature)
            val key = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(der))
            java.security.Signature.getInstance("Ed25519").run {
                initVerify(key); update(e.bytes); verify(sig)
            }
        }.map { it.id }
        assertTrue("平台 Ed25519 路径验签失败：$bad", bad.isEmpty())
    }

    @Test
    fun `真实插件能被内置公钥验签通过（纯 Kotlin 回退路径）`() {
        val entries = entries()
        if (entries.isEmpty()) return
        val bad = entries.filterNot { e ->
            val der = PluginSignature.decodePublicKey(e.pem)!!
            val raw = der.copyOfRange(der.size - PluginSignature.Ed25519.PUBLIC_KEY_BYTES, der.size)
            val sig = java.util.Base64.getDecoder().decode(e.signature)
            PluginSignature.Ed25519.verify(e.bytes, sig, raw)
        }.map { "${it.id}（${it.bytes.size} 字节）" }
        assertTrue(
            "纯 Kotlin Ed25519 路径验签失败：$bad —— 这条路径没有平台兜底，真机 API<33 上会直接拒装",
            bad.isEmpty(),
        )
    }
}
