package com.yunget.app.data.download

/** Engine facts shown in the UI, kept in sync with the TurboDL dependency. */
object EngineBuildInfo {
    const val TURBO_DL_VERSION = "0.5.0.7"
    val ANDROID_RUNTIME_LABEL: String
        get() = if (NativePreview.hasPackagedCore()) "C++ 原生核心" else "JVM 兼容核心"

    val compiledNativeProtocols: List<String> get() = NativePreview.compiledProtocols()
}
