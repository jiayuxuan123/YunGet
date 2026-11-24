package com.yunget.app.data.download

import android.content.Context
import com.yunget.app.data.db.AppDatabase
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.network.C139Api
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.repository.QuarkAccountRepository
import com.yunget.app.data.repository.UCAccountRepository
import com.yunget.app.data.repository.XunleiAccountRepository

internal interface CloudDriveRefreshAccess {
    suspend fun refreshQuarkDrive(accountId: String, fileId: String): Pair<DownloadLink, String>?
    suspend fun refreshUcDrive(accountId: String, fileId: String): Pair<DownloadLink, String>?
    suspend fun refreshXunleiDrive(accountId: String, fileId: String): DownloadLink?
    suspend fun refreshPan123Drive(accountId: String, file: ShareFile): DownloadLink?
    suspend fun refreshC139Drive(accountId: String, fileId: String): DownloadLink?
}

private class NetworkCloudDriveRefreshAccess(context: Context) : CloudDriveRefreshAccess {
    private val db = AppDatabase.get(context)
    private val quarkApi = QuarkApi()
    private val quarkRepo = QuarkAccountRepository(db.quarkAccountDao(), quarkApi)
    private val ucApi = UCApi()
    private val ucRepo = UCAccountRepository(db.ucAccountDao(), ucApi)
    private val xunleiApi = XunleiApi()
    private val xunleiRepo = XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi).also { repo ->
        xunleiApi.refreshTokenProvider = { deviceId, token -> repo.refreshSession(token, deviceId) }
    }
    private val pan123Api = Pan123Api()
    private val c139Api = C139Api()

    override suspend fun refreshQuarkDrive(accountId: String, fileId: String): Pair<DownloadLink, String>? {
        val dao = db.quarkAccountDao()
        val owner = if (accountId.isNotBlank()) dao.getById(accountId) else dao.getAccount()
        val targetId = owner?.id ?: return null
        val cookie = quarkRepo.getFreshCookie(targetId, force = true) ?: owner.cookie
        if (cookie.isBlank()) return null
        val link = quarkApi.getDownloadLink(fileId, cookie) ?: return null
        val verifiedCookie = dao.getById(targetId)?.cookie?.takeIf { it.isNotBlank() } ?: return null
        return link to verifiedCookie
    }

    override suspend fun refreshUcDrive(accountId: String, fileId: String): Pair<DownloadLink, String>? {
        val dao = db.ucAccountDao()
        val owner = if (accountId.isNotBlank()) dao.getById(accountId) else dao.getAccount()
        val targetId = owner?.id ?: return null
        val refreshed = ucApi.refreshSession(owner.cookie)
        val cookie = if (!refreshed.isNullOrBlank()) {
            dao.upsert(owner.copy(cookie = refreshed, updatedAt = System.currentTimeMillis()))
            refreshed
        } else {
            owner.cookie
        }
        if (cookie.isBlank()) return null
        val link = ucApi.getDownloadLink(fileId, cookie) ?: return null
        val verifiedCookie = dao.getById(targetId)?.cookie?.takeIf { it.isNotBlank() } ?: return null
        return link to verifiedCookie
    }

    override suspend fun refreshXunleiDrive(accountId: String, fileId: String): DownloadLink? {
        val dao = db.xunleiAccountDao()
        val owner = if (accountId.isNotBlank()) dao.getById(accountId) else dao.getAccount()
        val target = owner ?: return null
        if (target.accessToken.isBlank()) return null
        val deviceId = target.deviceId.ifBlank { XunleiApi.newDeviceId() }
        val link = xunleiApi.getFileDetail(fileId, target.accessToken, deviceId, target.captchaToken)
        if (link != null && link.downloadUrl.isNotBlank()) return link
        // Token 可能已过期：尝试主动刷新后重试一次
        val refreshed = xunleiRepo.refreshSession(target.accessToken, deviceId) ?: return null
        return xunleiApi.getFileDetail(fileId, refreshed.first, deviceId, target.captchaToken)
            ?.takeIf { it.downloadUrl.isNotBlank() }
    }

    override suspend fun refreshPan123Drive(accountId: String, file: ShareFile): DownloadLink? {
        val dao = db.pan123AccountDao()
        val owner = if (accountId.isNotBlank()) dao.getById(accountId) else dao.getAccount()
        val token = owner?.accessToken?.takeIf { it.isNotBlank() } ?: return null
        return pan123Api.getDownloadLink(file, token)?.takeIf { it.downloadUrl.isNotBlank() }
    }

    override suspend fun refreshC139Drive(accountId: String, fileId: String): DownloadLink? {
        val dao = db.c139AccountDao()
        val owner = if (accountId.isNotBlank()) dao.getById(accountId) else dao.getAccount()
        val cookie = owner?.cookie?.takeIf { it.isNotBlank() } ?: return null
        return c139Api.getDownloadUrl(fileId, cookie)?.takeIf { it.downloadUrl.isNotBlank() }
    }
}

/**
 * 统一的多网盘直链过期自动换链器（夸克分享/个人盘、UC 个人盘、迅雷个人盘、123 个人盘、139 个人盘）。
 * 保持与既有 [QuarkTaskRefresher] 完全兼容，并校验预期文件大小，避免断点拼接错文件。
 */
internal class CloudTaskRefresher(
    private val quarkShareRefresher: QuarkTaskRefresher,
    private val driveAccess: CloudDriveRefreshAccess,
) {
    constructor(context: Context) : this(
        quarkShareRefresher = QuarkTaskRefresher(context),
        driveAccess = NetworkCloudDriveRefreshAccess(context),
    )

    suspend fun refresh(task: DownloadTaskEntity, headers: Map<String, String>): RefreshedTaskSource? {
        val ref = TaskRefreshReference.decode(task.refreshReferenceJson) ?: return null
        if (ref.platform == "quark" && ref.mode == TaskRefreshReference.MODE_SHARE) {
            return quarkShareRefresher.refresh(task, headers)
        }
        val expected = ref.expectedSize.takeIf { it > 0L } ?: task.totalSize.takeIf { it > 0L } ?: 0L
        fun sizeMatches(link: DownloadLink): Boolean =
            expected <= 0L || link.size <= 0L || link.size == expected

        return when (ref.platform) {
            "quark" -> {
                val (link, cookie) = driveAccess.refreshQuarkDrive(task.sourceAccountId, ref.fileId) ?: return null
                if (!sizeMatches(link)) return null
                RefreshedTaskSource(link, headers.filterKeys { !it.equals("Cookie", true) } + ("Cookie" to cookie))
            }
            "uc" -> {
                val (link, cookie) = driveAccess.refreshUcDrive(task.sourceAccountId, ref.fileId) ?: return null
                if (!sizeMatches(link)) return null
                RefreshedTaskSource(link, headers.filterKeys { !it.equals("Cookie", true) } + ("Cookie" to cookie))
            }
            "xunlei" -> {
                val link = driveAccess.refreshXunleiDrive(task.sourceAccountId, ref.fileId) ?: return null
                if (!sizeMatches(link)) return null
                RefreshedTaskSource(link, headers)
            }
            "pan123" -> {
                val stub = ShareFile(
                    fid = ref.fileId,
                    fname = ref.fileName.ifBlank { task.fileName },
                    fsize = expected,
                    isdir = false,
                    pdirFid = ref.parentId,
                    fidToken = ref.fidToken,
                )
                val link = driveAccess.refreshPan123Drive(task.sourceAccountId, stub) ?: return null
                if (!sizeMatches(link)) return null
                RefreshedTaskSource(link, headers)
            }
            "c139" -> {
                val link = driveAccess.refreshC139Drive(task.sourceAccountId, ref.fileId) ?: return null
                if (!sizeMatches(link)) return null
                RefreshedTaskSource(link, headers)
            }
            else -> null
        }
    }
}
