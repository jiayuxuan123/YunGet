package com.yunget.app.data.account

import android.content.Context
import com.yunget.app.data.security.AndroidKeystoreCredentialCipher
import org.json.JSONObject

data class StoredExternalAccount(
    val platform: String,
    val nickname: String,
    val payload: String,
)

/**
 * Encrypted account storage for providers that use their own protocol instead
 * of Room tables. Keeping the payload opaque lets new providers add fields
 * without another database migration.
 */
class ExternalAccountStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreCredentialCipher.shared

    fun load(platform: String): StoredExternalAccount? {
        val raw = prefs.getString(key(platform), null) ?: return null
        return runCatching {
            val json = JSONObject(cipher.decrypt(raw, purpose(platform)))
            val payload = json.optString("payload")
            if (payload.isBlank()) return null
            StoredExternalAccount(
                platform = platform,
                nickname = json.optString("nickname").ifBlank { "已登录账号" },
                payload = payload,
            )
        }.getOrNull()
    }

    fun save(platform: String, nickname: String, payload: String) {
        require(platform.isNotBlank() && payload.isNotBlank())
        val plain = JSONObject()
            .put("nickname", nickname.take(120))
            .put("payload", payload)
            .toString()
        val encrypted = cipher.encrypt(plain, purpose(platform))
        check(prefs.edit().putString(key(platform), encrypted).commit()) {
            "无法保存账号信息"
        }
    }

    fun delete(platform: String) {
        prefs.edit().remove(key(platform)).commit()
    }

    private fun key(platform: String) = "account_${platform.replace(Regex("[^a-z0-9_]"), "_")}"
    private fun purpose(platform: String) = "external_account_$platform"

    private companion object {
        const val PREFS = "external_drive_accounts"
    }
}
