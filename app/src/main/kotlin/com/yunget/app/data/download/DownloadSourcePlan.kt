package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ordered source plan shared by non-TurboDL engines.
 *
 * A retry may rotate to the next URL, but each URL keeps its own headers so
 * multi-account mirrors never combine one account's URL with another's cookie.
 */
internal object DownloadSourcePlan {
    fun forTask(task: DownloadTaskEntity, primaryHeaders: Map<String, String>): List<DownloadSource> = buildList {
        add(DownloadSource(task.url, primaryHeaders))
        if (task.fallbackUrl.isNotBlank() && task.fallbackUrl != task.url) {
            add(DownloadSource(task.fallbackUrl, primaryHeaders))
        }
        runCatching {
            val array = JSONArray(task.altSourcesJson)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (url.isBlank() || any { it.url == url }) continue
                val json = item.optJSONObject("headers") ?: JSONObject()
                val headers = json.keys().asSequence()
                    .associateWith { json.optString(it) }
                    .filterValues { it.isNotBlank() }
                    .ifEmpty { primaryHeaders }
                add(DownloadSource(url, headers))
            }
        }
    }
}
