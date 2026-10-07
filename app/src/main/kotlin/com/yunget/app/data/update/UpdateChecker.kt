package com.yunget.app.data.update

import android.content.Context
import com.yunget.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 *
 * ## 为什么不用 `/releases/latest`（2026-09-29 修复）
 *
 * GitHub 的 `latest` 接口**按设计只返回已发布（非预发布、非草稿）的最新版**——
 * 预发布版（prerelease）永远不在其中。于是产生了一个真实故障：
 *
 * | 接口 | 返回 |
 * |---|---|
 * | `/releases/latest` | `v2.6.7`（把预发布都跳过了） |
 * | `/releases?per_page=30` | `v2.6.9`(prerelease)、`v2.6.7`、… |
 *
 * 用户装了 2.6.9 测试版后，检查更新反而提示"已是最新"（因为线上"最新"=2.6.7）。
 * 修法：改用列表接口，自行按**版本号**（而非发布时间）取最大的那个。
 *
 * 按版本号而非发布时间，是因为发布顺序不保证等于版本顺序
 * （补发旧版本、撤回重发都会打乱时间线）。
 */
object UpdateChecker {

    /**
     * 列表接口：一次取最近 30 个 release（含预发布），按版本号挑最大者。
     *
     * 取 30 个是本项目的发布节奏（rc 迭代密集）下的经验值 ——
     * 再多也只是徒增响应体积，30 个足够覆盖到"比当前版本新的那个"。
     */
    private const val RELEASES_LIST_URL =
        "https://api.github.com/repos/jiayuxuan123/YunGet/releases?per_page=30"

    /**
     * 任意仓库的 Release 列表接口（Gopeed 内核仓库等参数化调用用）。
     * @param repo `owner/name` 形式
     */
    private fun releasesListUrl(repo: String): String =
        "https://api.github.com/repos/$repo/releases?per_page=30"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：`<前缀><原直链>`；前缀未配置时用内置默认 */
    fun mirrorUrl(url: String, prefix: String = MIRROR_PREFIX): String = prefix + url

    /**
     * Release 说明里「网盘下载」条目的匹配规则，形如：
     * `[网盘下载](https://pan.quark.cn/s/a7287ee935cb)`
     * 命中时客户端把主按钮切成「网盘更新」（走内置解析下载），GitHub 直链退到次级入口。
     *
     * Gopeed 内核仓库同样用这个约定发布网盘镜像，所以这条规则不只服务于应用自身更新。
     */
    private val NETDISK_LINK_REGEX = Regex("""\[网盘下载\]\s*\(\s*(https?://[^)\s]+?)\s*\)""")

    /** 从 Release 说明正文里提取「网盘下载」链接；没有该条目时返回 null */
    fun netdiskDownloadUrl(body: String): String? =
        NETDISK_LINK_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    data class Asset(
        val name: String,
        val downloadUrl: String,
        /** 资产大小（GitHub API 的 size，字节）；缺省 0 = 未知（旧调用点不传） */
        val size: Long = 0L,
        /** 资产摘要，形如 `sha256:xxxx`；缺省空串 = 未知（GitHub 只在较新的响应里给） */
        val digest: String = ""
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String,
        /** 是否为预发布（测试版）。宿主可据此在提示里标明"测试版"。 */
        val prerelease: Boolean = false,
        /** Release 页面地址（html_url），供「打开 GitHub 页面」跳浏览器；缺省空串 */
        val htmlUrl: String = "",
    )

    /** 更新检测结果：失败时带上可读原因（HTTP 码 / 异常信息），既写 E 级日志也直接给用户提示 */
    sealed class CheckResult {
        data class Success(val release: Release) : CheckResult()
        data class Failure(val reason: String) : CheckResult()
    }

    /**
     * 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0。
     *
     * 支持预发布后缀（`2.6.9` / `2.6.9-rc1` / `2.6.9-dev12`）：
     * 按 semver 的约定，**有预发布后缀的比没有的小**（2.6.9-rc1 < 2.6.9），
     * 同后缀则按其中的数字比较（2.6.9-rc2 > 2.6.9-rc1）。
     *
     * 【为什么需要】本项目的测试版用 `-devN` / `-rcN` 命名（如 2.6.9-dev12）。
     * 若不识别后缀，`2.6.9-dev12` 与 `2.6.9` 会被判为相等，
     * 于是"从 dev12 升级到正式版"这类更新会检测不到。
     */
    fun compareVersions(v1: String, v2: String): Int {
        val (base1, pre1) = splitVersion(v1)
        val (base2, pre2) = splitVersion(v2)

        // 先比数字部分
        val maxLength = maxOf(base1.size, base2.size)
        for (i in 0 until maxLength) {
            val n1 = base1.getOrNull(i) ?: 0
            val n2 = base2.getOrNull(i) ?: 0
            if (n1 != n2) return n1 - n2
        }

        // 数字部分相同：无后缀 > 有后缀（2.6.9 > 2.6.9-rc1）
        if (pre1 == null && pre2 == null) return 0
        if (pre1 == null) return 1
        if (pre2 == null) return -1

        // 都有后缀：按"字母部分 + 数字部分"比较（dev9 < dev12，rc1 < rc2）
        val preParts1 = splitPre(pre1)
        val preParts2 = splitPre(pre2)
        val maxPre = maxOf(preParts1.size, preParts2.size)
        for (i in 0 until maxPre) {
            val (nameA, numA) = preParts1.getOrNull(i) ?: ("" to 0)
            val (nameB, numB) = preParts2.getOrNull(i) ?: ("" to 0)
            val byName = nameA.compareTo(nameB)
            if (byName != 0) return byName
            if (numA != numB) return numA - numB
        }
        return 0
    }

    /**
     * 把预发布后缀拆成 (字母段, 数字段) 的序列。
     *
     * `dev12` → [(dev,12)]；`rc1.2` → [(rc,1),("",2)]。
     * **必须把字母与数字分开比**：直接按字符串比会得出 `dev9 > dev12`
     * （字典序里 '9' > '1'），与直觉和 semver 都相反。
     */
    private fun splitPre(pre: String): List<Pair<String, Int>> =
        pre.split('.', '-')
            .flatMap { seg ->
                val m = Regex("^([a-zA-Z]*)(\\d*)$").find(seg)
                if (m == null) {
                    listOf(seg.lowercase() to 0)
                } else {
                    val name = m.groupValues[1].lowercase()
                    val num = m.groupValues[2].toIntOrNull() ?: 0
                    if (name.isEmpty() && num == 0) emptyList() else listOf(name to num)
                }
            }

    /** 把 `v2.6.9-dev12` 拆成 ([2,6,9], "dev12")；无后缀时第二项为 null。 */
    private fun splitVersion(v: String): Pair<List<Int>, String?> {
        val s = v.trim().trimStart('v', 'V')
        val idx = s.indexOfFirst { it == '-' || it == '+' }
        val base = if (idx < 0) s else s.substring(0, idx)
        val pre = if (idx < 0) null else s.substring(idx + 1).takeIf { it.isNotBlank() }
        return base.split(".").map { it.toIntOrNull() ?: 0 } to pre
    }

    /** 当前应用版本号（packageManager.versionName） */
    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"

    /**
     * 请求 GitHub Release 列表，返回**版本号最高**的那个（含预发布）。
     * 网络失败 / 仓库无 Release 返回 null。
     *
     * @param includePrerelease 是否把预发布版纳入候选。默认 true ——
     *   本项目的测试版就是预发布，用户需要能收到它们。
     * @param repo 目标仓库（`owner/name`）。默认本项目仓库；
     *   Gopeed 内核仓库（KernelProvisioner）等参数化调用走这里。
     */
    suspend fun fetchLatestRelease(
        includePrerelease: Boolean = true,
        repo: String? = null,
    ): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClients.apiClient()
            val request = Request.Builder()
                .url(if (repo.isNullOrBlank()) RELEASES_LIST_URL else releasesListUrl(repo))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "YunGet")
                .get()
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string() ?: return@runCatching null
            }
            val arr = org.json.JSONArray(body)
            val candidates = buildList {
                for (i in 0 until arr.length()) {
                    val json = arr.optJSONObject(i) ?: continue
                    // 草稿一律跳过（未发布，不该提示用户）
                    if (json.optBoolean("draft")) continue
                    if (!includePrerelease && json.optBoolean("prerelease")) continue
                    val tag = json.optString("tag_name")
                    if (tag.isBlank()) continue
                    val assets = buildList {
                        json.optJSONArray("assets")?.let { a ->
                            for (j in 0 until a.length()) {
                                val obj = a.optJSONObject(j) ?: continue
                                add(
                                    Asset(
                                        name = obj.optString("name"),
                                        downloadUrl = obj.optString("browser_download_url"),
                                        size = obj.optLong("size"),
                                        digest = obj.optString("digest"),
                                    )
                                )
                            }
                        }
                    }
                    add(
                        Release(
                            tagName = tag,
                            body = json.optString("body"),
                            assets = assets,
                            publishedAt = json.optString("published_at"),
                            prerelease = json.optBoolean("prerelease"),
                            htmlUrl = json.optString("html_url"),
                        )
                    )
                }
            }
            // 按**版本号**取最大（不用发布时间：补发/撤回会打乱时间线）
            candidates.maxWithOrNull { a, b -> compareVersions(a.tagName, b.tagName) }
        }.getOrNull()
    }
}