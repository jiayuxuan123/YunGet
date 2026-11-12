package com.yunget.app.data.download

/**
 * Decides whether a failed engine task's temporary output is safe to discard.
 *
 * Network, authorization and provider failures are recoverable: the engine keeps
 * its sidecar in the stable work directory, and the partial destination is the
 * data that sidecar refers to. Deleting it on failure silently turns resume into
 * a full redownload. Only failures that prove the assembled output is unusable
 * may discard it.
 */
internal object DownloadRecoveryPolicy {
    fun processingFailure(error: Throwable, saving: Boolean): String {
        if (!saving) return "处理失败，已保留原始文件，可重试处理"
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val text = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            causes.any { it is SecurityException } || text.contains("permission denied") || text.contains("存储权限") ->
                "保存失败：存储权限不可用。请重新选择保存目录后重试，完整文件已保留"
            text.contains("enospc") || text.contains("no space left") || text.contains("空间不足") ->
                "保存失败：存储空间不足。请释放空间后重试，完整文件已保留"
            else -> "保存失败，已保留文件，可重试保存"
        }
    }

    fun displayFailure(reason: String): String = when {
        reason.contains("Cannot verify existing download progress", ignoreCase = true) ->
            "无法验证已保存的下载进度，原数据已保留。请重新解析链接后重试。"
        reason.contains("LEGACY_STABLE_KEY_AMBIGUOUS") ->
            "旧下载记录无法安全对应，原数据已保留。请重新创建下载任务。"
        else -> reason
    }

    fun shouldDiscardPartial(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("sha-256 校验失败") ||
            normalized.contains("大小校验失败") ||
            normalized.contains("合并分片失败")
    }
}
