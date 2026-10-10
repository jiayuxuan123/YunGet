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
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.js.JsCapability
import dev.turbodl.plugin.js.JsPluginConfig
import dev.turbodl.plugin.js.JsPluginLoaderPlugin
import dev.turbodl.plugin.js.JsScriptPlugin
import dev.turbodl.plugin.js.JsScriptValidator
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.PluginSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把「已安装的 JS 插件」变成「真的在跑的插件」。
 *
 * ## 为什么单独一个类
 *
 * [PluginRepository] 管的是**持久化**：脚本落在哪、启用了没有、来源是什么。它一次都不碰引擎。
 * 这里管的是**运行时**：把这些脚本交给 TurboDL 的 `PluginHost`，让它们真的开始注册解析器与钩子。
 * 两者分开是因为它们的失败语义完全不同 —— 写文件失败要报给用户，而"某个脚本加载失败"应该
 * 只影响那一个插件（其余插件继续工作），并且要把错误记回库里给用户看。
 *
 * ## 一条规则：一个脚本一个实例，id 相同即视为同一个
 *
 * `PluginHost` 按插件 id 管理实例，重复加载同一 id 是覆盖语义。所以"重新加载"就等价于
 * 先卸载再加载 —— 这也是"用户改了脚本再点一次启用"的实现方式。
 *
 * ## 卸载是安全的，可以随时调用
 *
 * TurboDL 的 `PluginHost.uninstall` 把 `onUnload` 与 disposer 排在锁外执行，并且允许在脚本
 * 仍在跑时调用（脚本侧会走 STOPPING → DRAINING → DISPOSED）。如果 drain 超时，运行时会
 * **拒绝关闭**并计数泄漏 —— 那是引擎刻意的取舍：泄漏一个 QuickJS runtime 可以恢复，
 * 提前释放 JS 还在读的内存不行。所以这里的卸载不会抛，失败只记日志。
 */
class PluginRuntime(private val context: Context) {

    private companion object {
        /** 沙箱根目录：与插件脚本目录并列，都在应用私有区。 */
        const val SANDBOX_DIR = "turbodl-js"

        /**
         * JS 加载器在引擎里的插件 id。
         *
         * 写成常量而不是引用 `JsPluginLoaderPlugin` 的字段：那个类只把 `"loader.js"` 写在
         * `override val id` 里，并没有公开的 ID 常量（它的 companion 是 private）。
         */
        const val JS_LOADER_ID = "loader.js"

        /**
         * 单个脚本的执行预算。
         *
         * 这是**跑用户写的代码**，所以取的是"够用但不至于卡住应用"的值：
         * 5s 单次求值、20s 单次宿主调用、32 MiB 堆上限（引擎默认值即此）。
         * 网盘解析里最慢的是签名计算，纯 JS 通常几十毫秒 —— 5s 已经宽裕很多。
         */
        const val EVALUATION_TIMEOUT_MS = 5_000L
        const val INVOCATION_TIMEOUT_MS = 20_000L

        /**
         * 默认授予的能力。
         *
         * 刻意**不含** `storage` 与 `env`：脚本要这两样必须在自己的 `plugin.requires` 里声明，
         * 而且用户能在插件详情里看到它声明了什么。`timer` 同理（会长期占用一个调度任务）。
         * 这份集合是**上限**：脚本声明的是子集，实际授予是两者的交集 ——
         * 也就是说在这个类里放宽，等于同时给所有插件放宽，改它之前先想清楚。
         */
        val DEFAULT_GRANTS = setOf(
            JsCapability.HTTP,
            JsCapability.CRYPTO,
            JsCapability.LOG,
            JsCapability.TIME,
        )
    }

    private val repo = PluginRepository(context)

    /** 加载/卸载串行化：两个插件同时装载会让 id 冲突与沙箱目录竞争变得不可推理。 */
    private val mutex = Mutex()

    private var hostRef: PluginHost? = null

    /** 引擎的宿主对象；未初始化时为 null。 */
    val host: PluginHost? get() = hostRef

    /** JS 加载器实例（用于查询"现在跑着哪些脚本"）。未初始化时为 null。 */
    private fun loader(): JsPluginLoaderPlugin? = hostRef?.plugin(JS_LOADER_ID)

    /** 沙箱根目录（`filesDir/turbodl-js`）。 */
    private fun sandboxRoot(): File =
        File(context.filesDir, SANDBOX_DIR).apply { mkdirs() }

    /**
     * 初始化宿主并加载**所有已启用**的插件。可重复调用（幂等）。
     *
     * @return 每个插件的加载结果；`null` 值表示成功
     */
    suspend fun start(): Map<String, String?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (hostRef == null) hostRef = createHost()
            val host = hostRef ?: return@withContext emptyMap()

            val results = LinkedHashMap<String, String?>()
            for (p in repo.installedNow()) {
                if (!p.enabled) continue
                results[p.id] = loadLocked(host, p)
            }
            results
        }
    }

    /**
     * 装载单个插件（已加载则先卸载，等价于"重新加载"）。
     *
     * @return null 表示成功，否则是给用户看的原因
     */
    suspend fun load(id: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (hostRef == null) hostRef = createHost()
            val host = hostRef ?: return@withContext "插件宿主不可用"
            val p = repo.installedNow(id).firstOrNull() ?: return@withContext "插件不存在"
            loadLocked(host, p)
        }
    }

    /** 卸载单个插件（不删文件、不改库）。运行中卸载是安全的，见类注释。 */
    suspend fun unload(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            hostRef?.uninstall(id)
            Unit
        }
    }

    /** 释放整个宿主（应用退出/测试用）。 */
    suspend fun shutdown() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { hostRef?.shutdown() }
            hostRef = null
            Unit
        }
    }

    /** 当前真正在跑的脚本插件（引擎侧视角，含状态与已注册项）。 */
    fun livePlugins(): List<JsScriptPlugin.Info> = loader()?.livePlugins() ?: emptyList()

    /**
     * 启动后的健康检查（P4，设计文档第 8.5 章）。
     *
     * ## 它检查什么
     *
     * 对**每个已启用**的插件确认两件事：脚本文件还在、装载后真的出现在运行列表里。
     * 装载本身由 [start] 完成；这里只做"装载结果是否健康"的判定，并把不健康的
     * **写回库里的 `lastError`**（界面据此显示），不弹窗、不打扰用户。
     *
     * ## 为什么在启动时做
     *
     * 插件坏了（脚本被系统清理、更新后不兼容、调用了未授予的能力）**只在加载时才暴露**。
     * 而加载原先只发生在用户打开插件页时 —— 不进那个页面就永远发现不了，
     * 表现为"下载功能莫名失效，但插件列表看起来一切正常"。
     *
     * ## 为什么不自动回滚
     *
     * 回滚是**用户的决定**：也许新版只是缺一个权限、用户更想授权而不是退回旧版。
     * 所以这里只把状态记清楚（含"有可回滚版本"这一信息），把决定权留给用户 ——
     * 自动回滚会让"我明明更新了，怎么还是旧版"变成一个更难解释的现象。
     *
     * @return 不健康的插件：`id` → 原因。空表示全部健康。
     */
    suspend fun healthCheck(): Map<String, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val unhealthy = LinkedHashMap<String, String>()
            val host = hostRef
            if (host == null) {
                // 宿主都没起来（例如引擎初始化失败）：这是整体故障，不该逐插件报错
                return@withContext mapOf("*" to "插件宿主未初始化")
            }
            val live = livePlugins().associateBy { it.id }
            for (p in repo.installedNow()) {
                if (!p.enabled) continue
                val file = File(p.entity.scriptPath)
                val reason = when {
                    !file.isFile -> "脚本文件不存在（可能被系统清理）"
                    !live.containsKey(p.id) -> "未出现在运行列表中（加载失败或已被引擎卸下）"
                    else -> null
                }
                if (reason != null) {
                    unhealthy[p.id] = reason
                    repo.recordLoadErrorBlocking(p.id, reason)
                }
            }
            unhealthy
        }
    }

    /** 引擎拒绝关闭的运行时数量（drain 超时的脚本）。 */
    fun leakedRuntimeCount(): Int = loader()?.leakedRuntimeCount() ?: 0

    /**
     * 校验一份脚本（不执行），用于导入前与编辑器保存前。
     * 这是 [JsScriptValidator] 的转发，让调用方不必直接依赖引擎类型。
     */
    suspend fun validate(script: String): JsScriptValidator.Report = JsScriptValidator.validate(script)

    // ---------------------------------------------------------------- 内部

    private fun createHost(): PluginHost? = runCatching {
        val loader = JsPluginLoaderPlugin(
            engineConfig = TurboConfig(),
            jsConfig = JsPluginConfig(
                permissions = DEFAULT_GRANTS,
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS,
                invocationTimeoutMillis = INVOCATION_TIMEOUT_MS,
                sandboxRoot = sandboxRoot(),
            ),
        )
        // installAll 是宿主唯一的装载入口（PluginHost 没有 loadPlugin）。loader 类插件与普通
        // 插件走同一条 install 路径，装载失败会体现在它自己的 state/error 上，不抛异常。
        PluginHost().apply { installAll(listOf(loader)) }
    }.onFailure {
        android.util.Log.e("PluginRuntime", "创建插件宿主失败：${it.message}", it)
    }.getOrNull()

    /**
     * 真正的装载：先卸载同名实例（改过脚本后重新启用就走这条），再按来源装载。
     *
     * 失败一律**不抛出**：装载失败是"这一个插件的问题"，记回库里给用户看，其余插件继续。
     */
    private fun loadLocked(host: PluginHost, p: InstalledPlugin): String? {
        val file = File(p.entity.scriptPath)
        if (!file.isFile) {
            val msg = "脚本文件不存在（可能被系统清理）：${file.name}"
            repo.recordLoadErrorBlocking(p.id, msg)
            return msg
        }
        // 同 id 先卸：PluginHost 对重复 id 是覆盖，但显式卸载能保证旧实例的 disposer 先跑完
        runCatching { host.uninstall(p.id) }

        return runCatching {
            host.loadSource(
                PluginSource(
                    kind = "js",
                    uri = file.absolutePath,
                    // pluginId 必须显式传：脚本文件名是派生名，而库里记的是用户可见的真实 id
                    attributes = mapOf("pluginId" to p.id),
                )
            )
            // 引擎的加载是"登记 + onLoad"，脚本自身的注册错误会体现在状态里
            val info = loader()?.livePlugins()?.firstOrNull { it.id == p.id }
            if (info == null) {
                "加载后未出现在运行列表中（脚本可能不合法）"
            } else {
                null
            }
        }.getOrElse { t ->
            val msg = t.message ?: t.javaClass.simpleName
            android.util.Log.w("PluginRuntime", "插件 ${p.id} 加载失败：$msg", t)
            repo.recordLoadErrorBlocking(p.id, msg)
            msg
        }
    }
}
