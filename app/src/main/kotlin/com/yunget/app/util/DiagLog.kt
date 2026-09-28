package com.yunget.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关键诊断日志的**落盘**通道。
 *
 * ## 为什么不能只靠 logcat
 *
 * 原先宿主的"导出日志"用 `logcat -d --pid=<本进程>` 读系统环形缓冲。
 * 但在高性能设备 + 新版 Android（实测 OnePlus / ColorOS / Android 16）上，
 * **系统日志刷新极快，主缓冲只保留了几秒**：
 * 用户按提示"点下载 → 导出日志"时，应用自己那几行关键日志已经被挤掉了 ——
 * 实测导出文件里全是 `VRI`/`BufferQueue`/`OplusScrollToTopManager` 噪声，
 * 一行 `YunGet-DL` 都没有，时间跨度仅 5 秒。
 *
 * 结果就是：**我打印了日志 ≠ 用户能把它交给我**（同类事故已发生过一次：
 * 诊断结果只在弹窗里、无法复制/分享）。
 *
 * ## 做法
 *
 * 把**需要用户回传的关键行**同时写进应用私有文件 `filesDir/diag/diag.log`：
 * - 落盘不受系统日志缓冲影响，重启进程也还在；
 * - 容量上限（[MAX_BYTES]）自动截半，不会无限增长；
 * - [LogExporter] 导出时会把该文件**整份附在末尾**，用户一次导出就能带全。
 *
 * 只用于"诊断/取证"这类必须回传的行，**不是**通用日志：普通日志仍走 [Log]，
 * 避免把 IO 写放大到每次进度回调。
 */
object DiagLog {

    private const val TAG = "YunGet-Diag"

    /** 单文件上限：超出后保留后半（丢弃最旧一半）。1MB 足够容纳数万行诊断记录。 */
    private const val MAX_BYTES = 1L * 1024 * 1024

    private const val DIR = "diag"
    private const val FILE = "diag.log"

    /** 进程内串行化写入，避免多任务并发写乱序/交错。 */
    private val lock = Any()

    fun file(context: Context): File = File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    /**
     * 记录一行关键诊断（同时进 logcat 与落盘文件）。
     *
     * @param tag 逻辑标签（如 "启动阶段"），会原样出现在文件里，便于回传后检索。
     */
    fun i(context: Context, tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        append(context, tag, message)
    }

    private fun append(context: Context, tag: String, message: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "$ts [$tag] $message\n"
        runCatching {
            synchronized(lock) {
                val f = file(context)
                if (f.length() > MAX_BYTES) {
                    // 超限：保留后半，丢弃最旧一半（避免"最老的行把新的挤没"）
                    val kept = f.readText().takeLast((MAX_BYTES / 2).toInt())
                    f.writeText("…(已截断旧日志)…\n" + kept)
                }
                f.appendText(line)
            }
        }.onFailure {
            // 落盘失败绝不影响主流程，但要留痕（否则"以为写了其实没写"）
            Log.w(TAG, "诊断日志落盘失败：${it.message}")
        }
    }

    /** 读取全部诊断日志（供 LogExporter 附加到导出文件末尾）；不存在返回 null。 */
    fun readAll(context: Context): String? = runCatching {
        val f = file(context)
        if (f.isFile && f.length() > 0) f.readText() else null
    }.getOrNull()

    /** 清空诊断日志（用户可主动清理）。 */
    fun clear(context: Context): Boolean = runCatching {
        synchronized(lock) { file(context).delete() }
        true
    }.getOrDefault(false)
}
