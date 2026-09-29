package com.yunget.app.data.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「把字节下载到分片目录」这一层的可替换抽象。
 *
 * ## 为什么需要这层
 *
 * [LegacyDownloadManager] 的 `runTask` 里，**下载执行**与**其余全部职责**
 * （Room 持久化、进度节流、前台服务/通知、WakeLock、合并、保存到 MediaStore/SAF、
 * 暂停/恢复/删除）是混在一起的。若为 aria2 复制一份 manager，等于把这些逻辑抄第二遍 ——
 * 两份必然漂移。
 *
 * 因此把"下载执行"抽成接口：manager 只依赖它，具体实现（内置自适应引擎 / aria2 外部进程）
 * 可替换。**契约与 TurboDL 的 `BackendResult` 一致**：产出有序分片文件，合并交给上层。
 *
 * ## 为什么产物必须落到分片目录、而不是直接写最终文件
 *
 * 上层的 `finishDownload` 会做「分片非空校验 → 合并 → 大小校验 → 保存」。
 * 若某个引擎直接把完整文件写到最终路径并返回它，合并步骤会**先把目标文件截断为 0**
 * （TurboDL 侧 `PartMerger` 就是这个语义），导致数据全毁。
 * 所以契约强制：**实现只能写 [RunSpec.chunkDir] 下的文件**。
 */
interface DownloadEngineRunner {

    /** 供日志/诊断展示的引擎名。 */
    val name: String

    /**
     * 执行一次下载，把结果写入 [spec] 指定的分片目录。
     *
     * 实现必须：
     *  - 尊重 [RunSpec.isActive]（返回 false 时应尽快中止）；
     *  - 通过 [RunSpec.onBytes] 上报**累计**字节与活跃连接数；
     *  - 把产物写到 [RunSpec.chunkDir]，并返回**按序待合并**的文件列表；
     *  - 可续传时复用目录里已有的文件（实现自行判定）。
     */
    suspend fun run(spec: RunSpec): Outcome

    sealed interface Outcome {
        /** 成功；[parts] 为按序待合并的分片。 */
        data class Completed(val parts: List<File>) : Outcome

        /** 失败；[reason] 为可读原因（会展示给用户）。 */
        data class Failed(val reason: String) : Outcome
    }

    /** 一次下载的全部输入。 */
    class RunSpec(
        val taskId: Long,
        val url: String,
        val total: Long,
        val headers: Map<String, String>,
        val chunkDir: File,
        /** 期望连接数（实现可自行设上限）。 */
        val targetWorkers: Int,
        /** 续传起点（已下载字节）。 */
        val resumeFrom: Long,
        /** 每秒可传输字节上限；0 = 不限。 */
        val speedLimitBytesPerSec: Long,
        /** 累计字节上报：(增量, 累计)。 */
        val onBytes: suspend (delta: Long, absolute: Long) -> Unit,
        /** 活跃连接数上报。 */
        val onWorkers: (Int) -> Unit,
        /** 任务是否仍然有效（false = 已暂停/取消，应尽快停止）。 */
        val isActive: () -> Boolean,
    )
}

/**
 * aria2 外部进程引擎：调用 APK 内置的官方 aria2c 完成下载。
 *
 * ## 与上层契约的对接
 *
 * - 输出固定写到 `chunkDir/aria2.out`，返回 `listOf(它)` —— 上层照常"合并"
 *   （实际是单文件拷贝到最终路径），**绝不直接写最终文件**（见 [DownloadEngineRunner] 注释）；
 * - 进度靠**轮询输出文件大小**（最稳，不依赖解析 stdout 的 `\r` 覆盖式输出）；
 * - 取消靠 `isActive()` 检查 + `destroy()` 杀子进程；
 * - 同时必须**持续读走 stdout/stderr**，否则管道缓冲（约 64KB）写满会把 aria2 阻塞住。
 *
 * ## 参数映射与已知限制
 *
 * - `-x`（单服务器连接数）aria2 **硬上限 16**，故 `min(targetWorkers, 16)`；
 * - `-k`（最小分片）默认 20M，且实际门槛是 `2*值` → 小于 40MB 的文件根本不分片。
 *   这里设 4M（参考 Motrix 的加速档 `-x16 -s16 -k4M`），让中等文件也能分片；
 * - `--file-allocation=none`：Android 上预分配（默认 prealloc）可能长时间阻塞；
 * - `--auto-file-renaming=false`：否则同名文件会被改成 `name.1.ext`，破坏原地续传；
 * - `--no-conf -n`：HOME 下没有配置文件，显式禁用避免告警；
 * - 不使用 RPC：轮询文件大小已足够，且省掉一个监听端口与鉴权面。
 */
class Aria2Runner(private val context: Context) : DownloadEngineRunner {

    override val name: String = "aria2"

    companion object {
        private const val TAG = "YunGet-Aria2"
        /** 固定输出名：续传依赖"同一路径"，改名会丢断点。 */
        const val OUT_NAME = "aria2.out"

        /**
         * 单文件连接数上限。
         *
         * 内置引擎已从 aria2 1.37 换为 **aria2-next 2.8.3**，其
         * `--stream-max-connections` 的允许范围是 **1..256**（旧版 `-x` 硬上限只有 16）。
         *
         * 但这里**仍保守取 16**：不是受限于引擎，而是**避免网盘风控** ——
         * 用户明确要求默认线程数不得拉满。若将来要在 aria2 引擎上放开，
         * 应做成独立设置项并由用户显式选择，而不是改这个默认值。
         */
        const val MAX_CONN_PER_SERVER = 16
    }

    override suspend fun run(spec: DownloadEngineRunner.RunSpec): DownloadEngineRunner.Outcome {
        val bin = Aria2Executor.binaryPath(context)
            ?: return DownloadEngineRunner.Outcome.Failed(
                "aria2c 不可用（${Aria2Executor.probe(context).detail}）"
            )

        val out = File(spec.chunkDir, OUT_NAME)
        spec.chunkDir.mkdirs()

        // 已下载字节（续传起点）：用于进度增量计算。
        val baseBytes = if (out.isFile) out.length() else 0L
        if (baseBytes > 0 && spec.resumeFrom <= 0) {
            Log.i(TAG, "task=${spec.taskId} 发现已有 $baseBytes 字节，按续传处理")
        }

        val conns = spec.targetWorkers.coerceIn(1, MAX_CONN_PER_SERVER)
        // HTTPS 必需的 CA bundle（Android 上没有 aria2 默认查找的路径）。见下方注释。
        val caBundle = Aria2CaBundle.ensure(context, File(context.cacheDir, "aria2"))
        val cmd = buildList {
            add(bin.absolutePath)
            add("-d"); add(spec.chunkDir.absolutePath)
            add("-o"); add(OUT_NAME)
            add("-c")                                   // 续传
            // 【参数名必须用新的】内置的是 aria2-next 2.8.3（社区维护 fork），
            // 它把分片策略重写为 `--stream-max-connections`：
            //   - 旧的 `-s` / `-x` 会被"接受但归一化"（每次都打一条 warning 到输出）
            //   - 旧的 `-k`（最小分片）已被**移除**：它明确会打
            //     "accepted and skipped; the maintained native engine owns or retired this policy"
            //     —— 发了也没用，只会刷日志。
            // 故这里直接用新参数名，且不再传 -k（该 fork 自己决定分片粒度）。
            add("--stream-max-connections=$conns")
            add("--file-allocation=none")               // Android 上预分配会长时间阻塞
            add("--auto-file-renaming=false")           // 否则破坏原地续传
            add("--allow-overwrite=true")               // 配合 -c：控制文件缺失时从头下
            add("--no-conf"); add("-n")                 // 无配置文件、不读 netrc
            add("--console-log-level=warn")
            // 【必须指定 CA bundle】Android 上没有 aria2 默认查找的 /etc/ssl/certs/…，
            // 不指定就会对**所有 HTTPS** 报 "unable to get local issuer certificate"。
            // 实测（OnePlus/Android 16）：aria2 下 GitHub release 必失败，而同刻 OkHttp 正常。
            // 取不到时**不传该参数**（而不是传一个空文件）——让 aria2 用它自己的默认值，
            // 至少 HTTP 链接仍可用；失败由上层回退兜底。
            caBundle?.let {
                add("--ca-certificate=${it.absolutePath}")
                add("--check-certificate=true")
            }
            // 【必须保留 readout】它既是唯一的进度来源（见下方双通道注释），也是"任务在进行中"的信号。
            // 关掉它会让 aria2 在长时间无输出时看起来像卡死。
            add("--show-console-readout=true")
            // 进度行刷新频率：默认 1s；调密到 1s 已足够（更密只会增加解析开销）。
            add("--summary-interval=0")                 // 不打"摘要块"（那是多行文本），只要单行 readout
            add("--human-readable=false")               // 关键：进度行用原始字节而非 1.2Mi，才可解析
            if (spec.speedLimitBytesPerSec > 0) {
                // aria2 只认 K/M 后缀，不认 G；字节数直接传也可。
                add("--max-overall-download-limit=${spec.speedLimitBytesPerSec}")
            }
            spec.headers.forEach { (k, v) ->
                // 过滤掉会与 aria2 自身参数冲突的头（Host/Content-Length 由它自己算）。
                if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                    add("--header=$k: $v")
                }
            }
            add(spec.url)
        }

        Log.i(TAG, "task=${spec.taskId} 启动 aria2c: conns=$conns " +
            "limit=${spec.speedLimitBytesPerSec} headers=${spec.headers.keys}")

        val proc = try {
            ProcessBuilder(cmd)
                .also { it.environment()["HOME"] = context.filesDir.absolutePath }
                .start()
        } catch (e: Exception) {
            return DownloadEngineRunner.Outcome.Failed(
                "启动 aria2c 失败：${e.message ?: e.javaClass.simpleName}"
            )
        }

        // 必须持续读走输出：管道缓冲写满会阻塞 aria2。
        //
        // 【同时解析进度，这是必需的第二个通道】只用"轮询输出文件大小"会漏进度：
        // aria2 的数据先攒在内存缓冲里、且控制文件是周期性落盘，文件大小可能长时间不增长
        // → UI 上速度显示 0、进度卡住（实测 PC 端验证时 32MB 快速下载期间一次都没采到非零值）。
        // 这里解析 aria2 自己的进度摘要行（`[#gid 已完成/总长(百分比) ...]`），
        // 与文件大小**取较大值**，保证进度单调不倒退。
        val aria2DoneBytes = java.util.concurrent.atomic.AtomicLong(baseBytes)
        // 【正则必须与真实输出对齐】已踩过两次坑，这里把两种格式都覆盖：
        //
        //  aria2 1.37.0：`[#940138 65536B/67108864B(0%) CN:16 DL:95819B ETA:11m39s]`
        //      ↑ 数字带 `B` 后缀 —— 第一版写成 `(\d+)/(\d+)\(` 完全匹配不到（24 行 0 命中）。
        //
        //  aria2-next 2.8.3：`[#027130 [=====>    ]  26% 17825792B/67108864B CN:8 DL:17807984B]`
        //      ↑ **百分比挪到前面、进度条插在中间、末尾没有 `(`** ——
        //        只认 1.37 格式的正则对它 0 命中（实测 3/3 全丢），
        //        会导致进度永远显示 0%（因为文件大小那一路在 aria2 内存缓冲期间也是 0）。
        //
        // 故不再依赖"数字后紧跟 `(`"这个脆弱的锚点，改为直接匹配 `已完成/总长`：
        // 该组合在两种格式里都存在且语义一致。
        val progressPattern = Regex("""(\d+)B?/(\d+)B?""")
        val drain = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        Log.d(TAG, "[aria2] $line")
                        progressPattern.find(line)?.let { m ->
                            m.groupValues[1].toLongOrNull()?.let { done ->
                                aria2DoneBytes.updateAndGet { cur -> maxOf(cur, done) }
                            }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            var lastReported = baseBytes
            val lastWorkers = AtomicInteger(conns)
            while (proc.isAlive) {
                if (!spec.isActive()) {
                    Log.i(TAG, "task=${spec.taskId} 任务已取消，终止 aria2c")
                    proc.destroy()
                    if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
                    return DownloadEngineRunner.Outcome.Failed("已取消")
                }
                // 双通道取进度：文件实际大小 vs aria2 自报的已完成字节，取较大者。
                val fromFile = if (out.isFile) out.length() else 0L
                val now = maxOf(fromFile, aria2DoneBytes.get())
                if (now > lastReported) {
                    val delta = now - lastReported
                    lastReported = now
                    spec.onWorkers(lastWorkers.get())
                    spec.onBytes(delta, now)
                }
                withContext(Dispatchers.IO) { Thread.sleep(300) }
            }

            val code = proc.exitValue()
            val size = if (out.isFile) out.length() else 0L
            drain.join(500)

            // 退出码语义（官方手册）：0 成功；7 = 仍有未完成下载（Ctrl-C/TERM 退出）；
            // 其余见手册 EXIT STATUS。这里只认 0，并把常见码翻译成可读原因。
            return if (code == 0 && size > 0) {
                Log.i(TAG, "task=${spec.taskId} aria2c 完成，$size 字节")
                DownloadEngineRunner.Outcome.Completed(listOf(out))
            } else {
                DownloadEngineRunner.Outcome.Failed(
                    "aria2c 退出码 $code（${explainExitCode(code)}），产物 $size 字节"
                )
            }
        } finally {
            if (proc.isAlive) runCatching { proc.destroyForcibly() }
        }
    }

    /** 把 aria2 退出码翻译成可读原因（官方手册 EXIT STATUS 章节）。 */
    private fun explainExitCode(code: Int): String = when (code) {
        1 -> "未知错误"
        2 -> "超时"
        3 -> "资源未找到"
        5 -> "速度过低（低于 --lowest-speed-limit）"
        6 -> "网络问题"
        7 -> "仍有未完成的下载"
        8 -> "服务器不支持续传"
        9 -> "磁盘空间不足"
        11, 12 -> "同一文件已在下载中"
        13 -> "文件已存在"
        16, 17, 18 -> "文件创建/IO/建目录失败"
        19 -> "域名解析失败"
        23 -> "重定向过多"
        24 -> "HTTP 授权失败"
        28 -> "选项非法或参数越界（如 -x > 16）"
        29 -> "服务器过载或维护中"
        32 -> "校验和失败"
        else -> "见 aria2 手册 EXIT STATUS"
    }
}
