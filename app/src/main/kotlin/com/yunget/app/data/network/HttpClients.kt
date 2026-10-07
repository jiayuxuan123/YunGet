package com.yunget.app.data.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * 释放两套客户端的空闲连接（连接及其 socket / TLS 缓冲）。
     *
     * 用途：`Application.onTrimMemory` 时调用。分片下载的数据全部流式落盘、不在堆上缓存，
     * 所以丢弃空闲连接不会影响进行中的下载，但能在系统内存吃紧时让出一块可观的内存
     * （连接池最多 64 条空闲连接，每条都带 socket 与 TLS 缓冲）。
     * 只对**已创建**的实例生效：不因为一次内存回收就把懒加载的客户端提前唤醒。
     *
     * ## 为什么必须是异步的（不是"加个 try/catch"就够）
     *
     * 真实崩溃（用户实测，2.6.18）：
     * ```
     * android.os.NetworkOnMainThreadException
     *   at HttpClients.evictIdleConnections(HttpClients.kt:75)
     *   at YunGetApp.onTrimMemory(YunGetApp.kt:47)
     * ```
     * `onTrimMemory` 是**主线程**回调，而 `evictAll()` 不是"清空一个列表"这么无害 ——
     * 它会真的 close 掉每条空闲连接的 socket（`RealConnectionPool.evictAll` → `Util.closeQuietly(socket)`）。
     * 关闭 TLS 连接要把 `close_notify` 告警**写出去**，这是一次网络写；Android 因此在主线程上抛异常。
     * 而 OkHttp 的 `closeQuietly` 只吞 `IOException`，`NetworkOnMainThreadException` 是
     * `RuntimeException`，会**原样穿透**到调用方 —— 所以它崩掉了整个应用。
     *
     * 上游版本用 `runCatching` 包着这两句：那只让异常静默消失，**回收其实从未发生**
     * （每次内存告急都白跑一趟）。真正的修法是换线程 —— 这里把调度器写死在实现里，
     * 调用方（Application 回调 / 任何线程）都不再需要关心上下文。
     *
     * 用一次性线程而非共享线程池：本方法只在内存告急时被调用，频率极低；
     * 为它常驻一个线程池不划算，而每次开一条短命线程的代价可以忽略。
     * 线程内逐句 `runCatching`：一句失败不该让另一套客户端的回收被跳过。
     *
     * [evictInFlight] 是「在飞」闸门：`onTrimMemory` 在系统内存持续吃紧时会被反复回调，
     * 没有闸门就会一次堆一串线程（回收本身是幂等的，重复跑纯属浪费）。
     */
    fun evictIdleConnections() {
        if (!evictInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                runCatching { apiCache?.connectionPool?.evictAll() }
                runCatching { downloadCache?.connectionPool?.evictAll() }
            } finally {
                evictInFlight.set(false)
            }
        }, "yunget-idle-evict").apply { isDaemon = true }.start()
    }

    /** 回收是否正在后台进行（防止内存告急时的重复回调堆出多条线程）。 */
    private val evictInFlight = AtomicBoolean(false)

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
