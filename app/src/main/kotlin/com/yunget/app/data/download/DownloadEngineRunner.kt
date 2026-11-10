package com.yunget.app.data.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

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
 * - 进度与工作连接数读取内核 readout；文件逻辑长度仅用于退出后的产物检查；
 * - 取消靠 `isActive()` 检查 + `destroy()` 杀子进程；
 * - 同时必须**持续读走 stdout/stderr**，否则管道缓冲（约 64KB）写满会把 aria2 阻塞住。
 *
 * ## 参数映射与已知限制
 *
 * - 使用随可选包提供的 aria2-next `--stream-max-connections`（1..256）；
 *   默认仍由设置提供 16，用户选择更高值时完整传递，分片粒度由该内核管理。
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
         * 默认连接数在 SettingsRepository 中保持 16；显式选择的值应传给内核，
         * 不能再在执行器里把 32/64/128/256 悄悄截成 16。
         */
        const val MAX_CONN_PER_SERVER = Aria2CommandBuilder.MAX_CONNECTIONS
    }

    override suspend fun run(spec: DownloadEngineRunner.RunSpec): DownloadEngineRunner.Outcome {
        val bin = Aria2Executor.binaryPath(context)
            ?: return DownloadEngineRunner.Outcome.Failed(
                "aria2c 不可用（${Aria2Executor.probe(context).detail}）"
            )

        val out = File(spec.chunkDir, OUT_NAME)
        spec.chunkDir.mkdirs()

        val conns = spec.targetWorkers.coerceIn(1, MAX_CONN_PER_SERVER)
        // HTTPS 必需的 CA bundle（Android 上没有 aria2 默认查找的路径）。见下方注释。
        val caBundle = Aria2CaBundle.ensure(context, File(context.cacheDir, "aria2"))
        val cmd = Aria2CommandBuilder.build(bin, caBundle, spec)

        Log.i(TAG, "task=${spec.taskId} 启动 aria2c: requestedConns=${spec.targetWorkers} effectiveConns=$conns " +
            "limit=${spec.speedLimitBytesPerSec} headers=${spec.headers.keys}")

        val proc = try {
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .also { it.environment()["HOME"] = context.filesDir.absolutePath }
                .start()
        } catch (e: Exception) {
            return DownloadEngineRunner.Outcome.Failed(
                "启动 aria2c 失败：${e.message ?: e.javaClass.simpleName}"
            )
        }

        // Both streams are merged above. Drain them continuously; only readout frames carry progress.
        val progress = Aria2ProgressMonitor()
        val drain = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        Log.d(TAG, "[aria2] ${com.yunget.app.util.LogRedactor.line(line).take(1000)}")
                        progress.accept(line)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            var lastWorkers = 0
            spec.onWorkers(0)
            while (proc.isAlive) {
                if (!spec.isActive()) {
                    Log.i(TAG, "task=${spec.taskId} 任务已取消，终止 aria2c")
                    proc.destroy()
                    if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
                    return DownloadEngineRunner.Outcome.Failed("已取消")
                }
                progress.poll()?.let { sample ->
                    if (sample.connections != lastWorkers) {
                        lastWorkers = sample.connections
                        spec.onWorkers(sample.connections)
                    }
                    if (sample.initial || sample.delta > 0) spec.onBytes(sample.delta, sample.completed)
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
            runCatching { proc.inputStream.close() }
            drain.join(500)
            spec.onWorkers(0)
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
        28 -> "选项非法或参数越界"
        29 -> "服务器过载或维护中"
        32 -> "校验和失败"
        else -> "见 aria2 手册 EXIT STATUS"
    }
}
