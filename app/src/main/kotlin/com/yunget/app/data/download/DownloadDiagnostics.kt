package com.yunget.app.data.download

import dev.turbodl.core.TaskTransferDetails

data class DownloadDiagnostics(
    val firstByteMillis: Long?,
    val probeMillis: Long,
    val transferMillis: Long,
    val lastHttpStatus: Int? = null,
    val failureStage: String? = null,
    val negotiatedHttpVersions: Int = 0,
) {
    val httpProtocolDescription: String
        get() = buildList {
            if (negotiatedHttpVersions and 1 != 0) add("HTTP/1.1")
            if (negotiatedHttpVersions and 2 != 0) add("HTTP/2")
            if (negotiatedHttpVersions and 4 != 0) add("HTTP/3")
        }.joinToString(" · ")

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
