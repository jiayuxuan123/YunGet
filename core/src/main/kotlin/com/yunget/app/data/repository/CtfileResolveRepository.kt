package com.yunget.app.data.repository

import com.yunget.app.data.network.CtfileApi
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession

/** Anonymous Ctfile single-file shares. Folder/list routes use /d or /s and are intentionally rejected for now. */
class CtfileResolveRepository(
    private val api: CtfileApi = CtfileApi(),
) : ShareResolveRepository {
    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: throw IllegalArgumentException("无法识别城通分享链接")
            val info = api.open(
                parsed.shareId,
                link.substringBefore('#'),
                pwd.orEmpty(),
                cookie,
            )
            ShareSession(
                shareId = parsed.shareId,
                stoken = pwd.orEmpty(),
                title = info.optString("file_name").ifBlank { "城通分享" },
            )
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching { listOfNotNull(api.cachedFile(session.shareId)) }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("城通分享无需转存"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
    ): Result<String> = Result.failure(UnsupportedOperationException("城通分享无需转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("城通分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
    ): Result<DownloadLink> = runCatching {
        api.download(session.shareId, file.fid, cookie)
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
    ): Result<DownloadLink> = getShareDownloadLink(session, file, "")
}
