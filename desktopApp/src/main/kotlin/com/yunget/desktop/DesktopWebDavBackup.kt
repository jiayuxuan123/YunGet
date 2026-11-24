package com.yunget.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class AccountFields(val kind: DriveAccountKind, val credential: String, val extra: List<String> = emptyList()) {
    val platform = kind.name.lowercase(Locale.ROOT)
}

private val accountFields = listOf(
    AccountFields(DriveAccountKind.QUARK, "cookie"),
    AccountFields(DriveAccountKind.UC, "cookie"),
    AccountFields(DriveAccountKind.XUNLEI, "accessToken", listOf("refreshToken", "deviceId", "captchaToken", "authType")),
    AccountFields(DriveAccountKind.BAIDU, "cookie"),
    AccountFields(DriveAccountKind.C139, "cookie", listOf("authorization")),
    AccountFields(DriveAccountKind.PAN123, "accessToken", listOf("account")),
    AccountFields(DriveAccountKind.PAN115, "cookie"),
    AccountFields(DriveAccountKind.GUANGYA, "accessToken", listOf("refreshToken", "deviceId", "deviceSign", "account")),
    AccountFields(DriveAccountKind.ILANZOU, "appToken", listOf("uuid", "account", "password", "userId")),
    AccountFields(DriveAccountKind.LANZOU, "cookie"),
)

private fun encodeAccountRow(fields: AccountFields, account: DesktopAccount): JSONObject {
    val row = JSONObject()
        .put("platform", fields.platform)
        .put(fields.credential, account.credential)
        .put("nickname", account.nickname)
        .put("remark", account.remark)
        .put("updatedAt", account.updatedAt)
    for (key in fields.extra) account.extra[key]?.let { row.put(key, it) }
    return row
}

private fun decodeAccountRow(row: JSONObject): DesktopAccount? {
    val fields = accountFields.firstOrNull { it.platform == row.optString("platform") } ?: return null
    val credential = row.optString(fields.credential).takeIf(String::isNotBlank) ?: return null
    return DesktopAccount(
        kind = fields.kind,
        credential = credential,
        nickname = row.optString("nickname"),
        remark = row.optString("remark"),
        extra = fields.extra.mapNotNull { key ->
            row.optString(key).takeIf(String::isNotBlank)?.let { key to it }
        }.toMap(),
        updatedAt = row.optLong("updatedAt", System.currentTimeMillis()),
    )
}

internal fun exportDesktopWebDavSnapshot(
    accountStore: DesktopAccountStore,
    config: DesktopConfig,
    historyStore: DesktopResolveHistoryStore? = null,
): JSONObject {
    val rows = JSONArray()
    val extraRows = JSONArray()
    for (fields in accountFields) {
        val all = accountStore.accountsOf(fields.kind).filter { it.credential.isNotBlank() }
        val primary = all.firstOrNull { it.isPrimary } ?: all.firstOrNull() ?: continue
        rows.put(encodeAccountRow(fields, primary))
        all.filter { it.id != primary.id }.forEach { secondary ->
            extraRows.put(encodeAccountRow(fields, secondary))
        }
    }
    val root = JSONObject()
        .put("app", "yunget_auth_backup")
        .put("version", 1)
        .put("exportedAt", System.currentTimeMillis())
        .put("accounts", rows)
        .put(
            "downloadSettings",
            JSONObject()
                .put("connectionsPerTask", config.connectionsPerTask)
                .put("maxConcurrentTasks", config.maxConcurrentTasks)
                .put("speedLimitBytesPerSec", config.speedLimitBytesPerSec)
                .put("httpPolicy", config.httpPolicy)
                .put("providerSafeCapEnabled", config.providerSafeCapEnabled)
                .put("providerThreadOverrides", com.yunget.app.data.download.ProviderDownloadPolicy.encodeOverrides(config.providerThreadOverrides))
        )
    if (extraRows.length() > 0) root.put("extraAccounts", extraRows)
    val favorites = historyStore?.favorites?.value.orEmpty()
    if (favorites.isNotEmpty()) {
        val bookmarks = JSONArray()
        favorites.forEach { fav ->
            bookmarks.put(
                JSONObject()
                    .put("link", fav.input)
                    .put("title", fav.title)
                    .put("platform", fav.platformLabel)
                    .put("pwd", fav.passcode)
                    .put("createdAt", fav.timestamp)
            )
        }
        root.put("bookmarks", bookmarks)
    }
    return root
}

internal suspend fun restoreDesktopWebDavSnapshot(
    snapshot: JSONObject,
    accountStore: DesktopAccountStore,
    settingsStore: DesktopSettingsStore,
    historyStore: DesktopResolveHistoryStore? = null,
): Int = withContext(Dispatchers.IO) {
    val accounts = snapshot.getJSONArray("accounts")
    var restoredCount = 0
    for (index in 0 until accounts.length()) {
        val row = accounts.optJSONObject(index) ?: continue
        val account = decodeAccountRow(row) ?: continue
        accountStore.saveAccount(account, addAsNew = false)
        restoredCount++
    }
    snapshot.optJSONArray("extraAccounts")?.let { extras ->
        for (index in 0 until extras.length()) {
            val row = extras.optJSONObject(index) ?: continue
            val account = decodeAccountRow(row) ?: continue
            val existing = accountStore.accountsOf(account.kind)
            if (existing.none { it.credential == account.credential }) {
                accountStore.saveAccount(account.copy(isPrimary = false), addAsNew = true, makePrimary = false)
                restoredCount++
            }
        }
    }
    snapshot.optJSONObject("downloadSettings")?.let { settings ->
        settingsStore.update { current ->
            val nextSpeedMb = if (settings.has("speedLimitBytesPerSec")) {
                val bps = settings.optLong("speedLimitBytesPerSec", current.speedLimitBytesPerSec).coerceAtLeast(0L)
                if (bps <= 0L) 0.0 else (bps / (1024.0 * 1024.0))
            } else {
                current.speedLimitMbPerSec
            }
            current.copy(
                connectionsPerTask = settings.optInt("connectionsPerTask", current.connectionsPerTask).coerceIn(1, 512),
                maxConcurrentTasks = settings.optInt("maxConcurrentTasks", current.maxConcurrentTasks).coerceIn(1, 8),
                speedLimitMbPerSec = nextSpeedMb,
                httpPolicy = settings.optInt("httpPolicy", current.httpPolicy).coerceIn(0, 3),
                providerSafeCapEnabled = settings.optBoolean("providerSafeCapEnabled", current.providerSafeCapEnabled),
                providerThreadOverrides = if (settings.has("providerThreadOverrides")) {
                    com.yunget.app.data.download.ProviderDownloadPolicy.decodeOverrides(settings.optString("providerThreadOverrides"))
                } else current.providerThreadOverrides,
            )
        }
    }
    if (historyStore != null) {
        snapshot.optJSONArray("bookmarks")?.let { bookmarks ->
            val existing = historyStore.favorites.value.mapTo(hashSetOf()) { it.input.trim().lowercase(Locale.ROOT) }
            for (index in 0 until bookmarks.length()) {
                val row = bookmarks.optJSONObject(index) ?: continue
                val link = row.optString("link").trim()
                if (link.isEmpty() || !existing.add(link.lowercase(Locale.ROOT))) continue
                historyStore.toggleFavorite(
                    input = link,
                    passcode = row.optString("pwd"),
                    title = row.optString("title"),
                    platformLabel = row.optString("platform"),
                )
            }
        }
    }
    restoredCount
}
