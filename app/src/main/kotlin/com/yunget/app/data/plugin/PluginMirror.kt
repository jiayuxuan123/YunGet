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

import java.net.URI

/**
 * 插件分发地址的**镜像与回退**策略（P5，设计文档第 9 章）。
 *
 * ## 要解决什么
 *
 * 插件索引与脚本常挂在 GitHub raw 上，国内直连时通时不通；自建源也可能整体不可达。
 * 所以取字节这件事需要一条固定的动作序列：**先试首选地址，失败换下一个**，
 * 而不是碰到第一个错误就整个失败。
 *
 * ## 两条必须守住的边界
 *
 * **① 镜像只负责分发，不产生信任。**
 *
 * 镜像地址与直连地址取回的字节**走的是同一条校验链**（长度 → SHA-256 → Ed25519 验签 →
 * 脚本自报身份），没有任何一步被镜像放宽。也就是说：换镜像不会让一个没签名的插件装得上 ——
 * 校验对象是**字节**，不是地址。真正的信任来源始终是 `PluginTrust` 里的公钥。
 *
 * **② 回退只对"取不到字节"生效，不对"校验不通过"生效。**
 *
 * 镜像挂了 / 超时 / 返回 404 / 连不上 → 换下一个候选地址，这是分发层的事。
 * 但镜像**返回了内容、而内容对不上摘要或签名** → 直接判失败，不再去试直连。
 * 理由：出现这种分歧恰恰说明链路被动了，此时"换个通道再来一次"只会给篡改多一次机会；
 * 而"镜像是坏的"这个假设已经由①的强校验兜住了 —— 内容对不上就是不对上，与它从哪来无关。
 *
 * ## 为什么不把这段逻辑写在 [MarketClient] 里
 *
 * 候选顺序是本功能的全部要害（直连必须在最后、镜像只对 GitHub 生效、自定义镜像优先于全局），
 * 而 [MarketClient] 依赖 `Context`，纯 JVM 单测跑不了。这里把它抽成不依赖 Android 的
 * 纯函数，取字节的动作由调用方以 lambda 注入，于是"主地址挂了能不能回退"可以在
 * 单测里真的跑一遍 —— 用假的 fetcher 模拟"第一个地址不可用"。
 */
internal object PluginMirror {

    /** 候选地址的来源。界面上用它显示"当前用的是哪个通道"。 */
    enum class Route {
        /** 用户为**本源**单独指定的镜像（适用于任意主机）。 */
        SOURCE_MIRROR,

        /** 设置里的全局 GitHub 镜像（只对 GitHub 系主机生效）。 */
        GLOBAL_GITHUB_MIRROR,

        /** 原始地址直连 —— 永远排在最后，是保底。 */
        DIRECT,
    }

    /** 一个候选地址及其来源。 */
    data class Candidate(val url: String, val route: Route)

    /** 命中结果：地址 + 取回的内容。 */
    data class Hit<T>(val candidate: Candidate, val value: T)

    /**
     * 算出按顺序尝试的候选地址。
     *
     * @param url 原始地址
     * @param globalPrefix 设置里的全局 GitHub 镜像前缀；空 = 不套
     * @param sourceMirror 用户为本源指定的镜像前缀；空 = 没有。非空时**优先于**全局
     * @return 至少含一个 [Route.DIRECT]；地址非法时返回空列表
     */
    fun candidates(url: String, globalPrefix: String = "", sourceMirror: String = ""): List<Candidate> {
        val raw = url.trim()
        if (!isHttpUrl(raw)) return emptyList()

        // 用 LinkedHashMap 按地址去重：镜像前缀配歪了可能拼出与直连一样的地址，
        // 那就只试一次，别浪费一次超时。
        val out = LinkedHashMap<String, Candidate>()
        fun put(candidate: Candidate) {
            if (!out.containsKey(candidate.url)) out[candidate.url] = candidate
        }

        val custom = sourceMirror.trim()
        if (custom.isNotBlank()) {
            // 本源自定义镜像对**任意**主机生效：用户明确指着这个源说"走这儿"，
            // 就不该再拿"这是 GitHub 吗"去拦他。
            put(Candidate(applyMirror(raw, custom), Route.SOURCE_MIRROR))
        } else {
            val global = globalPrefix.trim()
            if (global.isNotBlank() && isGitHubUrl(raw)) {
                put(Candidate(applyMirror(raw, global), Route.GLOBAL_GITHUB_MIRROR))
            }
        }

        put(Candidate(raw, Route.DIRECT))
        return out.values.toList()
    }

    /**
     * 依次尝试候选地址，返回第一个取到内容的。
     *
     * @param fetch 返回 null 表示这个地址失败（不可达 / 超时 / 非 2xx / 空内容）
     * @return 全部失败时返回 null
     */
    fun <T> fetchFirst(candidates: List<Candidate>, fetch: (Candidate) -> T?): Hit<T>? {
        for (candidate in candidates) {
            // 单个地址抛异常（超时、DNS 失败）不能中断整条回退链 ——
            // 网络层的异常在这里是"这个地址不行"，不是"整个操作失败"。
            val value = runCatching { fetch(candidate) }.getOrNull()
            if (value != null) return Hit(candidate, value)
        }
        return null
    }

    /**
     * 给原始地址套上镜像前缀。
     *
     * 形如 `https://gh.dpik.top/` + `https://raw.githubusercontent.com/x/y` ——
     * 这类代理的用法就是把**整个原地址**接在后面，所以前缀末尾缺 `/` 时补上。
     */
    fun applyMirror(url: String, prefix: String): String {
        val base = prefix.trim().trimEnd('/')
        if (base.isEmpty()) return url
        return "$base/$url"
    }

    /**
     * 是不是 GitHub 系地址 —— 只有这些才套全局镜像前缀。
     *
     * 刻意不套用一切地址：镜像服务是第三方，把非 GitHub 的地址（自建源可能是任意服务器、
     * 网盘直链…）也推过去等于把它们的内容交给镜像，既无必要也不礼貌。
     * 用户给本源单独配了镜像的除外 —— 那是他自己的决定（见 [candidates]）。
     */
    fun isGitHubUrl(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "github.com" ||
            host == "raw.githubusercontent.com" ||
            host == "objects.githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    /** 只接受 http/https —— 既防 `file://` 读本地文件，也防拼出乱七八糟的地址。 */
    fun isHttpUrl(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return (scheme == "http" || scheme == "https") && url.length > scheme.length + 3
    }
}

/** 界面上显示用的通道说明。 */
internal fun PluginMirror.Route.describe(): String = when (this) {
    PluginMirror.Route.SOURCE_MIRROR -> "本源镜像"
    PluginMirror.Route.GLOBAL_GITHUB_MIRROR -> "全局镜像"
    PluginMirror.Route.DIRECT -> "直连"
}

/** 命中结果的通道说明（记日志用：让"这次是走镜像拿到的"这件事在日志里看得见）。 */
internal fun PluginMirror.Hit<*>.routeLabel(): String = candidate.route.describe()