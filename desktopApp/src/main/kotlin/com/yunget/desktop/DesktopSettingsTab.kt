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
) {
    val accounts by accountStore.accounts.collectAsState()
    val scroll = rememberScrollState()

    var speedLimitText by remember(config.speedLimitMbPerSec) {
        mutableStateOf(if (config.speedLimitMbPerSec <= 0.0) "" else config.speedLimitMbPerSec.toString())
    }
    var proxyText by remember(config.proxyUrl) { mutableStateOf(config.proxyUrl) }
    var dohText by remember(config.dohUrl) { mutableStateOf(config.dohUrl) }
    var mirrorText by remember(config.githubMirrorPrefix) { mutableStateOf(config.githubMirrorPrefix) }

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
                        subtitle = "多线程分片下载并发度（HTTP/1.1 或支持 Range 的 CDN 效果显著）",
                        value = config.connectionsPerTask,
                        min = 1,
                        max = 64,
                        step = if (config.connectionsPerTask >= 16) 4 else 2,
                        unit = "线程",
                        onChange = { v -> settingsStore.update { it.copy(connectionsPerTask = v) } },
                    )

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
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DriveAccountKind.entries.forEach { kind ->
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
                            "HTTP/3 (QUIC)" to capabilities.optBoolean("http3"),
                            "HTTP/2" to capabilities.optBoolean("http2", true),
                            "HLS / DASH" to (capabilities.optBoolean("hls", true) || capabilities.optBoolean("media", true)),
                            "BitTorrent" to capabilities.optBoolean("torrent", true),
                            "Metalink" to capabilities.optBoolean("metalink", true),
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
