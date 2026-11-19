package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.model.ShareFile
import kotlinx.coroutines.launch
import java.io.File

private data class DriveCrumb(val dirId: String, val label: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopDriveTab(
    config: DesktopConfig,
    accountStore: DesktopAccountStore,
    driveService: DesktopDriveService,
    downloads: DesktopDownloads,
    onNotify: (String) -> Unit,
    onOpenAccountDialog: (DriveAccountKind) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val accounts by accountStore.accounts.collectAsState()
    val personalDrives = remember { DriveAccountKind.entries.filter { it.supportsPersonalDrive } }
    var selectedDrive by remember {
        mutableStateOf(
            personalDrives.firstOrNull { accounts.containsKey(it) } ?: personalDrives.first()
        )
    }

    val crumbs = remember { mutableStateListOf<DriveCrumb>() }
    var files by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var batchStatus by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var filterQuery by remember { mutableStateOf("") }
    var selectedFids by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ShareFile?>(null) }
    var deleteTarget by remember { mutableStateOf<ShareFile?>(null) }

    val currentAccount = accounts[selectedDrive]
    val currentDirId = crumbs.lastOrNull()?.dirId ?: driveService.rootDirId(selectedDrive)

    fun loadDir(kind: DriveAccountKind, dirId: String, resetCrumbs: Boolean = false, pushLabel: String? = null, trimToIndex: Int? = null) {
        if (accounts[kind] == null) {
            files = emptyList()
            crumbs.clear()
            errorMessage = ""
            return
        }
        busy = true
        errorMessage = ""
        scope.launch {
            runCatching { driveService.listFiles(kind, dirId) }
                .onSuccess { list ->
                    if (resetCrumbs) {
                        crumbs.clear()
                        crumbs.add(DriveCrumb(dirId, "全部文件"))
                    } else if (trimToIndex != null) {
                        while (crumbs.size > trimToIndex + 1) crumbs.removeAt(crumbs.lastIndex)
                    } else if (pushLabel != null) {
                        crumbs.add(DriveCrumb(dirId, pushLabel))
                    }
                    selectedFids = emptySet()
                    files = list
                }
                .onFailure { err -> errorMessage = err.message ?: "读取目录失败" }
            busy = false
        }
    }

    LaunchedEffect(selectedDrive, currentAccount?.credential) {
        filterQuery = ""
        val root = driveService.rootDirId(selectedDrive)
        if (currentAccount != null) {
            loadDir(selectedDrive, root, resetCrumbs = true)
        } else {
            crumbs.clear()
            files = emptyList()
            selectedFids = emptySet()
            errorMessage = ""
        }
    }

    fun downloadItems(items: List<ShareFile>) {
        if (items.isEmpty()) return
        val kind = selectedDrive
        scope.launch {
            var added = 0
            var failed = 0
            var lastErr = ""
            batchStatus = "正在准备下载 ${items.size} 项…"
            try {
                val queue = mutableListOf<Pair<ShareFile, String>>()
                for (item in items) {
                    if (item.isdir) {
                        batchStatus = "正在扫描文件夹：${item.fname}…"
                        queue.addAll(driveService.collectFolderFiles(kind, item))
                    } else {
                        queue.add(item to "")
                    }
                }
                for ((idx, pair) in queue.withIndex()) {
                    val (file, subDir) = pair
                    batchStatus = "正在取链并入队 (${idx + 1}/${queue.size})：${file.fname}"
                    runCatching {
                        val resolved = driveService.resolveDownload(kind, file)
                        val baseDir = File(config.downloadDirectory)
                        val targetDir = if (subDir.isBlank()) baseDir else File(baseDir, subDir)
                        downloads.submit(
                            source = resolved.url,
                            directory = targetDir,
                            fileName = resolved.fileName,
                            kind = resolved.kind,
                            httpVersion = config.httpPolicy,
                            headers = resolved.headers,
                            autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                            sourceLabel = resolved.sourceLabel,
                            knownSize = resolved.size,
                        )
                    }.onSuccess { added++ }
                        .onFailure { err ->
                            failed++
                            lastErr = err.message.orEmpty()
                        }
                }
            } catch (err: Exception) {
                failed++
                lastErr = err.message ?: "扫描失败"
            } finally {
                batchStatus = ""
            }
            if (failed == 0) {
                onNotify("已将 $added 个文件加入下载队列")
            } else {
                errorMessage = "成功入队 $added 个，失败 $failed 个：$lastErr"
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left drive sidebar
        Column(
            modifier = Modifier
                .width(248.dp)
                .fillMaxHeight()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "我的网盘",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(personalDrives, key = { it.name }) { kind ->
                    val acc = accounts[kind]
                    val selected = selectedDrive == kind
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().clickable { selectedDrive = kind },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    StatusDot(active = acc != null)
                                    Text(
                                        text = kind.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                }
                                Text(
                                    text = if (acc != null) "已登录" else "未登录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (acc != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (acc != null) {
                                if (acc.nickname.isNotBlank()) {
                                    Text(
                                        text = acc.nickname,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (acc.totalBytes > 0L) {
                                    val ratio = (acc.usedBytes.coerceAtLeast(0L).toDouble() / acc.totalBytes).coerceIn(0.0, 1.0).toFloat()
                                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                                    Text(
                                        text = "${formatBytes(acc.usedBytes.coerceAtLeast(0L))} / ${formatBytes(acc.totalBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                "辅助凭证（解析加速）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(DriveAccountKind.GITHUB, DriveAccountKind.FEIJIPAN).forEach { kind ->
                    val ok = accounts.containsKey(kind)
                    OutlinedButton(
                        onClick = { onOpenAccountDialog(kind) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (ok) "${kind.displayName} ✓" else kind.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        // Right file explorer
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(selectedDrive.displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        if (currentAccount != null && currentAccount.nickname.isNotBlank()) {
                            DesktopPill(currentAccount.nickname)
                        }
                    }
                    if (currentAccount != null && currentAccount.totalBytes > 0L) {
                        Text(
                            "容量：${formatBytes(currentAccount.usedBytes.coerceAtLeast(0L))} / ${formatBytes(currentAccount.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenAccountDialog(selectedDrive) }) {
                        Text(if (currentAccount != null) "账号管理" else "立即登录")
                    }
                    if (currentAccount != null) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                loadDir(selectedDrive, currentDirId)
                                scope.launch { accountStore.refreshQuota(selectedDrive) }
                            },
                        ) { Text("刷新") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { showNewFolderDialog = true },
                        ) { Text("新建文件夹") }
                        FilledTonalButton(
                            enabled = selectedFids.isNotEmpty() && batchStatus.isEmpty(),
                            onClick = {
                                val picked = files.filter { it.fid in selectedFids }
                                downloadItems(picked)
                            },
                        ) { Text("下载选中 (${selectedFids.size})") }
                    }
                }
            }

            if (busy || batchStatus.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (batchStatus.isNotBlank()) {
                        Text(batchStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (errorMessage.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            if (currentAccount == null) {
                DesktopEmptyCard(
                    title = "尚未登录 ${selectedDrive.displayName}",
                    subtitle = selectedDrive.credentialHint,
                    actionLabel = "登录 / 导入凭证",
                    onAction = { onOpenAccountDialog(selectedDrive) },
                )
            } else {
                val visibleFiles = remember(files, filterQuery) {
                    val q = filterQuery.trim()
                    if (q.isEmpty()) files else files.filter { it.fname.contains(q, ignoreCase = true) }
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                crumbs.forEachIndexed { idx, crumb ->
                                    if (idx > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = crumb.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (idx == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable(enabled = idx < crumbs.lastIndex) {
                                            loadDir(selectedDrive, crumb.dirId, trimToIndex = idx)
                                        }.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = {
                                    selectedFids = if (selectedFids.size == visibleFiles.size && visibleFiles.isNotEmpty()) {
                                        emptySet()
                                    } else {
                                        visibleFiles.mapTo(linkedSetOf()) { it.fid }
                                    }
                                }) {
                                    Text(if (selectedFids.size == visibleFiles.size && visibleFiles.isNotEmpty()) "取消全选" else "全选")
                                }
                                OutlinedTextField(
                                    value = filterQuery,
                                    onValueChange = { filterQuery = it },
                                    placeholder = { Text("筛选文件名…") },
                                    singleLine = true,
                                    modifier = Modifier.width(200.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        if (visibleFiles.isEmpty() && !busy) {
                            DesktopEmptyCard(
                                title = "当前目录为空",
                                subtitle = "可以点击上方「新建文件夹」或切换到其他目录。",
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(visibleFiles, key = { it.fid }) { file ->
                                    val checked = file.fid in selectedFids
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (file.isdir) {
                                                    loadDir(selectedDrive, driveService.childDirId(selectedDrive, file), pushLabel = file.fname)
                                                } else {
                                                    selectedFids = if (checked) selectedFids - file.fid else selectedFids + file.fid
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                selectedFids = if (isChecked) selectedFids + file.fid else selectedFids - file.fid
                                            },
                                        )
                                        DesktopPill(
                                            text = if (file.isdir) "目录" else file.fname.substringAfterLast('.', "文件").uppercase().take(6),
                                            containerColor = if (file.isdir) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            contentColor = if (file.isdir) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.fname,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val meta = buildList {
                                                if (!file.isdir) add(formatBytes(file.fsize))
                                                if (file.modifyTime.isNotBlank()) add(file.modifyTime)
                                            }.joinToString(" · ")
                                            if (meta.isNotBlank()) {
                                                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        TextButton(onClick = { renameTarget = file }) { Text("重命名") }
                                        TextButton(onClick = { deleteTarget = file }) {
                                            Text("删除", color = MaterialTheme.colorScheme.error)
                                        }
                                        if (file.isdir) {
                                            FilledTonalButton(
                                                enabled = batchStatus.isEmpty(),
                                                onClick = { downloadItems(listOf(file)) },
                                            ) { Text("下载目录") }
                                        } else {
                                            TextButton(
                                                enabled = batchStatus.isEmpty(),
                                                onClick = {
                                                    scope.launch {
                                                        runCatching { driveService.resolveDownload(selectedDrive, file) }
                                                            .onSuccess { res ->
                                                                copyToClipboard(res.url)
                                                                onNotify("已复制直链：${file.fname}")
                                                            }
                                                            .onFailure { err -> errorMessage = err.message ?: "取链失败" }
                                                    }
                                                },
                                            ) { Text("复制直链") }
                                            FilledTonalButton(
                                                enabled = batchStatus.isEmpty(),
                                                onClick = { downloadItems(listOf(file)) },
                                            ) { Text("下载") }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // New folder dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        val name = folderName.trim()
                        showNewFolderDialog = false
                        scope.launch {
                            runCatching { driveService.createFolder(selectedDrive, currentDirId, name) }
                                .onSuccess {
                                    onNotify("已创建文件夹：$name")
                                    loadDir(selectedDrive, currentDirId)
                                }
                                .onFailure { err -> errorMessage = err.message ?: "新建文件夹失败" }
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("取消") }
            },
        )
    }

    // Rename dialog
    renameTarget?.let { target ->
        var nextName by remember(target.fid) { mutableStateOf(target.fname) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = nextName,
                    onValueChange = { nextName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = nextName.isNotBlank() && nextName.trim() != target.fname,
                    onClick = {
                        val name = nextName.trim()
                        renameTarget = null
                        scope.launch {
                            runCatching { driveService.rename(selectedDrive, target, name) }
                                .onSuccess {
                                    onNotify("已重命名为：$name")
                                    loadDir(selectedDrive, currentDirId)
                                }
                                .onFailure { err -> errorMessage = err.message ?: "重命名失败" }
                        }
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = { Text("确定要从${selectedDrive.displayName}删除「${target.fname}」吗？") },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { driveService.delete(selectedDrive, currentDirId, target) }
                            .onSuccess {
                                onNotify("已删除：${target.fname}")
                                loadDir(selectedDrive, currentDirId)
                            }
                            .onFailure { err -> errorMessage = err.message ?: "删除失败" }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
