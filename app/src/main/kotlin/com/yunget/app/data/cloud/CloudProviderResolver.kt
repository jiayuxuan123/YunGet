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

package com.yunget.app.data.cloud

import android.content.Context
import com.yunget.app.util.DiagLog

/**
 * 决定"这次解析用插件还是用内置实现"（P0 的核心回退机制）。
 *
 * ## 规则（刻意保守）
 *
 * ```
 * 插件已装 && 已启用 && 认领这个链接 && 健康检查通过  →  用插件
 * 以上任一不满足                                  →  用内置实现
 * ```
 *
 * **任何一步失败都回退，而不是报错。** 理由很直接：迁移是渐进的，
 * 用户升级到插件化版本后，他**不该**因为某个插件没装好而"网盘功能全没了"。
 * 内置实现一直保留着，就是为了让这条回退路径随时可用。
 *
 * ## 但回退必须留痕
 *
 * 静默回退有个致命副作用：插件装了却一直没生效，而用户和开发者都看不到原因。
 * 所以每一次回退都写日志（`DiagLog` 的 PLUGIN 与 CLOUD 两个分类），
 * 而且带 `providerId` —— 这样"为什么我的插件没被用上"是可回答的。
 *
 * ## 为什么健康检查放在每次路由前
 *
 * 因为插件的状态会变：账号被登出、网络变了、平台改了接口。
 * 装载时检查一次不够。`health()` 的实现应当便宜（读内存状态，不做网络请求），
 * 真正的网络验证由第一次实际调用承担 —— 那时失败同样会回退。
 */
class CloudProviderResolver(
    /**
     * 可空：日志写入在缺 Context 时静默跳过。
     *
     * 理由不是"方便测试"，而是**留痕不能成为新的崩溃源** —— 日志系统若反过来
     * 把下载搞挂，那比没有日志更糟。同时它让这条路由逻辑能在纯 JVM 单测里被覆盖，
     * 而它恰恰是迁移期最需要被测的部分（回退必须留痕）。
     */
    private val context: Context?,
    /** 已加载且已启用的 Provider；由插件管理器提供。 */
    private val providers: () -> List<CloudProvider>,
) {

    private companion object {
        const val TAG = "网盘路由"
    }

    /**
     * 写日志；**没有 Context 就跳过**。
     *
     * 回退留痕是这条类存在的意义之一，但"留痕"本身绝不能变成新的崩溃源 ——
     * 更不能因为日志不可用而让网盘功能整体失败。所以这里显式判空。
     */
    private fun log(level: String, message: String) {
        val ctx = context ?: return
        when (level) {
            "d" -> DiagLog.d(ctx, DiagLog.Category.CLOUD, message)
            "i" -> DiagLog.i(ctx, DiagLog.Category.CLOUD, message)
            "w" -> DiagLog.w(ctx, DiagLog.Category.CLOUD, message)
        }
    }

    private fun logPlugin(message: String) {
        val ctx = context ?: return
        DiagLog.w(ctx, DiagLog.Category.PLUGIN, message)
    }

    /**
     * 选出处理这个链接的 Provider；没有合适的插件时返回 null（= 走内置）。
     *
     * 永不抛异常 —— 插件侧的异常已经在这里被吞掉并记日志。
     */
    fun providerFor(rawInput: String): CloudProvider? {
        for (p in runCatching { providers() }.getOrDefault(emptyList())) {
            val claims = runCatching { p.handles(rawInput) }.getOrElse { t ->
                logPlugin("Provider ${p.providerId} 的 handles() 抛异常，已跳过它：${t.message}",)
                false
            }
            if (!claims) continue

            val unhealthy = runCatching { p.health() }.getOrElse { t ->
                logPlugin("Provider ${p.providerId} 的 health() 抛异常：${t.message}",)
                "健康检查本身失败"
            }
            if (unhealthy != null) {
                log("i", "插件 ${p.providerId} 认领了这个链接但不健康（$unhealthy），" +
                        "已回退到内置实现（内置一直保留，所以功能不受影响）",)
                return null
            }

            log("d", "这个链接由插件 ${p.providerId} 处理",)
            return p
        }
        return null
    }

    /**
     * 执行一次解析：优先插件，失败或不可用则回退内置。
     *
     * @param builtin 内置实现（[BuiltinShareParser]）；永远可调用
     */
    suspend fun resolveShare(
        request: CloudShareRequest,
        builtin: suspend (CloudShareRequest) -> CloudResult<CloudShare>,
    ): CloudResult<CloudShare> = withPlugin(request) { it.resolveShare(request) } ?: builtin(request)

    /** 文件列表：同上。 */
    suspend fun listShareFiles(
        provider: CloudProvider?,
        shareId: String,
        path: String,
        password: String?,
        page: String?,
        builtin: suspend (String, String, String?, String?) -> CloudResult<CloudFilePage>,
    ): CloudResult<CloudFilePage> {
        if (provider == null) return builtin(shareId, path, password, page)
        // 注意：不能写成 runCatching { provider.xxx() } —— suspend 调用在
        // runCatching 的非 suspend lambda 里编译不过，且会吞掉 CancellationException。
        return try {
            provider.listShareFiles(shareId, path, password, page)
        } catch (t: Throwable) {
            log("w", "插件 ${provider.providerId} 列目录抛异常，已回退内置：${t.message}",)
            builtin(shareId, path, password, page)
        }
    }

    /** 下载信息：同上。 */
    suspend fun resolveDownload(
        provider: CloudProvider?,
        shareId: String,
        file: CloudFile,
        password: String?,
        builtin: suspend (String, CloudFile, String?) -> CloudResult<CloudDownloadInfo>,
    ): CloudResult<CloudDownloadInfo> {
        if (provider == null) return builtin(shareId, file, password)
        return try {
            provider.resolveDownload(shareId, file, password)
        } catch (t: Throwable) {
            log("w", "插件 ${provider.providerId} 取下载地址抛异常，已回退内置：${t.message}",)
            builtin(shareId, file, password)
        }
    }

    /**
     * 调插件方法；插件不可用或抛异常时返回 null（= 让调用方走内置）。
     *
     * [call] 必须是 suspend：Provider 的三个方法都是 suspend，包它们的 lambda
     * 若声明成普通函数类型就调不动（编译器报 "Suspension functions can only
     * be called within coroutine body"）。
     */
    private suspend inline fun <T> withPlugin(
        request: CloudShareRequest,
        crossinline call: suspend (CloudProvider) -> CloudResult<T>,
    ): CloudResult<T>? {
        val provider = providerFor(request.rawInput) ?: return null
        return try {
            call(provider)
        } catch (t: Throwable) {
            log("w", "插件 ${provider.providerId} 解析抛异常，已回退内置：${t.message}",)
            null
        }
    }
}
