package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.ui.components.HttpPolicySelector
import com.yunget.app.ui.components.WebDavBackupDialog
import com.yunget.app.ui.components.SettingsSection
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopSettingsTab(
    dataDirectory: File,
    config: DesktopConfig,
    settingsStore: DesktopSettingsStore,
    accountStore: DesktopAccountStore,
    capabilities: JSONObject,
    onNotify: (String) -> Unit,
    onOpenAccountDialog: (DriveAccountKind) -> Unit,
    onOpenLogViewer: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    checkingUpdate: Boolean = false,
    historyStore: DesktopResolveHistoryStore? = null,
    onOpenProviderConcurrency: () -> Unit = {},
) {
    val accounts by accountStore.accounts.collectAsState()
    val scroll = rememberScrollState()
    var showWebDavDialog by remember { mutableStateOf(false) }
    if (showWebDavDialog) {
        WebDavBackupDialog(
            onDismiss = { showWebDavDialog = false },
            exportSnapshot = { exportDesktopWebDavSnapshot(accountStore, config, historyStore) },
            restoreSnapshot = { restoreDesktopWebDavSnapshot(it, accountStore, settingsStore, historyStore) },
            initialUrl = config.webdavUrl,
            initialUser = config.webdavUser,
            onRememberEndpoint = { u, usr ->
                settingsStore.update { it.copy(webdavUrl = u, webdavUser = usr) }
            },
        )
    }

    var speedLimitText by remember(config.speedLimitMbPerSec) {
        mutableStateOf(if (config.speedLimitMbPerSec <= 0.0) "" else config.speedLimitMbPerSec.toString())
    }
    var proxyText by remember(config.proxyUrl) { mutableStateOf(config.proxyUrl) }
    var dohText by remember(config.dohUrl) { mutableStateOf(config.dohUrl) }
    var mirrorText by remember(config.githubMirrorPrefix) { mutableStateOf(config.githubMirrorPrefix) }
    var sftpUserText by remember(config.sftpUsername) { mutableStateOf(config.sftpUsername) }
    var sftpPassText by remember(config.sftpPassword) { mutableStateOf(config.sftpPassword) }
    var sftpKnownHostsText by remember(config.sftpKnownHosts) { mutableStateOf(config.sftpKnownHosts) }
    var sftpPublicKeyText by remember(config.sftpPublicKey) { mutableStateOf(config.sftpPublicKey) }
    var sftpPrivateKeyText by remember(config.sftpPrivateKey) { mutableStateOf(config.sftpPrivateKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column {
                Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "配置下载目录、TurboDL 原生引擎参数、网络代理、外观与网盘账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(
                title = "存储与文件",
                description = "决定新任务的默认落盘目录以及目标文件重名时的处理策略。",
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("默认下载目录", style = MaterialTheme.typography.titleSmall)
                            Text(
                                config.downloadDirectory,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { revealInExplorer(File(config.downloadDirectory)) }) {
                            Text("打开目录")
                        }
                        FilledTonalButton(onClick = {
                            pickDirectory(File(config.downloadDirectory))?.let { chosen ->
                                settingsStore.update { it.copy(downloadDirectory = chosen.absolutePath) }
                                onNotify("默认下载目录已更新")
                            }
                        }) { Text("更改目录") }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("重名文件处理策略", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ConflictPolicy.entries.forEach { policy ->
                                FilterChip(
                                    selected = config.conflictPolicy == policy,
                                    onClick = {
                                        settingsStore.update { it.copy(conflictPolicy = policy) }
                                    },
                                    label = { Text(policy.label) },
                                )
                            }
                        }
                        Text(
                            config.conflictPolicy.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsSection(
                title = "TurboDL 下载引擎",
                description = "协议策略、并发任务数、单任务分片线程数与全局限速。",
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HttpPolicySelector(
                        value = config.httpPolicy,
                        allowHttp3 = capabilities.optBoolean("http3"),
                        onChange = { next -> settingsStore.update { it.copy(httpPolicy = next) } },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    StepperRow(
                        title = "最大同时下载任务数",
                        subtitle = "超过上限的新任务将自动排队等待",
                        value = config.maxConcurrentTasks,
                        min = 1,
                        max = 8,
                        step = 1,
                        unit = "个",
                        onChange = { v -> settingsStore.update { it.copy(maxConcurrentTasks = v) } },
                    )

                    StepperRow(
                        title = "单任务并发连接数",
                        subtitle = "HTTP 分片上限取决于服务器的 Range 支持和实际传输情况",
                        value = config.connectionsPerTask,
                        min = 1,
                        max = MAX_DESKTOP_HTTP_CONNECTIONS,
                        step = if (config.connectionsPerTask >= 16) 4 else 2,
                        unit = "线程",
                        onChange = { v -> settingsStore.update { it.copy(connectionsPerTask = v) } },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (connections in listOf(16, 32, 64, 128, 256, MAX_DESKTOP_HTTP_CONNECTIONS)) {
                            FilterChip(
                                selected = config.connectionsPerTask == connections,
                                onClick = { settingsStore.update { it.copy(connectionsPerTask = connections) } },
                                label = { Text("$connections 线程") },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("分网盘安全并发与快捷调节", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (config.providerSafeCapEnabled) {
                                    "默认开启（迅雷 8 / 百度·UC·115 16 / 123·139 等 32 / 夸克 64；已自定义 ${config.providerThreadOverrides.size} 项），可随时调高或关闭"
                                } else {
                                    "已关闭默认兜底：未单独设置的网盘全部跟随全局 ${config.connectionsPerTask} 线程"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = onOpenProviderConcurrency) {
                            Text("快捷调节")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = speedLimitText,
                            onValueChange = { speedLimitText = it },
                            label = { Text("全局限速（MB/s，留空或 0 表示不限速）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = {
                            val parsed = speedLimitText.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                            settingsStore.update { it.copy(speedLimitMbPerSec = parsed) }
                            onNotify(if (parsed <= 0.0) "已取消全局限速" else "全局限速已设为 $parsed MB/s")
                        }) { Text("应用") }
                    }
                }
            }

            SettingsSection(
                title = "网络、代理与镜像加速",
                description = "代理同时作用于网盘解析接口与 TurboDL 下载内核；GitHub 镜像仅用于 GitHub 源码与 Release 下载。",
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = proxyText,
                            onValueChange = { proxyText = it },
                            label = { Text("网络代理（例如 http://127.0.0.1:7890 或 socks5://127.0.0.1:7891）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = {
                            settingsStore.update { it.copy(proxyUrl = proxyText.trim()) }
                            onNotify(if (proxyText.isBlank()) "已关闭自定义代理" else "代理设置已保存")
                        }) { Text("保存") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = dohText,
                            onValueChange = { dohText = it },
                            label = { Text("DoH 地址（可选，例如 https://dns.alidns.com/dns-query）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = {
                            settingsStore.update { it.copy(dohUrl = dohText.trim()) }
                            onNotify(if (dohText.isBlank()) "已恢复系统 DNS" else "DoH 地址已保存")
                        }) { Text("保存") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = mirrorText,
                            onValueChange = { mirrorText = it },
                            label = { Text("GitHub 下载镜像前缀（可选，例如 https://ghfast.top/）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = {
                            settingsStore.update { it.copy(githubMirrorPrefix = mirrorText.trim()) }
                            onNotify(if (mirrorText.isBlank()) "已关闭 GitHub 镜像加速" else "GitHub 镜像前缀已保存")
                        }) { Text("保存") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            mirrorText = "https://ghfast.top/"
                            settingsStore.update { it.copy(githubMirrorPrefix = mirrorText) }
                            onNotify("已启用镜像 https://ghfast.top/")
                        }) { Text("填入 ghfast.top") }
                        if (config.githubMirrorPrefix.isNotBlank()) {
                            TextButton(onClick = {
                                mirrorText = ""
                                settingsStore.update { it.copy(githubMirrorPrefix = "") }
                                onNotify("已清除 GitHub 镜像前缀")
                            }) { Text("恢复直连") }
                        }
                    }
                    HorizontalDivider()
                    Text("SFTP 凭据与主机校验", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "用于 sftp:// 任务默认认证及 known_hosts 主机密钥校验（URL 自带用户名密码时优先使用 URL 凭据）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = sftpUserText,
                            onValueChange = { sftpUserText = it },
                            label = { Text("SFTP 用户名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = sftpPassText,
                            onValueChange = { sftpPassText = it },
                            label = { Text("SFTP 密码（可选）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = sftpKnownHostsText,
                        onValueChange = { sftpKnownHostsText = it },
                        label = { Text("known_hosts 文件路径（主机校验）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = sftpPublicKeyText,
                            onValueChange = { sftpPublicKeyText = it },
                            label = { Text("SSH 公钥路径（可选）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = sftpPrivateKeyText,
                            onValueChange = { sftpPrivateKeyText = it },
                            label = { Text("SSH 私钥路径（可选）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilledTonalButton(onClick = {
                            settingsStore.update {
                                it.copy(
                                    sftpUsername = sftpUserText.trim(),
                                    sftpPassword = sftpPassText,
                                    sftpKnownHosts = sftpKnownHostsText.trim(),
                                    sftpPublicKey = sftpPublicKeyText.trim(),
                                    sftpPrivateKey = sftpPrivateKeyText.trim(),
                                )
                            }
                            onNotify("SFTP 配置已保存")
                        }) { Text("保存 SFTP 配置") }
                    }
                }
            }

            SettingsSection(title = "外观与主题") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("主题模式", style = MaterialTheme.typography.titleSmall)
                        Text("支持跟随系统或手动切换浅色 / 深色主题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = config.themeMode == mode,
                                onClick = { settingsStore.update { it.copy(themeMode = mode) } },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                }
            }

            SettingsSection(
                title = "账号与凭证",
                description = "点击任意平台卡片以登录、导入 Cookie / Token 或退出登录。",
            ) {
                val driveAccounts = DriveAccountKind.entries.filter { !it.isVideoPlatform && it != DriveAccountKind.GITHUB }
                val mediaAndDevAccounts = DriveAccountKind.entries.filter { it.isVideoPlatform || it == DriveAccountKind.GITHUB }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "网盘账号（11 个平台）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        driveAccounts.forEach { kind ->
                            val acc = accounts[kind]
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (acc != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.width(190.dp).clickable { onOpenAccountDialog(kind) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        StatusDot(active = acc != null)
                                        Text(kind.displayName, style = MaterialTheme.typography.titleSmall)
                                    }
                                    Text(
                                        text = acc?.nickname?.ifBlank { "已连接" } ?: "点击配置",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Text(
                        "视频解析与 GitHub 凭证",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        mediaAndDevAccounts.forEach { kind ->
                            val acc = accounts[kind]
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (acc != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.width(190.dp).clickable { onOpenAccountDialog(kind) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        StatusDot(active = acc != null)
                                        Text(kind.displayName, style = MaterialTheme.typography.titleSmall)
                                    }
                                    Text(
                                        text = acc?.nickname?.ifBlank { "已连接" } ?: "点击配置",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsSection(
                title = "桌面体验与通知",
                description = "控制系统托盘常驻、下载完成通知与剪贴板自动识别。",
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ToggleRow(
                        title = "关闭窗口时最小化到系统托盘",
                        subtitle = "点击右上角关闭按钮后在托盘继续下载；可在托盘右键彻底退出",
                        checked = config.minimizeToTray,
                        onCheckedChange = { checked ->
                            settingsStore.update { it.copy(minimizeToTray = checked) }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ToggleRow(
                        title = "下载完成 / 失败系统通知",
                        subtitle = "任务完成或发生错误时弹出 Windows 系统通知",
                        checked = config.systemNotifications,
                        onCheckedChange = { checked ->
                            settingsStore.update { it.copy(systemNotifications = checked) }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ToggleRow(
                        title = "剪贴板自动监听",
                        subtitle = "复制网盘分享、视频链接、GitHub 或磁力链接时弹出顶部快捷解析提示",
                        checked = config.clipboardMonitor,
                        onCheckedChange = { checked ->
                            settingsStore.update { it.copy(clipboardMonitor = checked) }
                            onNotify(if (checked) "已开启剪贴板监听" else "已关闭剪贴板监听")
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ToggleRow(
                        title = "下载时阻止系统休眠",
                        subtitle = "挂机下载大文件时防止 Windows 进入睡眠中断网络（不影响屏幕熄屏）",
                        checked = config.preventSleepWhileDownloading,
                        onCheckedChange = { checked ->
                            settingsStore.update { it.copy(preventSleepWhileDownloading = checked) }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("全部任务完成后动作", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PostDownloadAction.entries.forEach { action ->
                                FilterChip(
                                    selected = config.postDownloadAction == action,
                                    onClick = { settingsStore.update { it.copy(postDownloadAction = action) } },
                                    label = { Text(action.label) },
                                )
                            }
                        }
                        Text(
                            config.postDownloadAction.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsSection(
                title = "浏览器扩展接管与手机局域网推送",
                description = "配合「云取链接助手」一键发送网页链接，或让手机端云取把大文件推送到电脑满速下载。",
            ) {
                var relayPortText by remember(config.relayPort) { mutableStateOf(config.relayPort.toString()) }
                val lanIps = remember(config.relayAllowLan) { DesktopRelayServer.localLanAddresses() }
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ToggleRow(
                        title = "启用本地链接接收服务",
                        subtitle = "监听 127.0.0.1:${config.relayPort}，接收本机浏览器「云取链接助手」扩展发来的链接",
                        checked = config.relayEnabled,
                        onCheckedChange = { checked ->
                            settingsStore.update { it.copy(relayEnabled = checked) }
                        },
                    )
                    if (config.relayEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = relayPortText,
                                onValueChange = { relayPortText = it.filter(Char::isDigit).take(5) },
                                label = { Text("监听端口") },
                                singleLine = true,
                                modifier = Modifier.width(150.dp),
                            )
                            FilledTonalButton(onClick = {
                                val parsed = relayPortText.toIntOrNull()
                                if (parsed == null || parsed !in 1024..65535) {
                                    onNotify("端口需在 1024–65535 之间")
                                } else {
                                    settingsStore.update { it.copy(relayPort = parsed) }
                                    onNotify("接收端口已更新为 $parsed")
                                }
                            }) { Text("保存端口") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ToggleRow(
                            title = "允许局域网手机推送（需配对令牌）",
                            subtitle = "开启后手机端云取可把分享链接或直链推送到本机解析/下载",
                            checked = config.relayAllowLan,
                            onCheckedChange = { checked ->
                                settingsStore.update { it.copy(relayAllowLan = checked) }
                            },
                        )
                        if (config.relayAllowLan) {
                            val primaryEndpoint = "http://${lanIps.firstOrNull() ?: "127.0.0.1"}:${config.relayPort}"
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "手机端填写地址：$primaryEndpoint",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (lanIps.size > 1) {
                                    Text(
                                        "其他可用局域网 IP：${lanIps.drop(1).joinToString(" / ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "配对令牌：${config.relayToken}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(onClick = {
                                        copyToClipboard(primaryEndpoint)
                                        onNotify("已复制局域网接收地址")
                                    }) { Text("复制地址") }
                                    OutlinedButton(onClick = {
                                        copyToClipboard(config.relayToken)
                                        onNotify("已复制配对令牌")
                                    }) { Text("复制令牌") }
                                    TextButton(onClick = {
                                        val nextToken = com.yunget.app.data.network.CoreDeviceIds.randomHex32().take(16)
                                        settingsStore.update { it.copy(relayToken = nextToken) }
                                        onNotify("已重置配对令牌")
                                    }) { Text("重置令牌") }
                                }
                            }
                        }
                    }
                }
            }

            SettingsSection(
                title = "配置备份、诊断与更新",
                description = "导入/导出桌面端配置 JSON、排查运行日志或检查新版本（当前 ${DesktopAppMeta.currentVersion}）。",
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val outFile = pickExportJsonFile()
                                if (outFile != null) {
                                    runCatching {
                                        outFile.writeText(settingsStore.exportConfigJson(includeSftpSecrets = false))
                                    }.onSuccess {
                                        onNotify("已导出配置到：${outFile.name}")
                                    }.onFailure { err ->
                                        onNotify("导出失败：${err.message.orEmpty()}")
                                    }
                                }
                            },
                        ) { Text("导出配置备份") }
                        OutlinedButton(
                            onClick = {
                                val inFile = pickImportJsonFile()
                                if (inFile != null) {
                                    runCatching {
                                        settingsStore.importConfigJson(inFile.readText())
                                    }.onSuccess {
                                        onNotify("已导入配置备份：${inFile.name}")
                                    }.onFailure { err ->
                                        onNotify("导入失败：${err.message.orEmpty()}")
                                    }
                                }
                            },
                        ) { Text("导入配置备份") }
                        OutlinedButton(onClick = { showWebDavDialog = true }) { Text("WebDAV 加密备份") }
                        OutlinedButton(onClick = onOpenLogViewer) {
                            Text("打开日志查看器")
                        }
                        OutlinedButton(
                            enabled = !checkingUpdate,
                            onClick = onCheckUpdate,
                        ) {
                            Text(if (checkingUpdate) "检查中…" else "检查更新")
                        }
                    }
                    Text(
                        text = "提示：配置导出默认不包含账号凭据（账号凭据已由 Windows DPAPI 加密保护）与 SFTP 私钥/密码。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSection(
                title = "引擎能力与数据目录",
                description = "YunGet Windows 桌面版（原生 TurboDL 内核 + Compose Desktop）。",
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val capPairs = listOf(
                            "HTTP/3 (QUIC)" to capabilities.optBoolean("http3_compiled"),
                            "HTTP/2" to capabilities.optBoolean("http2_compiled"),
                            "HLS / DASH" to (capabilities.optBoolean("hls") || capabilities.optBoolean("dash")),
                            "BitTorrent" to capabilities.optBoolean("bittorrent"),
                            "Metalink" to capabilities.optBoolean("metalink"),
                            "FTP" to capabilities.optBoolean("ftp"),
                            "SFTP" to capabilities.optBoolean("sftp"),
                            "Browser CDP" to (DesktopBrowserLocator.findBrowser() != null),
                            "DPAPI 凭据加密" to DesktopCredentialCrypto.isWindows,
                        )
                        capPairs.forEach { (label, enabled) ->
                            DesktopPill(
                                text = if (enabled) "$label ✓" else "$label —",
                                containerColor = if (enabled) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (enabled) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "配置与缓存目录：${dataDirectory.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { revealInExplorer(dataDirectory) }) {
                            Text("打开数据目录")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = value > min,
                onClick = { onChange((value - step).coerceAtLeast(min)) },
            ) { Text("-") }
            Text(
                "$value $unit",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(68.dp),
            )
            OutlinedButton(
                enabled = value < max,
                onClick = { onChange((value + step).coerceAtMost(max)) },
            ) { Text("+") }
        }
    }
}
