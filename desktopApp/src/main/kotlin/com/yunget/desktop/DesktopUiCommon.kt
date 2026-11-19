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

@Composable
internal fun DesktopAccountDialog(
    kind: DriveAccountKind,
    existing: DesktopAccount?,
    accountStore: DesktopAccountStore,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mode by remember(kind) { mutableIntStateOf(if (kind.supportsPasswordLogin && existing == null) 0 else 1) }
    var credential by remember(kind, existing) { mutableStateOf(existing?.credential.orEmpty()) }
    var accountInput by remember(kind) { mutableStateOf("") }
    var passwordInput by remember(kind) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${kind.displayName} 账号管理", style = MaterialTheme.typography.titleMedium)
                if (existing != null) {
                    DesktopPill(
                        text = existing.nickname.ifBlank { "已连接" },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (kind.supportsPasswordLogin) {
                    PrimaryTabRow(selectedTabIndex = mode) {
                        Tab(selected = mode == 0, onClick = { mode = 0; error = "" }, text = { Text("账号密码登录") })
                        Tab(selected = mode == 1, onClick = { mode = 1; error = "" }, text = { Text("导入 ${kind.credentialLabel}") })
                    }
                }
                if (kind.supportsPasswordLogin && mode == 0) {
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
                } else {
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
                if (existing != null && existing.totalBytes > 0L) {
                    Text(
                        text = "已用空间：${formatBytes(existing.usedBytes.coerceAtLeast(0L))} / ${formatBytes(existing.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        val res = runCatching {
                            if (kind.supportsPasswordLogin && mode == 0) {
                                require(accountInput.isNotBlank() && passwordInput.isNotBlank()) { "请填写账号与密码" }
                                accountStore.loginWithPassword(kind, accountInput, passwordInput)
                            } else {
                                accountStore.verifyAndSave(kind, credential)
                            }
                        }
                        busy = false
                        res.onSuccess { acc ->
                            onSaved("${kind.displayName}已登录：${acc.nickname.ifBlank { "成功" }}")
                            onDismiss()
                        }.onFailure { err ->
                            error = err.message ?: "校验失败"
                        }
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (kind.supportsPasswordLogin && mode == 0) "登录并保存" else "校验并保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            accountStore.remove(kind)
                            onSaved("已退出${kind.displayName}")
                            onDismiss()
                        },
                    ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
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
