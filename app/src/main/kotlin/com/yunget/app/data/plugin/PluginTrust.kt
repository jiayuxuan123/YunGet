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

import com.yunget.app.data.db.PluginInstalledEntity

/**
 * 插件信任：**内置公钥表 + 吊销名单**与"这个源算哪一级"的判定。
 *
 * ## 轮换与吊销（P6，设计文档第 8.2 章）
 *
 * Ed25519 没有"撤销签名"：发出去的签名永远数学上有效。唯一的补救是**不再信任那把钥匙**。
 * 所以这里有两张表，都随 App 版本发布：
 *
 * | 表 | 作用 | 出事时怎么办 |
 * |---|---|---|
 * | [BUILTIN_KEYS] 内置公钥表 | keyId → 公钥 + 指纹，一个 keyId 一条，**轮换就是加一条** | 旧 keyId 必须保留：已发布的插件还要验它 |
 * | [REVOKED_KEYS] 吊销名单 | keyId → 吊销原因 | 私钥泄露时把 keyId 加进来，下次发版即生效 |
 *
 * 三条不肯让步的规则：
 *
 * 1. **吊销先于验签**：被吊销的 keyId 在解析公钥**之前**就被拒（见 [verdictFor]），
 *    而不是"验签失败"。签名有效但钥匙已作废，和签名无效一样不能装。
 * 2. **吊销不能被源声明的公钥绕过**：`resolvePublicKey` 在取 `publicKeyUrl` 之前
 *    先查吊销名单 —— 否则换把钥匙再让源自己声明一把就绕过去了。
 * 3. **吊销名单随 App 发，不运行时从网络拉**。运行时拉吊销名单等于把"谁能撤销谁的钥匙"
 *    交给一个网络端点：拿到端点的人既能放行恶意签名，也能把官方 keyId 拉黑造成拒绝服务。
 *    代价是吊销生效要等发版 —— 这个代价换来的是信任根不外移。
 *
 * ## 指纹是干什么的
 *
 * 每把内置公钥都写死了指纹（SHA-256 of 32 字节原始公钥）。公钥本身没有签名保护，
 * 所以"换掉公钥再换掉插件"是一条完整的攻击链；把指纹一起内置并在每次使用时核对，
 * 才能在应用内部发现"代码里这把钥匙不是我以为的那把"。
 * [selfCheck] 会把这层核对固化成测试（见 `PluginTrustTest`）。
 *
 * ## 四个等级的语义（与设计文档第 8.1 章一致）
 *
 * | 等级 | 什么情况下给 | 界面怎么显示 |
 * |---|---|---|
 * | `official` | 官方源，**且**签名用的公钥是随应用内置的那把 | 正常样式，标"官方" |
 * | `verified` | 用户主动添加的源，签名公钥由该源自带（有签名，但发布者身份靠源自己声明） | 次级样式，标"已验证" |
 * | `community` | 用户主动添加的源，条目带签名但公钥来自网络 | 中性样式，标"社区" |
 * | `untrusted` | 没有签名，或公钥拿不到 | **error 色系**，标"未验证" + 明确风险提示 |
 *
 * ## 两条不肯让步的规则
 *
 * 1. **内置公钥只认官方源**：不能因为某个第三方源用了同一把公钥就把它当官方 ——
 *    那意味着任何人只要拿到官方签名过的插件就能自建一个"官方源"。所以等级判定要
 *    "源的身份 + 公钥来源"两个条件同时满足。
 * 2. **等级只影响显示与提示，不影响校验**：无论哪一级，[MarketClient] 都要求
 *    sha256 + 签名齐备才允许安装。低等级不是"可以少验一点"，而是"验完了仍然要提醒你"。
 *    把两件事混在一起（低等级就放宽校验）是这个设计最容易走错的一步。
 */
object PluginTrust {

    /** 一把内置公钥：PEM + 指纹（SHA-256 of 32 字节原始公钥，十六进制小写）。 */
    data class BuiltinKey(val keyId: String, val pem: String, val fingerprint: String)

    /**
     * 随应用内置的公钥表：**一个 keyId 一条**，所以轮换 = 加一条。
     *
     * 官方源的插件即使离线也能验签，不依赖网络去取公钥 —— 这一点很重要：
     * 公钥从网络取意味着"能改公钥的人就能伪造插件"，而内置公钥把这个信任根钉在应用里。
     *
     * 轮换规矩（照 `YunGet-Plugins/keys/README.md` 的「吊销」一节）：
     *  - **轮换**：加新 keyId 重新签，旧 keyId **保留**（已发布的插件还要验它）；
     *  - **吊销**：把 keyId 加进 [REVOKED_KEYS]，旧公钥从这张表里**移走**
     *    （留着只会让人以为还能用；历史审计靠的是 keys/ 里的文件，不是这张表）。
     */
    private val BUILTIN_KEYS: List<BuiltinKey> = listOf(
        // 官方源公钥，取自 YunGet-Plugins 仓库的 keys/official-2026.pub（SPKI PEM）。
        BuiltinKey(
            keyId = "official-2026",
            pem = """
                -----BEGIN PUBLIC KEY-----
                MCowBQYDK2VwAyEAreoDyAm377RaS2C91NsidyEFwLO8KDjvW3WLkRx7DqE=
                -----END PUBLIC KEY-----
            """.trimIndent(),
            // 指纹 = SHA-256(公钥原始 32 字节)。**换公钥时必须同步更新这一行**：
            // 它与仓库 keys/README.md 里记的是同一个值，两处对不上说明有人只改了一边。
            fingerprint = "8f0061ddbfa913b8a5b4050d3a0b246e5922b8383509c1879b1f4e9487902330",
        ),
    )

    /**
     * 吊销名单：keyId → 吊销原因（原因会原样显示给用户，所以要写人话 + 什么时候 + 换到哪把）。
     *
     * 随 App 版本发布，**不从网络更新**（理由见文件头规则 ③）。
     *
     * 吊销步骤（完整流程见 `YunGet-Plugins/keys/README.md`）：
     * 1. 停用泄露的私钥，生成新密钥用新 keyId 重签并发布；
     * 2. 把泄露的 keyId 写进下面这张表，附上原因；
     * 3. 发 App 更新 —— 这一步是**唯一**能挡住老版本用户的办法。
     *
     * 现在这张表是空的：截至目前没有泄露事件。留一个空表（而不是不写这层逻辑），
     * 是为了让"该怎么做"在出事当天不用临场想。
     */
    private val REVOKED_KEYS: Map<String, String> = mapOf(
        // 格式："keyId" to "原因（必须写清：什么事件、什么时间、改用哪个 keyId）"
    )

    /**
     * 真正的策略对象：两张表 + 全部判定。单独拆出来是为了**能在测试里注入一张
     * 合成的密钥表与吊销表**（见 `PluginTrustTest`）。
     *
     * 不拆的话，"吊销后旧签名被拒"这条验收标准就只能对着一个真实存在的、
     * 目前还是空的吊销名单断言 —— 那等于什么都没验；而要造一个真实吊销事件
     * 去验它，又得先生成密钥、签名、再删掉，比拆一个类贵得多也蠢得多。
     */
    internal class Policy(
        keys: List<BuiltinKey>,
        private val revokedKeys: Map<String, String>,
    ) {
        private val keysById: Map<String, BuiltinKey> = keys.associateBy { it.keyId }

        /**
         * 判定一个 keyId 当前能不能用 —— **在任何公钥查找与验签之前**调用。
         *
         * 放在最前面是刻意的：吊销是对"信任"而不是对"数学"的判断。
         * 先验签再查吊销看起来等价（结果都是拒绝），但一旦有人把吊销检查挪到验签之后，
         * 或者在回退路径上忘了查，就等于给吊销开了一个口子。
         */
        fun verdictFor(keyId: String): KeyVerdict {
            val key = keyId.trim()
            revokedKeys[key]?.let { return KeyVerdict.Revoked(key, it) }
            return KeyVerdict.Allowed
        }

        /** 该 keyId 是否已吊销。 */
        fun isRevoked(keyId: String): Boolean = verdictFor(keyId) is KeyVerdict.Revoked

        /** 吊销原因（未吊销返回 null）。 */
        fun revocationReason(keyId: String): String? =
            (verdictFor(keyId) as? KeyVerdict.Revoked)?.reason

        /** 已吊销的 keyId 集合，供界面提示。 */
        fun revokedKeyIds(): Set<String> = revokedKeys.keys

        /**
         * 内置公钥（找不到或已吊销返回 null）。
         *
         * 返回 null 本身**不足以**保证吊销生效：调用方还会回退到源声明的 `publicKeyUrl`，
         * 那把钥匙可能是被吊销的那一把。所以 [MarketClient.resolvePublicKey] 在那条路上
         * 也要先查 [verdictFor] —— 两处合起来才是"吊销的钥匙一条路都走不通"。
         */
        fun builtinPublicKey(keyId: String): String? {
            val key = keyId.trim()
            if (revokedKeys.containsKey(key)) return null
            return keysById[key]?.pem
        }

        /** 内置公钥的 keyId 集合，供界面说明"哪些 keyId 属于官方"。**不含已吊销的。** */
        fun builtinKeyIds(): Set<String> = keysById.keys - revokedKeys.keys

        /** 内置公钥声明的指纹（用于界面与核对；未内置返回 null）。 */
        fun fingerprintOf(keyId: String): String? = keysById[keyId.trim()]?.fingerprint

        /**
         * 内置表的自检：逐把公钥算指纹并与声明值比对，同时确认**吊销的钥匙不在有效表里**。
         *
         * 为什么专门查后者：轮换时最顺手的事就是"把这把加进新表 + 加进吊销表"，
         * 忘了从内置表里移走，于是 [builtinPublicKey] 仍把吊销的钥匙交出去 ——
         * 而 `resolvePublicKey` 拿到公钥后直接验签，吊销名单这条防线就被完全绕开。
         * 这个错误编译得过、运行时也无异常，只有自检能发现。
         */
        fun selfCheck(): List<String> {
            val problems = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (key in keysById.values) {
                if (!seen.add(key.keyId)) problems += "内置表里 keyId 重复：${key.keyId}"
                val der = PluginSignature.decodePublicKey(key.pem)
                if (der == null) {
                    problems += "${key.keyId}：公钥 PEM 解析失败"
                    continue
                }
                if (der.size < 32) {
                    problems += "${key.keyId}：公钥太短（${der.size} 字节），不是 Ed25519 公钥"
                    continue
                }
                val actual = sha256Hex(der.copyOfRange(der.size - 32, der.size))
                if (!actual.equals(key.fingerprint, ignoreCase = true)) {
                    problems += "${key.keyId}：指纹对不上（声明 ${key.fingerprint}，实际 $actual）——" +
                        "内置的公钥与记录不符，请当成泄露处理并核对 keys/ 仓库"
                }
            }
            for (revoked in revokedKeys.keys) {
                if (keysById.containsKey(revoked)) {
                    problems += "$revoked 已列入吊销名单，却仍留在内置公钥表里 —— 它仍会被用来验签通过"
                }
                if (revokedKeys[revoked].orEmpty().isBlank()) {
                    problems += "$revoked 在吊销名单里但没写原因 —— 用户会看到一句没有出处的拒绝"
                }
            }
            return problems
        }
    }

    private val policy = Policy(BUILTIN_KEYS, REVOKED_KEYS)

    /** keyId 的裁定结果。安装流程用它决定"这把钥匙能不能用"。 */
    sealed interface KeyVerdict {
        /** 钥匙可用（可能是内置的，也可能是稍后从源的 publicKeyUrl 取到的）。 */
        data object Allowed : KeyVerdict

        /** 这把钥匙已作废：签名再有效也一律拒绝，原因用于提示用户。 */
        data class Revoked(val keyId: String, val reason: String) : KeyVerdict
    }

    fun verdictFor(keyId: String): KeyVerdict = policy.verdictFor(keyId)

    /** 该 keyId 是否已吊销。 */
    fun isRevoked(keyId: String): Boolean = policy.isRevoked(keyId)

    /** 吊销原因（未吊销返回 null）。 */
    fun revocationReason(keyId: String): String? = policy.revocationReason(keyId)

    /** 已吊销的 keyId 集合，供界面提示。 */
    fun revokedKeyIds(): Set<String> = policy.revokedKeyIds()

    /** 内置公钥（找不到或已吊销返回 null）。 */
    fun builtinPublicKey(keyId: String): String? = policy.builtinPublicKey(keyId)

    /** 内置公钥的 keyId 集合，供界面说明"哪些 keyId 属于官方"。**不含已吊销的。** */
    fun builtinKeyIds(): Set<String> = policy.builtinKeyIds()

    /** 内置公钥声明的指纹（用于界面与核对；未内置返回 null）。 */
    fun fingerprintOf(keyId: String): String? = policy.fingerprintOf(keyId)

    /**
     * 内置表自检，**返回问题清单**（空 = 一切正常）。
     *
     * 做成"返回问题"而不是"抛异常"：这是一条开发期不变量，不该在用户手机上炸；
     * 由 `PluginTrustTest` 在 CI 上断言它为空。
     */
    fun selfCheck(): List<String> = policy.selfCheck()

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 判定一次安装该用哪个信任等级。
     *
     * @param sourceTrust 用户为该源设的等级（[PluginSourceEntity.trustLevel]）
     * @param keyId 该版本的签名 keyId（可能为空 = 没签名）
     */
    fun gradeFor(sourceTrust: String, keyId: String): String {
        val key = keyId.trim()
        // 被吊销的钥匙绝不能算进"官方"（正常路径下安装早已被拒，这里是纵深防御）
        val builtin = key.isNotEmpty() && !isRevoked(key) && builtinPublicKey(key) != null
        return when {
            // 官方源 + 内置公钥：两个条件都满足才叫官方
            sourceTrust == PluginInstalledEntity.TRUST_OFFICIAL && builtin -> PluginInstalledEntity.TRUST_OFFICIAL
            // 有签名：按源自己的等级走，但**最高只能到 verified**（社区源自带公钥不能自封官方）
            key.isNotEmpty() -> minOf(sourceTrust, PluginInstalledEntity.TRUST_VERIFIED, ::trustRank)
            // 没签名：一律最低等级
            else -> PluginInstalledEntity.TRUST_UNTRUSTED
        }
    }

    private fun trustRank(level: String): Int = MarketIndex.trustRank(level)

    /** 取两个等级里较保守的那个。 */
    private fun minOf(a: String, b: String, rank: (String) -> Int): String =
        if (rank(a) <= rank(b)) a else b

    /**
     * 界面上给用户看的一句话（列表/详情/安装确认都用它）。
     *
     * 措辞刻意保守：文档第 26 章明确要求"已验证"不得写成"绝对安全"，
     * 所以这里说的是"签名验证通过"，而不是"安全可用"。
     */
    fun describe(trustLevel: String): String = when (trustLevel) {
        PluginInstalledEntity.TRUST_OFFICIAL -> "官方（签名公钥随应用内置）"
        PluginInstalledEntity.TRUST_VERIFIED -> "已验证（签名有效，发布者身份由插件源声明）"
        PluginInstalledEntity.TRUST_COMMUNITY -> "社区（来自你添加的插件源，签名有效）"
        else -> "未验证（没有签名，无法确认来源与完整性）"
    }

    /** 是否需要在界面上用醒目样式提示风险。 */
    fun needsWarning(trustLevel: String): Boolean =
        trustLevel == PluginInstalledEntity.TRUST_UNTRUSTED
}
