package com.yunget.app.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide diagnostic capture that does not depend on individual feature call sites. */
object DiagnosticRuntime {
    private const val LOGCAT_FILE = "runtime-logcat.log"
    private const val MAX_LOGCAT_BYTES = 2L * 1024 * 1024
    private const val RETAIN_LOGCAT_BYTES = 1024 * 1024
    private val installed = AtomicBoolean(false)
    private val writeLock = Any()

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        Thread({
            runCatching { collectLogcat(context.applicationContext) }
                .onFailure { DiagLog.w(context, DiagLog.Category.BOOT, "常驻 logcat 采集不可用：${it.message}") }
        }, "yunget-logcat").apply {
            isDaemon = true
            start()
        }
    }

    private fun collectLogcat(context: Context) {
        val process = ProcessBuilder(
            "logcat", "-v", "threadtime", "--pid=${android.os.Process.myPid()}",
        ).redirectErrorStream(true).start()
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        val output = File(dir, LOGCAT_FILE)
        if (output.isFile && output.length() > MAX_LOGCAT_BYTES) {
            val tail = output.readText().takeLast(RETAIN_LOGCAT_BYTES)
            output.writeText("…(常驻 logcat 已滚动)…\n$tail")
        }
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            OutputStreamWriter(FileOutputStream(output, true), Charsets.UTF_8).use { writer ->
                var line: String?
                var written = 0
                while (reader.readLine().also { line = it } != null) {
                    append(writer, line.orEmpty())
                    written++
                    // Bound a long-lived process too, not only the next start.
                    // Once the cap is reached the file stays complete and useful;
                    // a later process start rotates it before appending again.
                    if (written and 0x3ff == 0 && output.length() >= MAX_LOGCAT_BYTES) {
                        writer.write("...(常驻 logcat 已达到 2 MiB 上限，本次进程停止追加)...\n")
                        writer.flush()
                        break
                    }
                }
            }
        }
    }

    private fun append(writer: Writer, line: String) {
        synchronized(writeLock) {
            writer.write(DiagLog.redact(line))
            writer.write("\n")
            writer.flush()
        }
    }

    fun appendSnapshot(writer: Writer, context: Context) {
        val runtime = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        writer.write("========== 自动运行状态快照 ==========\n")
        writer.write("进程：pid=${android.os.Process.myPid()}，线程=${Thread.activeCount()}\n")
        writer.write("Java 堆：max=${runtime.maxMemory()} used=${runtime.totalMemory() - runtime.freeMemory()}\n")
        writer.write("Native 堆：${Debug.getNativeHeapAllocatedSize()}\n")
        writer.write("系统内存：available=${memory.availMem} total=${memory.totalMem} low=${memory.lowMemory}\n")
        writer.write("线程：${Thread.getAllStackTraces().size}\n")
        writer.write("ABIs：${Build.SUPPORTED_ABIS.joinToString()}\n")
        writer.write("构建：${Build.DISPLAY} / ${Build.FINGERPRINT}\n")
        val metrics = context.resources.displayMetrics
        writer.write("显示：${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density} dpi=${metrics.densityDpi}\n")
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        writer.write("省电模式：${pm?.isPowerSaveMode}\n")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val capabilities = network?.let(cm::getNetworkCapabilities)
        writer.write("网络：validated=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "wifi=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
            "cellular=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
            "metered=${cm?.isActiveNetworkMetered}\n")
        writer.write("filesDir=${space(context.filesDir)}\n")
        writer.write("cacheDir=${space(context.cacheDir)}\n")
        writer.write("\n权限：\n")
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.sorted()?.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            writer.write("${if (granted) "granted" else "denied"}  $permission\n")
        }
        writer.write("\n偏好键（敏感值不导出）：\n")
        context.filesDir.parentFile?.let { dataDir ->
            File(dataDir, "shared_prefs").listFiles { file -> file.extension == "xml" }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val keys = runCatching {
                        context.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE).all.keys
                    }.getOrDefault(emptySet<String>())
                    writer.write("${file.name}: ${keys.sorted().joinToString()}\n")
                }
        }
        writer.write("\n线程状态：\n")
        Thread.getAllStackTraces().entries
            .filter { it.key.name.startsWith("yunget") || it.key.name.contains("OkHttp") || it.key.name.contains("DefaultDispatcher") }
            .sortedBy { it.key.name }
            .take(200)
            .forEach { (thread, stack) ->
                writer.write("${thread.name} [${thread.state}] alive=${thread.isAlive} daemon=${thread.isDaemon}\n")
                stack.take(20).forEach { writer.write("  at $it\n") }
            }
        writer.write("快照时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}\n\n")
    }

    private fun space(file: File): String = runCatching {
        val stat = StatFs(file.absolutePath)
        "total=${stat.blockCountLong * stat.blockSizeLong} free=${stat.availableBlocksLong * stat.blockSizeLong}"
    }.getOrDefault("unavailable")
}
