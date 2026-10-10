/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
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
import android.net.Uri
import com.yunget.app.data.db.AppDatabase
import com.yunget.app.data.db.PluginInstalledEntity
import com.yunget.app.data.db.PluginSourceEntity
import com.yunget.app.util.DiagLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 已安装插件（对外的领域视图）：在表实体 [PluginInstalledEntity] 之上补一层
 * "这个插件现在能不能用"的现况 —— 数据库有记录不等于脚本还在
 * （用户清过数据、或脚本被安全软件删掉），列表要能把这种情况显式标出来，
 * 而不是让上层拿着一个指向不存在文件的路径去加载、再收到一个看不懂的报错。
 */
data class InstalledPlugin(
    /** 表实体原文（启停、脚本路径、摘要、信任级别等都在这里） */
    val entity: PluginInstalledEntity,
    /** 脚本文件当前是否真的在应用私有目录里 */
    val scriptExists: Boolean,
) {
    val id: String get() = entity.id
    val name: String get() = entity.name
    val version: String get() = entity.version
    val enabled: Boolean get() = entity.enabled
    val sourceKind: String get() = entity.sourceKind

    /**
     * 能力级别（P20）。空串 = 升级前装的老数据，按 L1 读。
     *
     * 界面靠它决定要不要提示"需重启 App 生效"——卸载与启停时源可能已经拉不到了，
     * 所以这个判断不能依赖索引，只能看库里这一列。
     */
    val level: PluginLevel get() = PluginLevel.parseOrDefault(entity.level) ?: PluginLevel.DEFAULT
    val trustLevel: String get() = entity.trustLevel
    val scriptPath: String get() = entity.scriptPath

    /** 脚本声明的能力（逗号分隔入库，这里还原成列表） */
    val declaredPermissions: List<String>
        get() = entity.declaredPermissions.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 脚本文件（可能不存在，先看 [scriptExists]） */
    val scriptFile: File get() = File(entity.scriptPath)
}

/**
 * 插件管理的内核层：安装 / 卸载 / 启停 / 枚举 / 插件源，全部落 Room + 应用私有文件。
 *
 * ## 落盘约定
 *
 * ```
 * filesDir/plugins/scripts/<id>_<sha8>.js   ← 可被加载的脚本（唯一允许写脚本的地方）
 * filesDir/plugins/sources/<source-id>.json ← 插件源索引的本地缓存（只拉不装）
 * ```
 *
 * ## 安全边界（三层，缺一不可）
 *
 * 1. [PluginIds.validate]：脚本自报的 id 必须合法，否则**安装失败**（不洗、不猜、不退回派生）；
 * 2. [PluginIds.fileNameFor]：文件名只由"过滤过的 id + 脚本摘要"组成，不含任何路径语义；
 * 3. [resolveInDir]：写/删之前用 `canonicalPath` 前缀校验确认目标仍在该目录内 ——
 *    前两层都是纯函数层面的保证，这一层是唯一能挡住"符号链接/平台差异"的兜底。
 *
 * ## 线程
 *
 * 所有 suspend 方法内部切 `Dispatchers.IO`，调用方（Compose/ViewModel）不需要再包 withContext；
 * Flow 用 `flowOn(IO)` 把 `map` 里的文件探测也移出收集线程。
 *
 * ## 失败语义
 *
 * 一律返回 [Result]，**不抛给调用方**（只有取消异常会穿透 `runCatching`），
 * 并且失败都写 [DiagLog]（进 logcat 与 `filesDir/diag/diag.log`，随「导出日志」回传）——
 * "装了没生效"这类问题必须能从用户回传的日志里看出来。
 */
class PluginRepository(context: Context) {

    private val appContext = context.applicationContext

    private val db by lazy { AppDatabase.get(appContext) }
    private val installedDao by lazy { db.pluginInstalledDao() }
    private val sourceDao by lazy { db.pluginSourceDao() }

    // ---------------------------------------------------------------- 已安装插件

    /** 全部已安装插件（含脚本是否还在）。 */
    fun listInstalled(): Flow<List<InstalledPlugin>> =
        installedDao.observeAll()
            .map { rows -> rows.map { InstalledPlugin(it, scriptExists = isScriptPresent(it.scriptPath)) } }
            .flowOn(Dispatchers.IO)

    /** 启动健康检查只需知道有没有启用的插件，不要为了"空宿主"就初始化 QuickJS。 */
    suspend fun hasEnabledPlugins(): Boolean = withContext(Dispatchers.IO) {
        installedDao.getAllBlocking().any { it.enabled }
    }

    /** 单个插件（找不到返回 null）。 */
    suspend fun getInstalled(id: String): InstalledPlugin? = withContext(Dispatchers.IO) {
        installedDao.getById(id)?.let { InstalledPlugin(it, scriptExists = isScriptPresent(it.scriptPath)) }
    }

    /**
     * 粘贴导入：把脚本文本写进私有目录并入库。
     *
     * id 规则：脚本 `defineMeta({id})` / `plugin.id = '...'` 声明优先；
     * 声明了但**不合法直接失败**（不静默退回文件名派生）；没声明才 [PluginIds.deriveFromFileName]。
     *
     * @param fileName 原始文件名（用于派生 id / 兜底展示名），可为 null
     * @return 成功时返回插件 id
     */
    suspend fun installFromText(script: String, fileName: String? = null): Result<String> =
        install(
            script = script,
            fileName = fileName,
            sourceUri = null,
            sourceKind = if (fileName.isNullOrBlank()) {
                PluginInstalledEntity.SOURCE_PASTE
            } else {
                PluginInstalledEntity.SOURCE_FILE
            },
        )

    /**
     * 从 SAF uri 导入：读出文本后走 [installFromText] 的同一条链路
     * （id 校验、防穿越命名、落盘、入库只有一份实现）。
     *
     * 与粘贴导入的唯一区别是 `sourceUri` **存真实 uri**：脚本来源要可追溯
     * （"这个插件是从哪个文件来的"），将来"从原位置重新导入/更新"也靠它。
     */
    suspend fun installFromFile(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching { readTextUri(uri) }.fold(
            onSuccess = { text ->
                install(
                    script = text,
                    fileName = uri.lastPathSegment,
                    sourceUri = uri.toString(),
                    sourceKind = PluginInstalledEntity.SOURCE_FILE,
                )
            },
            onFailure = { t ->
                DiagLog.i(appContext, TAG, "读取插件文件失败（$uri）：${t.javaClass.simpleName} ${t.message.orEmpty()}")
                Result.failure(t)
            },
        )
    }

    /**
     * 安装的**唯一实现**：[installFromText] / [installFromFile] 只负责决定
     * 「id 从哪来、来源怎么记」，落盘与入库逻辑在此，避免两条链路各自演化出安全差异。
     */
    private suspend fun install(
        script: String,
        fileName: String?,
        sourceUri: String?,
        sourceKind: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (script.isBlank()) throw IllegalArgumentException("脚本内容为空")

                val id = resolveId(script, fileName)
                val sha256 = sha256Hex(script.toByteArray(Charsets.UTF_8))
                val target = scriptFileFor(id, sha256.take(8))

                // 先落盘、再写库：库里出现记录时脚本一定已经可读，避免"记录在但文件不在"的中间态
                target.parentFile?.mkdirs()
                target.writeText(script, Charsets.UTF_8)

                val previous = installedDao.getById(id)
                val now = System.currentTimeMillis()
                val meta = PluginIds.defineMetaBody(script)
                val displayName = PluginIds.stringField(meta, "name")
                    ?: fileName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: id
                val entity = PluginInstalledEntity(
                    id = id,
                    name = displayName,
                    version = PluginIds.stringField(meta, "version") ?: "",
                    sourceUri = sourceUri ?: sourceUriForPaste(fileName, id),
                    sourceKind = sourceKind,
                    manifestJson = "",
                    scriptPath = target.absolutePath,
                    scriptSha256 = sha256,
                    // 重复导入保持原来的启停状态：用户手动关掉的插件不该因为"再导一次"被悄悄打开
                    enabled = previous?.enabled ?: true,
                    declaredPermissions = PluginIds
                        .stringListField(PluginIds.requiresBody(script), "permissions")
                        .joinToString(","),
                    trustLevel = previous?.trustLevel ?: PluginInstalledEntity.TRUST_UNTRUSTED,
                    installedAt = previous?.installedAt ?: now,
                    updatedAt = now,
                    lastError = "",
                )
                installedDao.upsert(entity)

                // 版本变化会让文件名里的 sha 段变化 → 旧文件成为孤儿，这里顺手清掉（失败只记日志）
                previous?.scriptPath?.takeIf { it.isNotBlank() && it != target.absolutePath }
                    ?.let { deleteScriptQuietly(it) }

                DiagLog.i(
                    appContext,
                    TAG,
                    "已安装插件 $id（${entity.name} ${entity.version}）→ ${target.name}，" +
                        "来源=${entity.sourceKind}，声明能力=[${entity.declaredPermissions}]",
                )
                id
            }.onFailure {
                DiagLog.i(appContext, TAG, "安装插件失败（${fileName ?: "粘贴文本"}）：${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }
        }

    // ---------------------------------------------------------------- 从市场安装 / 更新

    /**
     * 从插件市场安装（或更新）一个插件。
     *
     * 与 [installFromText] 的关系：**落盘与入库只有一份实现**（下面复用了同一个写法），
     * 区别只在于这里额外记下"来自哪个源、哪个版本、哪个 sha、什么信任等级"，以及
     * `version` 以**索引为准**（索引的版本号已经过签名与自报身份双重校验，见 `MarketClient`）。
     *
     * 更新与安装走同一条路：id 已存在就是更新（REPLACE），版本号与来源被刷新，
     * **启停状态保持用户原来的选择** —— 用户手动关掉的插件不该因为"更新了一次"被悄悄打开。
     *
     * @param sourceId 该插件来自哪个源（用于界面上显示"来自哪个源"）
     * @param sourceUri 下载地址（可追溯：这个脚本是从哪一行 URL 拿到的）
     * @param trustLevel 判定出的信任等级（见 `PluginTrust.gradeFor`）
     */
    suspend fun installMarketPlugin(
        id: String,
        name: String,
        version: String,
        script: String,
        sha256: String,
        sourceId: String,
        sourceUri: String,
        trustLevel: String,
        declaredPermissions: String,
        /** 插件自带清单的原文；拿不到就空串（见 `MarketClient.fetchAndCheckManifest`）。 */
        manifestJson: String = "",
        /** 能力级别（P20）；默认 L1。 */
        level: PluginLevel = PluginLevel.DEFAULT,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            val validId = PluginIds.validate(id)
                ?: throw IllegalArgumentException("索引里的插件 id 不合法：$id")

            val scriptsDir = scriptsDir().apply { mkdirs() }
            val target = resolveInDir(scriptsDir, PluginIds.fileNameFor(validId, sha256.take(8)))
            writeText(target, script)

            val previous = installedDao.getById(validId)
            // 【P4】更新前给旧版本留快照 —— 必须在改库之前，否则拿不到旧版本信息。
            // 留了快照就不再删旧脚本（回滚要用它），见 snapshotBeforeUpdate。
            val hadSnapshotBefore = rollbackStore.load(validId) != null
            snapshotBeforeUpdate(previous)
            val keepOldScript = previous != null &&
                previous.scriptKindEligibleForRollback() &&
                rollbackStore.load(validId) != null

            val now = System.currentTimeMillis()
            installedDao.upsert(
                PluginInstalledEntity(
                    id = validId,
                    name = name.ifBlank { validId },
                    version = version,
                    sourceUri = sourceUri,
                    sourceKind = PluginInstalledEntity.SOURCE_MARKET,
                    level = level.id,
                    manifestJson = manifestJson,
                    scriptPath = target.absolutePath,
                    scriptSha256 = sha256,
                    enabled = previous?.enabled ?: true,
                    declaredPermissions = declaredPermissions,
                    trustLevel = trustLevel,
                    installedAt = previous?.installedAt ?: now,
                    updatedAt = now,
                    lastError = "",
                )
            )
            // 旧脚本只在"没有为它留快照"时才删：留了快照说明它是回滚目标，必须保留。
            previous?.scriptPath?.takeIf {
                it.isNotBlank() && it != target.absolutePath && !keepOldScript
            }?.let { deleteScriptQuietly(it) }
            if (keepOldScript) {
                DiagLog.i(appContext, TAG, "已为 $validId 保留上一版本脚本以备回滚")
            } else if (hadSnapshotBefore) {
                DiagLog.i(appContext, TAG, "$validId 的旧回滚快照已被本次更新取代")
            }

            val action = if (previous == null) "安装" else "更新"
            DiagLog.i(
                appContext, TAG,
                "$action 插件 $validId → $version（源=$sourceId，信任=$trustLevel，sha=${sha256.take(12)}…）"
            )
            InstalledPlugin(
                entity = installedDao.getById(validId)!!,
                scriptExists = target.isFile,
            )
        }.onFailure {
            DiagLog.i(appContext, TAG, "市场安装失败（$id $version）：${it.message.orEmpty()}")
        }
    }

    // ---------------------------------------------------------------- 插件源索引缓存

    /**
     * 把源索引原文缓存到私有目录（`filesDir/plugins/sources/<sourceId>.json`）。
     *
     * 为什么要存：拉不到索引时（离线 / 源挂了）市场不该只剩一句网络错误 ——
     * 上次拉到的列表仍然可看、已装插件的"有没有更新"仍可判断（只是可能过期）。
     */
    suspend fun cacheSourceIndex(sourceId: String, json: String) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = sourcesDir().apply { mkdirs() }
            val safe = PluginIds.sanitizeForFileName(sourceId)
            writeText(resolveInDir(dir, "$safe.json"), json)
        }
        Unit
    }

    /** 读回缓存的源索引原文；没有缓存返回 null。 */
    suspend fun cachedSourceIndex(sourceId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val safe = PluginIds.sanitizeForFileName(sourceId)
            val f = resolveInDir(sourcesDir(), "$safe.json")
            if (f.isFile) f.readText() else null
        }.getOrNull()
    }

    /** 标记"这个源刚成功拉取过"（供界面显示"上次更新于…"）。 */
    suspend fun markSourceFetched(sourceId: String) = withContext(Dispatchers.IO) {
        runCatching { sourceDao.updateFetchedAt(sourceId, System.currentTimeMillis()) }
        Unit
    }

    // ---------------------------------------------------------------- 启用 / 卸载 / 错误

    /** 启用 / 停用（不改脚本文件，只改加载开关）。 */
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (installedDao.getById(id) == null) throw IllegalArgumentException("插件不存在：$id")
            installedDao.updateEnabled(id, enabled, System.currentTimeMillis())
            DiagLog.i(appContext, TAG, "插件 ${if (enabled) "启用" else "停用"}：$id")
        }.onFailure { DiagLog.i(appContext, TAG, "切换插件状态失败（$id）：${it.message.orEmpty()}") }
    }

    /**
     * 卸载插件。
     *
     * @param deleteScript 是否同时删除私有目录里的脚本文件（默认删；传 false 用于"先摘记录、后清理"）
     */
    suspend fun uninstall(id: String, deleteScript: Boolean = true): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val row = installedDao.getById(id) ?: throw IllegalArgumentException("插件不存在：$id")
                installedDao.delete(id)
                if (deleteScript) deleteScriptQuietly(row.scriptPath)
                DiagLog.i(appContext, TAG, "已卸载插件 $id（删除脚本=${deleteScript}）")
            }.onFailure { DiagLog.i(appContext, TAG, "卸载插件失败（$id）：${it.message.orEmpty()}") }
        }

    /** 记录一次加载/校验失败（供插件列表展示"为什么没生效"）。 */
    suspend fun recordLoadError(id: String, error: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { installedDao.updateLastError(id, error, System.currentTimeMillis()) }
    }

    // ---------------------------------------------------------------- 给运行时的同步访问口
    //
    // 下面两个是**同步**版本，只给 [PluginRuntime] 用：它在一个 `mutex` 保护的代码块里
    // 装载/卸载插件，而那段代码本身可能是从 Dispatchers.IO 调进来的（`start()` 会包一层
    // withContext(IO)）。在那里再调上面的 suspend 版本会让"装载"这件事横跨两个调度边界，
    // 期间状态可能被并发的启停操作改掉 —— 装载决策必须基于一个确定快照。
    //
    // 它们**不做** Dispatchers 切换，所以调用方必须已经在 IO 线程上（[PluginRuntime] 保证了
    // 这一点）。名字带 Blocking 就是在提醒这件事。

    /**
     * 读一次已安装插件的快照（含脚本是否还在）。
     *
     * @param onlyId 只取这一个（找不到返回 null）；传 null 取全部
     */
    fun installedNow(onlyId: String? = null): List<InstalledPlugin> = if (onlyId != null) {
        installedDao.getByIdBlocking(onlyId)?.let {
            listOf(InstalledPlugin(it, scriptExists = isScriptPresent(it.scriptPath)))
        } ?: emptyList()
    } else {
        installedDao.getAllBlocking().map {
            InstalledPlugin(it, scriptExists = isScriptPresent(it.scriptPath))
        }
    }

    /**
     * 记一次加载失败（同步版）。失败**不上抛**：调用方正在装载流程里，
     * "把错误记下来"这件事本身失败不该掩盖原始错误。
     */
    fun recordLoadErrorBlocking(id: String, error: String) {
        runCatching { installedDao.updateLastErrorBlocking(id, error, System.currentTimeMillis()) }
    }

    /** 当前**启用**的插件源（同步快照，给检查更新用；调用方须已在 IO 线程）。 */
    fun enabledSourcesNow(): List<PluginSourceEntity> = sourceDao.getEnabledBlocking()

    // ---------------------------------------------------------------- 插件源（市场索引）

    /** 全部插件源。 */
    fun listSources(): Flow<List<PluginSourceEntity>> =
        sourceDao.observeAll().flowOn(Dispatchers.IO)

    /** 单个插件源。 */
    suspend fun getSource(id: String): PluginSourceEntity? = withContext(Dispatchers.IO) {
        sourceDao.getById(id)
    }

    /**
     * 登记一个插件源。
     *
     * id 由索引地址摘要派生（同一个 url 重复添加 = 覆盖同一条，不会堆出重复项）。
     *
     * @return 成功时返回源 id
     */
    suspend fun addSource(
        displayName: String,
        indexUrl: String,
        trustLevel: String = PluginInstalledEntity.TRUST_COMMUNITY,
        enabled: Boolean = true,
        mirrorUrl: String = "",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = indexUrl.trim()
            val scheme = url.substringBefore(':', "").lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("索引地址必须是 http/https：$indexUrl")
            }
            val id = sourceIdFor(url)
            val now = System.currentTimeMillis()
            val previous = sourceDao.getById(id)
            sourceDao.upsert(
                PluginSourceEntity(
                    id = id,
                    displayName = displayName.trim().ifEmpty { url },
                    indexUrl = url,
                    // 重复添加同一地址时保留已配的镜像：用户是"再登记一个源"，不是"覆盖掉设置"
                    mirrorUrl = mirrorUrl.trim().ifBlank { previous?.mirrorUrl.orEmpty() },
                    trustLevel = normalizeTrust(trustLevel),
                    addedAt = previous?.addedAt ?: now,
                    lastFetchedAt = previous?.lastFetchedAt ?: 0L,
                    enabled = enabled,
                ),
            )
            DiagLog.i(appContext, TAG, "已添加插件源 $id（$url，信任级别=${normalizeTrust(trustLevel)}）")
            id
        }.onFailure { DiagLog.i(appContext, TAG, "添加插件源失败：${it.message.orEmpty()}") }
    }

    /** 移除插件源（只摘登记与索引缓存，**不动**已经从它装过的插件）。 */
    suspend fun removeSource(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sourceDao.delete(id)
            sourceCacheFileOrNull(id)?.takeIf { it.isFile }?.delete()
            DiagLog.i(appContext, TAG, "已移除插件源 $id")
        }.onFailure { DiagLog.i(appContext, TAG, "移除插件源失败（$id）：${it.message.orEmpty()}") }
    }

    /** 启用 / 停用一个插件源。 */
    suspend fun setSourceEnabled(id: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (sourceDao.getById(id) == null) throw IllegalArgumentException("插件源不存在：$id")
            sourceDao.updateEnabled(id, enabled)
            DiagLog.i(appContext, TAG, "插件源 ${if (enabled) "启用" else "停用"}：$id")
        }.onFailure { DiagLog.i(appContext, TAG, "切换插件源状态失败（$id）：${it.message.orEmpty()}") }
    }

    /**
     * 改本源镜像前缀（P5）。空串 = 取消本源镜像。
     *
     * 只接受 http/https 前缀：镜像地址会被拼在原始地址**前面**（`前缀 + 原地址`），
     * 套 file:// 之类的 scheme 等于让应用去读本地文件，那不是分发该干的事。
     */
    suspend fun setSourceMirror(id: String, mirror: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (sourceDao.getById(id) == null) throw IllegalArgumentException("插件源不存在：$id")
            val value = mirror.trim()
            if (value.isNotEmpty()) {
                val scheme = value.substringBefore(':', "").lowercase()
                if (scheme != "http" && scheme != "https") {
                    throw IllegalArgumentException("镜像前缀必须是 http/https：$mirror")
                }
            }
            sourceDao.updateMirror(id, value)
            DiagLog.i(
                appContext, TAG,
                "插件源 $id 镜像已设为：${if (value.isEmpty()) "（空，回退到全局/直连）" else value}"
            )
        }.onFailure { DiagLog.i(appContext, TAG, "设置插件源镜像失败（$id）：${it.message.orEmpty()}") }
    }

    // ---------------------------------------------------------------- 内部：id 与路径

    /**
     * 决定插件 id：脚本声明优先，声明了就必须合法（否则失败）；
     * 没声明才从文件名派生（[PluginIds.deriveFromFileName] 保证结果合法）。
     */
    private fun resolveId(script: String, fileName: String?): String {
        val declared = PluginIds.extractDeclaredId(script)
        if (declared != null) {
            return PluginIds.validate(declared)
                ?: throw IllegalArgumentException(
                    "插件声明的 id 不合法：$declared（只允许 a-z0-9.- ，长度 ${PluginIds.MIN_LENGTH}..${PluginIds.MAX_LENGTH}）",
                )
        }
        return PluginIds.deriveFromFileName(fileName)
    }

    private fun scriptsDir(): File = File(appContext.filesDir, PluginIds.SCRIPTS_DIR)

    private fun sourcesDir(): File = File(appContext.filesDir, PluginIds.SOURCES_DIR)

    /** 回滚快照目录（P4）。用侧车文件而不是数据库字段，理由见 [PluginRollbackStore]。 */
    private fun rollbackDir(): File = File(appContext.filesDir, PluginIds.ROLLBACK_DIR)

    private val rollbackStore: PluginRollbackStore by lazy { PluginRollbackStore(rollbackDir()) }

    // ---------------------------------------------------------------- P4：更新回滚

    /**
     * 为「即将被更新覆盖的当前版本」留一份快照。
     *
     * **更新前调用**。旧脚本文件**不删**（默认会删，见 [installMarketPlugin]）——
     * 因为回滚需要它还在。返回的是被本次快照取代的旧备份，其脚本文件在这里顺手清理。
     *
     * 只对市场来源（`SOURCE_MARKET`）留快照：粘贴/文件导入的"上一版"没有可信来源，
     * 回滚到它不如让用户重新导入。这也避免给手工导入的脚本留下难以解释的隐藏副本。
     */
    private fun snapshotBeforeUpdate(previous: PluginInstalledEntity?) {
        val p = previous ?: return
        if (!p.scriptKindEligibleForRollback()) return
        if (p.scriptPath.isBlank() || p.version.isBlank()) return
        val old = rollbackStore.save(
            PluginRollbackStore.Snapshot(
                id = p.id,
                name = p.name,
                version = p.version,
                scriptPath = p.scriptPath,
                scriptSha256 = p.scriptSha256,
                sourceUri = p.sourceUri,
                sourceKind = p.sourceKind,
                manifestJson = p.manifestJson,
                declaredPermissions = p.declaredPermissions,
                trustLevel = p.trustLevel,
                savedAt = System.currentTimeMillis(),
            )
        )
        // 上一条备份的脚本文件已无用处（它比"上一可用版本"更旧），清掉避免堆积。
        // 注意别删掉当前记录正在用的那个（版本回退后 scriptPath 可能等于它）。
        old?.scriptPath?.takeIf { it.isNotBlank() && it != p.scriptPath }
            ?.let { stale -> runCatching { deleteScriptQuietly(stale) } }
    }

    /**
     * 把插件回滚到上一可用版本（P4）。
     *
     * @return 回滚到的版本号；没有可用快照（或脚本文件已不在）时返回 null
     */
    suspend fun rollbackToPrevious(id: String): String? = withContext(Dispatchers.IO) {
        val snap = rollbackStore.load(id) ?: return@withContext null
        val scriptFile = File(snap.scriptPath)
        if (!PluginRollbackStore.matchesDigest(scriptFile, snap.scriptSha256)) {
            // 快照指向的文件存在，但内容已被篡改 / 路径被复用 —— 不能把它当作上一可用版本加载。
            rollbackStore.clear(id)
            DiagLog.i(appContext, TAG, "回滚失败：$id 的备份摘要不匹配（${scriptFile.name}）")
            return@withContext null
        }
        val now = System.currentTimeMillis()
        val current = installedDao.getById(id)
        // 先写库、再删新脚本：库里记录指向的文件必须已存在（与本文件其它写点同一原则）
        installedDao.upsert(
            PluginInstalledEntity(
                id = id,
                name = snap.name.ifBlank { current?.name ?: id },
                version = snap.version,
                sourceUri = snap.sourceUri,
                sourceKind = snap.sourceKind,
                manifestJson = snap.manifestJson,
                scriptPath = snap.scriptPath,
                scriptSha256 = snap.scriptSha256,
                // 回滚不该改变用户的启停意愿：坏版本被用户关掉了，回滚后仍保持关着
                enabled = current?.enabled ?: true,
                declaredPermissions = snap.declaredPermissions,
                trustLevel = snap.trustLevel,
                installedAt = current?.installedAt ?: now,
                updatedAt = now,
                lastError = "",
            )
        )
        // 回滚成功后清掉快照：它已经被消费，再留着会让用户以为"还能再退一版"
        rollbackStore.clear(id)
        // 删掉那个加载失败的新脚本文件（copy 冲突时保留，交由 GC/清理自然处理）
        val replaced = current?.scriptPath
        if (!replaced.isNullOrBlank() && replaced != snap.scriptPath) {
            runCatching { deleteScriptQuietly(replaced) }
        }
        DiagLog.i(appContext, TAG, "已回滚插件 $id → ${snap.version}（${scriptFile.name}）")
        snap.version
    }

    /** 某个插件是否有可回滚的上一版本（界面据此决定是否显示"回退"）。 */
    fun hasRollback(id: String): Boolean {
        val snap = rollbackStore.load(id) ?: return false
        return File(snap.scriptPath).isFile
    }

    /**
     * 这条记录是否值得为它留回滚快照。
     *
     * **只对市场来源**：粘贴/文件导入的"上一版"没有可信来源，
     * 回滚到它不如让用户重新导入；而且给手工导入的脚本留隐藏副本会让人难以理解。
     *
     * 提成扩展函数是为了让 [snapshotBeforeUpdate] 与调用点用**同一个判据** ——
     * 两处各写一遍迟早会不一致（一处留快照、另一处却把脚本删了，回滚就成了空指针）。
     */
    private fun PluginInstalledEntity.scriptKindEligibleForRollback(): Boolean =
        sourceKind == PluginInstalledEntity.SOURCE_MARKET

    /** 脚本落盘路径：目录 + `PluginIds.fileNameFor` + canonicalPath 前缀校验。 */
    private fun scriptFileFor(id: String, sha8: String): File {
        val dir = scriptsDir()
        return resolveInDir(dir, PluginIds.fileNameFor(id, sha8))
    }

    /** 索引缓存路径；id 非法（例如有人直接往库里塞了脏数据）时返回 null，调用方据此拒绝写。 */
    private fun sourceCacheFileOrNull(id: String): File? {
        val safe = PluginIds.sanitizeForFileName(id).take(PluginIds.MAX_LENGTH).trimEnd('.', '-')
        if (safe.isEmpty()) return null
        return resolveInDir(sourcesDir(), "$safe.json")
    }

    /**
     * 防目录穿越的**唯一权威实现**：把目标规范化（`canonicalPath` 会解开 `.` / `..` / 符号链接），
     * 再确认它确实是 [dir] 的直接子项。
     *
     * 只在字符串层面比较前缀是不够的（`/a/scripts-evil` 也以 `/a/scripts` 开头），
     * 所以这里同时要求 `parentFile` 就是目标目录本身。
     */
    private fun resolveInDir(dir: File, fileName: String): File {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录：$dir")
        val canonicalDir = dir.canonicalFile
        val target = File(dir, fileName).canonicalFile
        val prefix = canonicalDir.path + File.separator
        if (!target.path.startsWith(prefix) || target.parentFile?.canonicalPath != canonicalDir.path) {
            throw SecurityException("拒绝越界写入：$fileName 不在 $canonicalDir 内")
        }
        return target
    }

    /** 删除脚本文件；**只允许删 scripts 目录内的直接子项**，失败只记日志（不打断卸载流程）。 */
    private fun deleteScriptQuietly(path: String) {
        if (path.isBlank()) return
        runCatching {
            val canonicalDir = scriptsDir().canonicalFile
            val target = File(path).canonicalFile
            if (target.parentFile?.canonicalPath != canonicalDir.path) {
                DiagLog.i(appContext, TAG, "拒绝删除 scripts 目录外的文件：$path")
                return
            }
            if (target.isFile && !target.delete()) {
                DiagLog.i(appContext, TAG, "脚本文件删除失败：$path")
            }
        }.onFailure { DiagLog.i(appContext, TAG, "删除脚本文件异常：${it.message.orEmpty()}") }
    }

    private fun isScriptPresent(path: String): Boolean =
        path.isNotBlank() && runCatching { File(path).isFile }.getOrDefault(false)

    // ---------------------------------------------------------------- 内部：IO / 文本

    /** 单次导入的字节上限：脚本是纯文本，超过这个量级只可能是选错了文件。 */
    private val maxScriptBytes = 4L * 1024 * 1024

    /** 读取 SAF 文本（带上限，避免误选大文件把内存吃满）。 */
    private fun readTextUri(uri: Uri): String {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开：$uri")
        return stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxScriptBytes) throw IOException("脚本超过 ${maxScriptBytes / 1024 / 1024}MB 上限")
                out.write(buffer, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private fun sourceUriForPaste(fileName: String?, id: String): String =
        if (fileName.isNullOrBlank()) "inline:$id" else "file:$fileName"

    /**
     * 写文本到已知安全路径。
     *
     * 路径**必须**已经过 [resolveInDir]（调用方负责）—— 这里不再校验一次，
     * 是为了让"防穿越"只有一个权威实现，而不是两处各自判断、日后其中一处被改坏。
     */
    private fun writeText(target: File, text: String) {
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    /** 信任级别归一化：库里只允许存白名单取值，非法值按"社区来源"处理。 */
    private fun normalizeTrust(raw: String): String =
        raw.trim().lowercase().takeIf { it in PluginInstalledEntity.TRUST_LEVELS }
            ?: PluginInstalledEntity.TRUST_COMMUNITY

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 插件源 id = `src.` + 索引地址摘要前 8 位（确定性：同一个 url 永远同一个 id）。 */
    private fun sourceIdFor(indexUrl: String): String =
        "src." + sha256Hex(indexUrl.lowercase().toByteArray(Charsets.UTF_8)).take(8)

    private companion object {
        const val TAG = "插件"
    }
}
