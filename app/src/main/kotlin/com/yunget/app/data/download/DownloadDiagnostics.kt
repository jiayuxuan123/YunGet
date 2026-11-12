package com.yunget.app.data.download

import dev.turbodl.core.TaskTransferDetails

data class DownloadDiagnostics(
    val firstByteMillis: Long?,
    val probeMillis: Long,
    val transferMillis: Long,
    val lastHttpStatus: Int? = null,
    val failureStage: String? = null,
) {
    companion object {
        fun from(details: TaskTransferDetails?): DownloadDiagnostics? {
            if (details == null) return null
            if (!details.requestMetricsAvailable && details.lastHttpStatus == null && details.failureStage == null) return null
            return DownloadDiagnostics(
                firstByteMillis = details.timeToFirstByteMs.takeIf { details.requestMetricsAvailable },
                probeMillis = details.probeMs.takeIf { details.requestMetricsAvailable } ?: 0,
                transferMillis = details.transferMs.takeIf { details.requestMetricsAvailable } ?: 0,
                lastHttpStatus = details.lastHttpStatus,
                failureStage = details.failureStage?.name?.lowercase(),
            )
        }
    }
}
