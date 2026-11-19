package com.yunget.desktop

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.GitHubApi
import com.yunget.app.data.network.GuangYaApi
import com.yunget.app.data.network.GuangYaConstants
import com.yunget.app.data.network.GuangYaDevice
import com.yunget.app.data.network.ILanzouApi
import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.Pan115Api
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.Pan123LoginResult
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.TokenCheck
import com.yunget.app.data.network.UCApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal enum class DriveAccountKind(
    val displayName: String,
    val credentialLabel: String,
    val credentialHint: String,
    val supportsPersonalDrive: Boolean = true,
    val supportsPasswordLogin: Boolean = false,
) {
    QUARK("夸克网盘", "Cookie", "从 pan.quark.cn 请求头复制完整 Cookie"),
    BAIDU("百度网盘", "Cookie", "从 pan.baidu.com 复制含 BDUSS / STOKEN 的 Cookie"),
    UC("UC 网盘", "Cookie", "从 drive.uc.cn 请求头复制完整 Cookie"),
    PAN123("123 云盘", "Bearer Token", "可直接用账号密码登录，或粘贴 authorization Token", supportsPasswordLogin = true),
    PAN115("115 网盘", "Cookie", "从 115.com 复制含 UID / CID / SEID 的 Cookie"),
    ILANZOU("蓝奏优享", "appToken", "可直接用账号密码登录，或粘贴 appToken", supportsPasswordLogin = true),
    GUANGYA("光鸭网盘", "AccessToken", "粘贴光鸭网盘登录后的 AccessToken"),
    LANZOU("蓝奏云", "Cookie", "从 up.woozooo.com 复制登录后的 Cookie（含 phpdisk_info）"),
    FEIJIPAN("小飞机网盘", "Token", "粘贴小飞机网盘登录后的 Token", supportsPersonalDrive = false),
    GITHUB("GitHub", "Personal Access Token", "可选：填写 ghp_... / github_pat_... 将 API 限额提升到 5000 次/小时", supportsPersonalDrive = false);

    companion object {
        fun fromPlatform(platform: SharePlatform): DriveAccountKind? = when (platform) {
            SharePlatform.QUARK -> QUARK
            SharePlatform.BAIDU -> BAIDU
            SharePlatform.UC -> UC
            SharePlatform.PAN123 -> PAN123
            SharePlatform.PAN115 -> PAN115
            SharePlatform.ILANZOU -> ILANZOU
            SharePlatform.GUANGYA -> GUANGYA
            SharePlatform.LANZOU -> LANZOU
            SharePlatform.FEIJIPAN -> FEIJIPAN
            else -> null
        }
    }
}

internal data class DesktopAccount(
    val kind: DriveAccountKind,
    val credential: String,
    val nickname: String = "",
    val usedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val extra: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
)

internal class DesktopAccountStore(private val dataDirectory: File) {
    private val file = File(dataDirectory, "accounts.json")
    private val state = MutableStateFlow(load())
    val accounts = state.asStateFlow()

    fun get(kind: DriveAccountKind): DesktopAccount? = state.value[kind]

    fun credentialOf(kind: DriveAccountKind): String = state.value[kind]?.credential.orEmpty()

    fun remove(kind: DriveAccountKind) {
        val next = state.value.toMutableMap()
        next.remove(kind)
        state.value = next
        save(next)
    }

    fun saveAccount(account: DesktopAccount) {
        val next = state.value.toMutableMap()
        next[account.kind] = account.copy(updatedAt = System.currentTimeMillis())
        state.value = next
        save(next)
    }

    suspend fun verifyAndSave(kind: DriveAccountKind, rawCredential: String): DesktopAccount =
        withContext(Dispatchers.IO) {
            val credential = rawCredential.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            require(credential.isNotEmpty()) { "请输入有效凭证" }
            val verified = when (kind) {
                DriveAccountKind.QUARK -> {
                    val api = QuarkApi()
                    val nick = api.fetchNickname(credential) ?: error("夸克 Cookie 无效或已过期")
                    val quota = runCatching { api.getQuota(credential) }.getOrNull()
                    DesktopAccount(kind, credential, nick, quota?.used ?: -1L, quota?.total ?: -1L)
                }
                DriveAccountKind.BAIDU -> {
                    val api = BaiduApi()
                    val nick = api.fetchNickname(credential) ?: error("百度网盘 Cookie 无效或已过期")
                    val quota = runCatching { api.getQuota(credential) }.getOrNull()
                    DesktopAccount(kind, credential, nick, quota?.used ?: -1L, quota?.total ?: -1L)
                }
                DriveAccountKind.UC -> {
                    val api = UCApi()
                    val nick = api.fetchNickname(credential) ?: error("UC 网盘 Cookie 无效或已过期")
                    val quota = runCatching { api.getQuota(credential) }.getOrNull()
                    DesktopAccount(kind, credential, nick, quota?.used ?: -1L, quota?.total ?: -1L)
                }
                DriveAccountKind.PAN123 -> {
                    val api = Pan123Api()
                    val nick = api.fetchNickname(credential) ?: error("123 云盘 Token 无效或已过期")
                    val quota = runCatching { api.getQuota(credential) }.getOrNull()
                    DesktopAccount(kind, credential, nick, quota?.used ?: -1L, quota?.total ?: -1L)
                }
                DriveAccountKind.PAN115 -> {
                    val api = Pan115Api()
                    val nick = api.fetchNickname(credential) ?: error("115 网盘 Cookie 无效或已过期")
                    val quota = runCatching { api.getQuota(credential) }.getOrNull()
                    DesktopAccount(kind, credential, nick, quota?.used ?: -1L, quota?.total ?: -1L)
                }
                DriveAccountKind.ILANZOU -> {
                    val api = ILanzouApi()
                    val uuid = state.value[kind]?.extra?.get("uuid")?.takeIf { it.isNotBlank() } ?: api.getUuid()
                    val info = api.fetchAccountInfo(credential, uuid) ?: error("蓝奏优享 appToken 无效或已过期")
                    val quota = runCatching { api.getQuota(credential, uuid) }.getOrNull()
                    DesktopAccount(
                        kind = kind,
                        credential = credential,
                        nickname = info.nickname.ifBlank { "蓝奏优享用户" },
                        usedBytes = quota?.used ?: -1L,
                        totalBytes = quota?.total ?: -1L,
                        extra = mapOf("uuid" to uuid, "userId" to info.userId),
                    )
                }
                DriveAccountKind.GUANGYA -> {
                    val api = GuangYaApi()
                    val existing = state.value[kind]
                    val deviceId = existing?.extra?.get("deviceId").orEmpty().ifBlank { GuangYaConstants.newDeviceId() }
                    val deviceSign = existing?.extra?.get("deviceSign").orEmpty().ifBlank { GuangYaConstants.newDeviceSign(deviceId) }
                    val device = GuangYaDevice(deviceId = deviceId, deviceSign = deviceSign)
                    val info = api.fetchAccountInfo(credential, device) ?: error("光鸭 AccessToken 无效或已过期")
                    val quota = runCatching { api.getQuota(credential, device) }.getOrNull()
                    DesktopAccount(
                        kind = kind,
                        credential = credential,
                        nickname = info.nickname.ifBlank { "光鸭用户" },
                        usedBytes = quota?.used ?: -1L,
                        totalBytes = quota?.total ?: -1L,
                        extra = mapOf("deviceId" to deviceId, "deviceSign" to deviceSign),
                    )
                }
                DriveAccountKind.LANZOU -> {
                    val api = LanzouApi()
                    val params = api.fetchLoginParams(credential) ?: error("蓝奏云 Cookie 无效或已过期")
                    DesktopAccount(
                        kind = kind,
                        credential = credential,
                        nickname = params.uid.ifBlank { "蓝奏云用户" },
                        extra = mapOf("uid" to params.uid, "vei" to params.vei),
                    )
                }
                DriveAccountKind.FEIJIPAN -> {
                    // 小飞机免登录即可解析大多数分享；保存 Token 供后续接口使用
                    val _api = FeijipanApi()
                    DesktopAccount(kind, credential, "已配置凭证")
                }
                DriveAccountKind.GITHUB -> {
                    val api = GitHubApi(tokenProvider = { credential })
                    when (val check = api.validateToken(credential)) {
                        is TokenCheck.Valid -> DesktopAccount(
                            kind = kind,
                            credential = credential,
                            nickname = "@${check.login}",
                        )
                        is TokenCheck.Invalid -> error("GitHub Token 无效、已过期或已被撤销")
                        is TokenCheck.Unknown -> error("无法连接 GitHub API 校验 Token，请检查网络或代理设置")
                    }
                }
            }
            saveAccount(verified)
            verified
        }

    suspend fun refreshQuota(kind: DriveAccountKind): DesktopAccount? = withContext(Dispatchers.IO) {
        val existing = state.value[kind] ?: return@withContext null
        val quota = runCatching {
            when (kind) {
                DriveAccountKind.QUARK -> QuarkApi().getQuota(existing.credential)
                DriveAccountKind.BAIDU -> BaiduApi().getQuota(existing.credential)
                DriveAccountKind.UC -> UCApi().getQuota(existing.credential)
                DriveAccountKind.PAN123 -> Pan123Api().getQuota(existing.credential)
                DriveAccountKind.PAN115 -> Pan115Api().getQuota(existing.credential)
                DriveAccountKind.ILANZOU -> {
                    val uuid = existing.extra["uuid"].orEmpty()
                    if (uuid.isNotBlank()) ILanzouApi().getQuota(existing.credential, uuid) else null
                }
                DriveAccountKind.GUANGYA -> {
                    val deviceId = existing.extra["deviceId"].orEmpty().ifBlank { GuangYaConstants.newDeviceId() }
                    val deviceSign = existing.extra["deviceSign"].orEmpty().ifBlank { GuangYaConstants.newDeviceSign(deviceId) }
                    GuangYaApi().getQuota(existing.credential, GuangYaDevice(deviceId, deviceSign))
                }
                else -> null
            }
        }.getOrNull() ?: return@withContext existing
        val updated = existing.copy(usedBytes = quota.used, totalBytes = quota.total)
        saveAccount(updated)
        updated
    }

    suspend fun loginWithPassword(kind: DriveAccountKind, account: String, password: String): DesktopAccount =
        withContext(Dispatchers.IO) {
            when (kind) {
                DriveAccountKind.PAN123 -> {
                    val api = Pan123Api()
                    when (val result = api.passwordLogin(account.trim(), password)) {
                        is Pan123LoginResult.Success -> verifyAndSave(DriveAccountKind.PAN123, result.token)
                        is Pan123LoginResult.Failure -> error(result.message)
                    }
                }
                DriveAccountKind.ILANZOU -> {
                    val api = ILanzouApi()
                    val uuid = api.getUuid()
                    val token = api.login(account.trim(), password, uuid)
                    val info = api.fetchAccountInfo(token, uuid)
                    val quota = runCatching { api.getQuota(token, uuid) }.getOrNull()
                    val saved = DesktopAccount(
                        kind = DriveAccountKind.ILANZOU,
                        credential = token,
                        nickname = info?.nickname?.ifBlank { account.trim() } ?: account.trim(),
                        usedBytes = quota?.used ?: -1L,
                        totalBytes = quota?.total ?: -1L,
                        extra = mapOf("uuid" to uuid, "userId" to (info?.userId ?: "")),
                    )
                    saveAccount(saved)
                    saved
                }
                else -> error("${kind.displayName}不支持账号密码直登，请导入 ${kind.credentialLabel}")
            }
        }

    private fun load(): Map<DriveAccountKind, DesktopAccount> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val array = JSONArray(file.readText())
            val map = linkedMapOf<DriveAccountKind, DesktopAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val kind = DriveAccountKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: continue
                val extraObj = obj.optJSONObject("extra") ?: JSONObject()
                val extra = extraObj.keys().asSequence().associateWith(extraObj::optString)
                val cred = obj.optString("credential")
                if (cred.isBlank()) continue
                map[kind] = DesktopAccount(
                    kind = kind,
                    credential = cred,
                    nickname = obj.optString("nickname"),
                    usedBytes = obj.optLong("usedBytes", -1L),
                    totalBytes = obj.optLong("totalBytes", -1L),
                    extra = extra,
                    updatedAt = obj.optLong("updatedAt", 0L),
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun save(map: Map<DriveAccountKind, DesktopAccount>) {
        val array = JSONArray()
        for (acc in map.values) {
            array.put(
                JSONObject()
                    .put("kind", acc.kind.name)
                    .put("credential", acc.credential)
                    .put("nickname", acc.nickname)
                    .put("usedBytes", acc.usedBytes)
                    .put("totalBytes", acc.totalBytes)
                    .put("extra", JSONObject(acc.extra))
                    .put("updatedAt", acc.updatedAt)
            )
        }
        Files.createDirectories(file.parentFile.toPath())
        val tmp = file.resolveSibling("accounts.json.tmp")
        tmp.writeText(array.toString(2))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
