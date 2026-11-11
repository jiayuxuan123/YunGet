package com.yunget.app.data.download

/**
 * 入队时需要随任务持久化的来源与策略上下文。
 *
 * 此处只保存非敏感引用，不得包含 Cookie 或账号凭据。
 */
data class DownloadSubmissionContext(
    val sourceAccountId: String = "",
    val refreshReferenceJson: String = "{}",
    val transferPolicyJson: String = "{}",
)
