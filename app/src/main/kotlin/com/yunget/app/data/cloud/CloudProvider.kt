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

/**
 * **网盘 Provider 插件的公共契约**（P0）。
 *
 * ## 为什么需要它，而不是继续用 JS 插件
 *
 * 现有 JS 插件能提供链接解析、任务钩子、事件监听 —— 但那**不等于**网盘能力：
 *
 * - JS 插件跑在 QuickJS 沙箱里，只能通过宿主开放的有限接口工作；
 * - 网盘登录要维护会话、处理风控、多轮请求、动态令牌；
 * - 分享解析与文件浏览是**多步骤状态机**，不是一个 `parse(url) -> request`。
 *
 * 所以需要宿主定义的一等契约：[CloudProvider]。JS 插件将来可以通过
 * "用 JS 实现 Provider 接口"的桥接层挂上来（那是后续工作），但**契约本身属于宿主**。
 *
 * ## 为什么保留内置实现作回退（重要）
 *
 * 迁移是渐进的，一个网盘一个网盘搬。在某个网盘的新 Provider 被证明可靠之前，
 * 内置实现**保留可用**。路由规则（见 [CloudProviderResolver]）：
 *
 * ```
 * 插件已装 + 已启用 + 兼容 + 健康检查通过  →  用插件
 * 以上任一不满足                          →  静默回退到内置实现，并写日志
 * ```
 *
 * 静默回退是刻意的：用户点了某个网盘，结果不能是"插件坏了所以什么都做不了"。
 * 但**必须留痕** —— 否则"插件一直没生效"会变成一个查不到原因的问题。
 *
 * ## 能力是声明式的，不是强制的
 *
 * 各网盘能力差异很大（115 要应用内登录页、蓝奏云没有分享解析接口、百度会风控）。
 * 所以 [CloudCapability] 是可选项的集合，宿主**只调用它已声明的能力**，
 * 不会去调一个 Provider 声称自己支持、实际并不存在的方法。
 */
interface CloudProvider {

    /** 稳定且全局唯一的插件 id（如 `provider.quark`）。 */
    val providerId: String

    /** 界面展示名。 */
    val displayName: String

    /** 宿主插件接口版本；不满足时宿主拒绝加载并说明。 */
    val apiVersion: String get() = API_VERSION

    /**
     * 这个 Provider 支持哪些能力。
     *
     * 宿主**只**按这里声明的能力调用；未声明的能力在界面上隐藏，
     * 而不是调过去再捕获异常 —— 后者会让插件作者误以为"调用成功了但没反应"。
     */
    fun capabilities(): Set<CloudCapability>

    // ------------------------------------------------------------ 生命周期

    /**
     * 初始化（加载插件时调用一次）。抛异常 = 初始化失败，宿主会回退到内置实现并记日志。
     */
    fun initialize() {}

    /** 释放资源。卸载/停用时调用。 */
    fun release() {}

    /**
     * 健康检查：宿主在每次路由前问一次。
     *
     * 存在的意义是把"插件看起来装着、其实不能用"变成**可查询**的状态，
     * 而不是等用户点下去才报错。返回 null 表示健康；返回字符串是原因（可展示）。
     */
    fun health(): String? = null

    // ------------------------------------------------------------ 分享解析

    /**
     * 这个 Provider 是否认领这个链接。**只做识别**，不做网络请求。
     *
     * 要求纯本地、无 IO：宿主要先靠它把链接路由到正确的 Provider。
     */
    fun handles(rawInput: String): Boolean

    /**
     * 解析分享链接，返回分享的元信息。
     *
     * 需要访问码时通过 [request] 里的 [CloudShareRequest.password] 传入；
     * 没有就返回 [CloudError.NEEDS_PASSWORD]，宿主会向用户要提取码再试一次 ——
     * 而不是让 Provider 自己去弹窗（那会让插件拿到 UI 控制权）。
     */
    suspend fun resolveShare(request: CloudShareRequest): CloudResult<CloudShare>

    /**
     * 列出分享里的内容。
     *
     * @param path 目录路径；空串表示根目录
     * @param page 分页游标；null 表示第一页
     */
    suspend fun listShareFiles(
        shareId: String,
        path: String,
        password: String?,
        page: String?,
    ): CloudResult<CloudFilePage>

    /**
     * 取某个文件的下载信息，交给宿主交给下载引擎。
     *
     * 返回的是**结构化的下载信息**，而不是让插件自己去下载 —— 这是与
     * 旧内置实现最大的差别：下载执行始终归引擎所有。
     */
    suspend fun resolveDownload(
        shareId: String,
        file: CloudFile,
        password: String?,
    ): CloudResult<CloudDownloadInfo>

    companion object {
        /** Provider 契约版本。宿主实现此接口的插件声明它，宿主据此拒绝过旧的。 */
        const val API_VERSION = "1.0.0"
    }
}

/** 宿主支持的能力（Provider 声明自己有哪些）。 */
enum class CloudCapability {
    /** 分享链接解析。 */
    SHARE_PARSE,

    /** 分享内文件浏览 / 目录展开。 */
    SHARE_BROWSE,

    /** 取下载地址。 */
    DIRECT_LINK,

    /** 账号登录 / 登出 / 状态查询。 */
    ACCOUNT_LOGIN,

    /** 登录账号内（非分享）文件浏览。 */
    ACCOUNT_BROWSE,
}

/**
 * 一次分享解析的请求。
 *
 * @param rawInput 原始分享链接**或**用户粘贴的整段分享文案
 * @param password 提取码；未提供时为 null
 */
data class CloudShareRequest(
    val rawInput: String,
    val password: String? = null,
)

/** 分享的元信息。 */
data class CloudShare(
    val shareId: String,
    val title: String = "",
    /** 需要访问码（当本次请求没带对时）。 */
    val needsPassword: Boolean = false,
)

/** 分享里的一个文件或目录。 */
data class CloudFile(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L,
    /** 目录内已知的子项数；-1 = 未取到。 */
    val childCount: Long = -1L,
    /** 该文件需要的请求头（如 Referer）；交给下载引擎时原样带上。 */
    val headers: Map<String, String> = emptyMap(),
)

/** 文件列表的一页。 */
data class CloudFilePage(
    val files: List<CloudFile>,
    /** 下一页游标；null 表示到底了。 */
    val nextPage: String? = null,
)

/**
 * 交给下载引擎的下载信息。
 *
 * ## 为什么不让 Provider 自己下载
 *
 * 那样会绕开引擎的多线程分片、断点续传、限速与合并 —— 用户会失去这些能力，
 * 而它们正是选这个下载器的理由。Provider 的职责到"给出一条可下载的信息"为止。
 *
 * @param headers 下载必需的头（Cookie / Referer / UA 等）
 * @param urlExpiresAtMillis 直链过期时刻（epoch ms）；0 = 未知或不过期
 */
data class CloudDownloadInfo(
    val url: String,
    val fileName: String,
    val sizeBytes: Long = -1L,
    val headers: Map<String, String> = emptyMap(),
    /** 直链过期时刻；过期后宿主需要重新调用 [CloudProvider.resolveDownload]。 */
    val urlExpiresAtMillis: Long = 0L,
)

/** Provider 的错误。 */
sealed interface CloudError {
    /** 分享不存在 / 已失效 / 被取消。 */
    data object SHARE_UNAVAILABLE : CloudError

    /** 需要访问码（当本次请求没带对时）。 */
    data object NEEDS_PASSWORD : CloudError

    /** 访问码错误。 */
    data object BAD_PASSWORD : CloudError

    /** 需要登录才能继续。宿主应引导到 [CloudCapability.ACCOUNT_LOGIN]。 */
    data object NEEDS_LOGIN : CloudError

    /** 被平台风控（验证码、限流、异地限制）。 */
    data object RISK_CONTROLLED : CloudError

    /** 平台返回了无法理解的响应（接口变更）。 */
    data class UNEXPECTED(val detail: String) : CloudError
}

/**
 * Provider 的返回值。
 *
 * 用 `Result` 而不是抛异常：网盘错误里有好几种是**正常业务结果**（要提取码、要登录、
 * 被风控），它们不是"程序出错"，用户看到的是"请输入提取码"而不是"出错了"。
 */
sealed interface CloudResult<out T> {
    data class Ok<T>(val value: T) : CloudResult<T>
    data class Err(val error: CloudError) : CloudResult<Nothing>
}

fun <T> CloudResult<T>.getOrNull(): T? = (this as? CloudResult.Ok)?.value

fun <T> CloudResult<T>.errorOrNull(): CloudError? = (this as? CloudResult.Err)?.error
