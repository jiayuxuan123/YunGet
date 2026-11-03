package com.yunget.app.data.download

internal object TurboTaskMapping {
    private const val STABLE_KEY_PREFIX = "room-"

    fun roomIdFromStableKey(stableKey: String?): Long? {
        val raw = stableKey?.takeIf { it.startsWith(STABLE_KEY_PREFIX) }
            ?.removePrefix(STABLE_KEY_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return raw.toLongOrNull()?.takeIf { it > 0 }
    }
}
