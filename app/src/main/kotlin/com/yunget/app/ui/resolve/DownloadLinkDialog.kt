package com.yunget.app.ui.resolve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.rememberGlobalSnackbarHostState

/**
 * 下载直链弹窗：展示文件名与直链（长按直链复制），支持「开始下载」（分片多线程下载）。
 * 点「关闭」或弹窗外（管壁）关闭 = 放弃下载，由上层清理临时转存。
 */
@Composable
fun DownloadLinkDialog(
    link: DownloadLink,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Dialog 内提示宿主（AlertDialog 为独立窗口）
    val snackbarHostState = rememberGlobalSnackbarHostState()

    val copyLink: () -> Unit = {
        copyToClipboard(context, link.downloadUrl)
        SnackbarController.show("下载链接已复制")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
        title = { Text("下载链接已就绪", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = link.filename, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = "下载直链已生成（有效期约 15-30 分钟）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(
                        onClick = copyLink,
                        onClickLabel = "复制下载链接",
                        onLongClick = copyLink,
                        onLongClickLabel = "复制下载链接"
                    ),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = link.downloadUrl,
                        modifier = Modifier.heightIn(min = 48.dp).padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = copyLink, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制链接")
                }
                Text(
                    text = "点击「开始下载」将分片多线程下载并保存到 Download 目录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        confirmButton = {
            Button(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("download_url", text))
}
