package com.yunget.app.data.download

import dev.turbodl.core.TaskTransferDetails

data class DownloadDiagnostics(
    val firstByteMillis: Long?,
    val probeMillis: Long,
    val transferMillis: Long,
) {
    companion object {
        fun from(details: TaskTransferDetails?): DownloadDiagnostics? {
            if (details == null || !details.requestMetricsAvailable) return null
            return DownloadDiagnostics(
                firstByteMillis = details.timeToFirstByteMs,
                probeMillis = details.probeMs,
                transferMillis = details.transferMs,
            )
        }
    }
}
