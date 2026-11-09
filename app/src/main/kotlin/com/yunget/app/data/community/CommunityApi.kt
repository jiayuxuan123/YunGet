package com.yunget.app.data.community

object CommunityApi {
    const val BASE_URL = "https://community.yunget.yunl.us.ci"

    val announcementsUrl: String get() = "$BASE_URL/api/announcements"
    val developerUrl: String get() = "$BASE_URL/admin"

    fun url(path: String): String = BASE_URL + "/" + path.trimStart('/')
}
