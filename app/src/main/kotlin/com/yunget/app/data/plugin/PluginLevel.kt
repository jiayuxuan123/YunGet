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
 * 插件的能力级别。**Android 上现在只有一档：JS。**（原因见下，是暂时的）
 *
 * ## 为什么曾经有两档，又为什么暂时只剩一档
 *
 * 原分级里 L2 是"跑在宿主进程内的 Kotlin/JVM 原生插件"（可热加载、能力与宿主等同），
 * 用来承载"改变字节面"的插件（新的下载后端、Adapter、文件 Handler）。它在 **Android 上
 * 暂时走不通**：
 *
 *  - Android 10+ 禁止加载应用可写目录里的 dex（"Writable dex file is not allowed"），
 *    把 jar 复制到 `code_cache` 也一样被拒；唯一还能走的是把 dex 字节直接交给系统内存
 *    加载器，但它同样是"非常难做、实际不可用"的状态，且系统一代比一代更严；
 *  - 首批 4 个 L2 网盘 Provider 实际上都是**空壳**（委托宿主的既有仓库干活），
 *    也就是说 L2 这一档从来没提供过宿主做不到的能力。
 *
 * 产品决定：**Android 上原生插件暂时禁用**，等云取出 Windows 端时在 Windows 端启用
 * （那边没有能写 dex 目录的限制）。整条 dex 加载链（内存加载器、jar 落盘、级别分流）
 * 随之暂时移除，插件源里的 native 条目也一并撤下。
 *
 * 枚举本身保留而不是删掉：`plugin_installed.level` 是已发布的数据库列（迁移
 * `MIGRATION_21_22`），而**放着的列比改 schema 安全得多** —— 这个项目踩过迁移事故
 * （2.6.17，见 `DatabaseMigrationContractTest`）。见 [parse] 对 `native` 的处置。
 *
 * L3（宿主内置）不是插件：只有下载调度、任务管理、权限裁决这类核心职责留在宿主里。
 * 它不出现在这个枚举里 —— 一旦它出现，就意味着有人正在把该插件化的东西又塞回宿主。
 *
 * ## 措辞纪律
 *
 * [capabilityNote] 是要**原样显示给用户**的，所以措辞按设计文档第 26 章的口径：
 * 说"验证流程通过"，不说"安全"。写轻了是误导（用户以为它什么都能做）。
 */
enum class PluginLevel(
    /** 索引 / 清单里写的字段值。 */
    val id: String,
    /** 界面标题。 */
    val title: String,
) {
    /** JS 插件：QuickJS 沙箱里的脚本。可热加载，改动即时生效。 */
    JS("js", "JS 插件"),
    ;

    /**
     * 一句话说明这个级别**能做什么**，用于安装确认与详情页。
     *
     * 必须写清楚它在沙箱里、能力要过宿主 API 的门 —— 给 JS 插件写"能力与宿主等同"
     * 是事实错误（它读不到宿主的内存，也做不了宿主没开的能力）。
     */
    fun capabilityNote(): String =
        "运行在应用内的 JS 沙箱里，只能通过宿主开放的接口访问网络与存储；" +
            "网络请求需应用已授予网络权限，文件读写限于应用私有目录与用户主动选择的路径。"

    /** 一句话说明级别与生效时机，用于列表徽标与提示。 */
    fun hotLoadNote(): String = "装上或更新后立即生效"

    companion object {
        /**
         * 索引 / 清单里 `level` 字段的默认值。
         *
         * **缺这个字段一律按 JS 读**，而不是拒绝：分级是后加的字段，存量索引与存量插件
         * 都没有它，把它们判成坏数据会让一次纯字段新增变成一次全量失效。
         */
        val DEFAULT = JS

        /**
         * 解析一个非空的 `level` 值；不认识返回 null（**调用方必须拒绝该条目**）。
         *
         * `native` 现在会落到"不认识"这一支 —— 这是刻意的：Android 端暂时不加载原生
         * 插件，把它当 JS 装上会让应用试图把一个 jar 当脚本读。具体拒绝理由由
         * [MarketIndex] 给出（那里能把它说成"原生插件在手机端暂时停用"，比"级别不受支持"
         * 对用户有用得多）。
         *
         * 未来的 Windows 端会重新启用这一档：那时改的是 `parse` 的返回值与加载链，
         * 调用方（拒绝分支）不用动。
         */
        fun parse(raw: String): PluginLevel? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }

        /** 空串（= 没写这个字段）返回默认值；非法值返回 null。 */
        fun parseOrDefault(raw: String?): PluginLevel? =
            if (raw.isNullOrBlank()) DEFAULT else parse(raw)
    }
}
