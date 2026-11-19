package com.yunget.desktop

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.BaiduConstants
import com.yunget.app.data.network.C139Api
import com.yunget.app.data.network.C139Constants
import com.yunget.app.data.network.C139DownloadPolicy
import com.yunget.app.data.network.C139KoulingParser
import com.yunget.app.data.network.CtfileApi
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.GitHubApi
import com.yunget.app.data.network.GitHubLinkParser
import com.yunget.app.data.network.GitHubLinkType
import com.yunget.app.data.network.GitHubRelease
import com.yunget.app.data.network.GitHubRepo
import com.yunget.app.data.network.GitHubTreeEntry
import com.yunget.app.data.network.GuangYaApi
import com.yunget.app.data.network.GuangYaConstants
import com.yunget.app.data.network.ILanzouApi
import com.yunget.app.data.network.ILanzouConstants
import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.LanzouConstants
import com.yunget.app.data.network.Pan115Api
import com.yunget.app.data.network.Pan115Constants
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.Pan123Constants
import com.yunget.app.data.network.ParsedShare
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.QuarkConstants
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.network.UCConstants
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.XunleiConstants
import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.app.data.network.XunleiKouling
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.network.video.DetectedVideoLink
import com.yunget.app.data.network.video.VideoLinkParser
import com.yunget.app.data.network.video.VideoMedia
import com.yunget.app.data.network.video.VideoMediaKind
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.data.repository.BaiduResolveRepository
import com.yunget.app.data.repository.C139ResolveRepository
import com.yunget.app.data.repository.CtfileResolveRepository
import com.yunget.app.data.repository.FeijipanResolveRepository
import com.yunget.app.data.repository.GuangYaResolveRepository
import com.yunget.app.data.repository.ILanzouCredentials
import com.yunget.app.data.repository.ILanzouResolveRepository
import com.yunget.app.data.repository.LanzouResolveRepository
import com.yunget.app.data.repository.Pan115ResolveRepository
import com.yunget.app.data.repository.Pan123ResolveRepository
import com.yunget.app.data.repository.QuarkResolveRepository
import com.yunget.app.data.repository.ShareResolveRepository
import com.yunget.app.data.repository.UCResolveRepository
import com.yunget.app.data.repository.VideoParseRepository
import com.yunget.app.data.repository.XunleiResolveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder

internal sealed class DesktopLinkTarget {
    data class CloudShare(val parsed: ParsedShare, val rawInput: String) : DesktopLinkTarget()
    data class Kouling(val platform: SharePlatform, val rawInput: String, val extractedPwd: String = "") : DesktopLinkTarget()
    data class Video(val detected: DetectedVideoLink, val rawInput: String) : DesktopLinkTarget()
    data class GitHub(val link: GitHubLinkType) : DesktopLinkTarget()
    data class Direct(val source: String, val kind: TransferKind, val suggestedName: String) : DesktopLinkTarget()
    data class Unsupported(val reason: String) : DesktopLinkTarget()
}

internal data class OpenedShare(
    val platform: SharePlatform,
    val session: ShareSession,
    val rootDirId: String,
    val files: List<ShareFile>,
)

internal data class ResolvedDownload(
    val url: String,
    val fileName: String,
    val size: Long,
    val kind: TransferKind,
    val headers: Map<String, String>,
    val sourceLabel: String,
    val cleanupHook: (suspend () -> Unit)? = null,
)

internal data class GitHubRepoBundle(
    val repo: GitHubRepo,
    val tree: List<GitHubTreeEntry>,
    val releases: List<GitHubRelease>,
)

internal fun platformDisplayName(platform: SharePlatform): String = when (platform) {
    SharePlatform.QUARK -> "夸克网盘"
    SharePlatform.UC -> "UC 网盘"
    SharePlatform.BAIDU -> "百度网盘"
    SharePlatform.PAN123 -> "123 云盘"
    SharePlatform.PAN115 -> "115 网盘"
    SharePlatform.GUANGYA -> "光鸭网盘"
    SharePlatform.ILANZOU -> "蓝奏优享"
    SharePlatform.LANZOU -> "蓝奏云"
    SharePlatform.FEIJIPAN -> "小飞机网盘"
    SharePlatform.CTFILE -> "城通网盘"
    SharePlatform.GITHUB -> "GitHub"
    SharePlatform.XUNLEI -> "迅雷云盘"
    SharePlatform.C139 -> "移动云盘"
}

internal fun buildPlatformDownloadHeaders(
    platform: SharePlatform,
    credential: String,
    link: DownloadLink,
): Map<String, String> {
    val isGuest = link.guestCookie.isNotBlank()
    return when (platform) {
        SharePlatform.BAIDU -> mapOf(
            "Cookie" to credential,
            "User-Agent" to BaiduConstants.UA_NETDISK,
        )
        SharePlatform.FEIJIPAN -> mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Origin" to "https://www.feijipan.com",
            "Referer" to "https://www.feijipan.com/",
        )
        SharePlatform.CTFILE -> mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Referer" to "https://www.ctfile.com/",
        )
        SharePlatform.PAN115 -> mapOf(
            "Cookie" to Pan115Constants.mergeCookies(credential, link.guestCookie),
            "User-Agent" to Pan115Constants.CLIENT_UA,
            "Referer" to Pan115Constants.DOWNLOAD_REFERER,
        )
        SharePlatform.PAN123 -> mapOf(
            "User-Agent" to Pan123Constants.WEB_UA,
            "Referer" to Pan123Constants.DOWNLOAD_REFERER,
        )
        SharePlatform.GUANGYA -> mapOf(
            "User-Agent" to GuangYaConstants.WEB_UA,
            "Referer" to GuangYaConstants.DOWNLOAD_REFERER,
        )
        SharePlatform.ILANZOU -> mapOf(
            "User-Agent" to ILanzouConstants.WEB_UA,
            "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
            "Origin" to ILanzouConstants.WEB_SITE,
        )
        SharePlatform.LANZOU -> {
            val origin = runCatching { URI(link.downloadUrl) }.getOrNull()
                ?.let { "${it.scheme}://${it.host}" } ?: "https://pan.lanzoui.com"
            mapOf(
                "User-Agent" to LanzouConstants.WEB_UA,
                "Referer" to "$origin/",
                "Cookie" to LanzouConstants.DOWN_IP_COOKIE,
            )
        }
        SharePlatform.UC -> if (isGuest) mapOf(
            "Cookie" to link.guestCookie,
            "User-Agent" to UCConstants.GUEST_UA,
            "Sec-Ch-Ua" to UCConstants.GUEST_SEC_CH_UA,
            "Referer" to UCConstants.DOWNLOAD_REFERER,
            "Origin" to UCConstants.WEB_ORIGIN,
        ) else mapOf(
            "Cookie" to credential,
            "User-Agent" to UCConstants.USER_AGENT,
            "Referer" to UCConstants.DOWNLOAD_REFERER,
            "Origin" to UCConstants.WEB_ORIGIN,
        )
        SharePlatform.QUARK -> if (isGuest) mapOf(
            "Cookie" to link.guestCookie,
            "User-Agent" to QuarkConstants.API_USER_AGENT,
            "Referer" to QuarkConstants.DOWNLOAD_REFERER,
        ) else mapOf(
            "Cookie" to credential,
            "User-Agent" to QuarkConstants.API_USER_AGENT,
            "Referer" to QuarkConstants.DOWNLOAD_REFERER,
        )
        SharePlatform.XUNLEI -> mapOf(
            "User-Agent" to XunleiConstants.APP_UA,
        )
        SharePlatform.C139 -> C139DownloadPolicy.headers()
        else -> emptyMap()
    }
}

internal fun detectDesktopLinkTarget(rawInput: String): DesktopLinkTarget {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return DesktopLinkTarget.Unsupported("请输入网盘链接/口令、视频链接、GitHub 链接、直链或磁力链接")

    val parsedShare = ShareLinkParser.parse(trimmed)
    if (parsedShare != null) {
        return DesktopLinkTarget.CloudShare(parsedShare, trimmed)
    }

    val videoLink = VideoLinkParser.parse(trimmed)
    if (videoLink != null) return DesktopLinkTarget.Video(videoLink, trimmed)

    val gh = GitHubLinkParser.parse(trimmed)
    if (gh != null) return DesktopLinkTarget.GitHub(gh)

    val c139Kouling = C139KoulingParser.parse(trimmed)
    if (c139Kouling != null) {
        return DesktopLinkTarget.Kouling(
            platform = SharePlatform.C139,
            rawInput = trimmed,
            extractedPwd = c139Kouling.password,
        )
    }

    val localFile = File(trimmed.removeSurrounding("\""))
    if (localFile.isFile) {
        val lower = localFile.name.lowercase()
        return when {
            lower.endsWith(".torrent") -> DesktopLinkTarget.Direct(
                source = localFile.absolutePath,
                kind = TransferKind.TORRENT,
                suggestedName = sanitizeFileName(localFile.nameWithoutExtension, "torrent-download"),
            )
            lower.endsWith(".metalink") || lower.endsWith(".meta4") -> DesktopLinkTarget.Direct(
                source = localFile.absolutePath,
                kind = TransferKind.METALINK,
                suggestedName = sanitizeFileName(localFile.nameWithoutExtension, "metalink-download"),
            )
            else -> DesktopLinkTarget.Unsupported("仅支持选择 .torrent、.metalink 或 .meta4 本地任务文件")
        }
    }

    val magnet = Regex("""magnet:\?[^\s]+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
    if (magnet != null) {
        val dn = Regex("""[?&]dn=([^&]+)""").find(magnet)?.groupValues?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: "magnet-download"
        return DesktopLinkTarget.Direct(magnet, TransferKind.TORRENT, sanitizeFileName(dn, "magnet-download"))
    }

    val httpUrl = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
    if (httpUrl != null) {
        val kind = inferTransferKind(httpUrl)
        val fallback = when (kind) {
            TransferKind.HLS, TransferKind.DASH -> "stream.mp4"
            TransferKind.TORRENT -> "torrent-download"
            else -> "download.bin"
        }
        val rawName = runCatching {
            URI(httpUrl).path.orEmpty().substringAfterLast('/').let {
                if (it.isBlank()) "" else URLDecoder.decode(it, "UTF-8")
            }
        }.getOrDefault("")
        val suggested = when {
            kind == TransferKind.HLS || kind == TransferKind.DASH ->
                sanitizeFileName(rawName.substringBeforeLast('.').ifBlank { "stream" } + ".mp4", fallback)
            else -> sanitizeFileName(rawName, fallback)
        }
        return DesktopLinkTarget.Direct(httpUrl, kind, suggested)
    }

    if (XunleiKouling.looksLikeKouling(trimmed)) {
        return DesktopLinkTarget.Kouling(
            platform = SharePlatform.XUNLEI,
            rawInput = trimmed,
        )
    }

    return DesktopLinkTarget.Unsupported("未识别的链接格式：支持 12 大网盘及口令、抖音/快手/B站/小红书/微博/Pixiv/AcFun、GitHub、HTTP 直链、HLS/DASH 与磁力链接")
}

internal class DesktopResolveService(
    private val accountStore: DesktopAccountStore,
) {
    private val quarkRepo = QuarkResolveRepository(QuarkApi())
    private val ucRepo = UCResolveRepository(UCApi())
    private val baiduRepo = BaiduResolveRepository(BaiduApi())
    private val xunleiApi = accountStore.configureXunleiApi(XunleiApi())
    private val xunleiRepo = XunleiResolveRepository(
        api = xunleiApi,
        accountProvider = { accountStore.credentialOf(DriveAccountKind.XUNLEI).ifBlank { null } },
        deviceIdProvider = {
            accountStore.get(DriveAccountKind.XUNLEI)?.extra?.get("deviceId")
                ?.takeIf { it.isNotBlank() } ?: XunleiDeviceFingerprint.deviceId()
        },
        captchaProvider = { accountStore.get(DriveAccountKind.XUNLEI)?.extra?.get("captchaToken").orEmpty() },
        refreshProvider = {
            val devId = accountStore.get(DriveAccountKind.XUNLEI)?.extra?.get("deviceId")
                ?.takeIf { it.isNotBlank() } ?: XunleiDeviceFingerprint.deviceId()
            accountStore.refreshXunleiToken(xunleiApi, devId)
        },
    )
    private val c139Repo = C139ResolveRepository(C139Api())
    private val pan123Repo = Pan123ResolveRepository(Pan123Api()) {
        accountStore.credentialOf(DriveAccountKind.PAN123).ifBlank { null }
    }
    private val pan115Repo = Pan115ResolveRepository(Pan115Api())
    private val guangYaRepo = GuangYaResolveRepository(GuangYaApi()) {
        accountStore.credentialOf(DriveAccountKind.GUANGYA).ifBlank { null }
    }
    private val iLanzouRepo = ILanzouResolveRepository(ILanzouApi()) {
        val acc = accountStore.get(DriveAccountKind.ILANZOU)
        if (acc == null) null
        else ILanzouCredentials(
            appToken = acc.credential,
            uuid = acc.extra["uuid"].orEmpty(),
            userId = acc.extra["userId"].orEmpty(),
        )
    }
    private val lanzouRepo = LanzouResolveRepository(LanzouApi())
    private val feijipanRepo = FeijipanResolveRepository(FeijipanApi())
    private val ctfileRepo = CtfileResolveRepository(CtfileApi())
    private val videoParseRepo = VideoParseRepository(
        cookieProvider = { platform ->
            DriveAccountKind.fromVideoPlatform(platform)?.let { accountStore.credentialOf(it) }.orEmpty()
        },
    )
    private val gitHubApi = GitHubApi(
        tokenProvider = { accountStore.credentialOf(DriveAccountKind.GITHUB).ifBlank { null } },
    )

    private fun repoFor(platform: SharePlatform): ShareResolveRepository = when (platform) {
        SharePlatform.QUARK -> quarkRepo
        SharePlatform.UC -> ucRepo
        SharePlatform.BAIDU -> baiduRepo
        SharePlatform.XUNLEI -> xunleiRepo
        SharePlatform.C139 -> c139Repo
        SharePlatform.PAN123 -> pan123Repo
        SharePlatform.PAN115 -> pan115Repo
        SharePlatform.GUANGYA -> guangYaRepo
        SharePlatform.ILANZOU -> iLanzouRepo
        SharePlatform.LANZOU -> lanzouRepo
        SharePlatform.FEIJIPAN -> feijipanRepo
        SharePlatform.CTFILE -> ctfileRepo
        else -> error("不支持的分享平台：${platformDisplayName(platform)}")
    }

    fun credentialFor(platform: SharePlatform): String {
        val kind = DriveAccountKind.fromPlatform(platform) ?: return ""
        return accountStore.credentialOf(kind)
    }

    fun shareRootDirId(platform: SharePlatform): String = when (platform) {
        SharePlatform.BAIDU, SharePlatform.GUANGYA, SharePlatform.LANZOU -> ""
        else -> "0"
    }

    fun shareChildDirId(platform: SharePlatform, folder: ShareFile): String = when (platform) {
        SharePlatform.BAIDU -> folder.fidToken.ifBlank { folder.fid }
        else -> folder.fid
    }

    suspend fun openShare(rawInput: String, overridePwd: String? = null): OpenedShare = withContext(Dispatchers.IO) {
        val parsed = ShareLinkParser.parse(rawInput) ?: error("无法识别分享链接")
        val repo = repoFor(parsed.platform)
        val pwd = overridePwd?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.pwd
        val cred = credentialFor(parsed.platform)
        if (parsed.platform == SharePlatform.C139 && cred.isBlank()) {
            error("请先登录移动云盘后再解析分享链接")
        }
        val session = repo.createSession(rawInput, pwd, cred).getOrThrow()
        val root = shareRootDirId(parsed.platform)
        val files = repo.listFiles(session, root, cred).getOrThrow().sortedWith(
            compareByDescending<ShareFile> { it.isdir }.thenBy { it.fname.lowercase() }
        )
        OpenedShare(parsed.platform, session, root, files)
    }

    suspend fun openKouling(target: DesktopLinkTarget.Kouling, overridePwd: String? = null): OpenedShare =
        withContext(Dispatchers.IO) {
            when (target.platform) {
                SharePlatform.C139 -> {
                    val cmd = C139KoulingParser.parse(target.rawInput)
                        ?: error("无法识别移动云盘口令")
                    val cookie = accountStore.credentialOf(DriveAccountKind.C139)
                    val shareUrl = c139Repo.resolveKouling(
                        command = cmd,
                        cookie = cookie,
                        authorization = C139Constants.extractAuthorization(cookie),
                    ).getOrThrow()
                    val effectivePwd = overridePwd?.trim()?.takeIf { it.isNotEmpty() }
                        ?: target.extractedPwd.takeIf { it.isNotBlank() }
                    openShare(shareUrl, effectivePwd)
                }
                SharePlatform.XUNLEI -> {
                    val shareUrl = xunleiRepo.resolveKouling(XunleiKouling.normalize(target.rawInput)).getOrThrow()
                    openShare(shareUrl, overridePwd)
                }
                else -> error("暂不支持该平台口令解析")
            }
        }

    suspend fun parseVideo(detected: DetectedVideoLink): VideoParseResult = withContext(Dispatchers.IO) {
        videoParseRepo.parse(detected)
    }

    fun buildVideoMediaDownload(result: VideoParseResult, media: VideoMedia, index: Int): ResolvedDownload {
        val kind = if (media.isHls) TransferKind.HLS else TransferKind.HTTP
        val fileName = buildVideoMediaFileName(result, media, index, forceMp4 = media.isHls)
        return ResolvedDownload(
            url = media.url,
            fileName = fileName,
            size = media.size,
            kind = kind,
            headers = media.headers,
            sourceLabel = result.platform.displayName,
        )
    }

    fun buildVideoAudioDownload(result: VideoParseResult, media: VideoMedia, index: Int): ResolvedDownload? {
        if (media.audioUrl.isBlank()) return null
        val base = buildVideoMediaFileName(result, media, index, forceMp4 = false).substringBeforeLast('.')
        return ResolvedDownload(
            url = media.audioUrl,
            fileName = sanitizeFileName("$base.m4a", "audio.m4a"),
            size = -1L,
            kind = TransferKind.HTTP,
            headers = media.audioHeaders.ifEmpty { media.headers },
            sourceLabel = "${result.platform.displayName}·音轨",
        )
    }

    fun buildVideoMotionDownload(result: VideoParseResult, media: VideoMedia, index: Int): ResolvedDownload? {
        if (media.motionUrl.isBlank()) return null
        val base = buildVideoMediaFileName(result, media, index, forceMp4 = false).substringBeforeLast('.')
        val ext = if (media.motionUrl.substringBefore('?').endsWith(".mov", ignoreCase = true)) ".mov" else ".mp4"
        return ResolvedDownload(
            url = media.motionUrl,
            fileName = sanitizeFileName("${base}_live$ext", "live$ext"),
            size = -1L,
            kind = TransferKind.HTTP,
            headers = media.headers,
            sourceLabel = "${result.platform.displayName}·实况",
        )
    }

    private fun buildVideoMediaFileName(
        result: VideoParseResult,
        media: VideoMedia,
        index: Int,
        forceMp4: Boolean,
    ): String {
        val explicit = media.fileName.trim()
        if (explicit.isNotEmpty()) {
            val normalized = if (forceMp4 && !explicit.endsWith(".mp4", ignoreCase = true)) {
                explicit.substringBeforeLast('.') + ".mp4"
            } else {
                explicit
            }
            return sanitizeFileName(normalized, "media.bin")
        }
        val ext = when {
            forceMp4 || media.kind == VideoMediaKind.VIDEO -> ".mp4"
            media.kind == VideoMediaKind.AUDIO -> ".m4a"
            else -> {
                val path = runCatching { URI(media.url).path.orEmpty().lowercase() }.getOrDefault("")
                when {
                    path.endsWith(".png") -> ".png"
                    path.endsWith(".webp") -> ".webp"
                    path.endsWith(".gif") -> ".gif"
                    path.endsWith(".jpeg") -> ".jpeg"
                    else -> ".jpg"
                }
            }
        }
        val base = sanitizeFileName(
            result.title.ifBlank { result.platform.displayName },
            fallback = result.platform.name.lowercase(),
        ).take(80)
        val suffix = if (result.medias.size > 1) "_${index + 1}" else ""
        return sanitizeFileName("$base$suffix$ext", "media$ext")
    }

    suspend fun listShareDir(platform: SharePlatform, session: ShareSession, dirId: String): List<ShareFile> =
        withContext(Dispatchers.IO) {
            val repo = repoFor(platform)
            val cred = credentialFor(platform)
            repo.listFiles(session, dirId, cred).getOrThrow().sortedWith(
                compareByDescending<ShareFile> { it.isdir }.thenBy { it.fname.lowercase() }
            )
        }

    suspend fun resolveShareDownload(
        platform: SharePlatform,
        session: ShareSession,
        file: ShareFile,
    ): ResolvedDownload = withContext(Dispatchers.IO) {
        require(!file.isdir) { "请选择具体文件进行下载" }
        val repo = repoFor(platform)
        val cred = credentialFor(platform)
        val link = if (cred.isBlank()) {
            repo.getGuestShareDownloadLink(session, file).getOrThrow()
        } else {
            if (platform == SharePlatform.QUARK) {
                repo.getShareDownloadLinkWithoutSave(session, file, cred)
                    .recoverCatching { repo.getShareDownloadLink(session, file, cred).getOrThrow() }
                    .getOrThrow()
            } else {
                repo.getShareDownloadLink(session, file, cred).getOrThrow()
            }
        }
        val headers = buildPlatformDownloadHeaders(platform, cred, link)
        val cleanupDir = link.cleanupDirFid
        val cleanup: (suspend () -> Unit)? = if (!cleanupDir.isNullOrBlank() && cred.isNotBlank()) {
            { repo.cleanupTempDir(cleanupDir, cred) }
        } else null
        val kind = if (link.isHls) TransferKind.HLS else TransferKind.HTTP
        val finalName = if (link.isHls && !link.filename.endsWith(".mp4", ignoreCase = true)) {
            sanitizeFileName(link.filename.substringBeforeLast('.') + ".mp4")
        } else {
            sanitizeFileName(link.filename.ifBlank { file.fname })
        }
        ResolvedDownload(
            url = link.downloadUrl,
            fileName = finalName,
            size = if (link.size > 0L) link.size else file.fsize,
            kind = kind,
            headers = headers,
            sourceLabel = platformDisplayName(platform),
            cleanupHook = cleanup,
        )
    }

    suspend fun collectShareFolderFiles(
        platform: SharePlatform,
        session: ShareSession,
        folder: ShareFile,
        prefix: String = sanitizeFileName(folder.fname, "folder"),
        maxDepth: Int = 10,
    ): List<Pair<ShareFile, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<ShareFile, String>>()
        suspend fun walk(dirId: String, currentPrefix: String, depth: Int) {
            if (depth > maxDepth) return
            val items = listShareDir(platform, session, dirId)
            for (item in items) {
                if (item.isdir) {
                    val nextPrefix = "$currentPrefix/${sanitizeFileName(item.fname, "sub")}"
                    walk(shareChildDirId(platform, item), nextPrefix, depth + 1)
                } else {
                    out.add(item to currentPrefix)
                }
            }
        }
        walk(shareChildDirId(platform, folder), prefix, 0)
        out
    }

    fun supportsSaveToDrive(platform: SharePlatform): Boolean = platform in setOf(
        SharePlatform.QUARK,
        SharePlatform.UC,
        SharePlatform.BAIDU,
        SharePlatform.C139,
        SharePlatform.PAN123,
        SharePlatform.PAN115,
        SharePlatform.GUANGYA,
        SharePlatform.ILANZOU,
        SharePlatform.XUNLEI,
    )

    suspend fun saveShareFilesToDrive(
        platform: SharePlatform,
        session: ShareSession,
        files: List<ShareFile>,
        targetDirFid: String,
    ): Int = withContext(Dispatchers.IO) {
        require(supportsSaveToDrive(platform)) { "${platformDisplayName(platform)}暂不支持一键转存" }
        require(files.isNotEmpty()) { "请先选择要转存的文件或文件夹" }
        val cred = credentialFor(platform)
        require(cred.isNotBlank()) { "请先登录${platformDisplayName(platform)}账号后再转存" }
        val repo = repoFor(platform)
        var saved = 0
        for (file in files) {
            repo.transferFile(session, file, targetDirFid, cred).getOrThrow()
            saved++
        }
        saved
    }

    suspend fun openGitHubRepo(owner: String, repoName: String): GitHubRepoBundle = withContext(Dispatchers.IO) {
        val repo = gitHubApi.getRepo(owner, repoName)
            ?: error("无法获取 GitHub 仓库 $owner/$repoName（可能不存在、为私有仓库或触发限流）")
        val tree = gitHubApi.getTree(owner, repoName, repo.defaultBranch).orEmpty()
        val releases = gitHubApi.getReleases(owner, repoName, page = 1).orEmpty()
            .filterNot { it.draft }
        GitHubRepoBundle(repo, tree, releases)
    }

    suspend fun listGitHubAccountRepos(owner: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val type = gitHubApi.getUserType(owner)
        val repos = if (type.equals("Organization", ignoreCase = true)) {
            gitHubApi.getOrgRepos(owner, page = 1)
        } else {
            gitHubApi.getUserRepos(owner, page = 1)
        }
        repos ?: error("无法获取 GitHub 账号 $owner 的仓库列表")
    }

    fun applyGitHubMirror(rawUrl: String, mirrorPrefix: String): String {
        val prefix = mirrorPrefix.trim()
        if (prefix.isEmpty()) return rawUrl
        val normalized = if (prefix.endsWith("/")) prefix else "$prefix/"
        return if (rawUrl.startsWith(normalized)) rawUrl else "$normalized$rawUrl"
    }

    fun buildGitHubArchiveUrl(repo: GitHubRepo, mirrorPrefix: String): String {
        val raw = "https://github.com/${repo.fullName}/archive/refs/heads/${repo.defaultBranch}.zip"
        return applyGitHubMirror(raw, mirrorPrefix)
    }

    fun buildGitHubRawFileUrl(repo: GitHubRepo, path: String, mirrorPrefix: String): String {
        val raw = "https://raw.githubusercontent.com/${repo.fullName}/${repo.defaultBranch}/$path"
        return applyGitHubMirror(raw, mirrorPrefix)
    }
}
