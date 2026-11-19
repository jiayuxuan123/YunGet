package com.yunget.app.data.download

import android.content.Context
import dev.turbodl.core.BackendContext
import dev.turbodl.core.BackendResult
import dev.turbodl.core.DownloadBackend
import dev.turbodl.core.DownloadRequest

internal class NativePreview(
    context: Context,
    speedLimit: () -> Long,
    httpVersionPreference: () -> Int,
    sftpConfig: () -> NativeSftpConfig = { NativeSftpConfig() },
) : NativePreviewRouting {
    override val available = false
    override fun supportsMedia(kind: String) = false
    override fun supportsTorrent() = false
    override fun supportsMetalink() = false
    override fun supportsSftp() = false
    override val backend = object : DownloadBackend {
        override val name = NativePreviewRouting.NATIVE
        override fun supports(request: DownloadRequest) = false
        override suspend fun download(context: BackendContext): BackendResult =
            error("此安装包缺少原生测试内核，断点已保留")
    }

    companion object {
        fun hasHttp3(): Boolean = false
        fun hasPackagedCore(): Boolean = false
        fun compiledProtocols(): List<String> = emptyList()
    }
}
