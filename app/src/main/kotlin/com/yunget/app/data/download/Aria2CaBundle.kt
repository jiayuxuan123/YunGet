package com.yunget.app.data.download

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 为 aria2c 准备 CA 证书 bundle（Android 专属适配）。
 *
 * ## 为什么必须做
 *
 * aria2 是普通 Unix 命令行程序，默认去 `/etc/ssl/certs/ca-certificates.crt`
 * 之类的位置找根证书 —— **Android 上这些路径不存在**。结果所有 HTTPS 下载都会失败：
 *
 * ```
 * [SocketCore.cc:1022] errorCode=1
 * SSL/TLS handshake failure: unable to get local issuer certificate
 * ```
 *
 * 这不是猜测：2026-09-28 真机实测（OnePlus PJA110 / Android 16）aria2 下 GitHub
 * release 就是这么失败的，而**同一时刻 App 自己（OkHttp）下载同一链接正常**。
 * 官方 `README.android` 也明确写了这个坑与 workaround。
 *
 * ## 做法
 *
 * 把系统根证书目录下的所有 PEM 文件**拼接成一个 bundle**，用
 * `--ca-certificate=<bundle>` 指给 aria2（该参数支持单个文件内含多个证书）。
 *
 * 证书目录有两个（新系统主用 apex）：
 *  - `/apex/com.android.conscrypt/cacerts/`（Android 14+ 的更新通道，优先）
 *  - `/system/etc/security/cacerts/`
 *
 * ## 为什么按 mtime 缓存
 *
 * 149 个证书、每个约 1~2KB，全拼一次约 200KB 读取 —— 每次下载都做是浪费。
 * 用「目录 mtime + 文件数 + bundle 大小」作为缓存键，目录没变就不重建。
 *
 * ## 为什么要降级而不是抛错
 *
 * 取不到 CA 只能说明"aria2 无法验证 HTTPS"，不代表整个下载要失败：
 * 上层（LegacyDownloadManager）在 aria2 失败时会回退到内置单流下载。
 * 所以这里返回 null 即可，由调用方决定是否继续尝试。
 */
internal object Aria2CaBundle {

    private const val TAG = "YunGet-Aria2"

    /** 新系统的证书主目录（Android 14+ 由 apex 提供，优先读这个）。 */
    private val CERT_DIRS = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts",
    )

    /**
     * 确保 bundle 存在并返回其路径；无法构造时返回 null。
     *
     * @param cacheDir 应用私有目录（bundle 写在这里，避免污染用户可见空间）
     */
    fun ensure(context: Context, cacheDir: File): File? = runCatching {
        val srcDir = CERT_DIRS.map { File(it) }.firstOrNull { it.isDirectory && it.canRead() }
        if (srcDir == null) {
            Log.w(TAG, "找不到可读的系统证书目录（试过 ${CERT_DIRS.joinToString()}）")
            return null
        }
        val files = srcDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) {
            Log.w(TAG, "证书目录为空：$srcDir")
            return null
        }

        cacheDir.mkdirs()
        val bundle = File(cacheDir, "ca-bundle.pem")

        // 缓存键：源目录 mtime + 文件数。证书更新（系统升级）时目录 mtime 会变。
        val stamp = File(cacheDir, "ca-bundle.stamp")
        val key = "${srcDir.absolutePath}|${srcDir.lastModified()}|${files.size}"
        if (bundle.isFile && bundle.length() > 0L &&
            stamp.isFile && stamp.readText().trim() == key
        ) {
            return bundle
        }

        // 重建：把所有 PEM 顺序写入一个文件（aria2 的 --ca-certificate 支持多证书 PEM）
        var written = 0
        bundle.outputStream().buffered().use { out ->
            for (f in files) {
                runCatching {
                    val text = f.readText()
                    if (text.contains("-----BEGIN CERTIFICATE-----")) {
                        out.write(text.toByteArray())
                        if (!text.endsWith("\n")) out.write('\n'.code)
                        written++
                    }
                }
            }
        }
        if (written == 0) {
            runCatching { bundle.delete() }
            Log.w(TAG, "系统证书目录里没有可用 PEM：$srcDir")
            return null
        }
        runCatching { stamp.writeText(key) }
        Log.i(TAG, "CA bundle 已生成：$written 个证书，${bundle.length()} 字节 → ${bundle.absolutePath}")
        bundle
    }.getOrElse {
        Log.w(TAG, "构造 CA bundle 失败：${it.message}")
        null
    }
}
