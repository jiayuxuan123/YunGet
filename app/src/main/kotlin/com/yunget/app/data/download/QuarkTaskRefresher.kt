package com.yunget.app.data.download

import android.content.Context
import com.yunget.app.data.db.AppDatabase
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.repository.QuarkAccountRepository

internal data class RefreshedTaskSource(val link: DownloadLink, val headers: Map<String, String>)

internal interface QuarkRefreshAccess {
    suspend fun freshCookie(accountId: String): String?
    suspend fun currentCookie(accountId: String): String?
    suspend fun savedLink(fileId: String, cookie: String): DownloadLink?
    suspend fun shareToken(reference: QuarkRefreshReference, cookie: String): String?
    suspend fun shareFiles(reference: QuarkRefreshReference, token: String, cookie: String, page: Int): List<ShareFile>?
    suspend fun sharedLink(file: ShareFile, reference: QuarkRefreshReference, token: String, cookie: String): DownloadLink?
}

private class NetworkQuarkRefreshAccess(context: Context) : QuarkRefreshAccess {
    private val accounts = AppDatabase.get(context).quarkAccountDao()
    private val api = QuarkApi()
    private val repository = QuarkAccountRepository(accounts, api)

    override suspend fun freshCookie(accountId: String) = repository.getFreshCookie(accountId, force = true)
    override suspend fun currentCookie(accountId: String) = accounts.getById(accountId)?.cookie
    override suspend fun savedLink(fileId: String, cookie: String) = api.getDownloadLink(fileId, cookie)
    override suspend fun shareToken(reference: QuarkRefreshReference, cookie: String) =
        api.getShareToken(reference.shareId, reference.password, cookie)?.stoken
    override suspend fun shareFiles(reference: QuarkRefreshReference, token: String, cookie: String, page: Int) =
        api.getShareFiles(reference.shareId, token, reference.parentId, cookie, page, 100)
    override suspend fun sharedLink(file: ShareFile, reference: QuarkRefreshReference, token: String, cookie: String) =
        api.getShareDownloadLinkWithoutSave(file.fid, file.fidToken, reference.shareId, token, cookie)
}

internal class QuarkTaskRefresher(private val access: QuarkRefreshAccess) {
    constructor(context: Context) : this(NetworkQuarkRefreshAccess(context))

    suspend fun refresh(task: DownloadTaskEntity, headers: Map<String, String>): RefreshedTaskSource? {
        val reference = QuarkRefreshReference.decode(task.refreshReferenceJson) ?: return null
        if (task.sourceAccountId.isBlank()) return null
        val cookie = access.freshCookie(task.sourceAccountId) ?: return null
        val link = if (reference.savedFileId.isNotBlank()) {
            access.savedLink(reference.savedFileId, cookie)
        } else {
            val token = access.shareToken(reference, cookie) ?: return null
            var found: ShareFile? = null
            for (page in 1..20) {
                val files = access.shareFiles(reference, token, cookie, page)
                    ?: return null
                found = files.firstOrNull { it.fid == reference.fileId }
                if (found != null || files.size < 100) break
            }
            val file = found ?: return null
            if (reference.expectedSize > 0 && file.fsize != reference.expectedSize) return null
            access.sharedLink(file, reference, token, cookie)
        } ?: return null
        if (reference.expectedSize > 0 && link.size > 0 && link.size != reference.expectedSize) return null
        // Recheck the same owner after network calls; logout must not revive cached credentials.
        val downloadCookie = access.currentCookie(task.sourceAccountId) ?: return null
        // Preserve the source's UA/Referer and replace credentials only for its original owner.
        return RefreshedTaskSource(link, headers.filterKeys { !it.equals("Cookie", true) } + ("Cookie" to downloadCookie))
    }
}
