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

    /**
     * 指定平台的下载线程数（上游口径：各平台可分别设置，见 `DownloadPlatform`）。
     *
     * ★ 本项目的设置页只有**一个**全局线程数（[downloadThreads]），没有做「按平台分别设置」。
     *   这里保留上游的方法签名（Gopeed 内核包下载会按 GitHub 通道取线程数），
     *   内部直接返回那个全局值 —— 语义一致且不会出现「设置页显示 16、实际用了 32」的错位。
     */
    fun downloadThreadsFor(platform: String): Int = downloadThreads

    /** 设置指定平台的下载线程数；本项目只有一个全局值，故与 [downloadThreads] 同义 */
    fun setDownloadThreads(platform: String, value: Int) {
        downloadThreads = value
    }

    /** 自定义下载保存目录（SAF tree Uri，content://...）；null/空 = 系统默认 Download 目录 */
    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) {
            prefs.edit().putString("download_dir_uri", value).apply()
        }

    /**
     * Gopeed 引擎的下载目录（**真实文件系统路径**，如 `/storage/emulated/0/Download/YunGet`）；
     * 空 = 默认目录（公共 `Download` 根目录，与内置下载器同一口径，见 `StorageDirs`）。
     *
     * 为什么不复用 [downloadDirUri]：引擎是原生核心，写不了 SAF 的 `content://` 目录，只能拿真实路径。
     * 用户在「下载引擎」页选目录时走 SAF（从 tree Uri 反解真实路径），反解不到才让他手输。
     */
    var engineDownloadDir: String
        get() = prefs.getString("engine_download_dir", "") ?: ""
        set(value) {
            prefs.edit().putString("engine_download_dir", value.trim()).apply()
        }

    /**
     * 下载引擎：`ENGINE_BUILTIN`（默认，项目自带的 Kotlin 分片下载器）
     * 或 `ENGINE_GOPEED`（内置 Gopeed 引擎，需先在「下载引擎」页导入 AAR）。
     *
     * 取值非法（手改 prefs 等）时按内置下载器处理；引擎没就绪时下载管理器也会自动回退，
     * 不会因为设置项把下载功能弄坏。
     */
    var downloadEngine: String
        get() = prefs.getString("download_engine", ENGINE_BUILTIN)?.takeIf {
            it == ENGINE_BUILTIN || it == ENGINE_GOPEED
        } ?: ENGINE_BUILTIN
        set(value) {
            prefs.edit().putString("download_engine", value).apply()
        }

    /** 最大同时下载任务数（默认 1：前台任务吃满带宽，其余排队；参考 IDM 默认单任务满速） */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.edit().putInt("max_concurrent_downloads", value.coerceIn(1, 10)).apply()
        }

    /**
     * 最大同时下载任务数（引擎侧的叫法）：**同一个值的别名**，见 [maxConcurrentDownloads]。
     *
     * 上游把「内置下载器并发闸门」与「Gopeed 引擎配置顶层的 maxRunning」都挂在同一个设置项上，
     * 引擎侧代码读的是这个名字；本项目沿用同一口径，避免出现两个并发上限各说各话。
     */
    var maxRunningTasks: Int
        get() = maxConcurrentDownloads
        set(value) {
            maxConcurrentDownloads = value
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

    /**
     * 自定义 GitHub 下载镜像前缀（如 `https://gh.dpik.top/`）。
     * null/空字符串表示使用内置默认镜像（`UpdateChecker.MIRROR_PREFIX`）。
     *
     * ★ 本项目设置页目前没有暴露这一项，本字段为移植 Gopeed 内核包下载
     *   （`KernelProvisioner.mirrorUrl`）而补入：内核包的 GitHub 镜像通道读它，
     *   未配置时回落到内置默认前缀，与「检查更新」的镜像下载同一口径。
     */
    var githubMirrorPrefix: String?
        get() = prefs.getString("github_mirror_prefix", null)
        set(value) {
            prefs.edit().putString("github_mirror_prefix", value).apply()
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

    /**
     * 下载引擎 id（见 `com.yunget.app.data.download.DownloadEngine`），默认 TurboDL。
     *
     * 用途：TurboDL 出现异常时的兜底 —— 可切到内置兼容引擎（LegacyDownloadManager）。
     *
     * ⚠️ **切换后需重启 App 才生效**：管理器由 ViewModel 持有，而各业务 ViewModel
     * 在构造时已强引用它。若做热切换，旧 ViewModel 会继续指向旧管理器
     * （两套引擎同时活着、连接池重复占用），所以刻意不做。UI 必须明确提示"重启后生效"，
     * 不能让用户以为点了就立即换引擎。
     */
    var downloadEngineId: String
        get() = prefs.getString("download_engine_id", DEFAULT_DOWNLOAD_ENGINE) ?: DEFAULT_DOWNLOAD_ENGINE
        set(value) {
            prefs.edit().putString("download_engine_id", value).apply()
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

    /** 文件名显示方式：false=单行跑马灯滚动（默认，保持原有观感），true=多行折行显示 */
    var fileNameMultiLine: Boolean
        get() = prefs.getBoolean("file_name_multi_line", false)
        set(value) {
            prefs.edit().putBoolean("file_name_multi_line", value).apply()
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 16
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 1
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3

        /** 默认下载引擎：TurboDL 内核 */
        const val DEFAULT_DOWNLOAD_ENGINE = "turbodl"

        /** 下载引擎标识（[downloadEngine] 的取值）：内置 Kotlin 分片下载器 */
        const val ENGINE_BUILTIN = "builtin"

        /** 下载引擎标识：内置 Gopeed 引擎（gomobile 核心，需用户导入 AAR） */
        const val ENGINE_GOPEED = "gopeed"

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}
