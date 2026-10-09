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
 * 插件信任：**内置公钥**与"这个源算哪一级"的判定。
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

    /**
     * 随应用内置的公钥：`keyId` → PEM 文本。
     *
     * 官方源的插件即使离线也能验签，不依赖网络去取公钥 —— 这一点很重要：
     * 公钥从网络取意味着"能改公钥的人就能伪造插件"，而内置公钥把这个信任根钉在应用里。
     *
     * 新增/轮换公钥就是往这个 map 里加一条：旧 keyId **保留**（已发布的插件还要验它），
     * 吊销则把对应的条目删掉或置空（见源仓库 `keys/README.md` 的约定）。
     */
    private val BUILTIN_KEYS: Map<String, String> = mapOf(
        // 官方源公钥，取自 YunGet-Plugins 仓库的 keys/official-2026.pub（SPKI PEM）。
        // 指纹（SHA-256 of DER）= 8f0061ddbfa913b8a5b4050d3a0b246e5922b8383509c1879b1f4e9487902330
        // —— 换公钥时必须同步更新这一行注释，它是"内置的到底是哪把钥匙"的唯一凭据。
        "official-2026" to """
            -----BEGIN PUBLIC KEY-----
            MCowBQYDK2VwAyEAreoDyAm377RaS2C91NsidyEFwLO8KDjvW3WLkRx7DqE=
            -----END PUBLIC KEY-----
        """.trimIndent(),
    )

    /** 内置公钥（找不到返回 null，调用方会回退到源声明的 publicKeyUrl）。 */
    fun builtinPublicKey(keyId: String): String? = BUILTIN_KEYS[keyId.trim()]

    /** 内置公钥的 keyId 集合，供界面说明"哪些 keyId 属于官方"。 */
    fun builtinKeyIds(): Set<String> = BUILTIN_KEYS.keys

    /**
     * 判定一次安装该用哪个信任等级。
     *
     * @param sourceTrust 用户为该源设的等级（[PluginSourceEntity.trustLevel]）
     * @param keyId 该版本的签名 keyId（可能为空 = 没签名）
     */
    fun gradeFor(sourceTrust: String, keyId: String): String {
        val key = keyId.trim()
        val builtin = key.isNotEmpty() && builtinPublicKey(key) != null
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
