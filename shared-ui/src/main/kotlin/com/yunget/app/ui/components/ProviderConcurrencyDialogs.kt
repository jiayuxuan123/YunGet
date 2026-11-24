package com.yunget.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.data.download.ProviderDownloadPolicy

/**
 * 更新后首次弹窗说明：告知用户「分网盘安全并发」只是默认值，随时可调高或关闭，并直达快捷调节面板。
 */
@Composable
internal fun ProviderConcurrencyNoticeDialog(
    globalConnections: Int,
    onOpenQuickTune: () -> Unit,
    onDismissForever: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissForever,
        title = { Text("分网盘并发策略更新说明") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "为避免开启高并发（如 128/512 线程）下直链时误伤易风控网盘账号，本版已为各网盘预设了一套默认安全并发上限：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "• 普通直链 / GitHub：仍直接跟随全局（当前 $globalConnections 线程，最高 512）\n" +
                        "• 迅雷网盘：默认 8 线程\n" +
                        "• 百度 / UC / 115 网盘：默认 16 线程\n" +
                        "• 123 / 139 / 光鸭 / 蓝奏 / 小飞机 / 城通：默认 32 线程\n" +
                        "• 夸克网盘：默认 64 线程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "注意：以上只是推荐默认值，不是写死限制。你随时可以在「分网盘并发快捷调节」里单独调高某个网盘的线程、设为跟随全局，或一键关闭兜底保护。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenQuickTune) { Text("去快捷调节") }
        },
        dismissButton = {
            TextButton(onClick = onDismissForever) { Text("知道了，不再提示") }
        },
    )
}

/**
 * 跨端共享的「分网盘并发快捷调节」界面（Android 与 Windows 桌面端共用）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderConcurrencyQuickDialog(
    globalConnections: Int,
    maxAllowedConnections: Int,
    safeCapEnabled: Boolean,
    overrides: Map<String, Int>,
    onSafeCapEnabledChange: (Boolean) -> Unit,
    onOverrideChange: (providerKey: String, value: Int?) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tierOptions = listOf(8, 16, 32, 64, 128, 256, 512).filter { it <= maxAllowedConnections }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分网盘并发快捷调节") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("启用分网盘默认安全兜底", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (safeCapEnabled) {
                                "未单独设置的网盘使用推荐安全上限；全局当前为 $globalConnections 线程"
                            } else {
                                "已关闭兜底：未单独设置的网盘全部跟随全局（$globalConnections 线程）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = safeCapEnabled, onCheckedChange = onSafeCapEnabledChange)
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (provider in ProviderDownloadPolicy.managedProviders) {
                        val custom = overrides[provider.key]
                        val effective = ProviderDownloadPolicy.effectiveConnections(
                            platform = provider.key,
                            url = "",
                            configured = globalConnections,
                            enforceSafeCap = safeCapEnabled,
                            providerOverrides = overrides,
                            maxAllowed = maxAllowedConnections,
                        )
                        val statusText = when {
                            custom == ProviderDownloadPolicy.FOLLOW_GLOBAL -> "跟随全局（当前 $effective 线程）"
                            custom != null && custom > 0 -> "自定义 $custom 线程"
                            safeCapEnabled -> "推荐默认（上限 ${provider.defaultSafeCap}，当前 $effective）"
                            else -> "跟随全局（当前 $effective 线程）"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    provider.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = custom == null,
                                    onClick = { onOverrideChange(provider.key, null) },
                                    label = { Text("默认(${provider.defaultSafeCap})") },
                                )
                                FilterChip(
                                    selected = custom == ProviderDownloadPolicy.FOLLOW_GLOBAL,
                                    onClick = { onOverrideChange(provider.key, ProviderDownloadPolicy.FOLLOW_GLOBAL) },
                                    label = { Text("跟随全局") },
                                )
                                for (tier in tierOptions) {
                                    FilterChip(
                                        selected = custom == tier,
                                        onClick = { onOverrideChange(provider.key, tier) },
                                        label = { Text("$tier") },
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onResetAll) { Text("恢复全部默认") }
        },
    )
}
