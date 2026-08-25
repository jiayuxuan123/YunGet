package com.yunget.app.data.repository

import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession

/**
 * 分享解析仓库公共接口：夸克 / UC 共用同一套流程（token → 列表 → 转存 → 直链）。
 */
interface ShareResolveRepository {
    suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession>
    suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>>
    suspend fun ensureTempDir(cookie: String): Result<String>
    suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String>
    suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink>

    /**
     * 获取分享文件下载直链（平台差异在此收敛）：
     * - 夸克：转存到临时目录 → 用转存后新 fid 取直链；
     * - UC：直接用分享 fid + stoken + fid_token 取直链（无需转存）。
     */
    suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink>

    /**
     * 下载完成后清理临时转存目录（夸克实现删除 tr_* 子目录；其它平台默认空实现）。
     * @param dirFid DownloadLink.cleanupDirFid 带回的临时目录 fid
     */
    suspend fun cleanupTempDir(dirFid: String, cookie: String) {}
}