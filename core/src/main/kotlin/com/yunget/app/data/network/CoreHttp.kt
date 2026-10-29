package com.yunget.app.data.network

import okhttp3.OkHttpClient

/**
 * 共享内核用的 HTTP 客户端出口。
 *
 * 内核里各网盘的 Api 都允许注入客户端，默认值走这里 —— 因为内核不能直接依赖宿主
 * 的网络栈：Android 的 `HttpClients` 带日志拦截器与全局上下文，桌面的引擎宿主
 * 可能只有裸 OkHttp。
 *
 * 宿主启动时装一次即可（Android 装成 `HttpClients.apiClient()`，桌面装成自己的配置）。
 * 默认是裸客户端，保证内核在没有宿主的情况下也能跑（测试、命令行脚本）。
 */
object CoreHttp {

    @Volatile
    private var provider: () -> OkHttpClient = { OkHttpClient() }

    /** 安装/替换客户端提供者（宿主启动时调一次）。 */
    fun install(provider: () -> OkHttpClient) {
        this.provider = provider
    }

    fun client(): OkHttpClient = provider()
}
