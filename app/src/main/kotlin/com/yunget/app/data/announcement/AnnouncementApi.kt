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

package com.yunget.app.data.announcement

import android.content.Context
import com.yunget.app.data.network.HttpClients
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.data.update.UpdateChecker
import com.yunget.app.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 公告：仓库里的一个 JSON 文件，不需要任何 token。
 *
 * ## 为什么不用 GitHub API
 *
 * 用 API 读公开仓库的 issue 是不需要 token 的，但**写**需要。而这个软件基本只有
 * 国内用户，让他们去 GitHub 建一个 personal access token 才能留言，绝大多数人不会做。
 *
 * 改成：公告就是仓库里的 `announcements.json`。维护者提交一次，所有客户端下一次
 * 刷新就能看到。读它既不需要 token，也不需要 API 配额（走 raw 内容地址）。
 *
 * ## 国内可访问性
 *
 * `raw.githubusercontent.com` 在国内经常连不上。所以：
 *   1. 先走用户配置的 GitHub 镜像前缀（设置里那一项，与插件源/图片共用）；
 *   2. 镜像失败再直连；
 *   3. 两者都失败时返回「上次成功缓存的内容」，而不是空列表 ——
 *      用户至少还能看到上一次的公告，而不是一片空白。
 */
object AnnouncementApi {

    /** 公告文件在主仓库里的路径。维护者改这个文件即可发公告。 */
    private const val FILE_PATH = "announcements.json"

    private const val REPO = "jiayuxuan123/YunGet"
    private const val RAW_URL = "https://raw.githubusercontent.com/$REPO/main/$FILE_PATH"

    /** 公告文件很小（几 KB），上限给 512KB 足够容纳长文，也防了异常响应。 */
    private const val MAX_BYTES = 512 * 1024

    private const val PREFS = "yunget_prefs"
    private const val CACHE_KEY = "announcements_cache"

    private const val TAG = "YunGet-Announce"

    /** 一条公告。 */
    data class Item(
        val id: String,
        val title: String,
        /** Markdown 正文。纯文本也能正确显示（渲染器对无标记的文本是透明的）。 */
        val body: String,
        /** 发布时间（ISO 8601 或 `yyyy-MM-dd`）；解析失败时原样显示。 */
        val date: String = "",
        /** 置顶。列表里排最前，并带一个标记。 */
        val pinned: Boolean = false,
        /** 重要公告（红点提示用）。 */
        val important: Boolean = false,
    )

    /**
     * 拉取公告列表。
     *
     * @return 成功时返回列表（可能为空）；全部失败时返回 `null`，
     *         调用方据此区分「没有公告」与「拿不到」。
     */
    suspend fun fetch(context: Context): List<Item>? = withContext(Dispatchers.IO) {
        val mirror = runCatching {
            SettingsRepository(context).githubMirrorPrefix.orEmpty()
        }.getOrDefault("")

        // 镜像优先：国内直连 raw.githubusercontent 大概率超时
        val urls = buildList {
            if (mirror.isNotBlank()) add(UpdateChecker.mirrorUrl(RAW_URL, mirror))
            add(RAW_URL)
        }

        for (url in urls) {
            val text = runCatching { httpGet(url) }.getOrNull()
            if (text != null) {
                val parsed = runCatching { parse(text) }.getOrNull()
                if (parsed != null) {
                    cachePut(context, text)
                    DiagLog.i(context, DiagLog.Category.NET, "公告拉取成功：${parsed.size} 条（$url）")
                    return@withContext parsed
                }
            }
            DiagLog.w(context, DiagLog.Category.NET, "公告拉取失败，换下一个通道：$url")
        }

        // 全失败：给上次成功的缓存，而不是空
        val cached = cacheGet(context)
        if (cached != null) {
            DiagLog.w(context, DiagLog.Category.NET, "公告全部通道失败，使用本机缓存")
            runCatching { parse(cached) }.getOrNull()
        } else {
            null
        }
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "YunGet")
            .get()
            .build()
        return HttpClients.apiClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) return null
            String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * 解析公告文件。
     *
     * 格式（顶层可以是数组，也可以是 `{"items": [...]}`）：
     * ```json
     * { "items": [
     *     { "id": "2026-10-fix", "title": "…", "body": "…",
     *       "date": "2026-10-26", "pinned": true, "important": true }
     * ] }
     * ```
     *
     * 容错：缺 `id` 时用 `title` 的哈希兜底（否则已读标记无法持久化）。
     */
    private fun parse(text: String): List<Item> {
        val trimmed = text.trim()
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("items") ?: JSONArray()
        }

        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            val body = o.optString("body")
            val id = o.optString("id").trim().ifBlank {
                // 没有 id 时用标题+日期的哈希：稳定，且同一公告改名后才变
                (title + "|" + o.optString("date")).hashCode().toString(16)
            }
            out.add(
                Item(
                    id = id,
                    title = title,
                    body = body,
                    date = o.optString("date"),
                    pinned = o.optBoolean("pinned", false),
                    important = o.optBoolean("important", false),
                ),
            )
        }
        // 置顶优先，其次保持文件里的顺序（维护者用它控制新旧）
        return out.sortedByDescending { it.pinned }
    }

    // ---------- 本机缓存（离线时还能看到上一次的公告） ----------

    private fun cachePut(context: Context, raw: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CACHE_KEY, raw).apply()
        }
    }

    private fun cacheGet(context: Context): String? =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null)
        }.getOrNull()

    // ---------- 已读标记 ----------

    private const val READ_KEY = "announcements_read_ids"

    /** 已读的公告 id 集合。 */
    fun readIds(context: Context): Set<String> =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(READ_KEY, emptySet())?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())

    fun markRead(context: Context, id: String) {
        runCatching {
            val cur = readIds(context).toMutableSet()
            if (cur.add(id)) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putStringSet(READ_KEY, cur).apply()
            }
        }
    }

    /** 未读数量（只有 important 的才计入红点，避免每发一条就红一次）。 */
    fun unreadImportantCount(context: Context, items: List<Item>): Int {
        val read = readIds(context)
        return items.count { it.important && it.id !in read }
    }
}
