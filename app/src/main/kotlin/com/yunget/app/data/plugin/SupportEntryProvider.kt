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

import dev.turbodl.plugin.runtime.ExtensionPointKey

/**
 * 「支持入口」扩展点（P7）—— 赞赏/支持内容由插件提供，而不是写死在 Host 里。
 *
 * ## 为什么这件事值得做
 *
 * 设计文档第 14 章要求"赞赏/支持属于非核心功能，可以实现为 Support Plugin"。
 * 更重要的是：**它是验证「扩展点机制在 App 侧真的能用」的最小案例** ——
 * 宿主不再需要知道"支持页长什么样"，只提供一个能挂内容的骨架。
 *
 * 如果这个最小的案例都跑不通，那么 P16（原生插件加载）那个更大的机制也没有可信的地基。
 *
 * ## 契约为什么这么窄
 *
 * 只给 [SupportEntry] 一个列表，不给"渲染整个页面"的能力：
 *
 *  - **窄契约不会让插件把界面做坏**：宿主控制版式、间距、深浅色适配，
 *    插件只提供内容。这样卸载插件不会让页面变成一个半成品；
 *  - **不需要把 Compose 暴露给插件**（那是 L2 才该有的事）：条目用数据描述，
 *    宿主决定怎么画。将来 L2 原生插件要自定义渲染，再新增一个更宽的扩展点，
 *    而不是把这个撑大。
 *
 * ## 没有提供者时的行为
 *
 * 宿主显示一句实话（"当前没有支持入口"），而不是回退到某个内置写死页面 ——
 * 回退会让"卸载插件后入口消失"这条验收标准变得无法验证（看起来像还在）。
 */
interface SupportEntryProvider {

    /**
     * 本插件提供的支持条目。
     *
     * 返回空列表 = 这个插件不提供任何内容（合法：例如插件按设置开关了）。
     */
    fun entries(): List<SupportEntry>

    companion object {
        /**
         * 扩展点键。
         *
         * id 用 `yung.cap.support.entries` 的形式（P3 的命名空间规范）：
         * 它是**跨插件协作的契约**，没有命名空间就无法判断"这是谁的契约"。
         */
        val KEY: ExtensionPointKey<SupportEntryProvider> =
            ExtensionPointKey.of("yung.cap.support.entries")
    }
}

/**
 * 一条支持入口。
 *
 * 刻意全是**数据**：没有回调、没有 Compose、没有可执行代码。
 * 宿主拿到它就知道该画什么，插件不需要（也无法）干涉渲染。
 */
data class SupportEntry(
    /** 条目标题（如"赞赏开发者"）。 */
    val title: String,
    /** 一句话说明。可以为空 —— 只有标题也是合法的条目。 */
    val note: String = "",
    /**
     * 条目类型，决定宿主怎么渲染。
     *
     * 只用枚举而不是自由字符串：自由字符串会让宿主不得不对每个新取值做兼容，
     * 而"插件发明了一种宿主不认识的表现形式"最终只能退化成显示一行纯文本。
     */
    val kind: Kind = Kind.TEXT,
    /**
     * 内容载荷，含义由 [kind] 决定：
     *  - [Kind.TEXT]：要显示的文本（可多行）
     *  - [Kind.IMAGE]：**应用私有目录内的**图片绝对路径（宿主负责读）
     *  - [Kind.LINK]：http/https 链接（宿主用浏览器打开）
     *  - [Kind.COPYABLE]：一段可复制的文本（如收款码链接、口令）
     */
    val payload: String = "",
) {
    enum class Kind {
        /** 一段说明文字。 */
        TEXT,

        /**
         * 一张图片（如收款码）。
         *
         * 路径必须是**应用私有目录下的文件** —— 插件通过 `host.storage` 之类落地，
         * 再把路径给它。不接受任意路径：那会让"插件读了一张它本不该读的图"变成
         * 宿主替它完成的操作。
         */
        IMAGE,

        /** 一个外部链接（宿主用系统浏览器打开）。 */
        LINK,

        /** 一段可复制文本。 */
        COPYABLE,
    }
}
