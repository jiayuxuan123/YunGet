package com.yunget.app.data.community

import okhttp3.HttpUrl.Companion.toHttpUrl

object CommunityApi {
    const val BASE_URL = "https://community.yunget.yunl.us.ci"

    val announcementsUrl: String get() = "$BASE_URL/api/announcements"
    val developerUrl: String get() = "$BASE_URL/admin"
    val analyticsUrl: String get() = "$BASE_URL/api/analytics"
    val notificationsUrl: String get() = "$BASE_URL/notifications?embed=app"
    private val notifications by lazy { CommunityNotificationClient("$BASE_URL/api/notifications/unread".toHttpUrl()) }

    internal suspend fun unread(cookie: String?): Int = notifications.unread(cookie)

    fun url(path: String): String = BASE_URL + "/" + path.trimStart('/')
}
