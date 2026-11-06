package com.yunget.app.data.download

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.yunget.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Optional signed APK: Android installs its executable into an executable native library directory. */
object Aria2KernelProvisioner {
    const val PACKAGE = "com.yunget.kernel.aria2"
    const val ASSET = "YunGet-aria2-kernel-arm64-v8a.apk"
    private const val MAX_APK_BYTES = 64L * 1024 * 1024
    private val signatureFlags get() = if (Build.VERSION.SDK_INT >= 28)
        PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun trusted(context: Context, info: PackageInfo): Boolean {
        val own = context.packageManager.getPackageInfo(context.packageName, signatureFlags)
        val expected = signatures(own)
        return expected.isNotEmpty() && info.packageName == PACKAGE && signatures(info) == expected
    }

    @Suppress("DEPRECATION")
    fun installedBinary(context: Context): File? = runCatching {
        val info = context.packageManager.getPackageInfo(PACKAGE, signatureFlags)
        if (!trusted(context, info)) return null
        val directory = info.applicationInfo?.nativeLibraryDir ?: return null
        File(directory, Aria2Executor.LIB_NAME).takeIf { it.isFile && it.canExecute() }
    }.getOrNull()

    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        check(Aria2Executor.isSupportedAbi()) { "aria2 可选内核目前仅支持 arm64-v8a" }
        val client = HttpClients.apiClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.MINUTES).build()
        val release = client.newCall(Request.Builder()
            .url("https://api.github.com/repos/jiayuxuan123/YunGet/releases/latest")
            .header("Accept", "application/vnd.github+json").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("内核版本查询失败：HTTP ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("版本响应为空"))
        }
        val assets = release.optJSONArray("assets") ?: throw IOException("发布中没有可选内核")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == ASSET }
            ?: throw IOException("当前发布还未提供 aria2 可选内核，请安装附带内核的新版本或手动导入内核 APK")
        val url = asset.getString("browser_download_url")
        require(url.startsWith("https://github.com/jiayuxuan123/YunGet/releases/download/")) { "内核来源无效" }
        val directory = File(context.cacheDir, "kernels").apply { mkdirs() }
        val partial = File(directory, "$ASSET.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("内核下载失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("内核响应为空")
                val expected = asset.getLong("size")
                require(expected in 1..MAX_APK_BYTES) { "内核包体积异常" }
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > expected) throw IOException("内核包超过预期大小")
                        output.write(buffer, 0, count)
                        onProgress(copied, expected)
                    }
                    if (copied != expected) throw IOException("内核包下载不完整")
                } }
            }
            validateArchive(context, partial)
            val target = File(directory, ASSET)
            if (target.exists() && !target.delete()) throw IOException("无法更新内核安装包")
            if (!partial.renameTo(target)) throw IOException("无法保存内核安装包")
            target
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    fun validateArchive(context: Context, file: File) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags)
            ?: throw IOException("不是有效的内核 APK")
        require(trusted(context, info)) { "内核包名或签名不匹配，已拒绝安装" }
        java.util.zip.ZipFile(file).use { zip ->
            require(zip.getEntry("lib/arm64-v8a/${Aria2Executor.LIB_NAME}") != null) { "内核缺少 arm64 二进制" }
        }
    }

    fun install(context: Context, apk: File) {
        validateArchive(context, apk)
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
