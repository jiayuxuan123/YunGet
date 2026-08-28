package com.yunget.app.data.prefs

import android.content.Context

/**
 * 应用设置（SharedPreferences 持久化）。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("yunget_settings", Context.MODE_PRIVATE)

    /** 下载线程数（分片并发上限；引擎慢启动在 [4, 该值] 间动态爬升），默认 16，上限 128 */
    var downloadThreads: Int
        get() = prefs.getInt("download_threads", DEFAULT_DOWNLOAD_THREADS).coerceIn(1, 128)
        set(value) {
            prefs.edit().putInt("download_threads", value.coerceIn(1, 128)).apply()
        }

    /** 自定义下载保存目录（SAF tree Uri，content://...）；null/空 = 系统默认 Download 目录 */
    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) {
            prefs.edit().putString("download_dir_uri", value).apply()
        }

    /** 最大同时下载任务数（默认 1：前台任务吃满带宽，其余排队；参考 IDM 默认单任务满速） */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.edit().putInt("max_concurrent_downloads", value.coerceIn(1, 10)).apply()
        }

    /** 下载速度限制（字节/秒；0 = 不限速） */
    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L)
        set(value) {
            prefs.edit().putLong("download_speed_limit", value.coerceAtLeast(0L)).apply()
        }

    /** 下载失败后自动重试次数（默认 3，范围 0-10） */
    var downloadRetryCount: Int
        get() = prefs.getInt("download_retry_count", DEFAULT_DOWNLOAD_RETRY_COUNT)
        set(value) {
            prefs.edit().putInt("download_retry_count", value.coerceIn(0, 10)).apply()
        }

    /** 锁屏后保持下载：开启后下载时获取 WakeLock，并可引导加入「忽略电池优化」白名单（默认开启） */
    var keepDownloadWhenLocked: Boolean
        get() = prefs.getBoolean("keep_download_when_locked", true)
        set(value) {
            prefs.edit().putBoolean("keep_download_when_locked", value).apply()
        }

    /** 通知栏进度样式：true=完整通知（进度条+下载速度）；false=仅显示通知（隐藏速度） */
    var notificationShowSpeed: Boolean
        get() = prefs.getBoolean("notification_show_speed", true)
        set(value) {
            prefs.edit().putBoolean("notification_show_speed", value).apply()
        }

    /** 桌面图标样式：0=默认(多线程下载图标 icon_dl，主 Activity)，1=经典图标(icon)，2=云X图标(icon2)；切换经 activity-alias 动态生效 */
    var appIconVariant: Int
        get() = prefs.getInt("app_icon_variant", 0)
        set(value) {
            prefs.edit().putInt("app_icon_variant", value.coerceIn(0, 2)).apply()
        }

    /** 忽略 SSL 证书校验（抓包调试用，隐藏菜单开启；默认关闭） */
    var ignoreSslCert: Boolean
        get() = prefs.getBoolean("ignore_ssl_cert", false)
        set(value) {
            prefs.edit().putBoolean("ignore_ssl_cert", value).apply()
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.edit().putBoolean("baidu_limit_hint_dismissed", value).apply()
        }

    /** 自定义 DNS over HTTPS 服务器 URL（空 = 使用系统 DNS）；例如 https://dns.google/dns-query */
    var dohUrl: String?
        get() = prefs.getString("doh_url", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString("doh_url", value?.trim().orEmpty()).apply()
        }

    /** 连接预热 / DNS 预解析（默认开）：下载前预建连接池，起步更快 */
    var warmUpConnections: Boolean
        get() = prefs.getBoolean("warm_up_connections", true)
        set(value) {
            prefs.edit().putBoolean("warm_up_connections", value).apply()
        }

    /** 慢启动（默认开）：并发从少逐步爬升到设定值，避免瞬时几十连接冲击服务器 */
    var slowStart: Boolean
        get() = prefs.getBoolean("slow_start", true)
        set(value) {
            prefs.edit().putBoolean("slow_start", value).apply()
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.edit().putInt("dark_mode", value.coerceIn(0, 2)).apply()
        }

    /** 主题色模式：0=动态色彩（Android12+ 壁纸取色，低版本回退默认蓝），1=默认蓝色，2=自定义种子色 */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.edit().putInt("theme_color_mode", value.coerceIn(0, 2)).apply()
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.edit().putLong("theme_seed_color", value).apply()
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 16
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 1
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}
