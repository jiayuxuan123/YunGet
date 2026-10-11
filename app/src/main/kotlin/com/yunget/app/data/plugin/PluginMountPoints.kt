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
import dev.turbodl.core.TurboClient
import dev.turbodl.plugin.runtime.ext.BackendRegistry
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.FileHandler
import dev.turbodl.plugin.runtime.ext.HandledFile
import com.yunget.app.util.DiagLog

/**
 * 把插件挂载点接到引擎上（P16 的 Adapter 接线）。
 *
 * ## 这一步为什么必要
 *
 * 引擎侧的 Adapter 机制**早就完整**了：`BackendRegistry` 按 `supports()` 选后端，
 * `turbo-plugin-hls` 已经用它注册了 HLS 后端，`demo/Example3ShimAdapter` 演示了
 * 接外部系统。但 **App 侧从来没有设置过 `TurboClient.backendResolver`** ——
 * 它一直是 null，于是 [TurboClient] 永远走内置后端，插件注册的后端根本不会被看到。
 *
 * 机制存在而没接线，等于没有。这与《我的世界》里"挂载点已定义、第一个模组还没来"
 * 是同一种状态：接线发生在第一个真实使用方到来的时候。
 *
 * ## 为什么这里只接线，不搬 Gopeed
 *
 * Gopeed 与宿主是**深耦合**的：实测 69 个静态调用点、横跨 4 个文件
 * （下载管理器的建任务/查状态/删任务/读进度/读详情/合并，下载引擎页的导入与检测，
 * 启动流程的自愈，内核供应器的下载与解包）。而且 [DownloadBackend] 契约是
 * "把字节抓成分片交回来"，Gopeed 的接口是"建一个引擎任务并轮询它"——
 * **两者的模型不一样**，硬套会得到一个到处是分支判断的适配层。
 *
 * 正确顺序是：先把接线做好（这一步）、让 HLS 之类的 L2 后端真的生效，
 * 再单独评估 Gopeed 怎么搬。混在一起做，出问题时无法判断是接线错了还是搬错了。
 */
internal object PluginMountPoints {

    private const val TAG = "插件挂载"

    /** 挂载点 id（与引擎侧一致）。诊断时用它确认注册表里到底有什么。
     *
     * 是 `val` 而不是 `const`：值来自另一个模块的运行时对象，不是编译期常量。
     */
    val BACKEND: String = ExtensionPoints.DOWNLOAD_BACKEND.id
    val FILE_HANDLER: String = ExtensionPoints.FILE_HANDLER.id

    /**
     * 构造后端解析器：把扩展点里的后端接进 [TurboClient]。
     *
     * @return 可直接赋给 `TurboClient.backendResolver` 的解析器
     */
    fun backendResolver(extensions: dev.turbodl.plugin.runtime.ExtensionRegistry): BackendRegistry =
        BackendRegistry(extensions)

    /**
     * 把解析器装到引擎客户端上。
     *
     * 幂等：重复调用只是再设一次同一个值，不会累积状态。
     */
    fun attach(
        context: Context,
        client: TurboClient,
        extensions: dev.turbodl.plugin.runtime.ExtensionRegistry,
    ) {
        client.backendResolver = backendResolver(extensions)
        DiagLog.i(
            context, DiagLog.Category.PLUGIN,
            "已接上插件后端路由；当前注册的后端：" +
                (extensions.snapshot()[BACKEND]?.joinToString { it.first } ?: "（无）"),
        )
    }

    /**
     * 把完成的文件交给文件处理器插件（P16 的 Handler 用途）。
     *
     * ## 三条行为约定（都写在这里而不是散在调用点）
     *
     *  1. **处理器失败不影响"下载已完成"**：异常只记录。一个播放器打不开，
     *     不该把已完成的任务变成失败、更不该删掉已下好的文件。
     *  2. **不吞掉结果**：每个处理器的返回值都进日志，用户能看到"处理了什么"。
     *  3. **先问再交**：由 [FileHandler.matches] 决定，宿主不替处理器猜类型。
     *
     * @return 实际处理了的处理器数量（用于日志与测试）
     */
    fun dispatchToHandlers(
        context: Context,
        extensions: dev.turbodl.plugin.runtime.ExtensionRegistry,
        file: HandledFile,
    ): Int {
        var handled = 0
        for (registration in extensions.registrations(ExtensionPoints.FILE_HANDLER)) {
            val handler: FileHandler = registration.instance
            val matched = try {
                handler.matches(file)
            } catch (e: Throwable) {
                DiagLog.w(
                    context, DiagLog.Category.PLUGIN,
                    "文件处理器 ${registration.ownerPluginId} 的 matches 抛异常，已跳过：${e.message}",
                )
                false
            }
            if (!matched) continue

            try {
                val note = handler.handle(file)
                handled++
                DiagLog.i(
                    context, DiagLog.Category.PLUGIN,
                    "文件已交给 ${registration.ownerPluginId}：${note ?: "（无说明）"}",
                )
            } catch (e: Throwable) {
                // 约定 1：处理器出错不影响下载结果，只记录
                DiagLog.w(
                    context, DiagLog.Category.PLUGIN,
                    "文件处理器 ${registration.ownerPluginId} 处理失败（已下载的文件不受影响）：${e.message}",
                )
            }
        }
        return handled
    }

    /** 当前挂载点诊断（设置页与导出日志用）。 */
    fun snapshot(extensions: dev.turbodl.plugin.runtime.ExtensionRegistry): Map<String, List<Pair<String, Int>>> =
        extensions.snapshot()

}
