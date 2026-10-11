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
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.update.UpdateChecker
import com.yunget.app.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

/**
 * 从插件源**取插件**：拉索引、下载脚本、按约定的顺序校验、交给 [PluginRepository] 落盘。
 *
 * ## 校验顺序（与源仓库 README 里写的规范必须一致）
 *
 * 一次安装或更新要依次过这几关，**任何一关不过就不装**：
 *
 *  1. 索引里的这一版必须齐备 `downloadUrl` + `sha256` + `signature` + `keyId`
 *     （[MarketPlugin.Version.isVerifiable]）；
 *  1.5 `keyId` 不在吊销名单里（P6，见 [PluginTrust.verdictFor]）—— 这一关在任何
 *     公钥查找与验签之前，因为"钥匙作废"是信任判断，签名有效并不代表还该认它；
 *  2. 宿主版本满足该版的 `minHostVersion`；
 *  3. 下载到的字节数必须等于索引里的 `sizeBytes`（不为 0 时），SHA-256 必须等于索引里的值；
 *  4. Ed25519 验签：**对脚本原始字节**验，公钥按 `keyId` 取（内置 → 源的 `publicKeyUrl`）；
 *  5. 脚本自报的 `defineMeta({id, version})` 必须与索引条目**逐字相等** ——
 *     这是"版本号不可被索引伪造"的落点：脚本字节被签名保护，所以自报值可信。
 *
 * **更新与安装走的是同一条链路**（本类只有一条 [fetchAndVerify]）：更新时没有任何一步被放宽，
 * 唯一多的动作是拿新版本号去覆盖记录。
 *
 * ## 镜像与回退（P5）
 *
 * 索引、脚本、清单、公钥四类请求都走 [PluginMirror] 给出的候选顺序：
 * 本源配了镜像先走本源镜像，否则 GitHub 系地址走全局镜像，**直连永远排在最后兜底**。
 * 取字节（[httpGetBytes]）与校验（[fetchAndVerify]）是分开的两步 —— 所有通道取回的字节
 * 走同一条校验链，所以换镜像只是换通道，不会换来"没验签也装得上"。
 */
class MarketClient(private val context: Context) {

    private companion object {
        const val TAG = "PluginMarket"

        /**
         * 索引体积上限。索引里会放较长的 description，但 4 MiB 已经远超合理范围 ——
         * 超过就当异常响应（被替换成一个大文件）处理，避免把内存吃光。
         */
        const val MAX_INDEX_BYTES = 4L * 1024 * 1024

        /**
         * 单个脚本体积上限，与引擎加载器的上限一致（512 KiB）。
         * 在**下载前**用索引里的 `sizeBytes` 先挡一次，下载后再按实际字节数校验一次。
         */
        const val MAX_SCRIPT_BYTES = 512L * 1024

        /**
         * 原生（L2）插件包体积上限。jar 里是编译后的类（可能还带 .so），512 KiB 的脚本
         * 上限对它没有意义；32 MiB 覆盖现实中会出现的 Adapter，再大的"插件包"更可能是
         * 拿错了文件而不是真的插件。
         */
        const val MAX_NATIVE_BYTES = 32L * 1024 * 1024
    }

    private val repo = PluginRepository(context)

    // ---------------------------------------------------------------- 索引

    /**
     * 拉取一个源的索引并解析。
     *
     * 顺带把原始 JSON 存进私有目录（[PluginRepository.cacheSourceIndex]）——
     * 这样"上次拉到的索引"在离线时仍然可看，而不是打开市场就是空白加一句网络错误。
     *
     * 取字节走 [PluginMirror]：本源配了镜像先走镜像，否则 GitHub 系地址走全局镜像，
     * 直连永远排在最后兜底（见 [PluginMirror] 里"回退只对取不到字节生效"那条边界）。
     */
    suspend fun fetchIndex(source: PluginSourceEntity): Result<MarketIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val hit = httpGetText(source.indexUrl, MAX_INDEX_BYTES, source.mirrorUrl)
                ?: throw IllegalStateException("索引下载失败（网络不可达或地址无效）")
            val json = hit.value
            val index = MarketIndex.parse(json).getOrThrow()
            repo.cacheSourceIndex(source.id, json)
            repo.markSourceFetched(source.id)
            DiagLog.i(
                context, TAG,
                "索引已更新：${index.sourceName}（${index.plugins.size} 个插件，" +
                    "schema=${index.schemaVersion}，经由${hit.routeLabel()}）"
            )
            index
        }
    }

    // ---------------------------------------------------------------- 安装 / 更新

    /**
     * 安装或更新一个插件。
     *
     * @param trustLevel 用户为该源选的信任等级（写进插件记录，界面按它显示风险）
     * @return 成功时是脚本落盘用的 sha256（前 8 位也用于文件名），失败是原因
     */
    suspend fun installOrUpdate(
        plugin: MarketPlugin,
        version: MarketPlugin.Version,
        sourceId: String,
        trustLevel: String,
        index: MarketIndex,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            // ---- 关 1：索引必须给出可校验的材料 ----
            if (!version.isVerifiable) {
                throw IllegalStateException("该版本缺少校验信息（sha256 / 签名 / keyId），不予安装")
            }
            // ---- 关 1.5：钥匙是否已被吊销（P6）----
            // 必须在任何公钥查找与验签**之前**。签名在数学上永远有效，
            // "这把钥匙作废了"是信任层面的判断，只有显式查名单才知道。
            (PluginTrust.verdictFor(version.keyId) as? PluginTrust.KeyVerdict.Revoked)?.let {
                throw IllegalStateException("该插件用的密钥「${it.keyId}」已被吊销：${it.reason}")
            }

            // ---- 关 2：宿主版本 ----
            if (version.minHostVersion.isNotBlank()) {
                val host = hostVersionName(context)
                if (UpdateChecker.compareVersions(host, version.minHostVersion) < 0) {
                    throw IllegalStateException(
                        "需要云取 ${version.minHostVersion} 或更高版本（当前 $host）"
                    )
                }
            }
            // ---- 关 2.5：体积（按索引声明值先挡一次，省一次下载） ----
            // L2 jar 与 JS 脚本的上限不同（见 MAX_NATIVE_BYTES），按级别取对应上限。
            val isNative = plugin.level == PluginLevel.NATIVE
            val maxBytes = if (isNative) MAX_NATIVE_BYTES else MAX_SCRIPT_BYTES
            if (version.sizeBytes > maxBytes) {
                throw IllegalStateException("插件包体积 ${version.sizeBytes} 字节，超过上限")
            }

            // ---- 关 3 + 4：下载、长度与摘要、验签 ----
            // 源的镜像配置取一次就够：本笔安装的三个请求（脚本、清单、公钥）走同一条回退链，
            // 中途源被改了也不该让前半段走镜像、后半段走直连。
            val sourceMirror = sourceMirror(sourceId)
            val bytes = fetchAndVerify(plugin, version, index, sourceMirror, maxBytes)

            if (isNative) {
                // L2：jar 字节直接落盘；跳过 JS 专有的 defineMeta 自报校验，
                // 改为要求清单声明入口类（见 [installNative]）。
                installNative(plugin, version, sourceId, trustLevel, bytes, sourceMirror)
            } else {
                val script = bytes.toString(Charsets.UTF_8)

                // ---- 关 5：脚本自报 id 与版本必须与索引一致 ----
                verifySelfReportedIdentity(script, plugin.id, version.version)

                // ---- 关 6：清单（可选，但有就必须一致） ----
                // 索引里的 manifestUrl 指到插件自带的 turbodl-plugin.json。它**不在签名覆盖范围内**
                // （签名只覆盖脚本字节），所以它只能当"辅助信息"用：拿它对一下 id/version 是否与
                // 脚本自报的一致、协议声明是否包含索引里写的那些。**拿不到就不装失败** ——
                // 很多源不提供清单，那不该拦住安装；但拿到了又不一致，说明索引在乱写。
                val manifestJson = fetchAndCheckManifest(plugin, version, sourceMirror)

                // ---- 落盘 + 入库（更新 = 同一条路，只是版本号变了） ----
                repo.installMarketPlugin(
                    id = plugin.id,
                    name = plugin.name,
                    version = version.version,
                    script = script,
                    sha256 = version.sha256,
                    sourceId = sourceId,
                    sourceUri = version.downloadUrl,
                    trustLevel = trustLevel,
                    level = plugin.level,
                    declaredPermissions = version.permissions.joinToString(","),
                    manifestJson = manifestJson,
                ).getOrThrow()
            }
        }.onFailure {
            DiagLog.i(context, TAG, "安装/更新失败（${plugin.id} ${version.version}）：${it.message.orEmpty()}")
        }
    }

    /**
     * L2 安装分支：清单在这里从"可选的辅助信息"变成**必需品** —— 因为入口类
     * （`entryClass`，L2 加载契约，见引擎 `NativePluginSource`）只存在于 manifest 里。
     *
     * 信任链不经过 manifest：jar 字节的 sha256 与 Ed25519 签名在 [fetchAndVerify] 已经过完；
     * manifest 提供的只是"加载这份已验证字节所需的定位信息"。但**没有它就装不了** ——
     * 没有入口类的 jar 谁也加载不出来，与其装一个启动必然失败的插件，不如装的时候就报清楚。
     */
    private suspend fun installNative(
        plugin: MarketPlugin,
        version: MarketPlugin.Version,
        sourceId: String,
        trustLevel: String,
        bytes: ByteArray,
        sourceMirror: String,
    ): InstalledPlugin {
        val manifestJson = fetchAndCheckManifest(plugin, version, sourceMirror)
        val entryClass = if (manifestJson.isNotBlank()) {
            runCatching { org.json.JSONObject(manifestJson).optString("entryClass").trim() }
                .getOrDefault("")
        } else {
            ""
        }
        if (entryClass.isEmpty()) {
            throw IllegalStateException(
                "原生插件必须在清单（turbodl-plugin.json）里声明 entryClass（入口类的全限定名）"
            )
        }
        // 把入口类回写进要存储的 manifest 文本：装载时 PluginRuntime 从这里读，
        // 不需要另开一个数据库列（manifestJson 本来就是 L2 元数据的落点）。
        val stored = runCatching {
            org.json.JSONObject(manifestJson).put("entryClass", entryClass).toString()
        }.getOrDefault(manifestJson)

        return repo.installNativePlugin(
            id = plugin.id,
            name = plugin.name,
            version = version.version,
            jar = bytes,
            sha256 = version.sha256,
            sourceId = sourceId,
            sourceUri = version.downloadUrl,
            trustLevel = trustLevel,
            manifestJson = stored,
        ).getOrThrow()
    }

    /**
     * 下载插件包并完成"长度 → SHA-256 → Ed25519"三道校验，返回**原始字节**。
     *
     * 单独抽出来是为了让 **安装与更新共用同一条校验链**：更新路径没有独立的实现，
     * 也就不存在"更新时忘了验签"这种漂移。返回字节而不是解码后的文本 ——
     * 文本解码是 JS 分支调用方的事，L2 jar 是二进制，在这里解码就毁了它。
     */
    private suspend fun fetchAndVerify(
        plugin: MarketPlugin,
        version: MarketPlugin.Version,
        index: MarketIndex,
        sourceMirror: String,
        maxBytes: Long,
    ): ByteArray {
        val hit = httpGetBytes(version.downloadUrl, maxBytes, sourceMirror)
            ?: throw IllegalStateException("插件包下载失败（网络不可达或地址无效）")
        val bytes = hit.value

        if (bytes.size > maxBytes) {
            throw IllegalStateException("插件包体积 ${bytes.size} 字节，超过上限")
        }
        if (version.sizeBytes > 0 && bytes.size.toLong() != version.sizeBytes) {
            throw IllegalStateException("插件包大小与索引不符（索引 ${version.sizeBytes}，实际 ${bytes.size}）")
        }

        val actual = bytes.sha256Hex()
        if (!actual.equals(version.sha256, ignoreCase = true)) {
            DiagLog.w(
                context, DiagLog.Category.PLUGIN,
                "摘要不符（${plugin.id} ${version.version}）：实际 sha256=$actual / 索引 ${version.sha256}，" +
                    "实际 ${bytes.size} 字节 / 索引 ${version.sizeBytes} —— 下载通道给了不一样的字节（缓存或代理）"
            )
            throw IllegalStateException("插件包摘要与索引不符（可能被篡改或索引过期）")
        }

        val publicKey = resolvePublicKey(version.keyId, index, sourceMirror)
            ?: throw IllegalStateException("找不到签名公钥（keyId=${version.keyId}）")
        if (!PluginSignature.verify(bytes, version.signature, publicKey)) {
            // 摘要匹配而签名不过 = 索引里的签名字段与这对（字节，公钥）对不上。
            // 把两边指纹都记下来：导出日志能直接分辨"索引被换了"还是"通道给了错字节"。
            DiagLog.w(
                context, DiagLog.Category.PLUGIN,
                "签名验证失败（${plugin.id} ${version.version}）：实际 sha256=$actual，" +
                    "索引 sig 前 16=${version.signature.take(16)}…，keyId=${version.keyId}，" +
                    "公钥来自=${if (index.publicKeyUrl.isNullOrBlank()) "内置表" else "源 publicKeyUrl"}"
            )
            throw IllegalStateException("签名验证失败 —— 插件包内容与签名不匹配")
        }

        DiagLog.i(
            context, TAG,
            "校验通过：${plugin.id} ${version.version}（sha256=${actual.take(12)}… " +
                "keyId=${version.keyId}，经由${hit.routeLabel()}）"
        )
        return bytes
    }

    /**
     * 按 keyId 找公钥：先看**内置**的（官方源公钥随应用发布，离线也验得了），
     * 再回退到源声明的 `publicKeyUrl`（社区源各自带公钥）。
     *
     * 找不到就返回 null，调用方拒绝安装 —— 不猜、不放行。
     */
    private fun resolvePublicKey(keyId: String, index: MarketIndex, sourceMirror: String): String? {
        // 吊销检查放在这里而**不只**放在调用点上：这条回退路径（源自带公钥）
        // 是一把独立的取钥匙途径，只在安装入口查一次的话，将来有人新增一个
        // 调用 resolvePublicKey 的地方就会漏掉。
        (PluginTrust.verdictFor(keyId) as? PluginTrust.KeyVerdict.Revoked)?.let { return null }
        PluginTrust.builtinPublicKey(keyId)?.let { return it }
        val url = index.publicKeyUrl ?: return null
        // 约定：公钥文件名就是 <keyId>.pub，替换源目录下的文件名
        val candidate = url.substringBeforeLast('/') + "/$keyId.pub"
        return httpGetText(candidate, 64 * 1024, sourceMirror)?.value
    }

    /**
     * 脚本自报 `defineMeta({id, version})` 必须与索引一致。
     *
     * 为什么这一步有分量：脚本字节被签名覆盖，所以脚本里写的版本号是**发布者签过的**；
     * 索引里的版本号没有签名保护。若两者不一致，说明索引在**谎报版本**（比如把旧脚本
     * 标成新版本以阻止用户看到真正的更新）。这里直接拒绝，而不是"信索引"。
     *
     * 脚本没自报 id/version 时**不拦**：那是仓库自己的约定宽松，不属于"谎报"。
     */
    private fun verifySelfReportedIdentity(script: String, expectedId: String, expectedVersion: String) {
        val meta = PluginIds.readSelfReportedMeta(script)
        if (meta.id != null && meta.id != expectedId) {
            throw IllegalStateException("脚本自报 id「${meta.id}」与索引「$expectedId」不一致")
        }
        if (meta.version != null && meta.version != expectedVersion) {
            throw IllegalStateException("脚本自报版本「${meta.version}」与索引「$expectedVersion」不一致")
        }
    }

    /**
     * 取插件清单并做一致性检查；返回清单原文（拿不到就返回空串）。
     *
     * ## 为什么"拿不到不算失败"
     *
     * 清单不在签名覆盖范围内（签名只覆盖脚本字节），所以它**不能作为信任依据** ——
     * 能作依据的只有"脚本自报 + 签名"。很多源（包括自建源）根本不提供清单文件，
     * 为此拒绝安装会把正常插件挡在门外。所以：
     *
     *  - 索引没给 `manifestUrl`、或下载失败 → 返回空串，安装继续；
     *  - 拿到了 → 对一下 `id` / `version` 是否与索引一致，以及 `protocols` 是否覆盖索引声明的协议。
     *    不一致说明索引在乱写（清单是插件作者写的，索引是源维护者写的），这时**拒绝安装** ——
     *    因为用户看到的列表信息（协议、版本）会与实际装上的插件不符。
     */
    private fun fetchAndCheckManifest(
        plugin: MarketPlugin,
        version: MarketPlugin.Version,
        sourceMirror: String,
    ): String {
        val url = plugin.manifestUrl.trim()
        if (url.isEmpty()) return ""
        val text = httpGetText(url, 256 * 1024, sourceMirror)?.value?.takeIf { it.isNotBlank() } ?: return ""

        val parsed = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return text

        val manifestId = parsed.optString("id").trim()
        if (manifestId.isNotEmpty() && manifestId != plugin.id) {
            throw IllegalStateException("清单里的 id「$manifestId」与索引「${plugin.id}」不一致")
        }
        val manifestVersion = parsed.optString("version").trim()
        if (manifestVersion.isNotEmpty() && manifestVersion != version.version) {
            throw IllegalStateException("清单里的版本「$manifestVersion」与索引「${version.version}」不一致")
        }
        // 协议：索引声明的每一个都应当在清单里出现（清单可以多声明，那不影响用户看到的列表）
        val declared = parsed.optJSONArray("protocols")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()
        if (declared.isNotEmpty()) {
            val missing = plugin.protocols.filterNot { want -> declared.any { it.equals(want, ignoreCase = true) } }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("索引声明了协议 ${missing.joinToString()}，但清单里没有")
            }
        }
        return text
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * GET 文本，返回内容 + **命中的是哪个通道**（日志里告诉用户"这次是走镜像拿到的"）。
     */
    private fun httpGetText(url: String, maxBytes: Long, sourceMirror: String = ""): PluginMirror.Hit<String>? =
        httpGetBytes(url, maxBytes, sourceMirror)?.let { hit ->
            PluginMirror.Hit(hit.candidate, hit.value.toString(Charsets.UTF_8))
        }

    /**
     * GET 字节，按 [PluginMirror] 给出的候选顺序依次尝试，全部失败才返回 null。
     *
     * 单个候选的失败判定（不抛异常，交给回退链继续）：抛异常 / 非 2xx /
     * 声明长度超限 / 内容为空。**内容为空算失败**是有意的：空响应通常是
     * 代理被限流或返回了一个错误页而不是真内容。
     *
     * 这里**只**负责"取到字节"。摘要与验签在 [fetchAndVerify] 里、拿到字节之后统一做，
     * 与字节从哪个通道来无关 —— 这就是"镜像只分发、不给信任"在代码上的落点。
     */
    private fun httpGetBytes(
        url: String,
        maxBytes: Long = MAX_SCRIPT_BYTES,
        sourceMirror: String = "",
    ): PluginMirror.Hit<ByteArray>? {
        val candidates = PluginMirror.candidates(url, mirrorPrefix(), sourceMirror)
        if (candidates.isEmpty()) return null
        val client = HttpClients.apiClient()
        return PluginMirror.fetchFirst(candidates) { candidate ->
            val req = Request.Builder().url(candidate.url)
                .header("User-Agent", "YunGet")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            client.newCall(req).execute().use { resp: Response ->
                if (!resp.isSuccessful) return@use null
                // 声明长度就超限的直接放弃（避免把大文件读进内存才发现）
                val declared = resp.body?.contentLength() ?: -1L
                if (declared > maxBytes) return@use null
                resp.body?.bytes()
            }.let { bytes -> if (bytes == null || bytes.isEmpty()) null else bytes }
        }
    }

    /**
     * 该源配置的镜像前缀。取不到（源被删了 / 读库失败）就当没配，
     * 大不了退回全局镜像与直连 —— 不该因为一个辅助参数让整个安装失败。
     */
    private suspend fun sourceMirror(sourceId: String): String =
        runCatching { repo.getSource(sourceId)?.mirrorUrl.orEmpty() }.getOrDefault("")

    /** 用户配置的 GitHub 镜像前缀（没配就空串 = 直连）。 */
    private fun mirrorPrefix(): String =
        runCatching { com.yunget.app.data.prefs.SettingsRepository(context).githubMirrorPrefix.orEmpty() }
            .getOrDefault("")

    /** 应用自身版本名（用于 `minHostVersion` 比较）。 */
    private fun hostVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}

/** 字节数组的 SHA-256 十六进制小写。 */
internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
