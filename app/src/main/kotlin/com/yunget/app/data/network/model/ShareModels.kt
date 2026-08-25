package com.yunget.app.data.network.model

/**
 * 分享解析会话（一次解析流程的凭证）。
 */
data class ShareSession(
    val shareId: String,
    val stoken: String,
    val title: String
)

/**
 * 分享 Token 响应（4.1 接口）。
 */
data class ShareToken(
    val stoken: String,
    val title: String,
    val firstFid: String
)

/**
 * 分享文件/目录项。
 */
data class ShareFile(
    val fid: String,
    val fname: String,
    val fsize: Long,
    val isdir: Boolean,
    val pdirFid: String,
    val fidToken: String,
    val modifyTime: String = ""
)

/**
 * 分享信息（云盘功能：创建分享后查询得到的链接与提取码）。
 */
data class ShareInfo(
    val shareUrl: String,
    val passcode: String,
    val pwdId: String,
    val title: String,
    val expiredType: Int
)

/**
 * 网盘空间详情（总容量 / 已用，单位字节）。
 */
data class QuotaInfo(
    val used: Long,
    val total: Long,
    val usedInTrash: Long = 0L
)

/**
 * 下载直链。
 * @param cleanupDirFid 下载完成后需删除的临时转存子目录 fid（根治夸克去重返回已删 fid）；null 表示无需清理
 */
data class DownloadLink(
    val fid: String,
    val filename: String,
    val downloadUrl: String,
    val size: Long,
    val cleanupDirFid: String? = null,
    /** 是否为 HLS（m3u8）转码流地址：下载走 HLS 分片合并路径（UC play 绕过会员墙） */
    val isHls: Boolean = false
)

/** UC 转码播放流（绕过非会员视频下载被换成宣传片的问题；url 为 m3u8/fmp4 分片地址） */
data class PlayLink(
    val url: String,
    val resolution: String,
    val format: String,
    val isHls: Boolean
)