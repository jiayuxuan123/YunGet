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

/**
 * 插件的能力级别（P20；分类定义的权威出处是 `_audit/插件分级补充.md`）。
 *
 * ## 为什么要有这一层
 *
 * 早期文档按"实现方式"分类，把**能力级别**和**实现方式**混在一个维度里，
 * 结果没人说得清"换插件之后要不要重启 App" —— 而这件事对用户的预期影响是直接的。
 * 所以这里把两个维度拆开：**级别**回答"能力多大、改了要不要重启"，实现方式另说。
 *
 * | 级别 | 运行位置 | 能做什么 | 热加载 | 变更后 |
 * |---|---|---|---|---|
 * | [JS] L1 | QuickJS 沙箱，进程内 | 网盘登录/解析/取直链、网页与 API 调用、规则与轻逻辑 | 可以 | **即时生效** |
 * | [NATIVE] L2 | 宿主进程内，Kotlin/JVM 原生代码 | 接入其他下载器的插件生态（Adapter）、新下载后端、文件 Handler | 不可以 | **必须重启 App** |
 *
 * L3（宿主内置）**不是插件**：只有下载调度、任务管理、权限裁决这类核心职责留在宿主里。
 * 它不出现在这个枚举里 —— 一旦它出现，就意味着有人正在把该插件化的东西又塞回宿主。
 *
 * ## 为什么 L2 不能热加载（不是实现偷懒）
 *
 * - **类加载器换不掉已加载的类**：原生插件注册进来的 Handler / Backend / 扩展点实现，
 *   类定义在 App 生命周期内无法替换；
 * - **它可能已经持有不可逆的系统资源**：Media3 播放器、硬件解码器、原生库句柄、fd；
 * - **它跑在宿主进程内、没有沙箱**：热加载等于"随时可能执行新代码"，而这类代码的能力
 *   与宿主等同。
 *
 * L1 能热加载恰恰因为它在沙箱里：所有能力都要过宿主 API 的权限门，运行时可整体重建。
 *
 * ## 措辞纪律
 *
 * [capabilityNote] 是要**原样显示给用户**的，所以措辞按设计文档第 26 章的口径：
 * 说"验证流程通过"，不说"安全"；说"能力与宿主等同"，不说"危险"。
 * 写轻了是误导（用户以为有沙箱），写重了会让所有原生插件没人敢装 —— 两者都是错的。
 */
enum class PluginLevel(
    /** 索引 / 清单里写的字段值。 */
    val id: String,
    /** 界面标题。 */
    val title: String,
) {
    /** L1：QuickJS 沙箱里的 JS 插件。可热加载。 */
    JS("js", "L1 · JS 插件"),

    /** L2：宿主进程内的原生（Kotlin/JVM）插件。不可热加载，改动需重启 App。 */
    NATIVE("native", "L2 · 原生插件"),
    ;

    /** 换掉 / 启停这个级别的插件后，是否需要重启 App 才生效。 */
    val requiresRestart: Boolean
        get() = this == NATIVE

    /**
     * 一句话说明这个级别**能做什么**，用于安装确认与详情页。
     *
     * 刻意分开写 L1 与 L2：给 L1 说"能力与宿主等同"不属实（它在沙箱里），
     * 给 L2 说"已沙箱隔离"则是撒谎。
     */
    fun capabilityNote(): String = when (this) {
        JS ->
            "运行在应用内的 JS 沙箱里，只能通过宿主开放的接口访问网络与存储；" +
                "网络请求需应用已授予网络权限，文件读写限于应用私有目录与用户主动选择的路径。"
        NATIVE ->
            "以 Kotlin/JVM 原生代码运行在应用进程内，能力与宿主等同，没有沙箱隔离 —— " +
                "它可以调用宿主能调用的一切系统 API。所声明的权限只作展示，不是限制。" +
                "装它等同于允许这段代码在你的设备上执行。"
    }

    /** 一句话说明级别与生效时机，用于列表徽标与提示。 */
    fun hotLoadNote(): String = when (this) {
        JS -> "装上或更新后立即生效"
        NATIVE -> "需要重启 App 才会生效（原生类无法在运行中替换）"
    }

    companion object {
        /**
         * 索引 / 清单里 `level` 字段的默认值。
         *
         * **缺这个字段一律按 L1 读**，而不是拒绝：分级的引入晚于索引格式，
         * 存量索引与存量插件都没有这个字段，把它们判成坏数据会让一次纯字段新增
         * 变成一次全量失效。向后兼容在这里比"语义严格"重要。
         */
        val DEFAULT = JS

        /**
         * 解析一个非空的 `level` 值；不认识返回 null（**调用方必须拒绝该条目**）。
         *
         * 与 [DEFAULT] 刻意分开：缺字段是"旧数据，按 L1 兼容"，
         * 而写了不认识的值是"新数据里出现了我们没有的级别" —— 后者不能猜。
         * 猜错的代价是让一个 L2 插件被当成 L1 装上，而界面正在告诉用户"即时生效"。
         */
        fun parse(raw: String): PluginLevel? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }

        /** 空串（= 没写这个字段）返回默认值；非法值返回 null。 */
        fun parseOrDefault(raw: String?): PluginLevel? =
            if (raw.isNullOrBlank()) DEFAULT else parse(raw)
    }
}