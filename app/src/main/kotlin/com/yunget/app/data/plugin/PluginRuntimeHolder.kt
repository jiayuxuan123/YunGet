package com.yunget.app.data.plugin

import android.content.Context

/**
 * 进程内唯一的 JS 插件运行时。
 *
 * 为什么必须单例：每个 [PluginRuntime] 都会新建一个 TurboDL `PluginHost`。如果插件页、编辑器、
 * 市场、Application 启动检查各自 new 一个，同一脚本就会在多个 Host 里各跑一份 ——
 * 带来重复请求、重复注册、难以解释的状态错乱；退出页面时旧 Host 里的脚本还会继续跑，
 * 却再没人持有它去 `shutdown()`。
 *
 * 用 [context.applicationContext]：这个对象活得比任何 Activity / Composable 都久，
 * 不能持有 UI 生命周期。
 *
 * 这个文件放在 `data.plugin` 而不是 `ui.screens`：Application 启动健康检查与三个 UI 页面
 * 必须共用**同一个**运行时；数据层/启动层不能反向依赖 UI 层。
 */
@Volatile
private var pluginRuntimeRef: PluginRuntime? = null

private val pluginRuntimeHolderLock = Any()

fun sharedPluginRuntime(context: Context): PluginRuntime =
    pluginRuntimeRef ?: synchronized(pluginRuntimeHolderLock) {
        pluginRuntimeRef ?: PluginRuntime(context.applicationContext).also { pluginRuntimeRef = it }
    }
