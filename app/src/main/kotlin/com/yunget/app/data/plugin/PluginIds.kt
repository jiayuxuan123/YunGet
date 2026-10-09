/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
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

package com.yunget.app.data.plugin

import java.security.MessageDigest

/**
 * 插件 id 与脚本文件名的**纯函数**规则层（不依赖 Android，可被纯 JVM 单测覆盖）。
 *
 * ## 为什么单独抽出来（这是安全边界，不只是"工具函数"）
 *
 * 插件 id 会**直接参与拼接脚本落盘路径**（`filesDir/plugins/scripts/<id>_<sha8>.js`）。
 * 一旦允许用户/脚本自报的 id 里出现 `..`、`/`、`\`，就等于把「写任意文件」的能力交出去：
 * 脚本内容来自网络与剪贴板，恶意 id 可以写成 `../../shared_prefs/xxx.xml` 这类路径，
 * 覆盖应用私有目录里任何文件。因此这里做两道闸：
 *
 *  1. [validate]：**白名单**校验（只认 `[a-z0-9.-]`，长度 3..64，首尾不能是 `.`/`-`，不许 `..`）。
 *     校验不通过就**拒绝安装**，绝不"洗一洗再用"—— 静默修正会让用户以为装的是 A、
 *     实际落盘的是 B。
 *  2. [fileNameFor]：即便调用方传了一个非法 id（不该发生，但不做假设），
 *     文件名也会被重新过滤成只含 `[A-Za-z0-9._-]`、且**不可能包含 `..` 或路径分隔符**，
 *     最后 `PluginRepository` 还会用 `canonicalPath` 前缀校验兜底。
 *
 * ## 为什么 id 必须小写、且必须由文件名派生时也要合法
 *
 * Android 的 `/data` 分区（多数机型是 ext4/f2fs，但用户可能装在 FAT 格式的 SD 卡上）
 * 大小写敏感性不一致：`Parser.A` 与 `parser.a` 在两个文件系统上可能是同一个文件、
 * 也可能不是。把 id 限制成小写是让「一个 id 一定对应一个文件」这条不变式成立，
 * 而不是审美偏好。
 */
object PluginIds {

    /** id 最短长度：太短的 id（如 `a`）容易与其它来源碰撞，也让"派生自文件名"失去区分度。 */
    const val MIN_LENGTH = 3

    /** id 最长长度：够表达命名空间（`vendor.category.name`），又不至于撑爆文件名长度上限。 */
    const val MAX_LENGTH = 64

    /** 脚本文件后缀（模块系统约定：可以被 `require`/`import` 的资源就是 `.js`）。 */
    const val SCRIPT_SUFFIX = ".js"

    /** 兜底 id 前缀：文件名完全不可用（中文名 / 空名 / 纯符号）时使用。 */
    const val FALLBACK_PREFIX = "plugin"

    /** 脚本目录名（相对 `filesDir`）：**唯一**允许写用户脚本的地方。 */
    const val SCRIPTS_DIR = "plugins/scripts"

    /** 插件源索引缓存目录名（相对 `filesDir`）。 */
    const val SOURCES_DIR = "plugins/sources"

    /** 合法 id 的全量字符集：小写字母、数字、点、连字符（顺手排除大小写不敏感文件系统的歧义）。 */
    private val ALLOWED_ID = Regex("[a-z0-9.\\-]+")

    /**
     * 校验并归一化插件 id。
     *
     * @return 校验通过时返回原 id（不做任何改写，便于和用户看到的字符串完全一致）；
     *         不合法返回 null。
     */
    fun validate(raw: String): String? {
        if (raw.length < MIN_LENGTH || raw.length > MAX_LENGTH) return null
        // 空串、含大写/空格/中文/斜杠/反斜杠都会在这里被拒（matches 是**全串**匹配）
        if (!ALLOWED_ID.matches(raw)) return null
        if (raw.startsWith(".") || raw.startsWith("-")) return null
        if (raw.endsWith(".") || raw.endsWith("-")) return null
        // 双点显式拒绝：即便被上面的规则漏掉，也不能让路径语义混进来
        if (raw.contains("..")) return null
        return raw
    }

    /** 是否是合法 id（[validate] 的布尔口径，供调用方做前置判断）。 */
    fun isValid(raw: String): Boolean = validate(raw) != null

    /**
     * 把任意字符串洗成**只能当文件名用**的形式：只保留 `[A-Za-z0-9._-]`，
     * 其余字符逐个替换为 `_`；连续的分隔符折叠成一个，首尾的 `.` / `-` 去掉。
     *
     * 该函数**不是** id 校验（不做长度/大小写/双点判断），只保证输出的字符集安全：
     *   · 不含 `/`、`\`、`:`、`..` 等任何能改变路径语义的东西；
     *   · 永远不会返回空串的调用方（由 [fileNameFor] 负责兜底命名）。
     */
    fun sanitizeForFileName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            val keep = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'
            val mapped = if (keep) c else '_'
            // 折叠连续同类分隔符：既让文件名可读，也**顺手消掉 `..`**
            if ((mapped == '.' || mapped == '_' || mapped == '-') && sb.isNotEmpty() && sb.last() == mapped) continue
            sb.append(mapped)
        }
        return sb.toString().trim('.', '-')
    }

    /**
     * 脚本文件名：[sanitized-id]`_`[sha 前若干位]`.js`。
     *
     * 带摘要后缀是为了让**同一插件的不同版本落在不同文件**上：
     * 升级时先写新文件、成功后再切换数据库里的 `scriptPath`，
     * 半途失败也不会留下一个「内容被截断但看起来正常」的脚本。
     *
     * 输出保证只含 `[A-Za-z0-9._-]`（无 `/`、无 `\`、无 `..`）。
     */
    fun fileNameFor(id: String, sha8: String): String {
        val safeId = sanitizeForFileName(id)
            .take(MAX_LENGTH)
            .trimEnd('.', '-')
            .ifEmpty { FALLBACK_PREFIX }
        val safeSha = sha8.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(16)
        return safeId + "_" + safeSha + SCRIPT_SUFFIX
    }

    /**
     * 从用户给的文件名派生一个**一定合法**的 id（脚本没声明 id 时的兜底）。
     *
     * 处理顺序：
     *  1. 只取最后一段路径（`../../evil.js` → `evil.js`），丢掉目录部分；
     *  2. 去掉一层扩展名（`.js`/`.mjs`/`.txt` 都算，超过 5 个字符的"扩展名"不当扩展名）；
     *  3. 洗成安全字符集并截断到 [MAX_LENGTH]；洗出来仍是合法 id 就直接用；
     *  4. 完全不可用（中文名、空名、纯符号）→ `plugin.<指名摘要>`：**确定性**且不同文件名不碰撞。
     */
    fun deriveFromFileName(fileName: String?): String {
        val raw = fileName.orEmpty()
        // 手工切分隔符，不用 File.name：Windows 上 File 才认 `\`，Linux 上不认，行为不一致
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val base = stripExtension(last)
        val normalized = sanitizeForFileName(base).take(MAX_LENGTH).trimEnd('.', '-')
        validate(normalized)?.let { return it }
        return FALLBACK_PREFIX + "." + shortHash(raw)
    }

    /**
     * 读脚本自报的 id：`plugin.defineMeta({ id: 'parser.example' })`，
     * 也接受直接赋值 `plugin.id = 'parser.example'`。
     *
     * 返回的是**原文**（可能非法），是否接受由 [validate] 决定 ——
     * 声明了却非法要**报错**，而不是悄悄退回文件名派生（那会让用户以为装的是声明的那个插件）。
     */
    fun extractDeclaredId(script: String): String? {
        stringField(defineMetaBody(script), "id")?.let { return it }
        return Regex("""plugin\s*\.\s*id\s*=\s*(['"])([^'"]+)\1""")
            .find(script)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 读 `plugin.defineMeta({...})` 的对象字面量正文（找不到返回 null）。 */
    fun defineMetaBody(script: String): String? = objectLiteralBody(script, "defineMeta")

    /**
     * 脚本**自报**的身份：`defineMeta({id, version})`。
     *
     * 用在市场安装的最后一道校验上：脚本字节受签名保护，所以脚本里写的 id/version 是
     * **发布者签过的**；索引里的版本号没有签名。两者不一致说明索引在谎报版本
     * （例如把旧脚本标成新版本，让用户以为已是最新）。判定逻辑在 `MarketClient`，
     * 这里只负责把值读出来 —— 读不到就是 null（仓库允许不声明，不算谎报）。
     */
    fun readSelfReportedMeta(script: String): SelfReportedMeta {
        val body = defineMetaBody(script)
        return SelfReportedMeta(
            id = stringField(body, "id"),
            version = stringField(body, "version"),
            name = stringField(body, "name"),
        )
    }

    /** [readSelfReportedMeta] 的结果；字段都可能为 null（脚本没声明）。 */
    data class SelfReportedMeta(val id: String?, val version: String?, val name: String?)

    /** 读 `plugin.requires({...})` 的对象字面量正文（找不到返回 null）。 */
    fun requiresBody(script: String): String? = objectLiteralBody(script, "requires")

    /** 从对象字面量正文里取一个字符串字段（如 `name` / `version`）；取不到返回 null。 */
    fun stringField(body: String?, field: String): String? {
        val text = body ?: return null
        return Regex("""(?<![\w$])${Regex.escape(field)}\s*:\s*(['"])([^'"]*)\1""")
            .find(text)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 从对象字面量正文里取一个"字符串列表"字段：
     * 支持数组写法 `permissions: ["http", "storage"]` 与简写 `permissions: "http storage"`。
     */
    fun stringListField(body: String?, field: String): List<String> {
        val text = body ?: return emptyList()
        val arrayBody = Regex("""(?<![\w$])${Regex.escape(field)}\s*:\s*\[([^\]]*)]""")
            .find(text)?.groupValues?.get(1)
        if (arrayBody != null) {
            return Regex("""['"]([^'"]*)['"]""").findAll(arrayBody)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
        val single = stringField(text, field) ?: return emptyList()
        return single.split(',', ' ', '\t', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- 内部工具

    /** 取 `callName({ ... })` 里第一个花括号对之间的正文（对象是扁平的，不做完整 JS 解析）。 */
    private fun objectLiteralBody(script: String, callName: String): String? {
        val open = Regex("""(?<![\w$])${Regex.escape(callName)}\s*\(\s*\{""").find(script) ?: return null
        val start = open.range.last + 1
        val end = script.indexOf('}', start)
        if (end <= start) return null
        return script.substring(start, end)
    }

    /** 去掉一层扩展名：只认 1..5 个字符的纯字母数字后缀（`.js`/`.mjs`/`.json`），避免误伤 `a.my-very-long-name`。 */
    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val ext = name.substring(dot + 1)
        if (ext.isEmpty() || ext.length > 5 || !ext.all { it.isAsciiLetterOrDigit() }) return name
        return name.substring(0, dot)
    }

    /** 摘要前 8 位十六进制：给"文件名不可用"的兜底 id 一个稳定且不碰撞的后缀。 */
    private fun shortHash(raw: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    } catch (_: Throwable) {
        // 理论上不会发生（SHA-256 是 JVM 必备算法）；真发生时也要给出合法 id
        "00000000"
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
