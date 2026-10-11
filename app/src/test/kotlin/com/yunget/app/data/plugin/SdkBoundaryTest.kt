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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **分层边界**：TurboDL 是独立开源 SDK，云取只是它的一个使用者。
 *
 * ## 这条边界为什么重要
 *
 * 起因是一个真实错误：P16 做 `FileHandler` 挂载点时，我把它的 id 写成了
 * `yung.cap.file.handler` —— 那是**云取的名字**。引擎侧的编译与测试全都通过
 * （它只是一个字符串），但对任何别的 TurboDL 使用者来说，这个挂载点等于不存在：
 * 他们的插件里不可能有 `yung` 这个前缀。
 *
 * 也就是说，那一刻开始，**SDK 的公共面依赖了某一个应用**。这正是"SDK 退化成
 * 某个应用的专用内核"的第一步，而且它不留任何运行时痕迹。
 *
 * ## 三层职责
 *
 * | 层 | 提供什么 | 不提供什么 |
 * |---|---|---|
 * | **TurboDL SDK** | 下载核心、`DownloadBackend` 契约、任务生命周期、通用扩展点 | 任何宿主的选择策略、权限模型、界面 |
 * | **插件运行时** | `BackendRegistry`、`PluginLoaderProvider`：发现与注册 | 决定是否加载、如何授权、何时重启 |
 * | **云取宿主** | 接线、权限、配置、界面、用户确认 | —— |
 *
 * 推论：**「支持插件」不等于「必须使用插件」**。别人用 TurboDL SDK 可以完全
 * 不用插件系统，直接用内置后端。SDK 不该强制所有使用者接受云取的方案。
 */
class SdkBoundaryTest {

    /** 宿主自己的命名空间。云取保留它，但**不由 SDK 保留**。 */
    private val hostNamespace = "yung"

    /** SDK 自己的命名空间。所有 SDK 内建的挂载点与能力都在这里。 */
    private val sdkNamespace = "dev.turbodl"

    // ---------------------------------------------- 命名空间归属

    @Test
    fun `SDK 的保留命名空间只含自己`() {
        val reserved = dev.turbodl.plugin.runtime.CapabilityId.RESERVED_NAMESPACES
        assertTrue(
            "SDK 必须保留自己的命名空间",
            sdkNamespace in reserved,
        )
        assertFalse(
            "SDK 的保留名单里不能有宿主名 —— 那会让 SDK 的公共面依赖某一个应用：" +
                "别的使用者读到「yung 是保留的」毫无意义。实际：$reserved",
            hostNamespace in reserved,
        )
    }

    @Test
    fun `SDK 内建的挂载点都在 SDK 命名空间下`() {
        // 这两个是 SDK 提供的公共契约，任何 TurboDL 使用者都能实现它们。
        val points = listOf(
            dev.turbodl.plugin.runtime.ext.ExtensionPoints.FILE_HANDLER.id,
            dev.turbodl.plugin.runtime.ext.ExtensionPoints.DOWNLOAD_BACKEND.id,
        )
        for (id in points) {
            assertFalse(
                "SDK 挂载点里出现了宿主名「$hostNamespace」：$id",
                id.startsWith("$hostNamespace."),
            )
        }
        assertTrue(
            "文件处理器挂载点应在 SDK 命名空间下：${points[0]}",
            points[0].startsWith("$sdkNamespace."),
        )
    }

    @Test
    fun `宿主可以自己保留命名空间而不必改动 SDK`() {
        // 宿主保留自己的前缀时**不需要** SDK 知道这件事 ——
        // 这正是分层的意义：两边各自管理，SDK 不被宿主撑大。
        val hostClaim = dev.turbodl.plugin.runtime.CapabilityId.parse(
            "$hostNamespace.cap.storage@1.0.0",
        )
        assertTrue(
            "宿主命名空间下的能力声明仍然是合法的",
            hostClaim is dev.turbodl.plugin.runtime.CapabilityId.Result.Ok,
        )
        // 合法，但**不属于** SDK 的保留集合（保留与否由宿主自己裁决）
        val parsed = (hostClaim as dev.turbodl.plugin.runtime.CapabilityId.Result.Ok).parsed
        assertFalse("宿主命名空间不由 SDK 标记为保留 —— SDK 无权替宿主做这个决定", parsed.isReserved)
    }

    // ---------------------------------------------- 「支持」不等于「必须用」

    @Test
    fun `SDK 不强制宿主使用插件系统`() {
        // TurboClient 的后端解析器是**可空**的：null 即"只用内置后端"。
        // 这正是"不用插件也能用 SDK"的机制体现——若它是必填的，
        // 每个使用者都得引入整套插件基础设施才能下载文件。
        assertTrue(
            "backendResolver 必须是可空的：null = 只用内置后端",
            dev.turbodl.core.TurboClient().backendResolver == null,
        )
    }

    // ---------------------------------------------- 引擎不得反向依赖宿主

    @Test
    fun `引擎模块里不出现宿主包名`() {
        // 结构性检查：turbodl-core 与 turbo-plugin-runtime 是发给所有使用者的，
        // 它们一旦 import 宿主的东西，发布出去的 jar 就绑死在某个应用上。
        val forbidden = listOf("com.yunget", "com/yanx", "yung.cap.")
        val roots = listOf("turbodl-core", "turbo-plugin-runtime")
        var checked = 0
        var violations = mutableListOf<String>()

        for (root in roots) {
            // 单测的工作目录是**模块根**（app/），而引擎是它的**同级目录**，
            // 所以要往上两级。写错一级不会报错、只会「检查了 0 个文件」——
            // 所以下面有一条 assertTrue(checked > 0) 专门防这个。
            val srcRoot = java.io.File("../../TurboDL/$root/src/main/kotlin")
            if (!srcRoot.isDirectory) continue
            srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                // 只查真正的代码行：注释里的示例提到宿主名是允许的（且有助于说明）
                f.readLines().forEachIndexed { i, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    checked++
                    for (bad in forbidden) {
                        if (line.contains(bad)) {
                            violations += "$root:${i + 1} 出现「$bad」：${line.trim().take(70)}"
                        }
                    }
                }
            }
        }
        assertTrue("没有可检查的源码（路径不对？）", checked > 0)
        val report = "引擎的源码里出现了宿主包名 —— SDK 一旦依赖某个应用，别的使用者就带上了它：\n" +
            violations.joinToString("\n")
        assertTrue(report, violations.isEmpty())
    }
}
