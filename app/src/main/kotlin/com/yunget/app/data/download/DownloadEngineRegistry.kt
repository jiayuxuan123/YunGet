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

package com.yunget.app.data.download

import android.content.Context

/**
 * 下载引擎注册表（P9；设计文档第 11 章"引擎适配层"的落地）。
 *
 * ## 要解决的问题
 *
 * 在加这张表之前，新增一个下载引擎要改**三处**：
 *
 *  1. `DownloadEngine` 枚举里加常量；
 *  2. `DownloadManagerViewModel.createManager()` 的 `when (engine)` 里加分支；
 *  3. 写一个新的 `createXxxManager()` 方法。
 *
 * 第 2 步是最糟的：它是 Host 层的代码，却因为"多了一个引擎"而要改；
 * 每加一个引擎就多一个分支，分支之间还会互相影响（比如 Gopeed 那次，
 * 它的路由逻辑被迫塞进 `TurboDownloadManager` 里，只因为那是唯一被实例化的实现）。
 *
 * 现在改成：**引擎自己声明 id 与构造方式，注册表按 id 查**。
 * 新增引擎只需要写一个 `EngineProvider` 并加进默认清单 —— Host 的调用点一行不动。
 *
 * ## 与性能无关
 *
 * 这层是**构造期**的间接，只在"切换引擎"时走一次。
 * 下载热路径（分片循环、字节回调）完全不经过注册表，所以不会带来运行时开销。
 */
object DownloadEngineRegistry {

    /**
     * 一个引擎的构造入口。
     *
     * [isAvailable] 与 [create] 分开是刻意的：某些引擎在**当前设备上**不可用
     * （aria2 只有 arm64 有二进制、Gopeed 要先导入内核），
     * 界面需要在不真的构造出一个管理器的情况下问出"能不能用"。
     * 若只有一个 [create]，调用方要么被迫构造（可能抛异常），要么自己复制一遍判定逻辑。
     */
    interface EngineProvider {
        /** 引擎标识，与设置里存的值一致。 */
        val id: String

        /** 界面展示名。 */
        val displayName: String

        /** 一句话说明（设置页用）。 */
        val description: String

        /**
         * 当前设备/当前安装状态下是否可用。
         *
         * 默认 true：多数引擎没有前置依赖（纯 Kotlin 实现总是可用）。
         * 需要外部资源的实现（原生库、外部二进制）应覆盖它。
         *
         * ## 为什么签名里没有 Context
         *
         * 需要判断"二进制在不在 APK 里"这类事确实要看 Context，但那属于**引擎自己去查**：
         * 由 provider 在构造时（或首次询问时）自行获取 Context，而不是让每个调用方
         * 都端着 Context 来问。这样这层接口就是纯逻辑，可以脱离 Android 运行时被测试 ——
         * 否则"新增引擎不用改 Host"这条契约根本没法在单测里验证（项目没有 mock 框架）。
         */
        fun isAvailable(): Boolean = true

        /**
         * 不可用时的原因（展示给用户，要能指出下一步怎么办）。
         *
         * 只在 [isAvailable] 返回 false 时被读取；默认给一句通用说明，
         * 覆盖 [isAvailable] 的实现通常也应覆盖这条，否则用户只看到"不可用"却不知道怎么办。
         */
        fun unavailableReason(): String = "当前设备不支持该引擎"

        /** 构造管理器。[DownloadManagerViewModel] 是唯一调用方。 */
        fun create(context: Context): DownloadManager
    }

    /**
     * 已注册的引擎。
     *
     * 注册顺序 = 设置页的展示顺序，所以默认引擎放第一个。
     *
     * ## 为什么用显式清单而不是自动发现
     *
     * Android 上没有可靠的"扫描所有实现"机制（不像 JVM 的 ServiceLoader 在 Android 上
     * 会因为 R8 裁剪而不完整）。用显式清单的好处是**能被静态分析**：
     * 哪个类在什么时候被实例化是看得见的，而不是运行时才知道。
     * 将来 L2 原生插件落地后，插件可以在运行时往这里追加注册 —— 那时
     * [registerExternal] 就是入口，而不是把清单换成反射扫描。
     */
    private val builtin: List<EngineProvider> = listOf(
        TurboDlEngineProvider,
        LegacyEngineProvider,
        Aria2EngineProvider,
    )

    /**
     * 运行时追加的引擎（留给 L2 原生插件，见 `_audit/插件分级补充.md`）。
     *
     * 与 [builtin] 分开是因为两者的生命周期不同：内置的在进程启动时就确定，
     * 追加的来自插件、可以随插件卸载而消失。
     */
    private val external = mutableListOf<EngineProvider>()
    private val externalLock = Any()

    /** 全部已注册引擎（内置 + 运行时追加）。 */
    fun all(): List<EngineProvider> {
        val extra = synchronized(externalLock) { external.toList() }
        return builtin + extra
    }

    /** 按 id 查；找不到返回 null（调用方决定回退策略）。 */
    fun find(id: String?): EngineProvider? {
        if (id.isNullOrBlank()) return null
        return all().firstOrNull { it.id == id }
    }

    /**
     * 按 id 构造；未知 id 回退到内置默认引擎。
     *
     * 回退而不是抛异常：设置里存的是**字符串**，可能来自旧版本、或被用户改过。
     * 为了一个过期的设置值让应用起不来，代价远大于"静默用回默认引擎"。
     */
    fun create(context: Context, id: String?): DownloadManager =
        (find(id) ?: builtin.first()).create(context)

    /** 默认引擎（清单里第一个）。 */
    fun default(): EngineProvider = builtin.first()

    /**
     * 注册一个外部引擎（L2 原生插件的入口）。
     *
     * 同 id 覆盖：插件更新时应当替换而不是并列，否则设置页会出现两个同名项。
     * 调用方须在插件卸载时调用 [unregisterExternal]。
     */
    fun registerExternal(provider: EngineProvider) {
        synchronized(externalLock) {
            external.removeAll { it.id == provider.id }
            external.add(provider)
        }
    }

    /** 注销一个外部引擎；返回是否确实移除过。 */
    fun unregisterExternal(id: String): Boolean = synchronized(externalLock) {
        external.removeAll { it.id == id }
    }
}

/**
 * TurboDL 内核引擎（默认）。
 *
 * 之所以默认：它是唯一支持现场诊断与插件路由（HLS）的实现，也是这四个里唯一
 * 由本项目维护、可随版本改进的引擎。
 */
internal object TurboDlEngineProvider : DownloadEngineRegistry.EngineProvider {
    override val id = "turbodl"
    override val displayName = "TurboDL（默认）"
    override val description =
        "多线程 Range 分片、工作窃取、限流退避、插件路由（HLS）、断点续传与现场诊断。"

    override fun create(context: Context): DownloadManager =
        EngineManagerFactory.createTurboDl(context)
}

/** 旧版自适应引擎：固定满并发 + 4MB 块 + 工作窃取；功能较少但独立实现。 */
internal object LegacyEngineProvider : DownloadEngineRegistry.EngineProvider {
    override val id = "legacy"
    override val displayName = "内置引擎（兼容模式）"
    override val description =
        "项目早期的自适应下载引擎。保留作为 TurboDL 异常时的兜底；不支持现场诊断。"

    override fun create(context: Context): DownloadManager =
        EngineManagerFactory.createLegacy(context)
}

/**
 * aria2 原生引擎（实验性，仅 arm64）。
 *
 * 可用性取决于两件事：设备 ABI 是否 arm64，以及原生二进制是否随包落盘
 * （APK 需以 `useLegacyPackaging = true` 打包，否则 so/二进制不落地）。
 * 两者都在 [isAvailable] 里如实反映，而不是等到用户切过去才报错。
 */
internal object Aria2EngineProvider : DownloadEngineRegistry.EngineProvider {
    override val id = "aria2"
    override val displayName = "aria2 原生引擎（实验）"
    override val description =
        "内置官方 aria2c（仅 arm64）。多协议、成熟稳定；不支持现场诊断，小文件不分片。"

    /**
     * 可用性取决于 ABI 与"二进制在不在 APK 里"。
     *
     * 后者需要 Context，所以这里不直接查，而是读 [Aria2Executor] 在**首次探测时**
     * 缓存下来的结论（应用启动时 `DownloadManagerViewModel` 会探一次并落盘，
     * 那次探测同时充当这里的可用性来源）。未探测过时按 ABI 判断——
     * 至少能挡住"非 arm64 设备"这个最常见的情况，不会给出误导性的"可用"。
     */
    override fun isAvailable(): Boolean = Aria2Executor.cachedAvailability() ?: Aria2Executor.isSupportedAbi()

    override fun unavailableReason(): String = when {
        !Aria2Executor.isSupportedAbi() ->
            "本机不是 arm64 设备，aria2 二进制只能运行在 arm64 上。"
        else ->
            "aria2 可执行文件不在 APK 内（打包时未启用 useLegacyPackaging）。" +
                "请改用 TurboDL 或内置引擎。"
    }

    override fun create(context: Context): DownloadManager =
        EngineManagerFactory.createAria2(context)
}
