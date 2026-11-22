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

internal fun exportDesktopWebDavSnapshot(accountStore: DesktopAccountStore, config: DesktopConfig): JSONObject {
    val rows = JSONArray()
    for (fields in accountFields) {
        val account = accountStore.get(fields.kind)?.takeIf { it.credential.isNotBlank() } ?: continue
        val row = JSONObject()
            .put("platform", fields.platform)
            .put(fields.credential, account.credential)
            .put("nickname", account.nickname)
            .put("remark", account.remark)
            .put("updatedAt", account.updatedAt)
        for (key in fields.extra) account.extra[key]?.let { row.put(key, it) }
        rows.put(row)
    }
    return JSONObject()
        .put("app", "yunget_auth_backup")
        .put("version", 1)
        .put("exportedAt", System.currentTimeMillis())
        .put("accounts", rows)
        .put("downloadSettings", JSONObject()
            .put("connectionsPerTask", config.connectionsPerTask)
            .put("maxConcurrentTasks", config.maxConcurrentTasks))
}

internal suspend fun restoreDesktopWebDavSnapshot(
    snapshot: JSONObject, accountStore: DesktopAccountStore, settingsStore: DesktopSettingsStore,
): Int = withContext(Dispatchers.IO) {
    val accounts = snapshot.getJSONArray("accounts")
    val restored = mutableListOf<DesktopAccount>()
    for (index in 0 until accounts.length()) {
        val row = accounts.optJSONObject(index) ?: continue
        val fields = accountFields.firstOrNull { it.platform == row.optString("platform") } ?: continue
        val credential = row.optString(fields.credential).takeIf(String::isNotBlank) ?: continue
        restored += DesktopAccount(
            kind = fields.kind,
            credential = credential,
            nickname = row.optString("nickname"),
            remark = row.optString("remark"),
            extra = fields.extra.mapNotNull { key ->
                row.optString(key).takeIf(String::isNotBlank)?.let { key to it }
            }.toMap(),
        )
    }
    restored.forEach { accountStore.saveAccount(it) }
    snapshot.optJSONObject("downloadSettings")?.let { settings ->
        settingsStore.update { current ->
            current.copy(
                connectionsPerTask = settings.optInt("connectionsPerTask", current.connectionsPerTask).coerceIn(1, 512),
                maxConcurrentTasks = settings.optInt("maxConcurrentTasks", current.maxConcurrentTasks).coerceIn(1, 8),
            )
        }
    }
    restored.size
}
