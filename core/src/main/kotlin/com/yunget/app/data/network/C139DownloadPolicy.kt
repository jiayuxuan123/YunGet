package com.yunget.app.data.network

/** Shared request policy for both personal and share download links returned by 139. */
object C139DownloadPolicy {
    fun headers(): Map<String, String> = mapOf(
        "User-Agent" to C139Constants.PC_UA,
        "Referer" to C139Constants.DOWNLOAD_REFERER,
    )
}
