package com.yunget.app.data.network

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID

/**
 * Android 端 139 口令 XML 接口设备信息适配：
 * 从系统 API 读取厂商、型号、系统版本、分辨率和语言，并持久化安装期随机 ID。
 */
object AndroidC139DeviceInfo {
    private const val PREFS = "c139_device"
    private const val KEY_DEVICE_ID = "installation_id"

    @Volatile
    private var cachedDeviceId: String? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        C139DeviceInfo.installProvider { from(appContext) }
    }

    fun from(context: Context): C139DeviceInfo {
        val appContext = context.applicationContext
        val metrics = appContext.resources.displayMetrics
        val configuration = appContext.resources.configuration
        val locale = runCatching { configuration.locales[0] }.getOrDefault(Locale.getDefault())
        val osVersion = Build.VERSION.RELEASE.takeIf { it.isNotBlank() }
            ?: Build.VERSION.SDK_INT.toString()
        return C139DeviceInfo(
            appVersion = C139Constants.SHARE_CODE_APP_VERSION,
            manufacturer = C139DeviceInfo.sanitizeField(Build.MANUFACTURER.ifBlank { "Android" }),
            model = C139DeviceInfo.sanitizeField(Build.MODEL.ifBlank { "Android Device" }),
            deviceId = installationId(appContext),
            osVersion = C139DeviceInfo.sanitizeField(osVersion),
            screen = "${metrics.widthPixels}X${metrics.heightPixels}",
            language = C139DeviceInfo.sanitizeField(locale.language.ifBlank { "zh" }),
            channel = C139Constants.SHARE_CODE_APP_CHANNEL,
            mmSource = C139Constants.SHARE_CODE_MM_SOURCE
        )
    }

    private fun installationId(context: Context): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_DEVICE_ID, null)
                ?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            val value = saved ?: UUID.randomUUID().toString().replace("-", "")
            if (saved == null) prefs.edit { putString(KEY_DEVICE_ID, value) }
            cachedDeviceId = value
            return value
        }
    }
}

fun C139DeviceInfo.Companion.from(context: Context): C139DeviceInfo =
    AndroidC139DeviceInfo.from(context)
