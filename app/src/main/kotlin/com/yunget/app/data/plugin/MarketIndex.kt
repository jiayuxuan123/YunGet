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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件市场索引（就是插件源仓库里那份 `plugins.json`）的**只读模型**。
 *
 * ## 为什么解析要写成这么"啰嗦"
 *
 * 这份 JSON 来自**网络**（GitHub raw / 用户自建的源），它不受本应用控制。所以每个字段都按
 * "可能没有、可能是错的类型、可能有脏值"来读：缺字段给默认值，类型不对就跳过这一条而不是
 * 让整次解析失败 —— 一个源里有一条坏数据，不该导致整个市场打不开。
 *
 * ## 索引与"已安装"的关系
 *
 * 索引描述的是**远端有什么**；[PluginRepository] 里的记录描述的是**本机装了什么**。
 * 两者靠插件 id 对应，[MarketIndex.findUpdateFor] 负责比对出"能更新"这一状态。
 */
data class MarketIndex(
    val schemaVersion: Int,
    val sourceId: String,
    val sourceName: String,
    val trustLevel: String,
    /** 该源声明的公钥地址（按 keyId 找不到内置公钥时用它兜底）。 */
    val publicKeyUrl: String?,
    val plugins: List<MarketPlugin>,
) {
    companion object {

        /** 本应用能读的索引格式版本。**
         *
         * 比这个大的版本说明源用了新格式，本应用不认识 —— 那时候**拒绝读取**，
         * 而不是"尽力猜字段"：猜错的表现是插件列表里出现错误的版本号或权限，
         * 那比明确说"这个源需要更新的应用"糟糕得多。
         */
        const val SUPPORTED_SCHEMA = 1

        /**
         * 解析索引。
         *
         * @return 成功是索引；失败是给用户看的原因（网络层已经保证了"网络失败"不会走到这里）
         */
        fun parse(json: String): Result<MarketIndex> = runCatching {
            val root = JSONObject(json)

            val schema = root.optInt("schemaVersion", 0)
            if (schema <= 0) throw IllegalArgumentException("索引缺少 schemaVersion")
            if (schema > SUPPORTED_SCHEMA) {
                throw IllegalArgumentException(
                    "索引格式版本 $schema 高于本应用支持的 $SUPPORTED_SCHEMA，请先更新应用"
                )
            }

            val source = root.optJSONObject("source") ?: JSONObject()
            val arr = root.optJSONArray("plugins") ?: JSONArray()
            val plugins = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parseMarketPlugin(o)?.let { add(it) }
                }
            }

            MarketIndex(
                schemaVersion = schema,
                sourceId = source.optString("id").ifBlank { "unknown" },
                sourceName = source.optString("name").ifBlank { "未命名插件源" },
                trustLevel = source.optString("trustLevel").ifBlank { PluginInstalledEntity.TRUST_UNTRUSTED },
                publicKeyUrl = source.optString("publicKeyUrl").takeIf { it.isNotBlank() },
                plugins = plugins,
            )
        }

        /**
         * 一次源里的"信任覆盖"：**用户为这个源选的信任等级优先于索引自称的等级**。
         *
         * 为什么不让索引自称等级说了算：那样任何人 fork 一份索引、把 `trustLevel` 改成
         * `official` 就能冒充官方源。索引里的等级只是**源的自我描述**，真正决定风险的是
         * "这个源是不是用户主动添加的、用户给了它什么等级"。所以这里做一次覆盖，
         * 且当索引自称的等级**高于**用户给的等级时取用户的（保守方向）。
         */
        fun effectiveTrust(index: MarketIndex, userTrust: String?): String {
            val u = userTrust?.takeIf { it.isNotBlank() } ?: return index.trustLevel
            return if (trustRank(index.trustLevel) > trustRank(u)) u else index.trustLevel
        }

        /** 信任等级的高低。未知等级按最低算 —— 不认识的东西不该被当成可信。 */
        fun trustRank(level: String): Int = when (level) {
            PluginInstalledEntity.TRUST_OFFICIAL -> 3
            PluginInstalledEntity.TRUST_VERIFIED -> 2
            PluginInstalledEntity.TRUST_COMMUNITY -> 1
            else -> 0
        }
    }

    /** 按 id 找插件。 */
    fun find(id: String): MarketPlugin? = plugins.firstOrNull { it.id == id }

    /**
     * 本地某个已装插件在这个源里的**可更新版本**，没有可更新就返回 null。
     *
     * 判据（与源仓库 README 里写的约定一致，改一处要改两处）：
     *  1. 索引里能找到同 id 的插件；
     *  2. 取它的最新版本（`versions` 已按版本从新到旧排；这里仍**重新挑一次最大值**，
     *     不盲信顺序 —— 顺序是别人写的，而版本号大小是可以算的）；
     *  3. 最新版 version **严格大于**本地版本才叫"有更新"（相等不动，更小是降级，都不提示）；
     *  4. 最新版必须带 `sha256` 与 `signature`，否则**不算可更新** ——
     *     没有校验信息的更新等于让用户盲装，宁可装作没更新。
     */
    fun findUpdateFor(installedId: String, installedVersion: String): MarketPlugin.Version? {
        val remote = find(installedId) ?: return null
        val newest = remote.newestVersion() ?: return null
        if (!newest.isVerifiable) return null
        return if (compareSemver(newest.version, installedVersion) > 0) newest else null
    }
}

/** 索引里的一个插件条目。 */
data class MarketPlugin(
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val author: String,
    val license: String,
    val category: String,
    val protocols: List<String>,
    val capabilities: List<String>,
    val tags: List<String>,
    val homepage: String,
    val sourceUrl: String,
    val manifestUrl: String,
    val versions: List<Version>,
) {
    /** 索引里的一个版本。 */
    data class Version(
        val version: String,
        val releasedAt: String,
        val downloadUrl: String,
        val sha256: String,
        val sizeBytes: Long,
        val minHostVersion: String,
        val entryLanguage: String,
        val permissions: List<String>,
        val signature: String,
        val keyId: String,
        val changelog: String,
    ) {
        /**
         * 这一版是否**具备**安装/更新所需的全部校验材料。
         *
         * 校验器只认这个条件，不看"是不是官方源"：官方源里某一条漏填签名，同样不能装。
         * 这是刻意的 —— 信任等级说的是"这个源是谁"，不是"这一版有没有被验过"。
         */
        val isVerifiable: Boolean
            get() = downloadUrl.isNotBlank() &&
                sha256.length == 64 &&
                sha256.all { it.isDigit() || it in 'a'..'f' } &&
                signature.isNotBlank() &&
                keyId.isNotBlank()
    }

    /** 最新版本 = 版本号最大的那个（不是数组里第一个）。 */
    fun newestVersion(): Version? = versions.maxWithOrNull { a, b -> compareSemver(a.version, b.version) }

    /** 是否包含某个协议（市场筛选用；一个插件可以声明多个）。 */
    fun servesProtocol(scheme: String): Boolean = protocols.any { it.equals(scheme, ignoreCase = true) }
}

/**
 * 版本比较：`a > b` 返回正数。
 *
 * **直接复用自更新那条比较器**（`UpdateChecker.compareVersions`）—— 它已经处理了这个项目里
 * 真实出现过的坑：预发布后缀（`2.6.9-rc1 < 2.6.9`）、后缀里的多位数（`dev9 < dev12`，
 * 按字符串比会反过来）。插件版本用同一套规则，没有理由写第二个实现。
 */
internal fun compareSemver(a: String, b: String): Int =
    com.yunget.app.data.update.UpdateChecker.compareVersions(a, b)

/** 从索引 JSON 里读一个插件条目；缺 id 或没有可用版本的条目按"坏数据"跳过。 */
internal fun parseMarketPlugin(o: JSONObject): MarketPlugin? {
    val id = o.optString("id").trim()
    if (id.isBlank()) return null

    val versions = o.optJSONArray("versions")?.let { arr ->
        buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val ver = v.optString("version").trim()
                if (ver.isBlank()) continue
                add(
                    MarketPlugin.Version(
                        version = ver,
                        releasedAt = v.optString("releasedAt"),
                        downloadUrl = v.optString("downloadUrl").trim(),
                        sha256 = v.optString("sha256").trim().lowercase(),
                        sizeBytes = v.optLong("sizeBytes", 0L),
                        minHostVersion = v.optString("minHostVersion").trim(),
                        entryLanguage = v.optString("entryLanguage").ifBlank { "js" },
                        permissions = v.optJSONArray("permissions").toStringList(),
                        signature = v.optString("signature").trim(),
                        keyId = v.optString("keyId").trim(),
                        changelog = v.optString("changelog"),
                    )
                )
            }
        }
    } ?: emptyList()

    if (versions.isEmpty()) return null

    return MarketPlugin(
        id = id,
        name = o.optString("name").ifBlank { id },
        summary = o.optString("summary"),
        description = o.optString("description"),
        author = o.optString("author"),
        license = o.optString("license"),
        category = o.optString("category"),
        protocols = o.optJSONArray("protocols").toStringList(),
        capabilities = o.optJSONArray("capabilities").toStringList(),
        tags = o.optJSONArray("tags").toStringList(),
        homepage = o.optString("homepage"),
        sourceUrl = o.optString("sourceUrl"),
        manifestUrl = o.optString("manifestUrl"),
        versions = versions,
    )
}

/** `JSONArray` → `List<String>`：非字符串元素直接丢掉，不抛。 */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val s = optString(i).trim()
            if (s.isNotEmpty()) add(s)
        }
    }
}
