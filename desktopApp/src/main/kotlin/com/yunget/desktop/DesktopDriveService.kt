package com.yunget.desktop

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.GuangYaApi
import com.yunget.app.data.network.ILanzouApi
import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.Pan115Api
import com.yunget.app.data.network.Pan115Constants
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopDriveService(
    private val accountStore: DesktopAccountStore,
) {
    private val quarkApi = QuarkApi()
    private val baiduApi = BaiduApi()
    private val ucApi = UCApi()
    private val pan123Api = Pan123Api()
    private val pan115Api = Pan115Api()
    private val iLanzouApi = ILanzouApi()
    private val guangYaApi = GuangYaApi()
    private val lanzouApi = LanzouApi()

    fun rootDirId(kind: DriveAccountKind): String = when (kind) {
        DriveAccountKind.BAIDU -> "/"
        DriveAccountKind.LANZOU -> "-1"
        DriveAccountKind.GUANGYA -> ""
        else -> "0"
    }

    fun childDirId(kind: DriveAccountKind, folder: ShareFile): String = when (kind) {
        DriveAccountKind.BAIDU -> folder.fidToken.ifBlank { folder.fid }
        else -> folder.fid
    }

    private fun requireAccount(kind: DriveAccountKind): DesktopAccount =
        accountStore.get(kind) ?: error("请先登录${kind.displayName}")

    private fun sharePlatformOf(kind: DriveAccountKind): SharePlatform = when (kind) {
        DriveAccountKind.QUARK -> SharePlatform.QUARK
        DriveAccountKind.BAIDU -> SharePlatform.BAIDU
        DriveAccountKind.UC -> SharePlatform.UC
        DriveAccountKind.PAN123 -> SharePlatform.PAN123
        DriveAccountKind.PAN115 -> SharePlatform.PAN115
        DriveAccountKind.ILANZOU -> SharePlatform.ILANZOU
        DriveAccountKind.GUANGYA -> SharePlatform.GUANGYA
        DriveAccountKind.LANZOU -> SharePlatform.LANZOU
        DriveAccountKind.FEIJIPAN -> SharePlatform.FEIJIPAN
        DriveAccountKind.GITHUB -> SharePlatform.GITHUB
    }

    suspend fun listFiles(kind: DriveAccountKind, dirId: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val acc = requireAccount(kind)
        val files = when (kind) {
            DriveAccountKind.QUARK -> quarkApi.listCloudFiles(dirId, acc.credential)
                ?: error("获取夸克网盘文件失败，请检查 Cookie 是否有效")
            DriveAccountKind.BAIDU -> baiduApi.listCloudFiles(dirId.ifBlank { "/" }, acc.credential)
            DriveAccountKind.UC -> ucApi.listCloudFiles(dirId, acc.credential)
                ?: error("获取 UC 网盘文件失败，请检查 Cookie 是否有效")
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
}
