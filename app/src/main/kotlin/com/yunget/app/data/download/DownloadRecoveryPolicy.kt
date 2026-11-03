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
    fun shouldDiscardPartial(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("sha-256 校验失败") ||
            normalized.contains("大小校验失败") ||
            normalized.contains("合并分片失败")
    }
}
