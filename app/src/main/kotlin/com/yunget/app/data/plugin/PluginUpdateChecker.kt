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

import android.content.Context
import com.yunget.app.data.db.PluginSourceEntity
import com.yunget.app.data.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 插件的**检查更新**：把"本机装了什么"和"源里有什么"对起来，算出谁可以更新。
 *
 * ## 为什么单独一个类，而不是塞进 [MarketClient]
 *
 * 检查更新是**跨源、批量、只读**的操作（对每个已装插件问一遍所有启用的源），
 * 而 [MarketClient] 管的是单次安装/下载。两者失败语义也不同：装插件失败要告诉用户原因，
 * 而"某个源拉不到"只该让那一个源的结果缺失，不该让整次检查失败 —— 这也是它返回
 * `updates + errors` 两部分、而不是一个 `Result` 的原因。
 *
 * ## 判据（用户明确要求："插件更新了要能更新它"）
 *
 * 对每个已装插件：
 *  1. 在所有**启用的源**里找同 id 的条目；
 *  2. 取该条目的最新版本（按 semver 大小挑，不信任数组顺序）；
 *  3. 最新版必须带 sha256 + 签名（[MarketPlugin.Version.isVerifiable]）——
 *     没有校验信息的更新宁可当作"没有更新"，绝不让用户盲装；
 *  4. 最新版版本号**严格大于**本地版本才算有更新（相等跳过、更小是降级也跳过）；
 *  5. 若最新版的 `minHostVersion` 高于当前应用版本，**仍然算有更新**，但标记出来，
 *     界面要说明"需要先更新应用"—— 瞒着用户比报错更糟。
 *
 * 一个插件同时出现在多个源里时，**取版本号最高的那个源**；版本相同时按源的信任等级取高的
 * （官方 > 已验证 > 社区 > 未验证）。这样"官方源慢了一版、社区源已经跟上"时用户能拿到新的，
 * 而两个源同版本时又不会被社区源覆盖掉官方来源的标注。
 */
class PluginUpdateChecker(private val context: Context) {

    /** 一个可更新的插件。 */
    data class Available(
        val installedId: String,
        val installedVersion: String,
        val plugin: MarketPlugin,
        val version: MarketPlugin.Version,
        /** 来自哪个源（用户能看到"我从哪拿到的这一版"）。 */
        val sourceId: String,
        val sourceName: String,
        val trustLevel: String,
        /** true = 这一版要求的宿主版本高于当前应用，装了也跑不起来。 */
        val requiresNewerApp: Boolean,
        /** 该版要求的宿主版本（requiresNewerApp 为 true 时有意义）。 */
        val minHostVersion: String,
    )

    /** 一次检查的结果：能更新的 + 各源各自的失败原因。 */
    data class Report(
        val updates: List<Available>,
        /** `sourceId` → 失败原因。空 map 表示所有源都拉到了。 */
        val sourceErrors: Map<String, String>,
        /** 检查过的已装插件数（用于界面显示"已检查 N 个插件"）。 */
        val checkedCount: Int,
    ) {
        val hasUpdates: Boolean get() = updates.isNotEmpty()
    }

    private val repo = PluginRepository(context)
    private val client = MarketClient(context)

    /**
     * 跑一次检查。
     *
     * @param onSourceStart 每个源开始拉取时的回调（界面显示进度用），可空
     */
    suspend fun check(
        onSourceStart: ((String) -> Unit)? = null,
    ): Report = withContext(Dispatchers.IO) {
        val installed = repo.installedNow()
        if (installed.isEmpty()) {
            return@withContext Report(emptyList(), emptyMap(), 0)
        }

        val sources = repo.enabledSourcesNow()
        if (sources.isEmpty()) {
            return@withContext Report(
                emptyList(),
                emptyMap(),
                installed.size,
            )
        }

        // 并行拉各个源：源之间互不依赖，串行拉会让"3 个源"的等待时间变成 3 倍
        val fetched: List<Pair<PluginSourceEntity, Result<MarketIndex>>> = coroutineScope {
            sources.map { source ->
                async {
                    onSourceStart?.invoke(source.displayName.ifBlank { source.id })
                    source to client.fetchIndex(source)
                }
            }.awaitAll()
        }

        val indexes: List<Triple<PluginSourceEntity, MarketIndex, String>> = fetched.mapNotNull { (source, result) ->
            result.getOrNull()?.let { index ->
                Triple(source, index, MarketIndex.effectiveTrust(index, source.trustLevel))
            }
        }
        val errors = fetched.mapNotNull { (source, result) ->
            result.exceptionOrNull()?.let { source.id to (it.message ?: it.javaClass.simpleName) }
        }.toMap()

        val updates = buildList {
            for (item in installed) {
                pickBest(item, indexes)?.let { add(it) }
            }
        }
        Report(updates, errors, installed.size)
    }

    /**
     * 在多个源里为 [installed] 挑出最合适的那一版；没有可更新就返回 null。
     *
     * 挑选规则见类注释第 5 段（先比版本，同版本比源信任等级）。
     */
    private fun pickBest(
        installed: InstalledPlugin,
        indexes: List<Triple<PluginSourceEntity, MarketIndex, String>>,
    ): Available? {
        val local = installed.entity.version.ifBlank { "0" }
        var best: Available? = null

        for ((source, index, trust) in indexes) {
            val plugin = index.find(installed.entity.id) ?: continue
            val version = plugin.newestVersion() ?: continue

            // 关 3：没有校验信息就不算可更新（见类注释）
            if (!version.isVerifiable) continue
            // 关 4：必须严格更新
            if (UpdateChecker.compareVersions(version.version, local) <= 0) continue

            val candidate = Available(
                installedId = installed.entity.id,
                installedVersion = local,
                plugin = plugin,
                version = version,
                sourceId = source.id,
                sourceName = source.displayName.ifBlank { index.sourceName },
                trustLevel = PluginTrust.gradeFor(trust, version.keyId),
                requiresNewerApp = version.minHostVersion.isNotBlank() &&
                    UpdateChecker.compareVersions(hostVersion(), version.minHostVersion) < 0,
                minHostVersion = version.minHostVersion,
            )

            best = better(best, candidate)
        }
        return best
    }

    /** a 与 b 谁更该被推荐：版本号大的赢；同版本时信任等级高的赢；再同就保持先来的。 */
    private fun better(a: Available?, b: Available): Available {
        val cur = a ?: return b
        val byVersion = UpdateChecker.compareVersions(b.version.version, cur.version.version)
        if (byVersion > 0) return b
        if (byVersion < 0) return cur
        return if (MarketIndex.trustRank(b.trustLevel) > MarketIndex.trustRank(cur.trustLevel)) b else cur
    }

    /** 当前应用版本名（用于 `minHostVersion` 比较）。 */
    private fun hostVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 一键更新：把 [available] 装上去（与安装同一条校验链），成功后**重新加载**该插件。
     *
     * 重新加载而不是等下次启动：用户点了"更新"就该立刻生效，否则会看到"已更新到 1.1.0"
     * 但脚本还是旧的行为 —— 那比不更新更让人困惑。
     *
     * ## 加载失败时自动回滚（P4）
     *
     * 更新是**覆盖式**的：新脚本写盘、库记录改版本、旧脚本文件被删。所以新版本一旦加载失败，
     * 用户就卡在坏版本上 —— 而旧版本刚被我们自己删掉，连手动退回的素材都没有。
     * 设计文档第 8.5 章要求"更新失败应支持回滚到上一可用版本"。
     *
     * 现在：更新前留快照（见 `PluginRepository.snapshotBeforeUpdate`），
     * 加载失败则自动回滚并**重新加载回滚后的版本**，然后把"已回滚到 X"作为失败原因返回。
     * 返回 `Result.failure` 而不是 success：这件事对用户是"更新没成功"，
     * 界面上不该显示成绿的 —— 但插件仍然是可用的，原因里会写清楚。
     */
    suspend fun apply(available: Available): Result<String> = withContext(Dispatchers.IO) {
        val runtime = PluginRuntime(context)
        client.installOrUpdate(
            plugin = available.plugin,
            version = available.version,
            sourceId = available.sourceId,
            trustLevel = available.trustLevel,
            index = cachedIndexFor(available.sourceId),
        ).mapCatching { installed ->
            if (!installed.entity.enabled) {
                // 用户把这个插件关着：更新完不加载，也就谈不上"加载失败要回滚"。
                return@mapCatching available.version.version
            }
            val loadError = runtime.load(installed.entity.id)
            if (loadError == null) return@mapCatching available.version.version

            // 加载失败 → 尝试回滚到上一可用版本
            val rolledBack = repo.rollbackToPrevious(installed.entity.id)
            if (rolledBack == null) {
                throw IllegalStateException(
                    "已更新但加载失败：$loadError（没有可回滚的上一版本）"
                )
            }
            // 回滚后立刻重载，确认回滚真的把插件救回来了 —— 否则用户会看到
            // "已回滚到 1.0.0" 但插件其实还是跑不起来，那比不回滚更糟。
            val afterRollback = runtime.load(installed.entity.id)
            if (afterRollback != null) {
                throw IllegalStateException(
                    "已更新但加载失败：$loadError；" +
                        "回滚到 $rolledBack 后仍无法加载：$afterRollback"
                )
            }
            throw IllegalStateException(
                "新版本 ${available.version.version} 加载失败：$loadError；已自动回滚到 $rolledBack"
            )
        }
    }

    /**
     * 取某个源上一次拉到的索引（给 `apply` 用：它只做安装，不需要重新拉一次网络）。
     *
     * 拿不到时返回一个**空索引**而不是抛错：安装流程里索引只用于"按 keyId 找公钥"，
     * 官方 keyId 有内置公钥，所以空索引仍能完成校验；非官方源缺公钥会由校验层明确拒绝，
     * 那是准确的结果。
     */
    private suspend fun cachedIndexFor(sourceId: String): MarketIndex {
        val json = repo.cachedSourceIndex(sourceId) ?: return emptyIndexOf(sourceId)
        return MarketIndex.parse(json).getOrElse { emptyIndexOf(sourceId) }
    }

    private fun emptyIndexOf(sourceId: String) = MarketIndex(
        schemaVersion = MarketIndex.SUPPORTED_SCHEMA,
        sourceId = sourceId,
        sourceName = sourceId,
        trustLevel = "",
        publicKeyUrl = null,
        plugins = emptyList(),
    )
}
