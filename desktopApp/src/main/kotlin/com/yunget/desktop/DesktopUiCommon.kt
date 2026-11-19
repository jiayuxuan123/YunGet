package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun DesktopPill(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun StatusDot(
    active: Boolean,
    activeColor: Color = MaterialTheme.colorScheme.tertiary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(if (active) activeColor else inactiveColor, CircleShape)
    )
}

@Composable
internal fun DesktopEmptyCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private enum class AccountLoginMode(val label: String) {
    WEB("网页登录"),
    PASSWORD("账号密码"),
    SMS("短信登录"),
    MANUAL("手动凭证"),
}

@Composable
internal fun DesktopAccountDialog(
    kind: DriveAccountKind,
    existing: DesktopAccount?,
    accountStore: DesktopAccountStore,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val allAccounts by accountStore.allAccounts.collectAsState()
    val kindAccounts = remember(allAccounts, kind) { accountStore.accountsOf(kind) }
    var addAsNewAccount by remember(kind) { mutableStateOf(false) }
    var editingRemarkId by remember(kind) { mutableStateOf<String?>(null) }
    var remarkDraft by remember(kind) { mutableStateOf("") }
    val modes = remember(kind) {
        buildList {
            if (kind.supportsWebLogin) add(AccountLoginMode.WEB)
            if (kind.supportsPasswordLogin) add(AccountLoginMode.PASSWORD)
            if (kind.supportsSmsLogin) add(AccountLoginMode.SMS)
            add(AccountLoginMode.MANUAL)
        }
    }
    var selectedMode by remember(kind) { mutableStateOf(modes.first()) }
    var credential by remember(kind, existing) { mutableStateOf(existing?.credential.orEmpty()) }
    var accountInput by remember(kind) { mutableStateOf("") }
    var passwordInput by remember(kind) { mutableStateOf("") }

    // 短信登录状态（迅雷 / 光鸭共用）
    var smsPhone by remember(kind) { mutableStateOf("") }
    var smsCode by remember(kind) { mutableStateOf("") }
    var xunleiCreditKey by remember(kind) { mutableStateOf("") }
    var xunleiSmsToken by remember(kind) { mutableStateOf("") }
    var guangyaVerificationId by remember(kind) { mutableStateOf("") }
    var guangyaIsUser by remember(kind) { mutableStateOf(true) }
    var smsHint by remember(kind) { mutableStateOf("") }
    var smsCountdown by remember(kind) { mutableIntStateOf(0) }
    var sendingSms by remember(kind) { mutableStateOf(false) }

    // 网页弹窗登录状态（Edge/Chrome CDP 自动捕获 Cookie / localStorage）
    val detectedBrowser = remember(kind) { DesktopBrowserLocator.findBrowser() }
    val webSpec = remember(kind) { DesktopWebLoginRegistry.specFor(kind) }
    var webSession by remember(kind) { mutableStateOf<DesktopWebLoginSession?>(null) }
    var webLaunching by remember(kind) { mutableStateOf(false) }
    var webStatus by remember(kind) { mutableStateOf("") }
    var webValidating by remember(kind) { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(smsCountdown) {
        if (smsCountdown > 0) {
            delay(1_000L)
            smsCountdown -= 1
        }
    }

    DisposableEffect(kind) {
        onDispose {
            webSession?.close()
            webSession = null
        }
    }

    // 自动轮询网页登录窗口凭证（对齐 Android WebLoginAutoDetect 节流与退避规则）
    LaunchedEffect(webSession) {
        val session = webSession ?: return@LaunchedEffect
        val spec = session.spec
        var lastValidateTs = 0L
        var lastFailedCred = ""
        var lastFailedTs = 0L
        while (webSession === session) {
            delay(1_200L)
            if (webSession !== session) break
            if (!session.checkAlive()) {
                webSession = null
                webStatus = "登录窗口已关闭，如需重新登录请再次点击打开。"
                break
            }
            if (webValidating || busy) continue
            val sampled = try {
                session.sampleCredential()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (sampled.isNullOrBlank() || !spec.isPlausible(sampled)) continue
            val now = System.currentTimeMillis()
            if (sampled == lastFailedCred && now - lastFailedTs < 10_000L) continue
            if (now - lastValidateTs < 5_000L) continue
            lastValidateTs = now
            webValidating = true
            webStatus = "检测到登录凭证，正在校验账号…"
            val savedResult = runCatching { accountStore.verifyAndSave(kind, sampled, addAsNew = addAsNewAccount) }
            webValidating = false
            savedResult.onSuccess { acc ->
                session.close()
                webSession = null
                onSaved("${kind.displayName}网页登录成功：${acc.nickname.ifBlank { "已连接" }}")
                onDismiss()
                return@LaunchedEffect
            }.onFailure {
                lastFailedCred = sampled
                lastFailedTs = System.currentTimeMillis()
                webStatus = "已检测到会话但尚未完成账号登录，请在浏览器窗口中继续完成登录…"
            }
        }
    }

    fun closeWebSession() {
        webSession?.close()
        webSession = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!busy && !webLaunching) {
                closeWebSession()
                onDismiss()
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${kind.displayName} 账号管理", style = MaterialTheme.typography.titleMedium)
                if (existing != null) {
                    DesktopPill(
                        text = existing.displayLabel,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (kindAccounts.size > 1) {
                    DesktopPill(text = "${kindAccounts.size} 个账号")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (kindAccounts.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "已绑定账号（${kindAccounts.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            kindAccounts.forEach { acc ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (acc.isPrimary) {
                                            DesktopPill(
                                                text = "主账号",
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                        Text(
                                            text = acc.displayLabel + if (acc.remark.isNotBlank() && acc.nickname.isNotBlank() && acc.remark != acc.nickname) " (${acc.nickname})" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (!acc.isPrimary) {
                                            TextButton(
                                                enabled = !busy,
                                                onClick = {
                                                    accountStore.setPrimary(kind, acc.id)
                                                    onSaved("已切换 ${kind.displayName} 主账号：${acc.displayLabel}")
                                                },
                                            ) { Text("设为主账号") }
                                        }
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                if (editingRemarkId == acc.id) {
                                                    editingRemarkId = null
                                                } else {
                                                    editingRemarkId = acc.id
                                                    remarkDraft = acc.remark
                                                }
                                            },
                                        ) { Text("备注") }
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                accountStore.removeById(acc.id)
                                                onSaved("已移除 ${kind.displayName} 账号：${acc.displayLabel}")
                                            },
                                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    }
                                    if (editingRemarkId == acc.id) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            OutlinedTextField(
                                                value = remarkDraft,
                                                onValueChange = { remarkDraft = it },
                                                placeholder = { Text("输入账号备注（留空清除）") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f),
                                            )
                                            FilledTonalButton(
                                                onClick = {
                                                    accountStore.updateRemark(acc.id, remarkDraft)
                                                    editingRemarkId = null
                                                },
                                            ) { Text("保存") }
                                        }
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Checkbox(
                                    checked = addAsNewAccount,
                                    onCheckedChange = { addAsNewAccount = it },
                                    enabled = !busy,
                                )
                                Text(
                                    text = "保存为新增账号（保留当前已有账号，并设为新主账号）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (modes.size > 1) {
                    val selectedIndex = modes.indexOf(selectedMode).coerceAtLeast(0)
                    PrimaryTabRow(selectedTabIndex = selectedIndex) {
                        modes.forEach { tabMode ->
                            Tab(
                                selected = selectedMode == tabMode,
                                onClick = {
                                    selectedMode = tabMode
                                    error = ""
                                },
                                text = { Text(tabMode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }

                when (selectedMode) {
                    AccountLoginMode.WEB -> {
                        val spec = webSpec
                        Text(
                            text = spec?.hint ?: "打开独立浏览器窗口完成登录并自动捕获凭证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    StatusDot(active = detectedBrowser != null)
                                    Text(
                                        text = if (detectedBrowser != null) {
                                            "浏览器引擎就绪：${detectedBrowser.displayName}（独立隔离会话）"
                                        } else {
                                            "未检测到 Edge / Chrome，请切换「手动凭证」导入"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (webStatus.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (webSession != null || webLaunching || webValidating) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        }
                                        Text(
                                            text = webStatus,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (webSession == null) {
                                        Button(
                                            enabled = detectedBrowser != null && !webLaunching && !busy,
                                            onClick = {
                                                webLaunching = true
                                                error = ""
                                                webStatus = "正在启动独立登录窗口…"
                                                scope.launch {
                                                    runCatching {
                                                        DesktopWebLoginSession.launch(kind, accountStore.storageDir)
                                                    }.onSuccess { session ->
                                                        webSession = session
                                                        webStatus = "登录窗口已打开（${session.browserName}），正在自动监听登录态…"
                                                    }.onFailure { err ->
                                                        webStatus = ""
                                                        error = err.message ?: "启动浏览器登录窗口失败"
                                                    }
                                                    webLaunching = false
                                                }
                                            },
                                        ) {
                                            Text(if (webLaunching) "启动中…" else "打开登录窗口（自动捕获）")
                                        }
                                    } else {
                                        FilledTonalButton(
                                            enabled = !webValidating && !busy,
                                            onClick = {
                                                val activeSession = webSession ?: return@FilledTonalButton
                                                busy = true
                                                error = ""
                                                webStatus = "正在手动抓取并校验登录态…"
                                                scope.launch {
                                                    val res = runCatching {
                                                        val captured = activeSession.sampleCredential()
                                                            ?.takeIf { it.isNotBlank() }
                                                            ?: error("尚未检测到登录凭证，请先在弹出的窗口中完成登录")
                                                        accountStore.verifyAndSave(kind, captured, addAsNew = addAsNewAccount)
                                                    }
                                                    busy = false
                                                    res.onSuccess { acc ->
                                                        closeWebSession()
                                                        onSaved("${kind.displayName}网页登录成功：${acc.nickname.ifBlank { "已连接" }}")
                                                        onDismiss()
                                                    }.onFailure { err ->
                                                        webStatus = "登录窗口仍在运行，可继续操作后重试抓取。"
                                                        error = err.message ?: "校验登录态失败"
                                                    }
                                                }
                                            },
                                        ) { Text("立即抓取当前登录态") }
                                        OutlinedButton(
                                            enabled = !busy,
                                            onClick = {
                                                closeWebSession()
                                                webStatus = "已关闭登录窗口。"
                                            },
                                        ) { Text("关闭窗口") }
                                    }
                                }
                            }
                        }
                    }

                    AccountLoginMode.PASSWORD -> {
                        OutlinedTextField(
                            value = accountInput,
                            onValueChange = { accountInput = it; error = "" },
                            label = { Text("手机号 / 账号") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it; error = "" },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    AccountLoginMode.SMS -> {
                        Text(
                            text = smsHint.ifBlank {
                                if (kind == DriveAccountKind.GUANGYA) {
                                    "输入光鸭绑定手机号，获取短信验证码后一键登录（新手机号自动注册）。"
                                } else {
                                    "输入迅雷手机号，点击获取短信验证码并完成登录。"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = smsPhone,
                                onValueChange = {
                                    smsPhone = it
                                    error = ""
                                    if (kind == DriveAccountKind.GUANGYA && (guangyaVerificationId.isNotBlank() || smsCountdown > 0)) {
                                        guangyaVerificationId = ""
                                        smsCountdown = 0
                                    }
                                },
                                label = { Text("手机号") },
                                singleLine = true,
                                enabled = !busy && !sendingSms,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(
                                enabled = !busy && !sendingSms && smsPhone.isNotBlank() && smsCountdown == 0,
                                onClick = {
                                    sendingSms = true
                                    error = ""
                                    scope.launch {
                                        if (kind == DriveAccountKind.GUANGYA) {
                                            runCatching { accountStore.guangyaSendSms(smsPhone) }
                                                .onSuccess { challenge ->
                                                    guangyaVerificationId = challenge.verificationId
                                                    guangyaIsUser = challenge.isUser
                                                    smsCountdown = 60
                                                    smsHint = "验证码已发送，请查收短信并在下方输入。"
                                                }
                                                .onFailure { err -> error = err.message ?: "发送短信验证码失败" }
                                        } else {
                                            runCatching { accountStore.xunleiSendSms(smsPhone) }
                                                .onSuccess { step ->
                                                    xunleiCreditKey = step.smsCreditKey
                                                    xunleiSmsToken = step.smsToken
                                                    smsCountdown = 60
                                                    smsHint = "验证码已发送，请查收短信并在下方输入。"
                                                }
                                                .onFailure { err -> error = err.message ?: "发送短信失败" }
                                        }
                                        sendingSms = false
                                    }
                                },
                            ) {
                                Text(
                                    when {
                                        sendingSms -> "发送中…"
                                        smsCountdown > 0 -> "${smsCountdown}s 后重发"
                                        else -> "发送验证码"
                                    }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = smsCode,
                            onValueChange = { smsCode = it; error = "" },
                            label = { Text("短信验证码") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    AccountLoginMode.MANUAL -> {
                        Text(
                            text = kind.credentialHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = credential,
                            onValueChange = { credential = it; error = "" },
                            label = { Text(kind.credentialLabel) },
                            minLines = 3,
                            maxLines = 6,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    val clip = readClipboardText().trim()
                                    if (clip.isNotEmpty()) {
                                        credential = clip
                                        error = ""
                                    }
                                },
                            ) { Text("从剪贴板粘贴") }
                            if (credential.isNotEmpty()) {
                                TextButton(enabled = !busy, onClick = { credential = "" }) { Text("清空") }
                            }
                        }
                    }
                }
                if (existing != null && existing.totalBytes > 0L) {
                    Text(
                        text = "已用空间：${formatBytes(existing.usedBytes.coerceAtLeast(0L))} / ${formatBytes(existing.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (DesktopCredentialCrypto.isWindows) {
                    Text(
                        text = "安全提示：账号凭据将通过 Windows DPAPI（当前用户密钥）本地加密保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (selectedMode != AccountLoginMode.WEB) {
                Button(
                    enabled = !busy && !sendingSms && when (selectedMode) {
                        AccountLoginMode.PASSWORD -> accountInput.isNotBlank() && passwordInput.isNotBlank()
                        AccountLoginMode.SMS -> smsPhone.isNotBlank() && smsCode.isNotBlank()
                        AccountLoginMode.MANUAL -> credential.isNotBlank()
                        AccountLoginMode.WEB -> false
                    },
                    onClick = {
                        busy = true
                        error = ""
                        scope.launch {
                            when (selectedMode) {
                                AccountLoginMode.PASSWORD -> {
                                    if (kind == DriveAccountKind.XUNLEI) {
                                        val res = runCatching {
                                            accountStore.xunleiPasswordLogin(accountInput, passwordInput, addAsNew = addAsNewAccount)
                                        }
                                        busy = false
                                        res.onSuccess { outcome ->
                                            when (outcome) {
                                                is XunleiLoginOutcome.LoggedIn -> {
                                                    closeWebSession()
                                                    onSaved("${kind.displayName}已登录：${outcome.account.nickname.ifBlank { "成功" }}")
                                                    onDismiss()
                                                }
                                                is XunleiLoginOutcome.NeedSms -> {
                                                    selectedMode = AccountLoginMode.SMS
                                                    smsPhone = outcome.mobile
                                                    xunleiCreditKey = outcome.step.smsCreditKey
                                                    xunleiSmsToken = outcome.step.smsToken
                                                    smsHint = outcome.step.message.ifBlank {
                                                        "当前设备首次登录需短信验证，请点击「发送验证码」完成登录。"
                                                    }
                                                }
                                            }
                                        }.onFailure { err ->
                                            error = err.message ?: "迅雷登录失败"
                                        }
                                    } else {
                                        val res = runCatching {
                                            accountStore.loginWithPassword(kind, accountInput, passwordInput, addAsNew = addAsNewAccount)
                                        }
                                        busy = false
                                        res.onSuccess { acc ->
                                            closeWebSession()
                                            onSaved("${kind.displayName}已登录：${acc.nickname.ifBlank { "成功" }}")
                                            onDismiss()
                                        }.onFailure { err ->
                                            error = err.message ?: "登录失败"
                                        }
                                    }
                                }

                                AccountLoginMode.SMS -> {
                                    val res = runCatching {
                                        if (kind == DriveAccountKind.GUANGYA) {
                                            accountStore.guangyaSmsLogin(
                                                phoneNumber = smsPhone,
                                                verificationId = guangyaVerificationId,
                                                code = smsCode,
                                                isUser = guangyaIsUser,
                                                addAsNew = addAsNewAccount,
                                            )
                                        } else {
                                            accountStore.xunleiSmsLogin(
                                                mobile = smsPhone,
                                                smsCode = smsCode,
                                                creditKey = xunleiCreditKey,
                                                smsToken = xunleiSmsToken,
                                                addAsNew = addAsNewAccount,
                                            )
                                        }
                                    }
                                    busy = false
                                    res.onSuccess { acc ->
                                        closeWebSession()
                                        onSaved("${kind.displayName}已登录：${acc.nickname.ifBlank { "成功" }}")
                                        onDismiss()
                                    }.onFailure { err ->
                                        error = err.message ?: "短信登录失败"
                                    }
                                }

                                AccountLoginMode.MANUAL -> {
                                    val res = runCatching { accountStore.verifyAndSave(kind, credential, addAsNew = addAsNewAccount) }
                                    busy = false
                                    res.onSuccess { acc ->
                                        closeWebSession()
                                        onSaved("${kind.displayName}已登录：${acc.nickname.ifBlank { "成功" }}")
                                        onDismiss()
                                    }.onFailure { err ->
                                        error = err.message ?: "校验失败"
                                    }
                                }

                                AccountLoginMode.WEB -> {
                                    busy = false
                                }
                            }
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when (selectedMode) {
                            AccountLoginMode.SMS -> "验证并登录"
                            AccountLoginMode.PASSWORD -> "登录并保存"
                            else -> "校验并保存"
                        }
                    )
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    TextButton(
                        enabled = !busy && !webLaunching,
                        onClick = {
                            closeWebSession()
                            accountStore.remove(kind)
                            onSaved("已退出${kind.displayName}")
                            onDismiss()
                        },
                    ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(
                    enabled = !busy && !webLaunching,
                    onClick = {
                        closeWebSession()
                        onDismiss()
                    },
                ) { Text("关闭") }
            }
        },
    )
}

@Composable
internal fun NewDownloadDialog(
    defaultDirectory: File,
    defaultHttpPolicy: Int,
    conflictPolicy: ConflictPolicy,
    onDismiss: () -> Unit,
    onSubmit: suspend (
        source: String,
        directory: File,
        fileName: String,
        kind: TransferKind,
        httpVersion: Int,
        sha256: String?,
        headers: Map<String, String>,
        autoRename: Boolean,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(defaultDirectory.absolutePath) }
    var sha256 by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun autofillFromSource(raw: String) {
        source = raw
        error = ""
        if (fileName.isBlank()) {
            val target = detectDesktopLinkTarget(raw)
            if (target is DesktopLinkTarget.Direct) {
                fileName = target.suggestedName
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("新建下载任务", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = source,
                    onValueChange = { autofillFromSource(it) },
                    label = { Text("下载地址 / 磁力链接 / 本地种子或 Metalink 路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clip = readClipboardText().trim()
                        if (clip.isNotEmpty()) autofillFromSource(clip)
                    }) { Text("粘贴剪贴板") }
                    OutlinedButton(onClick = {
                        pickTaskFile()?.let { file ->
                            source = file.absolutePath
                            fileName = sanitizeFileName(file.nameWithoutExtension, "task")
                            error = ""
                        }
                    }) { Text("选择种子 / Metalink") }
                }
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it; error = "" },
                    label = { Text("保存文件名（种子任务为保存目录名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = directory,
                        onValueChange = { directory = it },
                        label = { Text("保存目录") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        pickDirectory(File(directory))?.let { directory = it.absolutePath }
                    }) { Text("浏览") }
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "收起高级选项" else "高级选项（SHA-256 / 自定义请求头）")
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = sha256,
                        onValueChange = { sha256 = it.trim() },
                        label = { Text("预期 SHA-256（可选，64 位十六进制）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("自定义请求头（每行 Key: Value，可选）") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && source.isNotBlank(),
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        val res = runCatching {
                            val trimmed = source.trim().removeSurrounding("\"")
                            val kind = inferTransferKind(trimmed)
                            val resolvedName = sanitizeFileName(
                                fileName.ifBlank {
                                    (detectDesktopLinkTarget(trimmed) as? DesktopLinkTarget.Direct)?.suggestedName
                                        ?: "download.bin"
                                }
                            )
                            val parsedHeaders = parseHeaderLines(headersText)
                            onSubmit(
                                trimmed,
                                File(directory),
                                resolvedName,
                                kind,
                                defaultHttpPolicy,
                                sha256.takeIf { it.isNotBlank() },
                                parsedHeaders,
                                conflictPolicy == ConflictPolicy.AUTO_RENAME,
                            )
                        }
                        busy = false
                        res.onSuccess { onDismiss() }
                            .onFailure { err -> error = err.message ?: "提交任务失败" }
                    }
                },
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}

internal fun parseHeaderLines(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val out = linkedMapOf<String, String>()
    raw.lineSequence().forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) {
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
    }
    return out
}

internal fun pickDirectory(initial: File?): File? {
    val chooser = JFileChooser(initial?.takeIf { it.exists() } ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = "选择下载目录"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun pickTaskFile(): File? {
    val chooser = JFileChooser(File(System.getProperty("user.home"), "Downloads")).apply {
        dialogTitle = "选择 Torrent 或 Metalink 文件"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("下载描述文件 (*.torrent, *.metalink, *.meta4)", "torrent", "metalink", "meta4")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun pickUploadFiles(initialDir: File? = null): List<File> {
    val chooser = JFileChooser(initialDir?.takeIf { it.exists() } ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = "选择要上传到网盘的本地文件"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = true
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFiles?.toList().orEmpty().filter { it.isFile }
    } else {
        emptyList()
    }
}
