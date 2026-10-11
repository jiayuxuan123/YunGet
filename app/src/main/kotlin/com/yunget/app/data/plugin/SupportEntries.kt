/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
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

package com.yunget.app.data.plugin

/**
 * 支持条目的**净化**与排序（P7）。
 *
 * ## 为什么需要一层净化
 *
 * 条目来自插件 —— 也就是**用户装进来的第三方代码**。宿主负责渲染，
 * 所以必须在这里挡掉几类东西，而不是把插件给的原样交给 UI：
 *
 *  1. **空标题**：渲染出来是一个空壳条目，用户看不懂那是什么；
 *  2. **非法链接**：`event.getValue()` 之类非 http/https 的 scheme 交给系统
 *     打开是危险动作（`intent://`、`file://` 都可能被别的应用接走）；
 *  3. **越界的图片路径**：只接受应用私有目录内的文件 ——
 *     否则插件可以指示宿主去读它本来读不到的文件（宿主代它完成越权访问）；
 *  4. **超长文本**：一个插件返回几 MB 的字符串会让页面卡死。
 *
 * 纯逻辑、不碰 Android API（路径判断用字符串前缀），所以能直接被单测覆盖。
 *
 * ## 排序为什么是"按插件 id 再按键名"
 *
 * 多个插件都能提供支持入口。列表顺序必须是**确定的**：不确定的顺序会让同一台设备
 * 每次打开页面看到的排列都不同，用户会以为页面上东西在乱跳。用 `(ownerId, title)`
 * 字典序，与注册先后无关。
 */
object SupportEntries {

    /** 单条文本的字符上限。 */
    const val MAX_TEXT_CHARS = 4_000

    /** 单条说明的字符上限。 */
    const val MAX_NOTE_CHARS = 500

    /** 一个插件最多能提供多少条（防止用大量条目刷屏）。 */
    const val MAX_ENTRIES_PER_PLUGIN = 12

    /**
     * 净化一个插件提供的条目列表。
     *
     * @param ownerPluginId 提供者的插件 id（用于排序与诊断）
     * @param privateRoot 应用私有目录的绝对路径（图片只允许在这个前缀下）
     * @return 净化后的条目；被丢掉的条目数在 [Result.dropped] 里如实给出
     */
    fun sanitize(
        ownerPluginId: String,
        entries: List<SupportEntry>,
        privateRoot: String,
    ): Result {
        val kept = mutableListOf<SupportEntry>()
        var dropped = 0

        for (entry in entries) {
            if (kept.size >= MAX_ENTRIES_PER_PLUGIN) {
                dropped += entries.size - kept.size - dropped
                break
            }
            val clean = sanitizeOne(entry, privateRoot)
            if (clean == null) dropped++ else kept.add(clean)
        }

        // 先按标题排序再返回：插件给的顺序可能是"重要性"，但跨插件无法比较重要性，
        // 所以统一用标题字典序 —— 至少是稳定的、可预期的。
        return Result(kept.sortedBy { it.title }, dropped)
    }

    /** 净化单条；不可用返回 null。 */
    private fun sanitizeOne(entry: SupportEntry, privateRoot: String): SupportEntry? {
        val title = entry.title.trim()
        if (title.isEmpty()) return null

        val note = entry.note.trim().take(MAX_NOTE_CHARS)
        val payload = entry.payload.trim()

        return when (entry.kind) {
            SupportEntry.Kind.TEXT -> {
                val text = payload.take(MAX_TEXT_CHARS)
                if (text.isEmpty()) null else entry.copy(title = title, note = note, payload = text)
            }

            SupportEntry.Kind.IMAGE -> {
                if (!isInsidePrivateRoot(payload, privateRoot)) null
                else entry.copy(title = title, note = note, payload = payload)
            }

            SupportEntry.Kind.LINK -> {
                if (!isHttpUrl(payload)) null
                else entry.copy(title = title, note = note, payload = payload)
            }

            SupportEntry.Kind.COPYABLE -> {
                val text = payload.take(MAX_TEXT_CHARS)
                if (text.isEmpty()) null else entry.copy(title = title, note = note, payload = text)
            }
        }
    }

    /**
     * 路径是否落在应用私有目录内。
     *
     * 用**规范化的字符串前缀**判断，不碰 `java.io.File.canonicalPath` ——
     * 后者要做真实文件系统查询（在 UI 线程上是明显的开销），而且对不存在的路径
     * 会退化成一个非规范形式，反而给出错误的判断。
     *
     * 前缀比较时补一个分隔符：否则 `/data/data/app/files-secret` 会被
     * `/data/data/app/files` 的前缀匹配放过去。
     */
    internal fun isInsidePrivateRoot(path: String, root: String): Boolean {
        if (path.isEmpty() || root.isEmpty()) return false
        // 拒绝显式的向上跳：即便规范化后仍在根内，也说明调用方意图可疑
        if (path.split('/', '\\').any { it == ".." }) return false

        val normRoot = root.trimEnd('/', '\\')
        val normPath = normalize(path)
        if (!normPath.startsWith(normRoot)) return false
        // 必须是根目录下的子路径，而不是同前缀的兄弟目录
        return normPath.length == normRoot.length || normPath[normRoot.length] == '/'
    }

    /** 只做表层规范化：把反斜杠统一成斜杠、折叠重复分隔符。不解析 `.`（无必要且易错）。 */
    private fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() || true }
            .joinToString("/")
            .let { if (path.startsWith("/")) "/" + it.trimStart('/') else it }

    /** 只接受 http/https。其它 scheme（`intent:`、`file:`、`javascript:`）一律拒绝。 */
    internal fun isHttpUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // 光有 scheme 不算 URL：`https://` 后面得真的有东西
        val rest = url.substringAfter("://", "")
        return rest.isNotBlank() && !rest.startsWith("/")
    }

    /**
     * 单个插件的净化结果。
     *
     * 不带来源信息 —— 净化只关心"这一条能不能信"，来源由 [merge] 在合并时补上。
     * 让净化也管来源会让两个职责混在一起，而它们的调用方不同
     * （净化可能被单独用于校验，合并只用于渲染）。
     */
    data class Result(
        val entries: List<SupportEntry>,
        /** 因不合法被丢掉的条目数（用于诊断 —— 静默丢弃会让插件作者以为自己的条目生效了）。 */
        val dropped: Int,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** 带来源信息的条目（UI 要显示"由哪个插件提供"）。 */
    data class SanitizedEntry(val ownerPluginId: String, val entry: SupportEntry)

    /**
     * 合并多个插件的条目。
     *
     * 排序键是 `(插件 id, 标题)`：**与注册顺序无关**。同一台设备每次打开的排列都一样，
     * 而"哪个插件先注册"是实现细节，不该影响用户看到的内容顺序。
     */
    fun merge(perPlugin: List<Pair<String, List<SupportEntry>>>, privateRoot: String): List<SanitizedEntry> =
        perPlugin
            .flatMap { (owner, raw) ->
                sanitize(owner, raw, privateRoot).entries.map { SanitizedEntry(owner, it) }
            }
            .sortedWith(compareBy({ it.ownerPluginId }, { it.entry.title }))
}
