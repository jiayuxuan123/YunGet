package com.yunget.app.data.network

import android.content.Context

/**
 * Android 端迅雷设备指纹持久化适配（SharedPreferences）。
 * 键名与历史实现完全一致，升级后沿用已生成指纹，不触发风控重算。
 */
class AndroidXunleiDeviceFingerprintStore(context: Context) : XunleiDeviceFingerprintStore {

    private val appContext = context.applicationContext

    override fun load(): XunleiDeviceIdentity? {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_ID, null) ?: return null
        return XunleiDeviceIdentity(
            deviceId = savedId,
            peerId = prefs.getString(KEY_PEER, XunleiConstants.PEER_ID) ?: XunleiConstants.PEER_ID,
            deviceSign = prefs.getString(KEY_SIGN, XunleiConstants.DEVICE_SIGN) ?: XunleiConstants.DEVICE_SIGN
        )
    }

    override fun save(identity: XunleiDeviceIdentity) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ID, identity.deviceId)
            .putString(KEY_PEER, identity.peerId)
            .putString(KEY_SIGN, identity.deviceSign)
            .apply()
    }

    companion object {
        private const val PREFS = "xunlei_device_fp"
        private const val KEY_ID = "device_id"
        private const val KEY_PEER = "peer_id"
        private const val KEY_SIGN = "device_sign"
    }
}

fun XunleiDeviceFingerprint.init(context: Context) {
    init(AndroidXunleiDeviceFingerprintStore(context))
}
