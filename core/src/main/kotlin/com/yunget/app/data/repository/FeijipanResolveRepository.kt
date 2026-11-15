package com.yunget.app.data.repository

import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession

/** Anonymous Feijipan share parser. */
class FeijipanResolveRepository(
    private val api: FeijipanApi = FeijipanApi(),
) : ShareResolveRepository {
    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val shareId = com.yunget.app.data.network.ShareLinkParser.parse(link)?.shareId
                ?: throw IllegalArgumentException("无法识别小飞机分享链接")
            val data = api.open(shareId, pwd.orEmpty(), cookie)
            ShareSession(
                shareId = shareId,
                stoken = pwd.orEmpty(),
                title = data.optString("shareName").ifBlank { "小飞机分享" },
            )
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            api.list(
                session.shareId,
                session.stoken,
                dirFid.takeUnless { it == "0" || it == "root" || it.isBlank() },
            )
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("小飞机分享无需转存"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
    ): Result<String> = Result.failure(UnsupportedOperationException("小飞机分享无需转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("小飞机分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
    ): Result<DownloadLink> = runCatching {
        api.download(session.shareId, session.stoken, file.fid).copy(filename = file.fname)
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
    ): Result<DownloadLink> = getShareDownloadLink(session, file, "")
}
