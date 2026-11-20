package com.yunget.app.data.network

import java.net.URI

enum class OfficialWebDrive(
    val id: String,
    val displayName: String,
    val entryUrl: String,
    private val domains: Set<String>,
) {
    CTFILE("ctfile", "城通网盘", "https://www.ctfile.com/login", setOf(
        "ctfile.com", "ctfile.net", "ctfile.cc", "ctfile.cn", "545c.com",
    )),
    ALIYUN("aliyun", "阿里云盘", "https://www.alipan.com/sign/in", setOf("alipan.com", "aliyundrive.com")),
    TIANYI("tianyi", "天翼云盘", "https://cloud.189.cn/web/login.html", setOf("cloud.189.cn")),
    WEIYUN("weiyun", "腾讯微云", "https://www.weiyun.com/", setOf("weiyun.com")),
    WOPAN("wopan", "联通沃盘", "https://pan.wo.cn/login", setOf("pan.wo.cn")),
    PIKPAK("pikpak", "PikPak", "https://mypikpak.com/drive/all", setOf("mypikpak.com", "pikpak.com"));

    fun accepts(url: URI): Boolean {
        val host = url.host?.lowercase() ?: return false
        return url.scheme.equals("https", ignoreCase = true) &&
            url.rawUserInfo == null && url.port in setOf(-1, 443) &&
            domains.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        fun fromUri(url: URI): OfficialWebDrive? = entries.firstOrNull { it.accepts(url) }
    }
}
