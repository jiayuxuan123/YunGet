package com.yunget.app.data.download

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * aria2c 原生可执行文件的定位与探测（Android）。
 *
 * ## 可行性的三条硬前提（都已核实）
 *
 * 1. **二进制放在 `jniLibs/arm64-v8a/` 并命名为 `lib*.so`**：AGP 只把符合该命名的文件
 *    打进 `lib/<abi>/`；安装后位于 `applicationInfo.nativeLibraryDir`。
 * 2. **`packaging.jniLibs.useLegacyPackaging = true`**（见 app/build.gradle.kts）：
 *    否则 `nativeLibraryDir` 是空目录，`exec()` 必然 `ENOENT`。
 * 3. **该目录属于 `apk_data_file` 而非 `app_data_file`**：Android 10+（API 29）起
 *    SELinux 不再允许应用执行自己的私有数据目录，但 `nativeLibraryDir` 仍允许执行 ——
 *    这也是"把可执行文件命名成 lib*.so 放 jniLibs"这一通行做法的依据。
 *
 * ⚠️ 这是**平台未承诺的灰色地带**，Google 近年持续收紧（API 29 的 W^X 等）。
 * 因此本类只做"能否用"的判定，且 [Aria2Executor.isAvailable] 为 false 时
 * 上层必须能优雅回退到 TurboDL 内核，绝不把 aria2 作为唯一路径。
 *
 * ## 官方二进制的事实（1.37.0 aarch64-linux-android-build1）
 *
 * - ELF64 AArch64、`PT_INTERP = /system/bin/linker64`、类型 DYN(PIE)；
 * - `DT_NEEDED` 仅 `libm.so` / `libdl.so` / `libc.so`（全是系统库）——
 *   openssl/expat/zlib/c-ares/libssh2 **全部静态链接**，故**只需这 1 个文件**；
 * - 无 RUNPATH 依赖（不像 Termux 版需要 patchelf 改路径）。
 */
object Aria2Executor {

    private const val TAG = "YunGet-Aria2"

    /** jniLibs 里的文件名（必须以 lib 开头、.so 结尾，AGP 才会打包）。 */
    const val LIB_NAME = "libaria2c.so"

    /**
     * 可执行文件路径；不存在返回 null。
     *
     * 只在 arm64 设备上返回路径：官方仅提供 aarch64 构建，
     * 其他 ABI 上即便文件存在也无法执行（`ENOEXEC`），不如直接判为不可用。
     */
    fun binaryPath(context: Context): File? {
        if (!isSupportedAbi()) return null
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, LIB_NAME)
        return f.takeIf { it.isFile }
    }

    /** 当前设备 ABI 是否能跑官方 aria2（只提供 aarch64）。 */
    fun isSupportedAbi(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    /**
     * 探测可执行文件能否真正运行：执行 `--version` 并读回输出。
     *
     * @return 成功时返回版本首行；失败时返回**可读的失败原因**（含 errno 语义），
     *   供设置页/日志直接展示 —— 这样"为什么用不了 aria2"不用猜。
     */
    fun probe(context: Context): Aria2ProbeResult {
        if (!isSupportedAbi()) {
            return Aria2ProbeResult(
                available = false,
                detail = "设备非 arm64（当前 ABI：${Build.SUPPORTED_ABIS.joinToString()}）" +
                    "，官方 aria2 仅提供 aarch64 构建"
            )
        }
        val bin = binaryPath(context)
            ?: return Aria2ProbeResult(
                available = false,
                detail = "未找到 $LIB_NAME（nativeLibraryDir=" +
                    "${context.applicationInfo.nativeLibraryDir}）；" +
                    "若 APK 是用 useLegacyPackaging=false 打包的，该目录为空 → 必然 ENOENT"
            )

        // 先尝试补可执行位（部分设备安装后 lib 无 x 位）。
        val execBitSet = runCatching { bin.setExecutable(true, false) }.getOrDefault(false)

        return try {
            val pb = ProcessBuilder(bin.absolutePath, "--version")
                .redirectErrorStream(true)
            // HOME 未定义时 aria2 会尝试读 ~/.aria2/aria2.conf；用应用私有目录兜底。
            pb.environment()["HOME"] = context.filesDir.absolutePath
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val finished = proc.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                Aria2ProbeResult(false, "aria2c --version 超时（12s），已强制结束")
            } else if (proc.exitValue() == 0) {
                val firstLine = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?: "(无输出)"
                Log.i(TAG, "aria2 可用：$firstLine (execBitSet=$execBitSet)")
                Aria2ProbeResult(true, firstLine)
            } else {
                Aria2ProbeResult(
                    false,
                    "aria2c --version 退出码 ${proc.exitValue()}；输出：${out.take(300)}"
                )
            }
        } catch (e: Exception) {
            // 这里最需要说清 errno 语义：ENOENT / EACCES / ENOEXEC 对应的原因完全不同。
            val msg = e.message ?: e.javaClass.simpleName
            val hint = when {
                msg.contains("ENOENT", true) || msg.contains("No such file", true) ->
                    "文件不存在（大概率是 useLegacyPackaging=false 导致 so 未落盘）"
                msg.contains("EACCES", true) || msg.contains("Permission denied", true) ||
                    msg.contains("error=13", true) ->
                    "无执行权限（Android 10+ 对私有目录禁 exec；nativeLibraryDir 理论允许，" +
                        "若仍被拒是 SELinux/app_data_file 限制）"
                msg.contains("ENOEXEC", true) || msg.contains("Exec format error", true) ->
                    "格式错误（ABI 不匹配：该二进制是 aarch64）"
                else -> "未知错误"
            }
            Log.w(TAG, "aria2 探测失败：$msg（$hint）")
            Aria2ProbeResult(false, "$msg（$hint）")
        }
    }
}

/** aria2 可用性探测结果。 */
data class Aria2ProbeResult(
    val available: Boolean,
    /** 成功时为版本首行；失败时为可读原因（含 errno 语义）。 */
    val detail: String,
)
