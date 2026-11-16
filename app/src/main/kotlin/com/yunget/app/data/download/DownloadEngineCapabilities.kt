package com.yunget.app.data.download

data class DownloadEngineCapabilities(
    val authenticatedHttp: Boolean = false,
    val rangedRequests: Boolean = false,
    val hls: Boolean = false,
    val sourceFailover: Boolean = false,
    val bitTorrent: Boolean = false,
    val ed2k: Boolean = false,
    val liveDiagnostics: Boolean = false,
    val directFilesystemTarget: Boolean = false,
)
