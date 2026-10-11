/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
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

package com.yunget.app.data.cloud

import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.repository.ShareResolveRepository

/**
 * 把一个 [CloudProvider] 适配成宿主解析页用的 [ShareResolveRepository]。
 *
 * ## 为什么是适配器而不是改解析页
 *
 * 解析页的状态机（会话 / 目录栈 / 下载入队）围绕 [ShareSession]/[ShareFile] 建成，
 * 已经服务 10 个内置网盘。给插件再开一条平行 UI 状态机，等于把"解析由谁做"之外的
 * 全部复杂度复制一份。适配器让插件路径**复用整条既有流程**：解析页感知不到差异，
 * 插件失败时的回退也只是在路由处换回内置仓库（见 ResolveViewModel 的分流）。
 *
 * ## 字段映射（契约 ↔ 宿主模型）
 *
 * | CloudProvider | ShareResolveRepository |
 * |---|---|
 * | `resolveShare(request)` | `createSession(link, pwd, cookie)` → [ShareSession]（shareId 承载，stoken 置空——那是夸克/UC 的内部概念） |
 * | `listShareFiles(shareId, path, pwd, page)` | `listFiles(session, dirFid, cookie)`；提取码由本类记住（内置仓库的提取码在会话里，契约里它每次显式传） |
 * | `resolveDownload(shareId, file, pwd)` | `getShareDownloadLink` / `getShareDownloadLinkWithoutSave` / `getGuestShareDownloadLink`——三者对插件是同一件事：插件自己管登录态 |
 * | `capabilities()` 未声明转存 | `ensureTempDir` / `transferFile` 明确失败（提示直接下载），插件路径不做临时转存 |
 *
 * 错误按业务语义翻译（要提取码 ≠ 出错了），与内置实现的用户提示保持同一水平。
 */
class ProviderBackedShareRepository(
    private val provider: CloudProvider,
) : ShareResolveRepository {

    /** shareId → 本次解析使用的提取码（listShareFiles / resolveDownload 需要重复携带）。 */
    private val passwords = java.util.concurrent.ConcurrentHashMap<String, String?>()

    private fun CloudError.userMessage(): String = when (this) {
        CloudError.SHARE_UNAVAILABLE -> "分享不存在或已失效"
        CloudError.NEEDS_PASSWORD -> "请输入提取码"
        CloudError.BAD_PASSWORD -> "提取码错误"
        CloudError.NEEDS_LOGIN -> "该网盘插件需要登录态：请先登录对应网盘，或查看插件说明"
        CloudError.RISK_CONTROLLED -> "被平台风控，请稍后再试"
        is CloudError.UNEXPECTED -> "插件返回了无法理解的响应：$detail"
    }

    private fun <T> CloudResult<T>.asResult(): Result<T> = when (this) {
        is CloudResult.Ok -> Result.success(value)
        is CloudResult.Err -> Result.failure(IllegalStateException(error.userMessage()))
    }

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val share = provider.resolveShare(CloudShareRequest(rawInput = link, password = pwd)).asResult()
        return share.map { s ->
            passwords[s.shareId] = pwd
            ShareSession(shareId = s.shareId, stoken = "", title = s.title.ifBlank { link })
        }
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String,
    ): Result<List<ShareFile>> {
        val page = provider.listShareFiles(
            shareId = session.shareId,
            path = dirFid,
            password = passwords[session.shareId],
            page = null,
        ).asResult()
        return page.map { p ->
            p.files.map { f ->
                ShareFile(
                    fid = f.id,
                    fname = f.name,
                    fsize = f.sizeBytes.coerceAtLeast(0L),
                    isdir = f.isDirectory,
                    pdirFid = dirFid,
                    fidToken = "",
                )
            }
        }
    }

    /** 插件路径不做临时转存：直链由插件直接给出（能力未声明时也是这句实话）。 */
    private fun transferUnsupported(): Result<String> =
        Result.failure(IllegalStateException("该分享由插件解析，不经过转存，请直接下载"))

    override suspend fun ensureTempDir(cookie: String): Result<String> = transferUnsupported()

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
    ): Result<String> = transferUnsupported()

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(IllegalStateException("插件解析的分享没有独立的取链步骤，请从分享列表直接下载"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
    ): Result<DownloadLink> = resolveDownload(session, file)

    /** 免转存取链与登录态取链对插件是同一件事（插件自己管登录态）。 */
    override suspend fun getShareDownloadLinkWithoutSave(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
    ): Result<DownloadLink> = resolveDownload(session, file)

    /** 游客分支同样直达插件：有没有登录态由插件的 health()/内部状态决定，宿主不替它判断。 */
    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
    ): Result<DownloadLink> = resolveDownload(session, file)

    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        // 插件路径没有临时转存目录，无需清理
    }

    private suspend fun resolveDownload(session: ShareSession, file: ShareFile): Result<DownloadLink> {
        val info = provider.resolveDownload(
            shareId = session.shareId,
            file = CloudFile(
                id = file.fid,
                name = file.fname,
                isDirectory = false,
                sizeBytes = file.fsize,
            ),
            password = passwords[session.shareId],
        ).asResult()
        return info.map { d ->
            DownloadLink(
                fid = file.fid,
                filename = d.fileName.ifBlank { file.fname },
                downloadUrl = d.url,
                size = d.sizeBytes.coerceAtLeast(0L),
            )
        }
    }
}
