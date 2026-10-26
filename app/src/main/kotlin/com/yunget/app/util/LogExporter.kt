package com.yunget.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具：
 * 1. 头部写入应用 / 设备信息；
 * 2. `logcat -d -v time --pid=<当前进程>` dump 当前应用的运行日志（按包名进程过滤）；
 * 3. 合并写入 cacheDir/logs/ 下文本文件，通过 FileProvider + 系统分享导出。
 */
object LogExporter {

    private const val EXPORT_DIR = "logs"

    /** 单缓冲区最多保留的行数（防止超大 buffer 导致内存/文件过大） */
    private const val MAX_LINES = 30000

    /** 导出时带上的崩溃报告份数上限（新的在前）。 */
    private const val MAX_CRASH_REPORTS = 3

    /** 单份崩溃报告保留的字节数：堆栈超长时截断（根因异常段在文件开头，保头不保尾）。 */
    private const val MAX_CRASH_REPORT_BYTES = 16 * 1024

    /** 生成日志文件（cacheDir 内）并返回；失败返回 null（不抛异常） */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val out = File(dir, "yunget_log_${timestamp()}.txt")
        FileOutputStream(out).use { exportTo(context, it) }
        out
    }.getOrNull()

    /**
     * 直接保存日志到公共「下载」目录；成功返回 true。
     * - Android 10+（API 29+）：MediaStore.Downloads 直写，无需任何权限；
     * - Android 9-（API 21-28）：写公共 Download 目录（需 WRITE_EXTERNAL_STORAGE）。
     * 不经过 FileProvider / 跨进程分享，彻底规避「保存到下载」时系统 UI 读取 uri 被拒的问题。
     */
    fun saveToDownloads(context: Context): Boolean = runCatching {
        val fileName = "yunget_log_${timestamp()}.txt"
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                exportTo(context, out)
            } ?: false
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { out -> exportTo(context, out) }
        }
        ok
    }.getOrDefault(false)

    /** 把头部信息 + logcat 运行/崩溃日志写入指定输出流 */
    private fun exportTo(context: Context, output: OutputStream): Boolean = runCatching {
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            // ---------- 头部：应用与设备信息 ----------
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            writer.write("云取（YunGet）日志导出\n")
            writer.write("导出时间：${now()}\n")
            writer.write("应用版本：${pkg.versionName}（${pkg.versionCode}）\n")
            writer.write("设备：${Build.MANUFACTURER} ${Build.MODEL}\n")
            writer.write("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
            writer.write("\n")

            writer.write("\n")
            writer.write("========== 错误汇总 ==========\n")
            // 错误单独放最前面：用户说"有问题"时，这一节是第一个该看的。
            // 旧版所有级别混在一个文件里，找错误要通读全文 —— 这次日志升级要解决的主要问题。
            val errors = DiagLog.readErrors(context)
            if (errors.isNullOrBlank()) {
                writer.write("（没有 ERROR 级别的记录）\n")
            } else {
                writer.write(errors)
            }

            // ---------- 崩溃报告：紧跟错误汇总 ----------
            // 崩溃正文原本只在 filesDir/crash/ 里，「导出日志」对它一无所知 ——
            // 用户报"闪退"时附来的日志里没有崩溃现场，等于白导。
            writer.write("\n")
            writer.write("========== 崩溃报告（filesDir/crash，最近 3 份）==========\n")
            val crashes = recentCrashReports(context)
            if (crashes.isEmpty()) {
                writer.write("（没有崩溃记录）\n")
            } else {
                crashes.forEachIndexed { i, c ->
                    writer.write("\n----- 崩溃 ${i + 1}/${crashes.size} -----\n")
                    writer.write(c)
                    writer.write("\n")
                }
            }

            writer.write("\n")
            writer.write("========== 分类诊断日志 ==========\n")
            writer.write("（按子系统分段：启动 / 网络 / 下载 / 解析 / 账号 / 插件 / 存储 / 界面 / 更新）\n")
            writer.write("（每行格式：时间 [级别] [分类] 消息；级别 V/D/I/W/E，可直接搜 \" [E] \"）\n")
            val diag = DiagLog.readAll(context)
            if (diag.isNullOrBlank()) {
                writer.write("（暂无。跑过一次下载/解析/开关插件后即会有内容）\n")
            } else {
                writer.write(diag)
            }

            // ---------- 结构化诊断（DiagnosticLog，诊断模式开启时才有） ----------
            //
            // 与上面 DiagLog 的"分类日志"是两套：
            //  - DiagLog：面向用户的简短中文描述，按子系统分段；
            //  - DiagnosticLog：上游移植过来的结构化事件（module/event/字段），
            //    网络请求、WebView、数据库操作都会记进来。
            //
            // 两套都要带上 —— 用户导出日志时不该因为"某一套没同步"而缺半边信息。
            writer.write("\n")
            writer.write("========== 结构化诊断（DiagnosticLog）==========\n")
            val structured = runCatching {
                com.yunget.app.util.DiagnosticLog.readForExport(context)
            }.getOrNull()
            if (structured.isNullOrBlank()) {
                writer.write("（未启用诊断模式，或还没有记录）\n")
                writer.write("（开启方式：设置 → 关于云取 → 长按进入开发菜单 → 诊断模式）\n")
            } else {
                writer.write(structured)
            }

            // ---------- 运行日志：当前应用进程（logcat 按 pid 过滤，只保留本应用） ----------
            // 注意：这一段**放在落盘日志之后**。实测在 OnePlus/ColorOS/Android 16 上，
            // logcat 的环形缓冲只保留数秒，用户按提示操作时这里往往一行本应用日志都没有
            // （全是 VRI/BufferQueue 等系统噪声）。把可靠的那段放前面，避免打开文件先看到一堆噪声。
            writer.write("\n")
            writer.write("========== 运行日志（logcat -d -v time --pid=${Process.myPid()}）==========\n")
            writer.write("（提示：本段依赖系统日志缓冲，新系统上可能只覆盖最近几秒；\n")
            writer.write("  请以上面的落盘日志为准）\n")
            dumpLogcat(
                writer,
                listOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
            )
        }
        true
    }.getOrDefault(false)

    /** 清空 logcat 缓冲（便于复现后只导出本次操作日志） */
    fun clearLogcat(): Boolean = runCatching {
        ProcessBuilder("logcat", "-c").start().waitFor()
        true
    }.getOrDefault(false)

    /**
     * 最近的崩溃报告（新的在前）。
     *
     * 文件名 `crash_<millis>.txt`（[com.yunget.app.crash.CrashHandler] 生成），
     * 按文件名倒序即按时间倒序。超长报告只保留**开头**：崩溃文件的第一段就是
     * 异常类型与堆栈顶部，那是定位根因的部分。
     */
    private fun recentCrashReports(context: Context): List<String> = runCatching {
        val dir = File(context.filesDir, "crash")
        dir.listFiles { f: File -> f.isFile && f.name.startsWith("crash_") }
            ?.sortedByDescending { it.name }
            ?.take(MAX_CRASH_REPORTS)
            ?.map { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                if (text.length <= MAX_CRASH_REPORT_BYTES) text
                else text.take(MAX_CRASH_REPORT_BYTES) + "\n…（已截断，完整内容见 ${f.name}）"
            }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** 执行 logcat 命令并写入 writer（仅保留最近 MAX_LINES 行） */
    private fun dumpLogcat(writer: OutputStreamWriter, command: List<String>) {
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val reader =
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))

            // 环形缓冲：只保留最近 MAX_LINES 行
            val lines = ArrayDeque<String>()
            var line: String? = reader.readLine()
            while (line != null) {
                lines.addLast(line)
                if (lines.size > MAX_LINES) lines.removeFirst()
                line = reader.readLine()
            }
            process.waitFor()

            if (lines.isEmpty()) {
                writer.write("（无输出）\n")
            } else {
                lines.forEach { writer.write(it); writer.write("\n") }
            }
        } catch (e: Exception) {
            writer.write("（读取日志失败：${e.message}）\n")
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** 通过系统分享导出日志文件；成功返回 true */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // chooser 外层也带上读权限 flag：部分接收者（文件管理器/系统 UI）通过
        // 自己的 Intent 读取 uri 时需要授权，否则报 Permission Denial
        val chooser = Intent.createChooser(intent, "分享日志").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        true
    }.getOrElse {
        false
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}