package com.yunget.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.OfficialWebDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopWebDrivePanel(
    drive: OfficialWebDrive,
    hasProfile: Boolean,
    sessions: DesktopWebDriveSessions,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var link by remember(drive) { mutableStateOf("") }
    var error by remember(drive) { mutableStateOf("") }
    var busy by remember(drive) { mutableStateOf(false) }
    var confirmClear by remember(drive) { mutableStateOf(false) }
    fun runAction(action: suspend () -> Unit) {
        busy = true
        error = ""
        scope.launch {
            try {
                action()
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                error = err.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(drive.displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "在独立浏览器窗口登录、浏览和下载文件。下次打开会继续使用本机网页会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(enabled = !busy, onClick = { runAction { sessions.open(drive) } }) {
                Text("打开官网")
            }
            OutlinedButton(enabled = !busy, onClick = {
                copyToClipboard(drive.entryUrl)
                onNotify("已复制官网地址")
            }) { Text("复制官网地址") }
            if (hasProfile) {
                OutlinedButton(enabled = !busy, onClick = { confirmClear = true }) {
                    Text("退出本机网页会话")
                }
            }
        }
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("${drive.displayName}分享链接") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(enabled = !busy && link.isNotBlank(), onClick = {
            val url = runCatching { URI(link.trim()) }.getOrNull()
            if (url == null || !drive.accepts(url)) {
                error = "请输入${drive.displayName}的 HTTPS 分享链接"
            } else {
                runAction { sessions.open(drive, url) }
            }
        }) { Text("打开分享链接") }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("退出${drive.displayName}网页会话") },
            text = { Text("关闭该网盘的浏览器窗口后，清除云取为该网盘保存的网页会话。下次进入时需要重新登录。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    runAction {
                        sessions.clear(drive)
                        onNotify("已退出${drive.displayName}的本机网页会话")
                    }
                }) { Text("退出会话") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
