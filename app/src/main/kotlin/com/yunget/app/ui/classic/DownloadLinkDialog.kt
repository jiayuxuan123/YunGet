package com.yunget.app.ui.classic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunget.app.data.download.DirectLinkExport
import com.yunget.app.data.download.PcRelayClient
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.rememberGlobalSnackbarHostState
import kotlinx.coroutines.launch

/**
 * 下载直链弹窗（经典样式）：支持复制裸链、带头 curl/aria2 命令、推送到电脑端和开始下载。
 * 点「关闭」或弹窗外（管壁）关闭 = 放弃下载，由上层清理临时转存。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadLinkDialog(
    link: DownloadLink,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { SettingsRepository(context) }
    val effectiveHeaders = remember(link, headers) { headers.ifEmpty { link.headers } }
    // Dialog 内提示宿主（AlertDialog 为独立窗口）
    val snackbarHostState = rememberGlobalSnackbarHostState()
    var showPcPushConfig by remember { mutableStateOf(false) }
    var pushingToPc by remember { mutableStateOf(false) }
    var pcEndpoint by remember { mutableStateOf(settings.pcPushEndpoint) }
    var pcToken by remember { mutableStateOf(settings.pcPushToken) }

    val copyLink: () -> Unit = {
        copyToClipboard(context, link.downloadUrl)
        SnackbarController.show("下载链接已复制")
    }
    val copyCurl: () -> Unit = {
        copyToClipboard(context, DirectLinkExport.toCurlCommand(link.downloadUrl, link.filename, effectiveHeaders))
        SnackbarController.show(if (effectiveHeaders.isNotEmpty()) "已复制含请求头的 curl 命令" else "已复制 curl 命令")
    }
    val copyAria2: () -> Unit = {
        copyToClipboard(context, DirectLinkExport.toAria2Command(link.downloadUrl, link.filename, effectiveHeaders))
        SnackbarController.show(if (effectiveHeaders.isNotEmpty()) "已复制含请求头的 aria2c 命令" else "已复制 aria2c 命令")
    }
    val triggerPcPush: (String, String) -> Unit = { endpoint, token ->
        pushingToPc = true
        scope.launch {
            val result = PcRelayClient.push(
                endpoint = endpoint,
                token = token,
                url = link.downloadUrl,
                fileName = link.filename,
                headers = effectiveHeaders,
                mode = "download",
            )
            pushingToPc = false
            result.onSuccess { msg -> SnackbarController.show(msg) }
                .onFailure { err -> SnackbarController.show("推送失败：${err.message.orEmpty()}") }
        }
    }

    if (showPcPushConfig) {
        AlertDialog(
            onDismissRequest = { showPcPushConfig = false },
            title = { Text("推送到局域网电脑端") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在电脑端云取「设置 → 浏览器扩展接管与手机局域网推送」开启局域网接收，填入地址与配对令牌：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pcEndpoint,
                        onValueChange = { pcEndpoint = it },
                        label = { Text("电脑端地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pcToken,
                        onValueChange = { pcToken = it },
                        label = { Text("配对令牌") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pcEndpoint.isNotBlank() && !pushingToPc,
                    onClick = {
                        settings.pcPushEndpoint = pcEndpoint
                        settings.pcPushToken = pcToken
                        showPcPushConfig = false
                        triggerPcPush(pcEndpoint, pcToken)
                    },
                ) { Text("保存并推送") }
            },
            dismissButton = {
                TextButton(onClick = { showPcPushConfig = false }) { Text("取消") }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = link.filename,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                Text(
                    text = if (effectiveHeaders.isNotEmpty()) {
                        "下载直链已生成（有效期约 15-30 分钟；外部工具请用 curl/aria2）"
                    } else {
                        "下载直链已生成（有效期约 15-30 分钟）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        text = link.downloadUrl,
                        modifier = Modifier
                            .padding(12.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = copyLink,
                                onLongClickLabel = "复制下载链接",
                            ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = copyLink) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制链接")
                    }
                    TextButton(onClick = copyCurl) { Text("curl") }
                    TextButton(onClick = copyAria2) { Text("aria2") }
                    TextButton(
                        enabled = !pushingToPc,
                        onClick = {
                            if (pcEndpoint.isBlank()) showPcPushConfig = true
                            else triggerPcPush(pcEndpoint, pcToken)
                        },
                    ) { Text(if (pushingToPc) "推送中…" else "推到电脑") }
                }
                Text(
                    text = "点击「开始下载」将分片多线程下载并保存到 Download 目录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Dialog 内提示（AlertDialog 为独立窗口，需自带 Snackbar 宿主）
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        confirmButton = {
            Button(onClick = { onDownload() }) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("开始下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        modifier = modifier
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("download_url", text))
}
