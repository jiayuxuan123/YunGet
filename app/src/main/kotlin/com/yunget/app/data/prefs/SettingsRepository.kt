package com.yunget.app.data.prefs

import android.content.Context
import com.yunget.app.data.download.EngineDefaults

/**
 * 应用设置（SharedPreferences 持久化）。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("yunget_settings", Context.MODE_PRIVATE)

    /**
     * 下载线程数（分片并发上限；引擎慢启动在 [4, 该值] 间动态爬升）。默认 16。
     *
     * 上限 **256** = 引擎（TurboDL `maxConnectionsPerTask`）支持的最大值；
     * 以前这里卡在 128，白白少用一半（上游 YunX 也早已开放到更高）。
     */
    var downloadThreads: Int
        get() = prefs.getInt("download_threads", DEFAULT_DOWNLOAD_THREADS).coerceIn(1, MAX_DOWNLOAD_THREADS)
        set(value) {
            prefs.edit().putInt("download_threads", value.coerceIn(1, MAX_DOWNLOAD_THREADS)).apply()
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
     * null/空字符串表示**用户没有自定义**；此时各调用方按自己的场景决定默认值，
     * 这两个默认值**不一样**，不要在别处假设其中之一：
     *
     *  - `KernelProvisioner.mirrorUrl`（Gopeed 内核包）：空 → 用内置默认镜像
     *    [UpdateChecker.MIRROR_PREFIX]（内核包几十 MB，走镜像是这轮移植的初衷）；
     *  - `MarketClient`（插件索引与脚本）：空 → **直连**，不套任何镜像。
     *    刻意不同：插件市场默认把所有流量过第三方 CDN 是一次信任面变化，
     *    该由用户自己显式开启，而不是悄悄发生。
     *
     * ★ 本项目设置页目前没有暴露这一项，本字段为移植 Gopeed 内核包下载
     *   （`KernelProvisioner.mirrorUrl`）而补入。
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

    /**
     * 颜色对比度档位：0=标准，1=中等对比，2=高对比。
     *
     * 这三档不是"更好看/更难看"的区别，而是无障碍设置：视力不佳或强光下使用时，
     * Material 的标准配色对比度可能不够。`Theme.kt` 里四套对比度方案本来就存在
     * （中等/高 × 浅色/深色），但此前没有任何入口能选中它们 —— 等于白写。
     */
    var contrastLevel: Int
        get() = prefs.getInt("contrast_level", 0)
        set(value) {
            prefs.edit().putInt("contrast_level", value.coerceIn(0, 2)).apply()
        }

    // ====================================================================
    // 引擎高级参数（TurboDL 真实支持、但此前在界面上**完全没有入口**）
    //
    // 这些值以前被硬编码在 `TurboDownloadManager.buildConfig()` 里：
    // 改一次就要重新发版，用户遇到特定的服务端行为（HTTP/2 塌缩、CDN 反复 503）
    // 也没有任何自救手段。现在全部落盘，默认值**保持与旧硬编码完全一致**，
    // 所以「不碰这些开关」的用户行为不会有任何变化。
    //
    // 每一组的注释都写清「什么情况下才该动它」—— 这些是排障旋钮，不是日常选项。
    // ====================================================================

    /**
     * 强制 HTTP/1.1（默认开）。
     *
     * 关掉会允许 HTTP/2：h2 把所有分片**多路复用到同一条 TCP 连接**上，
     * 共享一个拥塞窗口 —— 开几十个线程也只有单连接速度。
     * 只有当你确认目标服务器**没有**启用 h2、或 h2 表现更好时才该关掉。
     */
    var forceHttp1: Boolean
        get() = prefs.getBoolean("force_http1", true)
        set(value) {
            prefs.edit().putBoolean("force_http1", value).apply()
        }

    /**
     * 动态分片（默认开）：把快完成的分片再切开交给空闲连接，消除慢连接长尾。
     *
     * 关掉后每个分片只下一个整块 —— 分片数固定，行为更可预测，适合排查
     * "为什么并发数上不去"。
     */
    var dynamicSegmentation: Boolean
        get() = prefs.getBoolean("dynamic_segmentation", true)
        set(value) {
            prefs.edit().putBoolean("dynamic_segmentation", value).apply()
        }

    /**
     * 每连接预分块数（默认 4）。
     *
     * 块数 = 连接数 × 本值。块越多，慢连接被窃取的机会越大；但块太小会增加
     * 建连与合并开销。**别为了"减少请求数"调大它** —— 那会让块数暴涨。
     */
    var segmentsPerConnection: Int
        get() = prefs.getInt("segments_per_connection", 4)
        set(value) {
            prefs.edit().putInt("segments_per_connection", value.coerceIn(1, 16)).apply()
        }

    /**
     * 收尾托管（默认开）：队列空但仍有分片在跑时，把最大的那个尾部让给空闲连接。
     *
     * 只在收尾阶段触发，不影响大块传输 —— 关掉一般只会让最后几秒变慢。
     */
    var tailAssist: Boolean
        get() = prefs.getBoolean("tail_assist", true)
        set(value) {
            prefs.edit().putBoolean("tail_assist", value).apply()
        }

    /**
     * 吞吐自适应（默认开）：按实测吞吐寻找"够用的最低并发"。
     *
     * ⚠️ 已知缺陷（0.2.3.0 起记录）：真实链路上它可能把并发一路降到 1，
     * 实测有一次 373 MiB 花了 377 秒，而同链路关掉它只要 35 秒。
     * 遇到"下载越跑越慢、线程数只降不升"，先关掉这一项。
     */
    var adaptiveConcurrency: Boolean
        get() = prefs.getBoolean("adaptive_concurrency", true)
        set(value) {
            prefs.edit().putBoolean("adaptive_concurrency", value).apply()
        }

    /**
     * 下载中预先合并（默认关）：把完成的分片立刻写进目标文件，让合并 I/O 与
     * 网络等待重叠。开启会增加磁盘写入量（每个分片写两次）。
     */
    var overlapMerge: Boolean
        get() = prefs.getBoolean("overlap_merge", false)
        set(value) {
            prefs.edit().putBoolean("overlap_merge", value).apply()
        }

    /** 分片缓冲写入（默认关）：减少 write 系统调用；开启会推迟落盘、放大中断损失。 */
    var bufferedSegmentWrite: Boolean
        get() = prefs.getBoolean("buffered_segment_write", false)
        set(value) {
            prefs.edit().putBoolean("buffered_segment_write", value).apply()
        }

    /**
     * 背压连续失败阈值（默认 0 = 关闭）。
     *
     * 开启后，连续 N 个分片失败就把并发减半。**默认关闭是因为网盘 CDN 频繁 502/503**，
     * 开了会让线程只降难升，这正是旧版"下到后面速度暴跌"的主因。
     * 只有在服务端确实因为并发过高而限流时才该打开。
     */
    var backpressureFailures: Int
        get() = prefs.getInt("backpressure_failures", 0)
        set(value) {
            prefs.edit().putInt("backpressure_failures", value.coerceIn(0, 16)).apply()
        }

    /**
     * 单主机连接上限（默认 0 = 不限）。
     *
     * 单个下载的所有分片都来自同一主机，设小值会把每个下载直接限死到该值
     * （表现为"设 64 只跑 16"）。仅用于对抗明确按连接数封禁的 CDN。
     */
    var maxConnectionsPerHost: Int
        get() = prefs.getInt("max_connections_per_host", 0)
        set(value) {
            prefs.edit().putInt("max_connections_per_host", value.coerceIn(0, 256)).apply()
        }

    /**
     * 停滞多久断开该连接（毫秒，默认 45000；0 = 关闭）。
     *
     * 服务端接受连接却不发数据时，没有它就要一路等到读超时（默认 30 秒）。
     */
    var stallTimeoutMs: Long
        get() = prefs.getLong("stall_timeout_ms", 45_000L)
        set(value) {
            prefs.edit().putLong("stall_timeout_ms", value.coerceIn(0L, 300_000L)).apply()
        }

    /**
     * 弱校验器续传（默认关 = 安全）。
     *
     * 服务器只回 Content-Length、没有 ETag/Last-Modified 时，续传令牌退化为 `len=N`，
     * 检测不到"同尺寸的新文件"，旧分片会被复用 → **合并出静默损坏的文件**。
     * 只有在你确认来源内容不会变、且更在意省流量时才该打开。
     */
    var trustWeakValidator: Boolean
        get() = prefs.getBoolean("trust_weak_validator", false)
        set(value) {
            prefs.edit().putBoolean("trust_weak_validator", value).apply()
        }

    /**
     * 诊断模式：开启后 DiagnosticLog 会把网络/数据库/WebView 的结构化事件
     * 写进 filesDir/diag/ 下的滚动日志。默认关 —— 它会持续写盘。
     */
    var diagnosticMode: Boolean
        get() = prefs.getBoolean("diagnostic_mode", false)
        set(value) {
            prefs.edit().putBoolean("diagnostic_mode", value).apply()
        }

    /** 下载前跳过探测（默认开，调用方已知文件大小时）：省一个往返，起步更快。 */
    var skipProbeWhenSizeKnown: Boolean
        get() = prefs.getBoolean("skip_probe_when_size_known", true)
        set(value) {
            prefs.edit().putBoolean("skip_probe_when_size_known", value).apply()
        }

    // ====================================================================
    // 第二批引擎旋钮：分片粒度 / 超时 / UA / HTTP 版本策略
    //
    // 这 11 项此前**完全没传**给引擎，所以一直跑的是引擎默认值。
    // 默认值与原引擎默认逐项一致，因此不碰它们时行为不变。
    // 每项都对应引擎 TurboConfig 里的 require 约束，范围取的是引擎允许值。
    // ====================================================================

    /**
     * 单块大小上限（字节，默认 16MB）。
     *
     * 这是**控制大文件请求数**的正确旋钮（不是 maxSegmentsPerTask）。
     * 调小 = 切得更细、并发利用率更高，但建连与合并开销上升。
     */
    var engineBlockSizeBytes: Long
        get() = prefs.getLong("engine_block_size_bytes", EngineDefaults.BLOCK_SIZE)
        set(value) {
            prefs.edit().putLong("engine_block_size_bytes", value.coerceAtLeast(4096L)).apply()
        }

    /** 最小分片大小（字节，默认 64KB）。低于它的块不再继续切分（引擎要求 ≥4KB）。 */
    var engineMinSegmentSizeBytes: Long
        get() = prefs.getLong("engine_min_segment_size_bytes", EngineDefaults.MIN_SEGMENT_SIZE)
        set(value) {
            prefs.edit().putLong("engine_min_segment_size_bytes", value.coerceAtLeast(4096L)).apply()
        }

    /** 单任务分片总数上限（默认 0 = 不限）。 */
    var engineMaxSegmentsPerTask: Int
        get() = prefs.getInt("engine_max_segments_per_task", EngineDefaults.MAX_SEGMENTS_PER_TASK)
        set(value) {
            prefs.edit().putInt("engine_max_segments_per_task", value.coerceAtLeast(0)).apply()
        }

    /** 单个分片最多再被切几次（默认 2）。配合动态分片消除慢连接长尾。 */
    var engineMaxSplitsPerSegment: Int
        get() = prefs.getInt("engine_max_splits_per_segment", EngineDefaults.MAX_SPLITS_PER_SEGMENT)
        set(value) {
            prefs.edit().putInt("engine_max_splits_per_segment", value.coerceIn(0, 16)).apply()
        }

    /** 连接超时（毫秒，默认 15000）。 */
    var engineConnectTimeoutMs: Long
        get() = prefs.getLong("engine_connect_timeout_ms", EngineDefaults.CONNECT_TIMEOUT_MS)
        set(value) {
            prefs.edit().putLong("engine_connect_timeout_ms", value.coerceIn(1_000L, 120_000L)).apply()
        }

    /** 读超时（毫秒，默认 30000，与引擎默认值一致）。服务端接了连接却不发数据时，靠它兜底。 */
    var engineReadTimeoutMs: Long
        get() = prefs.getLong("engine_read_timeout_ms", EngineDefaults.READ_TIMEOUT_MS)
        set(value) {
            prefs.edit().putLong("engine_read_timeout_ms", value.coerceIn(5_000L, 600_000L)).apply()
        }

    /** 首字节探测超时（毫秒，默认 6000，引擎要求 ≥1000）。 */
    var engineProbeTimeoutMs: Long
        get() = prefs.getLong("engine_probe_timeout_ms", EngineDefaults.PROBE_TIMEOUT_MS)
        set(value) {
            prefs.edit().putLong("engine_probe_timeout_ms", value.coerceIn(1_000L, 60_000L)).apply()
        }

    /** 探测重试次数（默认 1）。CDN 抖动导致探测失败时多试几次，代价是首字节更慢。 */
    var engineProbeRetries: Int
        get() = prefs.getInt("engine_probe_retries", EngineDefaults.PROBE_RETRIES)
        set(value) {
            prefs.edit().putInt("engine_probe_retries", value.coerceIn(0, 5)).apply()
        }

    /**
     * 慢启动的初始并发（默认 0 = 由引擎自行决定，即 1）。
     *
     * 【这一项为什么必须暴露】「慢启动」开关只决定"是否逐步爬升"，
     * 而起点的并发数由本值决定。不设它，开关只有半截效果 ——
     * 用户以为调了慢启动，实际起点仍是引擎默认。
     */
    var engineSlowStartInitial: Int
        get() = prefs.getInt("engine_slow_start_initial", EngineDefaults.SLOW_START_INITIAL)
        set(value) {
            prefs.edit().putInt("engine_slow_start_initial", value.coerceAtLeast(0)).apply()
        }

    /**
     * 自定义 User-Agent（空 = 用引擎默认的 Chrome UA）。
     *
     * 引擎 0.2.3.0 的 UA 协商以本值为**起点**，被 403 时自动在
     * curl/aria2 等备选里降级重试。留空即可享受默认策略；
     * 只有在目标站点明确只认某个 UA 时才需要填。
     */
    var engineUserAgent: String
        get() = prefs.getString("engine_user_agent", EngineDefaults.USER_AGENT).orEmpty()
        set(value) {
            prefs.edit().putString("engine_user_agent", value.trim()).apply()
        }

    /**
     * HTTP 版本策略（0=AUTO / 1=强制 HTTP/1.1 / 2=允许 HTTP/2）。
     *
     * 与「强制 HTTP/1.1」布尔开关的关系：那个开关是旧接口，只覆盖分片链路；
     * 本项是引擎的完整三态策略，语义更细（AUTO 会让探测与单流回退走 h2，
     * 而分片并发仍走 h1）。**本项优先于布尔开关**。
     */
    var engineHttpVersionPolicy: Int
        get() = prefs.getInt("engine_http_version_policy", 0)
        set(value) {
            prefs.edit().putInt("engine_http_version_policy", value.coerceIn(0, 2)).apply()
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 16

        /**
         * 线程数上限 = 引擎（TurboDL `maxConnectionsPerTask`）支持的最大值 256。
         *
         * 以前这里写死 128，等于主动放弃一半并发。注意网盘服务端普遍有风控：
         * 拉满不会更快，反而更容易被限速 —— 默认仍是 16。
         */
        const val MAX_DOWNLOAD_THREADS = 256
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
