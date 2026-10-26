/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
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

package com.yunget.app.data.network

import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.util.DiagLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 出站请求的统一日志。
 *
 * ## 为什么挂在拦截器上而不是逐处打点
 *
 * 之前的问题是：日志"跟到哪算哪"。有人更新了一处解析逻辑顺手加一行日志，
 * 另一处出问题时却什么都看不到 —— 用户导出日志后，里面只有零星几条，
 * 排查等于从头开始。
 *
 * OkHttp 的拦截器是**唯一必经之路**：所有 API 调用（网盘、视频解析、更新检查、
 * 插件 HTTP）都会经过它。挂在这里就保证了"任何出站请求都会留痕"，
 * 不依赖每个调用方自觉。
 *
 * ## 记录什么
 *
 * - 方法、主机、路径（**不含查询串** —— 那里的签名/token 是凭证）
 * - 状态码、耗时、响应体大小
 * - 失败原因（超时 / DNS / TLS / 连接重置）
 *
 * ## 不记录什么
 *
 * - 请求头与 Cookie（凭证）
 * - 响应体内容（可能是用户数据）
 * - 完整 URL 的查询串
 *
 * 默认只在**异常**（非 2xx 或抛异常）时写一条；
 * 成功请求只在「诊断模式」开启时记录，避免正常使用把日志刷满。
 */
internal class NetworkLogInterceptor(
    private val context: android.content.Context,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.currentTimeMillis()
        val verbose = runCatching { SettingsRepository(context).diagnosticMode }.getOrDefault(false)

        return try {
            val response = chain.proceed(request)
            val cost = System.currentTimeMillis() - startedAt
            val ok = response.isSuccessful

            // 失败一律记；成功只在诊断模式下记
            if (!ok || verbose) {
                val line = buildString {
                    append(request.method).append(' ').append(describe(request.url))
                    append(" → ").append(response.code)
                    append(" (").append(cost).append("ms)")
                    response.body?.contentLength()?.takeIf { it >= 0 }?.let {
                        append(" ").append(formatSize(it))
                    }
                }
                if (ok) {
                    DiagLog.i(context, DiagLog.Category.NET, line)
                } else {
                    DiagLog.w(context, DiagLog.Category.NET, line)
                }
            }
            response
        } catch (e: IOException) {
            val cost = System.currentTimeMillis() - startedAt
            DiagLog.e(
                context,
                DiagLog.Category.NET,
                "${request.method} ${describe(request.url)} 失败 (${cost}ms) " +
                    "reason=${describeFailure(e)}",
                e,
            )
            throw e
        }
    }

    /**
     * 只保留 `host/路径首段`。
     *
     * 查询串里经常带 `sign=`、`token=`、`auth_key=` —— 那是凭证，
     * 写进日志等于把凭证明文落盘，用户导出日志时会一并泄露。
     */
    private fun describe(url: okhttp3.HttpUrl): String {
        val path = url.encodedPath
        val shortPath = if (path.length > 60) path.take(60) + "…" else path
        return "${url.host}$shortPath"
    }

    /** 把异常翻成一句人能看懂的话，而不是甩一个类名。 */
    private fun describeFailure(e: IOException): String = when {
        e is java.net.SocketTimeoutException -> "超时"
        e is java.net.UnknownHostException -> "DNS 解析失败"
        e is javax.net.ssl.SSLException -> "TLS 握手失败（${e.message?.take(60)}）"
        e is java.net.ConnectException -> "连接被拒绝"
        e.message?.contains("reset", ignoreCase = true) == true -> "连接被重置"
        e.message?.contains("closed", ignoreCase = true) == true -> "连接被关闭"
        else -> e.javaClass.simpleName + (e.message?.let { ": ${it.take(80)}" } ?: "")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }
}
