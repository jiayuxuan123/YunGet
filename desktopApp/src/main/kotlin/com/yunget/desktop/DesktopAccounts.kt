package com.yunget.desktop

import com.yunget.app.data.network.BaiduApi
import com.yunget.app.data.network.C139Api
import com.yunget.app.data.network.C139Constants
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.GitHubApi
import com.yunget.app.data.network.GuangYaApi
import com.yunget.app.data.network.GuangYaConstants
import com.yunget.app.data.network.GuangYaDevice
import com.yunget.app.data.network.GuangYaSmsChallenge
import com.yunget.app.data.network.ILanzouApi
import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.Pan115Api
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.Pan123LoginResult
import com.yunget.app.data.network.PanCaptcha
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.TokenCheck
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.network.WebCaptchaInit
import com.yunget.app.data.network.XunleiApi
import com.yunget.app.data.network.XunleiDeviceFingerprint
import com.yunget.app.data.network.XunleiLoginStep
import com.yunget.app.data.network.XunleiWebCredential
import com.yunget.app.data.network.video.VideoParsers
import com.yunget.app.data.network.video.VideoPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class DriveAccountKind(
    val displayName: String,
    val credentialLabel: String,
    val credentialHint: String,
    val supportsPersonalDrive: Boolean = true,
    val supportsPasswordLogin: Boolean = false,
    val videoPlatform: VideoPlatform? = null,
) {
    QUARK("夸克网盘", "Cookie", "从 pan.quark.cn 请求头复制完整 Cookie"),
    BAIDU("百度网盘", "Cookie", "从 pan.baidu.com 复制含 BDUSS / STOKEN 的 Cookie"),
    UC("UC 网盘", "Cookie", "从 drive.uc.cn 请求头复制完整 Cookie"),
    XUNLEI(
        "迅雷云盘",
        "网页凭据 JSON / AccessToken",
        "支持账号密码（含短信验证）登录，或粘贴 pan.xunlei.com localStorage 中的 credentials_Xqp0kJBXWhwaTpB6 JSON / access_token",
        supportsPasswordLogin = true,
    ),
    C139(
        "移动云盘",
        "Cookie",
        "从 yun.139.com 复制登录后的完整 Cookie（含 authorization 或 Os_SSo_Sid / RMKEY 与账号字段）",
    ),
    PAN123("123 云盘", "Bearer Token", "可直接用账号密码登录，或粘贴 authorization Token", supportsPasswordLogin = true),
    PAN115("115 网盘", "Cookie", "从 115.com 复制含 UID / CID / SEID 的 Cookie"),
    ILANZOU("蓝奏优享", "appToken", "可直接用账号密码登录，或粘贴 appToken", supportsPasswordLogin = true),
    GUANGYA("光鸭网盘", "AccessToken", "支持账号密码/短信验证码直登，或粘贴 AccessToken", supportsPasswordLogin = true),
    LANZOU("蓝奏云", "Cookie", "支持网页登录、账号密码直登，或粘贴含 phpdisk_info 的 Cookie", supportsPasswordLogin = true),
    FEIJIPAN("小飞机网盘", "Token", "支持账号密码直登，或粘贴 Token JSON", supportsPersonalDrive = true, supportsPasswordLogin = true),
    BILIBILI(
        "哔哩哔哩",
        "Cookie",
        "可选：从 bilibili.com 复制登录后的 Cookie（含 SESSDATA），登录后可解析更高清晰度",
        supportsPersonalDrive = false,
        videoPlatform = VideoPlatform.BILIBILI,
    ),
    XHS(
        "小红书",
        "Cookie",
        "从 xiaohongshu.com 复制登录后的 Cookie（含 web_session），用于解析小红书图文与视频笔记",
        supportsPersonalDrive = false,
        videoPlatform = VideoPlatform.XHS,
    ),
    PIXIV(
        "Pixiv",
        "Cookie",
        "可选：从 pixiv.net 复制登录后的 Cookie（含 PHPSESSID），用于解析受限与全量作品",
        supportsPersonalDrive = false,
        videoPlatform = VideoPlatform.PIXIV,
    ),
    GITHUB("GitHub", "Personal Access Token", "可选：填写 ghp_... / github_pat_... 将 API 限额提升到 5000 次/小时", supportsPersonalDrive = false);

    val isVideoPlatform: Boolean get() = videoPlatform != null
    val supportsWebLogin: Boolean get() = DesktopWebLoginRegistry.supportsWebLogin(this)
    val supportsSmsLogin: Boolean
        get() = this == XUNLEI || this == GUANGYA

    companion object {
        fun fromPlatform(platform: SharePlatform): DriveAccountKind? = when (platform) {
            SharePlatform.QUARK -> QUARK
            SharePlatform.BAIDU -> BAIDU
            SharePlatform.UC -> UC
            SharePlatform.XUNLEI -> XUNLEI
            SharePlatform.C139 -> C139
            SharePlatform.PAN123 -> PAN123
            SharePlatform.PAN115 -> PAN115
            SharePlatform.ILANZOU -> ILANZOU
            SharePlatform.GUANGYA -> GUANGYA
            SharePlatform.LANZOU -> LANZOU
            SharePlatform.FEIJIPAN -> FEIJIPAN
            else -> null
        }

        fun fromVideoPlatform(platform: VideoPlatform): DriveAccountKind? =
            entries.firstOrNull { it.videoPlatform == platform }
    }
}

internal sealed class XunleiLoginOutcome {
    data class LoggedIn(val account: DesktopAccount) : XunleiLoginOutcome()
    data class NeedSms(val step: XunleiLoginStep, val mobile: String) : XunleiLoginOutcome()
}

internal data class DesktopAccount(
    val kind: DriveAccountKind,
    val credential: String,
    val nickname: String = "",
    val usedBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val extra: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
    val remark: String = "",
    val isPrimary: Boolean = true,
) {
    val displayLabel: String
        get() = remark.ifBlank { nickname.ifBlank { "已连接" } }
}

private fun buildPrimaryMap(list: List<DesktopAccount>): Map<DriveAccountKind, DesktopAccount> {
    val map = linkedMapOf<DriveAccountKind, DesktopAccount>()
    for (kind in DriveAccountKind.entries) {
        val group = list.filter { it.kind == kind }
        if (group.isEmpty()) continue
        map[kind] = group.firstOrNull { it.isPrimary } ?: group.maxBy { it.updatedAt }
    }
    return map
}

internal class DesktopAccountStore(private val dataDirectory: File) {
    internal val storageDir: File get() = dataDirectory
    private val file = File(dataDirectory, "accounts.json")
    private val listState = MutableStateFlow(loadAll())
    private val state = MutableStateFlow(buildPrimaryMap(listState.value))
    val allAccounts: StateFlow<List<DesktopAccount>> = listState.asStateFlow()
    val accounts: StateFlow<Map<DriveAccountKind, DesktopAccount>> = state.asStateFlow()

    fun get(kind: DriveAccountKind): DesktopAccount? = state.value[kind]

    fun accountsOf(kind: DriveAccountKind): List<DesktopAccount> =
        listState.value
            .filter { it.kind == kind }
            .sortedWith(compareByDescending<DesktopAccount> { it.isPrimary }.thenByDescending { it.updatedAt })

    fun credentialOf(kind: DriveAccountKind): String = state.value[kind]?.credential.orEmpty()

    fun remove(kind: DriveAccountKind) {
        val primary = state.value[kind] ?: return
        removeById(primary.id)
    }

    fun removeById(accountId: String) {
        val current = listState.value
        val target = current.firstOrNull { it.id == accountId } ?: return
        val remaining = current.filterNot { it.id == accountId }.toMutableList()
        if (target.isPrimary) {
            val nextPrimaryIdx = remaining
                .withIndex()
                .filter { it.value.kind == target.kind }
                .maxByOrNull { it.value.updatedAt }
                ?.index
            if (nextPrimaryIdx != null) {
                remaining[nextPrimaryIdx] = remaining[nextPrimaryIdx].copy(isPrimary = true)
            }
        }
        commitList(remaining)
    }

    fun setPrimary(kind: DriveAccountKind, accountId: String) {
        val current = listState.value
        if (current.none { it.kind == kind && it.id == accountId }) return
        val updated = current.map { acc ->
            if (acc.kind == kind) acc.copy(isPrimary = acc.id == accountId) else acc
        }
        commitList(updated)
    }

    fun updateRemark(accountId: String, remark: String) {
        val trimmed = remark.trim()
        val updated = listState.value.map { acc ->
            if (acc.id == accountId) acc.copy(remark = trimmed) else acc
        }
        commitList(updated)
    }

    fun saveAccount(account: DesktopAccount, addAsNew: Boolean = false) {
        val now = System.currentTimeMillis()
        val current = listState.value
        val sameKind = current.filter { it.kind == account.kind }
        val existingMatch = if (addAsNew) {
            null
        } else {
            sameKind.firstOrNull { it.id == account.id }
                ?: sameKind.firstOrNull { it.isPrimary }
                ?: sameKind.firstOrNull()
        }
        val nextEntry = account.copy(
            id = existingMatch?.id ?: account.id.ifBlank { UUID.randomUUID().toString() },
            remark = account.remark.ifBlank { existingMatch?.remark.orEmpty() },
            isPrimary = true,
            updatedAt = now,
        )
        val updated = buildList(current.size + 1) {
            for (acc in current) {
                if (acc.id == nextEntry.id) continue
                if (acc.kind == account.kind) {
                    add(acc.copy(isPrimary = false))
                } else {
                    add(acc)
                }
            }
            add(nextEntry)
        }
        commitList(updated)
    }

    private fun commitList(next: List<DesktopAccount>) {
        listState.value = next
        state.value = buildPrimaryMap(next)
        saveAll(next)
    }

    fun configureXunleiApi(api: XunleiApi): XunleiApi {
        api.refreshTokenProvider = { devId, token -> refreshXunleiToken(api, devId, expectedAccess = token) }
        api.webCaptchaRefresher = { devId, action -> refreshXunleiWebCaptcha(api, devId, action) }
        return api
    }

    suspend fun refreshXunleiToken(
        api: XunleiApi, deviceId: String = "", expectedAccess: String? = null,
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val existing = state.value[DriveAccountKind.XUNLEI] ?: return@withContext null
            if (expectedAccess != null && existing.credential != expectedAccess) return@withContext null
            val refreshToken = existing.extra["refreshToken"].orEmpty()
            if (refreshToken.isBlank()) return@withContext null
            val effectiveDeviceId = deviceId.ifBlank {
                existing.extra["deviceId"].orEmpty().ifBlank { XunleiDeviceFingerprint.deviceId() }
            }
            val authType = existing.extra["authType"].orEmpty()
            val refreshed = api.refreshToken(refreshToken, effectiveDeviceId, authType) ?: return@withContext null
            if (state.value[DriveAccountKind.XUNLEI] != existing) return@withContext null
            val nextRefresh = refreshed.second.ifBlank { refreshToken }
            saveAccount(
                existing.copy(
                    credential = refreshed.first,
                    extra = existing.extra + mapOf(
                        "refreshToken" to nextRefresh,
                        "deviceId" to effectiveDeviceId,
                    ),
                )
            )
            refreshed.first to nextRefresh
        }

    private suspend fun refreshXunleiWebCaptcha(
        api: XunleiApi,
        deviceId: String,
        action: String,
    ): PanCaptcha? {
        val existing = state.value[DriveAccountKind.XUNLEI] ?: return null
        if (existing.extra["authType"] != XunleiWebCredential.AUTH_TYPE) return null
        api.cacheUserId(existing.credential)
        val effectiveDeviceId = deviceId.ifBlank {
            existing.extra["deviceId"].orEmpty().ifBlank { XunleiDeviceFingerprint.deviceId() }
        }
        return when (val init = api.initWebCaptcha(effectiveDeviceId, action)) {
            is WebCaptchaInit.Ok -> init.captcha
            else -> null
        }
    }

    suspend fun verifyAndSave(
        kind: DriveAccountKind,
        rawCredential: String,
        addAsNew: Boolean = false,
    ): DesktopAccount =
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
                DriveAccountKind.XUNLEI -> {
                    val parsed = XunleiWebCredential.parse(rawCredential)
                        ?: error("无法识别迅雷凭据，请粘贴 pan.xunlei.com 的 credentials JSON 或有效 access_token")
                    val api = XunleiApi()
                    val existing = state.value[kind]
                    val deviceId = parsed.deviceId.ifBlank {
                        existing?.extra?.get("deviceId").orEmpty().ifBlank { XunleiDeviceFingerprint.deviceId() }
                    }
                    val trimmedRaw = rawCredential.trim()
                    var authType = if (
                        trimmedRaw.startsWith("{") ||
                        trimmedRaw.startsWith("\"") ||
                        parsed.refreshToken.isNotBlank()
                    ) {
                        XunleiWebCredential.AUTH_TYPE
                    } else {
                        ""
                    }
                    var accessToken = parsed.accessToken
                    var refreshToken = parsed.refreshToken
                    api.cacheUserId(accessToken)
                    var captchaToken = parsed.captchaToken
                    if (captchaToken.isBlank() && authType == XunleiWebCredential.AUTH_TYPE) {
                        captchaToken = (api.initWebCaptcha(deviceId, "GET:/drive/v1/about") as? WebCaptchaInit.Ok)
                            ?.captcha?.token.orEmpty()
                    }
                    if (captchaToken.isBlank()) {
                        captchaToken = api.initCaptcha(
                            deviceId = deviceId,
                            username = "",
                            action = "GET:/drive/v1/about",
                            userId = parsed.userId,
                        ).orEmpty()
                    }
                    var valid = api.verifyAccessToken(accessToken, deviceId, captchaToken)
                    if (!valid && refreshToken.isNotBlank()) {
                        val refreshed = api.refreshToken(refreshToken, deviceId, authType)
                            ?: if (authType == XunleiWebCredential.AUTH_TYPE) {
                                api.refreshToken(refreshToken, deviceId, "").also {
                                    if (it != null) authType = ""
                                }
                            } else null
                        if (refreshed != null) {
                            accessToken = refreshed.first
                            refreshToken = refreshed.second.ifBlank { refreshToken }
                            api.cacheUserId(accessToken)
                            if (authType == XunleiWebCredential.AUTH_TYPE) {
                                captchaToken = (api.initWebCaptcha(deviceId, "GET:/drive/v1/about") as? WebCaptchaInit.Ok)
                                    ?.captcha?.token.orEmpty().ifBlank { captchaToken }
                            }
                            valid = api.verifyAccessToken(accessToken, deviceId, captchaToken)
                        }
                    }
                    if (!valid) {
                        error("迅雷凭据无效或已过期，请重新复制 pan.xunlei.com 的 credentials JSON 或使用账号密码登录")
                    }
                    if (authType == XunleiWebCredential.AUTH_TYPE) {
                        api.webCaptchaRefresher = { dev, act ->
                            (api.initWebCaptcha(dev, act) as? WebCaptchaInit.Ok)?.captcha
                        }
                    }
                    val quota = runCatching { api.getQuota(accessToken, deviceId, captchaToken) }.getOrNull()
                    val nickname = parsed.nickname.ifBlank {
                        parsed.userId.takeIf { it.isNotBlank() }?.let { "迅雷用户 #$it" } ?: "迅雷用户"
                    }
                    DesktopAccount(
                        kind = kind,
                        credential = accessToken,
                        nickname = nickname,
                        usedBytes = quota?.used ?: -1L,
                        totalBytes = quota?.total ?: -1L,
                        extra = mapOf(
                            "refreshToken" to refreshToken,
                            "deviceId" to deviceId,
                            "captchaToken" to captchaToken,
                            "authType" to authType,
                            "userId" to parsed.userId,
                        ),
                    )
                }
                DriveAccountKind.C139 -> {
                    val normalized = if (!credential.contains("=") && credential.startsWith("Basic ", ignoreCase = true)) {
                        "authorization=$credential"
                    } else {
                        credential
                    }
                    if (!C139Constants.isValidCookie(normalized)) {
                        error("移动云盘 Cookie 缺少 authorization 或 Os_SSo_Sid / RMKEY，请从 yun.139.com 复制完整 Cookie")
                    }
                    val api = C139Api()
                    if (C139Constants.extractAuthorization(normalized) != null) {
                        runCatching { api.listCloudFiles("/", normalized) }
                            .onFailure { err ->
                                error("移动云盘 Cookie 无效或已过期：${err.message ?: "鉴权失败"}")
                            }
                    }
                    val quota = runCatching { api.getQuota(normalized) }.getOrNull()
                    val nickname = C139Constants.extractAccount(normalized)
                        ?: C139Constants.extractAccountFull(normalized)
                        ?: "移动云盘用户"
                    DesktopAccount(
                        kind = kind,
                        credential = normalized,
                        nickname = nickname,
                        usedBytes = quota?.used ?: -1L,
                        totalBytes = quota?.total ?: -1L,
                        extra = mapOf("authorization" to C139Constants.extractAuthorization(normalized).orEmpty()),
                    )
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
                    val api = FeijipanApi()
                    val parsed = runCatching { JSONObject(credential) }.getOrNull()
                    val token = parsed?.optString("token").orEmpty().ifBlank { credential }
                    val uuid = parsed?.optString("uuid").orEmpty()
                    if (uuid.isNotBlank()) {
                        val info = api.account(token, uuid)
                        val storedJson = JSONObject()
                            .put("token", token)
                            .put("uuid", uuid)
                            .put("userId", info.userId)
                            .toString()
                        DesktopAccount(
                            kind = kind,
                            credential = storedJson,
                            nickname = info.nickname.ifBlank { "小飞机用户" },
                            usedBytes = info.usedBytes,
                            totalBytes = info.totalBytes,
                            extra = mapOf("token" to token, "uuid" to uuid, "userId" to info.userId),
                        )
                    } else {
                        DesktopAccount(kind, credential, "已配置凭证")
                    }
                }
                DriveAccountKind.BILIBILI, DriveAccountKind.XHS, DriveAccountKind.PIXIV -> {
                    val platform = kind.videoPlatform ?: error("不支持的视频平台")
                    val parser = VideoParsers.of(platform) ?: error("未找到 ${kind.displayName} 解析器")
                    val nickname = parser.validateCookie(credential)
                        ?: error("${kind.displayName} Cookie 无效或已过期")
                    DesktopAccount(
                        kind = kind,
                        credential = credential,
                        nickname = nickname.ifBlank { "${kind.displayName}已登录" },
                    )
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
            saveAccount(verified, addAsNew = addAsNew)
            verified
        }

    suspend fun refreshQuota(kind: DriveAccountKind): DesktopAccount? = withContext(Dispatchers.IO) {
        val existing = state.value[kind] ?: return@withContext null
        val quota = runCatching {
            when (kind) {
                DriveAccountKind.QUARK -> QuarkApi().getQuota(existing.credential)
                DriveAccountKind.BAIDU -> BaiduApi().getQuota(existing.credential)
                DriveAccountKind.UC -> UCApi().getQuota(existing.credential)
                DriveAccountKind.XUNLEI -> {
                    val api = configureXunleiApi(XunleiApi())
                    api.cacheUserId(existing.credential)
                    val deviceId = existing.extra["deviceId"].orEmpty().ifBlank { XunleiDeviceFingerprint.deviceId() }
                    val captcha = existing.extra["captchaToken"].orEmpty()
                    api.getQuota(existing.credential, deviceId, captcha)
                }
                DriveAccountKind.C139 -> C139Api().getQuota(existing.credential)
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
                DriveAccountKind.FEIJIPAN -> {
                    val parsed = runCatching { JSONObject(existing.credential) }.getOrNull()
                    val token = existing.extra["token"].orEmpty().ifBlank { parsed?.optString("token").orEmpty() }
                    val uuid = existing.extra["uuid"].orEmpty().ifBlank { parsed?.optString("uuid").orEmpty() }
                    if (token.isNotBlank() && uuid.isNotBlank()) {
                        val info = FeijipanApi().account(token, uuid)
                        com.yunget.app.data.network.model.QuotaInfo(used = info.usedBytes, total = info.totalBytes)
                    } else null
                }
                else -> null
            }
        }.getOrNull() ?: return@withContext existing
        val updated = existing.copy(usedBytes = quota.used, totalBytes = quota.total)
        saveAccount(updated)
        updated
    }

    suspend fun loginWithPassword(
        kind: DriveAccountKind,
        account: String,
        password: String,
        addAsNew: Boolean = false,
    ): DesktopAccount =
        withContext(Dispatchers.IO) {
            when (kind) {
                DriveAccountKind.PAN123 -> {
                    val api = Pan123Api()
                    when (val result = api.passwordLogin(account.trim(), password)) {
                        is Pan123LoginResult.Success -> verifyAndSave(DriveAccountKind.PAN123, result.token, addAsNew = addAsNew)
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
                    saveAccount(saved, addAsNew = addAsNew)
                    saved
                }
                DriveAccountKind.GUANGYA -> {
                    val cleanAccount = account.trim()
                    require(cleanAccount.isNotEmpty() && password.isNotEmpty()) { "请输入光鸭账号和密码" }
                    val api = GuangYaApi()
                    val device = guangyaDeviceForCurrent()
                    val tokens = api.signIn(cleanAccount, password, device)
                    val info = runCatching { api.fetchAccountInfo(tokens.accessToken, device) }.getOrNull()
                    val quota = runCatching { api.getQuota(tokens.accessToken, device) }.getOrNull()
                    val saved = DesktopAccount(
                        kind = DriveAccountKind.GUANGYA,
                        credential = tokens.accessToken,
                        nickname = info?.nickname.orEmpty().ifBlank { cleanAccount },
                        usedBytes = quota?.used ?: -1L,
                        totalBytes = quota?.total ?: -1L,
                        extra = mapOf(
                            "refreshToken" to tokens.refreshToken,
                            "deviceId" to device.deviceId,
                            "deviceSign" to device.deviceSign,
                            "userId" to info?.userId.orEmpty(),
                            "account" to cleanAccount,
                        ),
                    )
                    saveAccount(saved, addAsNew = addAsNew)
                    saved
                }
                DriveAccountKind.LANZOU -> {
                    val cleanAccount = account.trim()
                    require(cleanAccount.isNotEmpty() && password.isNotEmpty()) { "请输入蓝奏云账号和密码" }
                    val api = LanzouApi()
                    val cookie = api.login(cleanAccount, password)
                    val params = api.fetchLoginParams(cookie)
                        ?: error("蓝奏云登录成功但未能获取会话参数，请改用网页登录或手动粘贴 Cookie")
                    val saved = DesktopAccount(
                        kind = DriveAccountKind.LANZOU,
                        credential = cookie,
                        nickname = params.uid.ifBlank { cleanAccount },
                        extra = mapOf("uid" to params.uid, "vei" to params.vei),
                    )
                    saveAccount(saved, addAsNew = addAsNew)
                    saved
                }
                DriveAccountKind.FEIJIPAN -> {
                    val cleanAccount = account.trim()
                    require(cleanAccount.isNotEmpty() && password.isNotEmpty()) { "请输入小飞机账号和密码" }
                    val api = FeijipanApi()
                    val login = api.login(cleanAccount, password)
                    val storedJson = JSONObject()
                        .put("token", login.token)
                        .put("uuid", login.uuid)
                        .put("userId", login.account.userId)
                        .toString()
                    val saved = DesktopAccount(
                        kind = DriveAccountKind.FEIJIPAN,
                        credential = storedJson,
                        nickname = login.account.nickname.ifBlank { cleanAccount },
                        usedBytes = login.account.usedBytes,
                        totalBytes = login.account.totalBytes,
                        extra = mapOf(
                            "token" to login.token,
                            "uuid" to login.uuid,
                            "userId" to login.account.userId,
                        ),
                    )
                    saveAccount(saved, addAsNew = addAsNew)
                    saved
                }
                else -> error("${kind.displayName}不支持账号密码直登，请导入 ${kind.credentialLabel}")
            }
        }

    private fun guangyaDeviceForCurrent(): GuangYaDevice {
        val existing = state.value[DriveAccountKind.GUANGYA]
        val deviceId = existing?.extra?.get("deviceId").orEmpty().ifBlank { GuangYaConstants.newDeviceId() }
        val deviceSign = existing?.extra?.get("deviceSign").orEmpty().ifBlank { GuangYaConstants.newDeviceSign(deviceId) }
        return GuangYaDevice(deviceId = deviceId, deviceSign = deviceSign)
    }

    suspend fun guangyaSendSms(phoneNumber: String): GuangYaSmsChallenge = withContext(Dispatchers.IO) {
        val trimmed = phoneNumber.trim()
        require(trimmed.isNotEmpty()) { "请输入光鸭手机号" }
        val api = GuangYaApi()
        val device = guangyaDeviceForCurrent()
        api.sendSmsCode(api.normalizeAccount(trimmed), device)
    }

    suspend fun guangyaSmsLogin(
        phoneNumber: String,
        verificationId: String,
        code: String,
        isUser: Boolean,
        addAsNew: Boolean = false,
    ): DesktopAccount = withContext(Dispatchers.IO) {
        val trimmedPhone = phoneNumber.trim()
        val trimmedCode = code.trim()
        require(trimmedPhone.isNotEmpty()) { "请输入手机号" }
        require(verificationId.isNotBlank()) { "请先获取短信验证码" }
        require(trimmedCode.isNotEmpty()) { "请输入短信验证码" }
        val api = GuangYaApi()
        val device = guangyaDeviceForCurrent()
        val normalized = api.normalizeAccount(trimmedPhone)
        val verificationToken = api.verifySmsCode(verificationId, trimmedCode, device)
        val tokens = if (isUser) {
            api.smsSignIn(normalized, trimmedCode, verificationToken, device)
        } else {
            val digits = normalized.filter(Char::isDigit).takeLast(11)
            val fallbackName = if (digits.length >= 7) {
                "${digits.take(3)}****${digits.takeLast(4)}"
            } else {
                "光鸭用户"
            }
            api.smsSignUp(normalized, fallbackName, trimmedCode, verificationToken, device)
        }
        val info = runCatching { api.fetchAccountInfo(tokens.accessToken, device) }.getOrNull()
        val quota = runCatching { api.getQuota(tokens.accessToken, device) }.getOrNull()
        val saved = DesktopAccount(
            kind = DriveAccountKind.GUANGYA,
            credential = tokens.accessToken,
            nickname = info?.nickname.orEmpty().ifBlank { normalized },
            usedBytes = quota?.used ?: -1L,
            totalBytes = quota?.total ?: -1L,
            extra = mapOf(
                "refreshToken" to tokens.refreshToken,
                "deviceId" to device.deviceId,
                "deviceSign" to device.deviceSign,
                "userId" to info?.userId.orEmpty(),
                "account" to normalized,
            ),
        )
        saveAccount(saved, addAsNew = addAsNew)
        saved
    }

    suspend fun xunleiPasswordLogin(
        account: String,
        password: String,
        addAsNew: Boolean = false,
    ): XunleiLoginOutcome =
        withContext(Dispatchers.IO) {
            val trimmedAccount = account.trim()
            require(trimmedAccount.isNotEmpty() && password.isNotEmpty()) { "请填写迅雷账号与密码" }
            val api = XunleiApi()
            val deviceId = XunleiApi.newDeviceId()
            val step = api.loginWithPassword(trimmedAccount, password, deviceId)
            if (step.sessionId.isNotBlank()) {
                val saved = finalizeXunleiSession(api, step, trimmedAccount, deviceId, addAsNew = addAsNew)
                return@withContext XunleiLoginOutcome.LoggedIn(saved)
            }
            if (step.needSms) {
                val parsedParams = if (step.reviewUrl.isNotBlank()) {
                    XunleiApi.parseReviewUrl(step.reviewUrl)
                } else emptyMap()
                val mergedStep = step.copy(
                    smsCreditKey = step.smsCreditKey.ifBlank { parsedParams["creditkey"].orEmpty() },
                    smsToken = step.smsToken.ifBlank { parsedParams["token"].orEmpty() },
                )
                return@withContext XunleiLoginOutcome.NeedSms(mergedStep, trimmedAccount)
            }
            error(step.message.ifBlank { "迅雷登录失败，请检查账号密码或改用网页凭据导入" })
        }

    suspend fun xunleiSendSms(mobile: String): XunleiLoginStep = withContext(Dispatchers.IO) {
        val trimmed = mobile.trim()
        require(trimmed.isNotEmpty()) { "请输入接收短信验证码的手机号" }
        val api = XunleiApi()
        val step = api.sendSms(trimmed, XunleiApi.newDeviceId())
        if (step.smsCreditKey.isBlank()) {
            error(step.message.ifBlank { "发送短信验证码失败，请稍后重试或改用网页凭据导入" })
        }
        step
    }

    suspend fun xunleiSmsLogin(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String,
        addAsNew: Boolean = false,
    ): DesktopAccount = withContext(Dispatchers.IO) {
        val trimmedMobile = mobile.trim()
        val trimmedCode = smsCode.trim()
        require(trimmedMobile.isNotEmpty() && trimmedCode.isNotEmpty()) { "请填写手机号与短信验证码" }
        require(creditKey.isNotBlank()) { "请先点击发送短信验证码" }
        val api = XunleiApi()
        val deviceId = XunleiApi.newDeviceId()
        val step = api.smsLogin(trimmedMobile, trimmedCode, creditKey, smsToken, deviceId)
        if (step.sessionId.isBlank()) {
            error(step.message.ifBlank { "短信验证码登录失败，请检查验证码后重试" })
        }
        finalizeXunleiSession(api, step, trimmedMobile, deviceId, addAsNew = addAsNew)
    }

    private suspend fun finalizeXunleiSession(
        api: XunleiApi,
        step: XunleiLoginStep,
        accountHint: String,
        deviceId: String,
        addAsNew: Boolean = false,
    ): DesktopAccount {
        val captchaToken = api.initCaptcha(deviceId, accountHint, userId = step.userID).orEmpty()
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken)
            ?: error("迅雷换取登录令牌失败，请重试或改用网页凭据导入")
        val quota = runCatching { api.getQuota(tokens.first, deviceId, captchaToken) }.getOrNull()
        val saved = DesktopAccount(
            kind = DriveAccountKind.XUNLEI,
            credential = tokens.first,
            nickname = step.nickname.ifBlank { accountHint.ifBlank { "迅雷用户" } },
            usedBytes = quota?.used ?: -1L,
            totalBytes = quota?.total ?: -1L,
            extra = mapOf(
                "refreshToken" to tokens.second,
                "deviceId" to deviceId,
                "captchaToken" to captchaToken,
                "authType" to "",
                "userId" to step.userID,
            ),
        )
        saveAccount(saved, addAsNew = addAsNew)
        return saved
    }

    private fun loadAll(): List<DesktopAccount> {
        if (!file.isFile) return emptyList()
        var needsMigration = false
        return runCatching {
            val array = JSONArray(file.readText())
            val list = mutableListOf<DesktopAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val kind = DriveAccountKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: continue
                val rawCred = obj.optString("credential")
                if (rawCred.isBlank()) continue
                if (DesktopCredentialCrypto.isWindows && !DesktopCredentialCrypto.isEncrypted(rawCred)) {
                    needsMigration = true
                }
                val cred = runCatching { DesktopCredentialCrypto.unprotect(rawCred) }.getOrNull().orEmpty()
                if (cred.isBlank()) continue

                val extraEnc = obj.optString("extraEncrypted")
                val extra: Map<String, String> = if (extraEnc.isNotBlank()) {
                    val extraJson = runCatching { JSONObject(DesktopCredentialCrypto.unprotect(extraEnc)) }.getOrNull()
                    extraJson?.keys()?.asSequence()?.associateWith(extraJson::optString).orEmpty()
                } else {
                    val extraObj = obj.optJSONObject("extra") ?: JSONObject()
                    if (DesktopCredentialCrypto.isWindows && extraObj.length() > 0) needsMigration = true
                    extraObj.keys().asSequence().associateWith(extraObj::optString)
                }
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val hasPrimaryFlag = obj.has("isPrimary")
                list.add(
                    DesktopAccount(
                        kind = kind,
                        credential = cred,
                        nickname = obj.optString("nickname"),
                        usedBytes = obj.optLong("usedBytes", -1L),
                        totalBytes = obj.optLong("totalBytes", -1L),
                        extra = extra,
                        updatedAt = obj.optLong("updatedAt", 0L),
                        id = id,
                        remark = obj.optString("remark"),
                        isPrimary = if (hasPrimaryFlag) obj.optBoolean("isPrimary", true) else true,
                    )
                )
            }
            val normalized = mutableListOf<DesktopAccount>()
            for (kind in DriveAccountKind.entries) {
                val group = list.filter { it.kind == kind }
                if (group.isEmpty()) continue
                val primaryId = (group.firstOrNull { it.isPrimary } ?: group.maxBy { it.updatedAt }).id
                group.forEach { normalized.add(it.copy(isPrimary = it.id == primaryId)) }
            }
            if (needsMigration && normalized.isNotEmpty()) {
                runCatching { saveAll(normalized) }
            }
            normalized
        }.getOrDefault(emptyList())
    }

    private fun saveAll(list: List<DesktopAccount>) {
        val array = JSONArray()
        for (acc in list) {
            val obj = JSONObject()
                .put("id", acc.id)
                .put("kind", acc.kind.name)
                .put("credential", DesktopCredentialCrypto.protect(acc.credential))
                .put("nickname", acc.nickname)
                .put("remark", acc.remark)
                .put("isPrimary", acc.isPrimary)
                .put("usedBytes", acc.usedBytes)
                .put("totalBytes", acc.totalBytes)
                .put("updatedAt", acc.updatedAt)
            if (acc.extra.isNotEmpty()) {
                val extraRaw = JSONObject(acc.extra).toString()
                if (DesktopCredentialCrypto.isWindows) {
                    obj.put("extraEncrypted", DesktopCredentialCrypto.protect(extraRaw))
                } else {
                    obj.put("extra", JSONObject(acc.extra))
                }
            }
            array.put(obj)
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
