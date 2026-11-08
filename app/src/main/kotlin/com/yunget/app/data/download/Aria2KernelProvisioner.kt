package com.yunget.app.data.download

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.data.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Optional signed APK: Android installs its executable into an executable native library directory. */
object Aria2KernelProvisioner {
    const val PACKAGE = "com.yunget.kernel.aria2"
    const val ASSET = Aria2KernelDownload.NAME
    private val downloadMutex = Mutex()
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

    suspend fun download(
        context: Context,
        onStatus: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): File = downloadMutex.withLock { withContext(Dispatchers.IO) {
        check(Aria2Executor.isSupportedAbi()) { "aria2 可选内核目前仅支持 arm64-v8a" }
        val client = HttpClients.apiClient().newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build()
        val bundled = context.assets.open("aria2-kernel-release.json").bufferedReader().use { it.readText() }
        Aria2KernelDownload(client).download(
            directory = File(context.cacheDir, "kernels"),
            mirrorPrefix = SettingsRepository(context).githubMirrorPrefix?.takeIf { it.isNotBlank() }
                ?: UpdateChecker.MIRROR_PREFIX,
            bundledMetadata = bundled,
            onStatus = onStatus,
            onProgress = onProgress,
            validate = { validateArchive(context, it) },
        )
    } }

    @Suppress("DEPRECATION")
    fun validateArchive(context: Context, file: File) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags)
            ?: throw IOException("不是有效的内核 APK")
        require(trusted(context, info)) { "内核包名或签名不匹配，已拒绝安装" }
        java.util.zip.ZipFile(file).use { zip ->
            require(zip.getEntry("lib/arm64-v8a/${Aria2Executor.LIB_NAME}") != null) { "内核缺少 arm64 二进制" }
        }
    }

    /** Returns false when the user must grant installation permission and tap install again. */
    fun install(context: Context, apk: File): Boolean {
        validateArchive(context, apk)
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
