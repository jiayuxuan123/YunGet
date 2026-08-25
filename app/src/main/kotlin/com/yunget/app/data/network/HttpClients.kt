package com.yunget.app.data.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 全局 HTTP 客户端管理：
 * - [apiClient]：平台 API（登录/解析/直链）、HLS 下载、更新检查共用，超时宽松；
 * - [downloadClient]：分片下载专用，大 Dispatcher 保障分片并发（默认实例 maxRequestsPerHost=5 会锁死并发）。
 *
 * 两套客户端均支持「忽略 SSL 证书校验」：开关切换后重建缓存实例即时生效，
 * 各调用方通过 Provider 动态获取，无需重启应用（用于抓包调试）。
 */
object HttpClients {

    /** 忽略 SSL 证书校验（抓包调试用，仅设置页隐藏菜单可开） */
    @Volatile
    var ignoreSsl: Boolean = false
        set(value) {
            field = value
            rebuildAll()
        }

    private val lock = Any()

    @Volatile
    private var apiCache: OkHttpClient? = null

    @Volatile
    private var downloadCache: OkHttpClient? = null

    /** 普通 API 客户端（各平台 API、HLS、更新检查） */
    fun apiClient(): OkHttpClient {
        apiCache?.let { return it }
        synchronized(lock) {
            apiCache?.let { return it }
            return buildApi().also { apiCache = it }
        }
    }

    /** 下载专用客户端：大 Dispatcher + 长超时，不锁死分片并发 */
    fun downloadClient(): OkHttpClient {
        downloadCache?.let { return it }
        synchronized(lock) {
            downloadCache?.let { return it }
            return buildDownload().also { downloadCache = it }
        }
    }

    /** 开关变化：丢弃缓存，下次获取时按新配置重建 */
    private fun rebuildAll() {
        synchronized(lock) {
            apiCache = null
            downloadCache = null
        }
    }

    private fun buildApi(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (ignoreSsl) applyIgnoreSsl(builder)
        return builder.build()
    }

    private fun buildDownload(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 512
            maxRequestsPerHost = 512 // 与设置页线程数上限（512）对齐，不锁死并发
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 64,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (ignoreSsl) applyIgnoreSsl(builder)
        return builder.build()
    }

    /** 注入「信任所有证书」的 TrustManager + 放行所有 Hostname（仅抓包调试） */
    private fun applyIgnoreSsl(builder: OkHttpClient.Builder) {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }
}
