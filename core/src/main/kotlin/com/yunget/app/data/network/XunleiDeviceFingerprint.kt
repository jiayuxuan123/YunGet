/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.network

import java.security.MessageDigest
import kotlin.random.Random

/** 迅雷设备指纹三元组（一次生成、长期持久化复用）。 */
data class XunleiDeviceIdentity(
    val deviceId: String,
    val peerId: String,
    val deviceSign: String
)

/** 宿主持久化接口：Android 端落盘到 SharedPreferences，桌面端可落盘或使用内存默认实现。 */
interface XunleiDeviceFingerprintStore {
    fun load(): XunleiDeviceIdentity?
    fun save(identity: XunleiDeviceIdentity)
}

/** 纯 JVM 默认存储（进程内复用，供桌面端或测试直接使用）。 */
class InMemoryXunleiDeviceFingerprintStore(
    private var cached: XunleiDeviceIdentity? = null
) : XunleiDeviceFingerprintStore {
    override fun load(): XunleiDeviceIdentity? = cached
    override fun save(identity: XunleiDeviceIdentity) {
        cached = identity
    }
}

/**
 * 迅雷设备指纹管理器（动态生成 + 持久化）：
 * - 每台设备首次启动生成唯一 deviceId/peerId/devicesign，此后永久复用（进程重启不变）；
 * - devicesign 按 §8 公式：div101.{deviceId}{md5(sha1(deviceId + package + appid + appkey))}；
 * - 未初始化（异常路径）时回退到 XunleiConstants 官方抓包指纹，保证行为不崩。
 *
 * 目的：开源分发后每台设备独立指纹，避免所有用户共享一个官方指纹被迅雷风控识别/连带封禁。
 */
object XunleiDeviceFingerprint {

    // devicesign 计算常量（与文档 §8 / alist 一致）
    private const val PACKAGE_NAME = "com.xunlei.downloadprovider"
    private const val APPID = "40"
    private const val APP_KEY = "34a062aaa22f906fca4fefe9fb3a3021"
    private const val HEX = "0123456789abcdef"

    @Volatile
    private var initialized = false

    // 未初始化时的 fallback：官方抓包真实设备（保持旧行为，绝不崩）
    @Volatile
    private var deviceId: String = XunleiConstants.DEVICE_ID
    @Volatile
    private var peerId: String = XunleiConstants.PEER_ID
    @Volatile
    private var deviceSign: String = XunleiConstants.DEVICE_SIGN

    /** 进程启动时调用一次（Android 在 Application.onCreate 注入持久化实现）；幂等，可重复调用 */
    fun init(store: XunleiDeviceFingerprintStore = InMemoryXunleiDeviceFingerprintStore()) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val identity = store.load() ?: generate().also { store.save(it) }
            deviceId = identity.deviceId
            peerId = identity.peerId
            deviceSign = identity.deviceSign
            initialized = true
        }
    }

    /** 生成一组新的随机设备指纹（纯函数，不改全局状态）。 */
    fun generate(): XunleiDeviceIdentity {
        val newId = randomHex(32)
        val newPeer = randomHex(32)
        return XunleiDeviceIdentity(
            deviceId = newId,
            peerId = newPeer,
            deviceSign = buildDeviceSign(newId)
        )
    }

    internal fun resetForTest() {
        synchronized(this) {
            initialized = false
            deviceId = XunleiConstants.DEVICE_ID
            peerId = XunleiConstants.PEER_ID
            deviceSign = XunleiConstants.DEVICE_SIGN
        }
    }

    fun deviceId(): String = deviceId

    fun peerId(): String = peerId

    fun deviceSign(): String = deviceSign

    /** devicesign：div101.{deviceId}{md5(sha1(deviceId + package_name + appid + app_key))} */
    fun buildDeviceSign(id: String): String {
        val base = id + PACKAGE_NAME + APPID + APP_KEY
        val sha1 = sha1Hex(base)
        val md5 = md5Hex(sha1)
        return "div101.$id$md5"
    }

    private fun randomHex(len: Int): String = buildString {
        repeat(len) { append(HEX[Random.nextInt(16)]) }
    }

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
