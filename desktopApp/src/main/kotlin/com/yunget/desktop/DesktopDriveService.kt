package com.yunget.desktop

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.C139Api
import com.yunget.app.data.network.CoreHttp
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.GuangYaApi
import com.yunget.app.data.network.ILanzouApi
import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.Pan115Api
import com.yunget.app.data.network.Pan115Constants
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.Pan123Constants
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareExpire
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareInfo
import com.yunget.app.data.upload.UploadIo
import com.yunget.app.data.upload.UploadPhase
import com.yunget.app.data.upload.UploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class DesktopDriveService(
    private val accountStore: DesktopAccountStore,
) {
    private val quarkApi = QuarkApi()
    private val baiduApi = BaiduApi()
    private val ucApi = UCApi()
    private val xunleiApi = accountStore.configureXunleiApi(XunleiApi())
    private val c139Api = C139Api()
    private val pan123Api = Pan123Api()
    private val pan115Api = Pan115Api()
    private val iLanzouApi = ILanzouApi()
    private val guangYaApi = GuangYaApi()
    private val lanzouApi = LanzouApi()
    private val feijipanApi = FeijipanApi()

    fun supportsUpload(kind: DriveAccountKind): Boolean = kind in setOf(
        DriveAccountKind.QUARK,
        DriveAccountKind.BAIDU,
        DriveAccountKind.UC,
        DriveAccountKind.XUNLEI,
        DriveAccountKind.C139,
        DriveAccountKind.PAN123,
        DriveAccountKind.PAN115,
        DriveAccountKind.ILANZOU,
        DriveAccountKind.GUANGYA,
    )

    fun supportsCreateShare(kind: DriveAccountKind): Boolean = kind in setOf(
        DriveAccountKind.QUARK,
        DriveAccountKind.BAIDU,
        DriveAccountKind.UC,
        DriveAccountKind.XUNLEI,
        DriveAccountKind.C139,
        DriveAccountKind.PAN123,
        DriveAccountKind.PAN115,
        DriveAccountKind.GUANGYA,
        DriveAccountKind.LANZOU,
    )

    fun supportsFolderMutation(kind: DriveAccountKind): Boolean = kind != DriveAccountKind.FEIJIPAN

    fun shareExpireOptions(kind: DriveAccountKind): List<Pair<String, Int>> = when (kind) {
        DriveAccountKind.PAN115 -> listOf(
            "长期" to ShareExpire.PAN115_FOREVER,
            "1 天" to ShareExpire.PAN115_ONE_DAY,
            "3 天" to ShareExpire.PAN115_THREE_DAYS,
            "7 天" to ShareExpire.PAN115_SEVEN_DAYS,
            "15 天" to ShareExpire.PAN115_FIFTEEN_DAYS,
        )
        DriveAccountKind.LANZOU -> listOf("永久" to ShareExpire.FOREVER)
        else -> listOf(
            "永久" to ShareExpire.FOREVER,
            "1 天" to ShareExpire.ONE_DAY,
            "7 天" to ShareExpire.SEVEN_DAYS,
            "30 天" to ShareExpire.THIRTY_DAYS,
        )
    }

    fun rootDirId(kind: DriveAccountKind): String = when (kind) {
        DriveAccountKind.BAIDU, DriveAccountKind.C139 -> "/"
        DriveAccountKind.LANZOU -> "-1"
        DriveAccountKind.GUANGYA, DriveAccountKind.XUNLEI -> ""
        else -> "0"
    }

    fun childDirId(kind: DriveAccountKind, folder: ShareFile): String = when (kind) {
        DriveAccountKind.BAIDU -> folder.fidToken.ifBlank { folder.fid }
        else -> folder.fid
    }

    private fun requireAccount(kind: DriveAccountKind): DesktopAccount =
        accountStore.get(kind) ?: error("请先登录${kind.displayName}")

    private fun feijipanSession(acc: DesktopAccount): Triple<String, String, String> {
        val parsed = runCatching { JSONObject(acc.credential) }.getOrNull()
        val token = acc.extra["token"].orEmpty().ifBlank { parsed?.optString("token").orEmpty() }
        val uuid = acc.extra["uuid"].orEmpty().ifBlank { parsed?.optString("uuid").orEmpty() }
        val userId = acc.extra["userId"].orEmpty().ifBlank { parsed?.optString("userId").orEmpty() }
        require(token.isNotBlank() && uuid.isNotBlank()) { "小飞机登录信息不完整，请使用账号密码重新登录" }
        return Triple(token, uuid, userId)
    }

    private fun sharePlatformOf(kind: DriveAccountKind): SharePlatform = when (kind) {
        DriveAccountKind.QUARK -> SharePlatform.QUARK
        DriveAccountKind.BAIDU -> SharePlatform.BAIDU
        DriveAccountKind.UC -> SharePlatform.UC
        DriveAccountKind.XUNLEI -> SharePlatform.XUNLEI
        DriveAccountKind.C139 -> SharePlatform.C139
        DriveAccountKind.PAN123 -> SharePlatform.PAN123
        DriveAccountKind.PAN115 -> SharePlatform.PAN115
        DriveAccountKind.ILANZOU -> SharePlatform.ILANZOU
        DriveAccountKind.GUANGYA -> SharePlatform.GUANGYA
        DriveAccountKind.LANZOU -> SharePlatform.LANZOU
        DriveAccountKind.FEIJIPAN -> SharePlatform.FEIJIPAN
        DriveAccountKind.GITHUB -> SharePlatform.GITHUB
        else -> error("${kind.displayName}不支持个人网盘操作")
    }

    private suspend fun prepareXunlei(acc: DesktopAccount): Triple<String, String, String> {
        var current = acc
        val deviceId = current.extra["deviceId"].orEmpty().ifBlank { XunleiDeviceFingerprint.deviceId() }
        val exp = xunleiApi.jwtExp(current.credential)
        if (exp > 0L && exp - System.currentTimeMillis() / 1000L <= 60L) {
            val refreshed = accountStore.refreshXunleiToken(xunleiApi, deviceId)
            if (refreshed != null) {
                current = accountStore.get(DriveAccountKind.XUNLEI) ?: current.copy(credential = refreshed.first)
            } else if (current.extra["refreshToken"].orEmpty().isNotBlank()) {
                error("迅雷登录已过期，请重新登录")
            }
        }
        xunleiApi.cacheUserId(current.credential)
        val captcha = current.extra["captchaToken"].orEmpty()
        return Triple(current.credential, deviceId, captcha)
    }

    suspend fun listFiles(kind: DriveAccountKind, dirId: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val acc = requireAccount(kind)
        val files = when (kind) {
            DriveAccountKind.QUARK -> quarkApi.listCloudFiles(dirId, acc.credential)
                ?: error("获取夸克网盘文件失败，请检查 Cookie 是否有效")
            DriveAccountKind.BAIDU -> baiduApi.listCloudFiles(dirId.ifBlank { "/" }, acc.credential)
            DriveAccountKind.UC -> ucApi.listCloudFiles(dirId, acc.credential)
                ?: error("获取 UC 网盘文件失败，请检查 Cookie 是否有效")
            DriveAccountKind.XUNLEI -> {
                val (token, deviceId, captcha) = prepareXunlei(acc)
                xunleiApi.getFiles(dirId, token, deviceId, captcha).orEmpty()
            }
            DriveAccountKind.C139 -> c139Api.listCloudFiles(dirId.ifBlank { "/" }, acc.credential)
            DriveAccountKind.PAN123 -> pan123Api.listCloudFiles(dirId, acc.credential)
            DriveAccountKind.PAN115 -> {
                val all = mutableListOf<ShareFile>()
                var offset = 0
                while (true) {
                    val page = pan115Api.listFiles(dirId.ifBlank { Pan115Constants.ROOT_CID }, acc.credential, offset)
                    all.addAll(page.files)
                    offset += page.files.size
                    if (page.files.isEmpty() || offset >= page.total) break
                }
                all
            }
            DriveAccountKind.ILANZOU -> {
                val uuid = acc.extra["uuid"].orEmpty()
                require(uuid.isNotBlank()) { "蓝奏优享缺少设备 UUID，请重新登录" }
                iLanzouApi.listFiles(acc.credential, uuid, dirId)
            }
            DriveAccountKind.GUANGYA -> guangYaApi.listCloudFiles(acc.credential, dirId)
            DriveAccountKind.LANZOU -> {
                val uid = acc.extra["uid"].orEmpty()
                val vei = acc.extra["vei"].orEmpty()
                require(uid.isNotBlank() && vei.isNotBlank()) { "蓝奏云登录态不完整，请重新导入 Cookie" }
                lanzouApi.listCloudFiles(acc.credential, uid, vei, dirId.ifBlank { "-1" })
            }
            DriveAccountKind.FEIJIPAN -> {
                val (token, uuid, _) = feijipanSession(acc)
                feijipanApi.personalFiles(token, uuid, dirId.ifBlank { "0" })
            }
            else -> error("${kind.displayName}暂不支持个人网盘浏览")
        }
        files.sortedWith(compareByDescending<ShareFile> { it.isdir }.thenBy { it.fname.lowercase() })
    }

    suspend fun resolveDownload(kind: DriveAccountKind, file: ShareFile): ResolvedDownload =
        withContext(Dispatchers.IO) {
            require(!file.isdir) { "请选择文件进行下载" }
            val acc = requireAccount(kind)
            val link: DownloadLink = when (kind) {
                DriveAccountKind.QUARK -> quarkApi.getDownloadLink(file.fid, acc.credential)
                    ?: error("无法获取夸克文件直链")
                DriveAccountKind.BAIDU -> {
                    val path = file.fidToken.ifBlank { file.fid }
                    val url = baiduApi.locateDownload(path, acc.credential)
                    DownloadLink(file.fid, file.fname, url, file.fsize)
                }
                DriveAccountKind.UC -> ucApi.getDownloadLink(file.fid, acc.credential)
                    ?: error("无法获取 UC 网盘直链")
                DriveAccountKind.XUNLEI -> {
                    val (token, deviceId, captcha) = prepareXunlei(acc)
                    val raw = xunleiApi.getFileDetail(file.fid, token, deviceId, captcha)
                        ?: error("无法获取迅雷云盘直链")
                    raw.copy(filename = file.fname.ifBlank { raw.filename })
                }
                DriveAccountKind.C139 -> {
                    val raw = c139Api.getDownloadUrl(file.fid, acc.credential)
                        ?: error("无法获取移动云盘直链")
                    raw.copy(filename = file.fname.ifBlank { raw.filename })
                }
                DriveAccountKind.PAN123 -> pan123Api.getDownloadLink(file, acc.credential)
                    ?: error("无法获取 123 云盘直链")
                DriveAccountKind.PAN115 -> pan115Api.getDownloadLink(file, acc.credential)
                    ?: error("无法获取 115 网盘直链")
                DriveAccountKind.ILANZOU -> {
                    val uuid = acc.extra["uuid"].orEmpty()
                    val userId = acc.extra["userId"].orEmpty()
                    iLanzouApi.getDownloadLink(acc.credential, uuid, file.fid, userId)
                        ?: error("无法获取蓝奏优享直链")
                }
                DriveAccountKind.GUANGYA -> guangYaApi.getDownloadLink(acc.credential, file)
                    ?: error("无法获取光鸭网盘直链")
                DriveAccountKind.LANZOU -> {
                    val uid = acc.extra["uid"].orEmpty()
                    val vei = acc.extra["vei"].orEmpty()
                    lanzouApi.getPersonalDownloadLink(acc.credential, uid, vei, file.fid, file.fname)
                }
                DriveAccountKind.FEIJIPAN -> {
                    val (token, uuid, userId) = feijipanSession(acc)
                    require(userId.isNotBlank()) { "小飞机账号缺少 userId，请重新登录" }
                    val raw = feijipanApi.personalDownload(token, uuid, userId, file.fid)
                    raw.copy(filename = file.fname.ifBlank { raw.filename }, size = if (raw.size > 0L) raw.size else file.fsize)
                }
                else -> error("${kind.displayName}不支持个人网盘下载")
            }
            val headers = buildPlatformDownloadHeaders(sharePlatformOf(kind), acc.credential, link)
            val transferKind = if (link.isHls) TransferKind.HLS else TransferKind.HTTP
            ResolvedDownload(
                url = link.downloadUrl,
                fileName = sanitizeFileName(link.filename.ifBlank { file.fname }),
                size = if (link.size > 0L) link.size else file.fsize,
                kind = transferKind,
                headers = headers,
                sourceLabel = "${kind.displayName}·个人盘",
            )
        }

    suspend fun collectFolderFiles(
        kind: DriveAccountKind,
        folder: ShareFile,
        prefix: String = sanitizeFileName(folder.fname, "folder"),
        maxDepth: Int = 10,
    ): List<Pair<ShareFile, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<ShareFile, String>>()
        suspend fun walk(dirId: String, currentPrefix: String, depth: Int) {
            if (depth > maxDepth) return
            val items = listFiles(kind, dirId)
            for (item in items) {
                if (item.isdir) {
                    walk(childDirId(kind, item), "$currentPrefix/${sanitizeFileName(item.fname, "sub")}", depth + 1)
                } else {
                    out.add(item to currentPrefix)
                }
            }
        }
        walk(childDirId(kind, folder), prefix, 0)
        out
    }

    suspend fun createFolder(kind: DriveAccountKind, parentId: String, rawName: String) = withContext(Dispatchers.IO) {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "请输入文件夹名称" }
        val acc = requireAccount(kind)
        when (kind) {
            DriveAccountKind.QUARK -> {
                quarkApi.createFolder(name, parentId, acc.credential) ?: error("创建夸克文件夹失败")
            }
            DriveAccountKind.BAIDU -> {
                val base = parentId.trimEnd('/')
                val fullPath = "$base/$name"
                if (!baiduApi.createDir(fullPath, acc.credential)) error("创建百度网盘文件夹失败")
            }
            DriveAccountKind.UC -> {
                ucApi.createFolder(name, parentId, acc.credential) ?: error("创建 UC 文件夹失败")
            }
            DriveAccountKind.XUNLEI -> {
                val (token, deviceId, captcha) = prepareXunlei(acc)
                xunleiApi.createFolder(name, parentId, token, deviceId, captcha)
                    ?: error("创建迅雷文件夹失败")
            }
            DriveAccountKind.C139 -> {
                c139Api.createDir(parentId.ifBlank { "/" }, name, acc.credential)
                    ?: error("创建移动云盘文件夹失败")
            }
            DriveAccountKind.PAN123 -> pan123Api.createDir(parentId, name, acc.credential)
            DriveAccountKind.PAN115 -> {
                pan115Api.createDir(parentId.ifBlank { Pan115Constants.ROOT_CID }, name, acc.credential)
                    ?: error("创建 115 文件夹失败")
            }
            DriveAccountKind.ILANZOU -> {
                val uuid = acc.extra["uuid"].orEmpty()
                iLanzouApi.createDir(acc.credential, uuid, parentId, name)
            }
            DriveAccountKind.GUANGYA -> guangYaApi.createDir(acc.credential, parentId, name)
            DriveAccountKind.LANZOU -> {
                val uid = acc.extra["uid"].orEmpty()
                val vei = acc.extra["vei"].orEmpty()
                lanzouApi.createFolder(acc.credential, uid, vei, parentId.ifBlank { "-1" }, name)
            }
            else -> error("${kind.displayName}不支持新建文件夹")
        }
    }

    suspend fun rename(kind: DriveAccountKind, file: ShareFile, rawNewName: String) = withContext(Dispatchers.IO) {
        val newName = rawNewName.trim()
        require(newName.isNotEmpty()) { "请输入新名称" }
        val acc = requireAccount(kind)
        when (kind) {
            DriveAccountKind.QUARK -> {
                if (!quarkApi.renameFile(file.fid, newName, acc.credential)) error("重命名失败")
            }
            DriveAccountKind.BAIDU -> {
                val path = file.fidToken.ifBlank { file.fid }
                if (!baiduApi.renameFile(path, newName, acc.credential)) error("重命名失败")
            }
            DriveAccountKind.UC -> {
                if (!ucApi.renameFile(file.fid, newName, acc.credential)) error("重命名失败")
            }
            DriveAccountKind.XUNLEI -> {
                val (token, deviceId, captcha) = prepareXunlei(acc)
                if (!xunleiApi.renameFile(file.fid, newName, token, deviceId, captcha)) error("重命名失败")
            }
            DriveAccountKind.C139 -> {
                if (!c139Api.renameFile(file.fid, newName, acc.credential)) error("重命名失败")
            }
            DriveAccountKind.PAN123 -> pan123Api.renameFile(file.fid, newName, acc.credential)
            DriveAccountKind.PAN115 -> pan115Api.rename(file.fid, newName, acc.credential)
            DriveAccountKind.ILANZOU -> {
                val uuid = acc.extra["uuid"].orEmpty()
                if (file.isdir) iLanzouApi.renameFolder(acc.credential, uuid, file.fid, newName)
                else iLanzouApi.renameFile(acc.credential, uuid, file.fid, newName)
            }
            DriveAccountKind.GUANGYA -> guangYaApi.renameFile(acc.credential, file.fid, newName)
            DriveAccountKind.LANZOU -> {
                val uid = acc.extra["uid"].orEmpty()
                val vei = acc.extra["vei"].orEmpty()
                if (file.isdir) lanzouApi.renameFolder(acc.credential, uid, vei, file.fid, newName)
                else lanzouApi.renameFile(acc.credential, uid, vei, file.fid, newName)
            }
            else -> error("${kind.displayName}不支持重命名")
        }
    }

    suspend fun delete(kind: DriveAccountKind, parentId: String, file: ShareFile) = withContext(Dispatchers.IO) {
        val acc = requireAccount(kind)
        when (kind) {
            DriveAccountKind.QUARK -> quarkApi.deleteFile(file.fid, acc.credential)
            DriveAccountKind.BAIDU -> {
                val path = file.fidToken.ifBlank { file.fid }
                if (!baiduApi.deleteFiles(listOf(path), acc.credential)) error("删除失败")
            }
            DriveAccountKind.UC -> ucApi.deleteFile(file.fid, acc.credential)
            DriveAccountKind.XUNLEI -> {
                val (token, deviceId, captcha) = prepareXunlei(acc)
                if (!xunleiApi.batchDelete(listOf(file.fid), token, deviceId, captcha)) error("删除失败")
            }
            DriveAccountKind.C139 -> {
                c139Api.deleteFiles(listOf(file.fid), acc.credential) ?: error("删除失败")
            }
            DriveAccountKind.PAN123 -> pan123Api.deleteFiles(listOf(file), acc.credential)
            DriveAccountKind.PAN115 -> pan115Api.delete(
                fids = listOf(file.fid),
                pid = parentId.ifBlank { Pan115Constants.ROOT_CID },
                cookie = acc.credential,
            )
            DriveAccountKind.ILANZOU -> {
                val uuid = acc.extra["uuid"].orEmpty()
                iLanzouApi.deleteEntries(
                    appToken = acc.credential,
                    uuid = uuid,
                    folderIds = if (file.isdir) listOf(file.fid) else emptyList(),
                    fileIds = if (file.isdir) emptyList() else listOf(file.fid),
                )
            }
            DriveAccountKind.GUANGYA -> guangYaApi.deleteFiles(acc.credential, listOf(file.fid))
            DriveAccountKind.LANZOU -> {
                val uid = acc.extra["uid"].orEmpty()
                val vei = acc.extra["vei"].orEmpty()
                if (file.isdir) lanzouApi.deleteFolder(acc.credential, uid, vei, file.fid)
                else lanzouApi.deleteFile(acc.credential, uid, vei, file.fid)
            }
            else -> error("${kind.displayName}不支持删除")
        }
    }

    suspend fun uploadFile(
        kind: DriveAccountKind,
        parentId: String,
        localFile: File,
        onIoCreated: (UploadIo) -> Unit = {},
        onProgress: (UploadPhase, Long, Long) -> Unit = { _, _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(supportsUpload(kind)) { "${kind.displayName}暂不支持个人网盘上传" }
        require(localFile.isFile) { "本地文件不存在：${localFile.name}" }
        val acc = requireAccount(kind)
        val source = UploadSource.fromFile(localFile)
        val io = UploadIo(source, { CoreHttp.client() }) { phase, sent ->
            onProgress(phase, sent, source.size)
        }
        onIoCreated(io)
        when (kind) {
            DriveAccountKind.QUARK -> quarkApi.upload(io, source, parentId.ifBlank { "0" }, acc.credential)
                ?: error("夸克上传未返回文件 ID")
            DriveAccountKind.BAIDU -> baiduApi.upload(io, source, parentId.ifBlank { "/" }, acc.credential)
                ?: error("百度上传未返回文件 ID")
            DriveAccountKind.UC -> ucApi.upload(io, source, parentId.ifBlank { "0" }, acc.credential)
                ?: error("UC 上传未返回文件 ID")
            DriveAccountKind.XUNLEI -> {
                val (token, deviceId, captcha) = prepareXunlei(acc)
                xunleiApi.upload(io, source, parentId, token, deviceId, captcha)
                    ?: error("迅雷上传未返回文件 ID")
            }
            DriveAccountKind.C139 -> c139Api.upload(io, source, parentId.ifBlank { "/" }, acc.credential)
            DriveAccountKind.PAN123 -> pan123Api.upload(io, source, parentId.ifBlank { "0" }, acc.credential)
            DriveAccountKind.PAN115 -> pan115Api.upload(
                io = io,
                file = source,
                pid = parentId.ifBlank { Pan115Constants.ROOT_CID },
                cookie = acc.credential,
            ) ?: error("115 上传未返回文件 ID")
            DriveAccountKind.ILANZOU -> {
                val uuid = acc.extra["uuid"].orEmpty()
                require(uuid.isNotBlank()) { "蓝奏优享缺少设备 UUID，请重新登录" }
                iLanzouApi.upload(io, source, parentId.ifBlank { "-1" }, acc.credential, uuid)
            }
            DriveAccountKind.GUANGYA -> guangYaApi.upload(io, source, parentId, acc.credential)
            else -> error("${kind.displayName}暂不支持个人网盘上传")
        }
    }

    suspend fun createShare(
        kind: DriveAccountKind,
        files: List<ShareFile>,
        title: String,
        expiredType: Int,
        passcode: String,
    ): ShareInfo = withContext(Dispatchers.IO) {
        require(supportsCreateShare(kind)) { "${kind.displayName}暂不支持创建分享" }
        require(files.isNotEmpty()) { "请至少选择一个文件或文件夹" }
        val acc = requireAccount(kind)
        val cleanTitle = title.trim().ifBlank { files.first().fname.ifBlank { "分享文件" } }
        val cleanCode = passcode.trim()
        val fids = files.map { it.fid }
        when (kind) {
            DriveAccountKind.QUARK -> {
                val urlType = if (cleanCode.isNotEmpty()) 2 else 1
                val shareId = quarkApi.createShare(fids, cleanTitle, urlType, cleanCode, expiredType, acc.credential)
                    ?: error("创建夸克分享失败")
                quarkApi.getShareInfo(shareId, acc.credential) ?: error("获取夸克分享链接失败")
            }
            DriveAccountKind.UC -> {
                val urlType = if (cleanCode.isNotEmpty()) 2 else 1
                val shareId = ucApi.createShare(fids, cleanTitle, urlType, cleanCode, expiredType, acc.credential)
                    ?: error("创建 UC 分享失败")
                ucApi.getShareInfo(shareId, acc.credential) ?: error("获取 UC 分享链接失败")
            }
            DriveAccountKind.BAIDU -> {
                val pwd = cleanCode.ifBlank { randomPasscode4() }
                val res = baiduApi.createShare(fids, ShareExpire.baiduPeriod(expiredType), pwd, acc.credential)
                ShareInfo(
                    shareUrl = res.link,
                    passcode = res.pwd,
                    pwdId = res.shareId,
                    title = cleanTitle,
                    expiredType = expiredType,
                )
            }
            DriveAccountKind.XUNLEI -> {
                val (token, deviceId, captcha) = prepareXunlei(acc)
                xunleiApi.createShare(
                    fileIds = fids,
                    title = cleanTitle,
                    expirationDays = ShareExpire.xunleiDays(expiredType),
                    accessToken = token,
                    deviceId = deviceId,
                    captchaToken = captcha,
                    passCode = cleanCode,
                ) ?: error("创建迅雷分享失败")
            }
            DriveAccountKind.C139 -> {
                val coIDLst = files.filterNot { it.isdir }.map { it.fid }
                val caIDLst = files.filter { it.isdir }.map { it.fid }
                c139Api.createShare(
                    coIDLst = coIDLst,
                    caIDLst = caIDLst,
                    period = ShareExpire.daysOrNull(expiredType),
                    dedicatedName = cleanTitle,
                    cookie = acc.credential,
                )
            }
            DriveAccountKind.PAN123 -> {
                val days = ShareExpire.daysOrNull(expiredType)
                val expiration = if (days == null) {
                    Pan123Constants.EXPIRATION_FOREVER
                } else {
                    ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                        .plusDays(days.toLong())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                }
                pan123Api.createShare(
                    fileIds = fids,
                    shareName = cleanTitle,
                    expiration = expiration,
                    sharePwd = cleanCode.ifBlank { null },
                    token = acc.credential,
                )
            }
            DriveAccountKind.PAN115 -> pan115Api.createShare(
                fileIds = fids,
                duration = ShareExpire.pan115Duration(expiredType),
                cookie = acc.credential,
            )
            DriveAccountKind.GUANGYA -> guangYaApi.createShare(
                accessToken = acc.credential,
                fileIds = fids,
                title = cleanTitle,
                expireDays = ShareExpire.daysOrNull(expiredType),
                passcode = cleanCode.ifBlank { null },
            )
            DriveAccountKind.LANZOU -> {
                require(files.size == 1) { "蓝奏云每次只能分享单个文件或文件夹" }
                val uid = acc.extra["uid"].orEmpty()
                val vei = acc.extra["vei"].orEmpty()
                val target = files.first()
                if (target.isdir) {
                    lanzouApi.createFolderShare(acc.credential, uid, vei, target.fid)
                } else {
                    lanzouApi.createFileShare(acc.credential, uid, vei, target.fid)
                }
            }
            else -> error("${kind.displayName}暂不支持创建分享")
        }
    }

    private fun randomPasscode4(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }
}
