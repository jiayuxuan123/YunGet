package com.yunget.desktop

import com.yunget.app.data.network.model.formatShareFileTime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.material3.FilterChip
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
import com.yunget.app.data.network.OfficialWebDrive
import com.yunget.app.data.network.model.ShareInfo
import com.yunget.app.data.upload.UploadIo
import com.yunget.app.data.upload.UploadPhase
import kotlinx.coroutines.launch
import java.io.File

private data class DriveCrumb(val dirId: String, val label: String)

private data class ActiveUploadState(
    val fileName: String,
    val index: Int,
    val totalCount: Int,
    val phase: UploadPhase,
    val sentBytes: Long,
    val totalBytes: Long,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopDriveTab(
    config: DesktopConfig,
    accountStore: DesktopAccountStore,
    driveService: DesktopDriveService,
    webDriveSessions: DesktopWebDriveSessions,
    downloads: DesktopDownloads,
    onNotify: (String) -> Unit,
    onOpenAccountDialog: (DriveAccountKind) -> Unit,
    pendingUploadFiles: List<File> = emptyList(),
    onConsumePendingUploadFiles: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val accounts by accountStore.accounts.collectAsState()
    val allAccounts by accountStore.allAccounts.collectAsState()
    val webProfiles by webDriveSessions.savedProfiles.collectAsState()
    val personalDrives = remember { DriveAccountKind.entries.filter { it.supportsPersonalDrive } }
    var selectedDrive by remember {
        mutableStateOf(
            personalDrives.firstOrNull { accounts.containsKey(it) } ?: personalDrives.first()
        )
    }
    var selectedWebDrive by remember { mutableStateOf<OfficialWebDrive?>(null) }
    var driveSearch by remember { mutableStateOf("") }
    val driveSearchTerm = driveSearch.trim()
    val nativeMatches = personalDrives.filter {
        it.displayName.contains(driveSearchTerm, ignoreCase = true) || it.name.contains(driveSearchTerm, ignoreCase = true)
    }
    val webMatches = OfficialWebDrive.entries.filter {
        it.displayName.contains(driveSearchTerm, ignoreCase = true) || it.id.contains(driveSearchTerm, ignoreCase = true)
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
    var shareTargets by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var activeUpload by remember { mutableStateOf<ActiveUploadState?>(null) }
    var activeUploadIo by remember { mutableStateOf<UploadIo?>(null) }

    val currentAccount = accounts[selectedDrive]
    val driveAccounts = remember(allAccounts, selectedDrive) { accountStore.accountsOf(selectedDrive) }
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

    LaunchedEffect(selectedDrive, currentAccount?.id, currentAccount?.credential) {
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

    fun uploadLocalFiles(localFiles: List<File>) {
        val valid = localFiles.filter { it.isFile }
        if (valid.isEmpty()) return
        if (currentAccount == null) {
            errorMessage = "请先登录${selectedDrive.displayName}后再上传文件"
            return
        }
        if (!driveService.supportsUpload(selectedDrive)) {
            errorMessage = "${selectedDrive.displayName}暂不支持个人网盘上传"
            return
        }
        val kind = selectedDrive
        val targetDir = currentDirId
        scope.launch {
            errorMessage = ""
            var succeeded = 0
            var failed = 0
            var lastErr = ""
            for ((idx, file) in valid.withIndex()) {
                activeUpload = ActiveUploadState(
                    fileName = file.name,
                    index = idx + 1,
                    totalCount = valid.size,
                    phase = UploadPhase.PREPARING,
                    sentBytes = 0L,
                    totalBytes = file.length().coerceAtLeast(1L),
                )
                val res = runCatching {
                    driveService.uploadFile(
                        kind = kind,
                        parentId = targetDir,
                        localFile = file,
                        onIoCreated = { io -> activeUploadIo = io },
                        onProgress = { phase, sent, total ->
                            activeUpload = ActiveUploadState(
                                fileName = file.name,
                                index = idx + 1,
                                totalCount = valid.size,
                                phase = phase,
                                sentBytes = sent,
                                totalBytes = total.coerceAtLeast(1L),
                            )
                        },
                    )
                }
                activeUploadIo = null
                res.onSuccess { succeeded++ }
                    .onFailure { err ->
                        failed++
                        lastErr = err.message ?: "上传失败"
                    }
                if (lastErr.contains("取消")) break
            }
            activeUpload = null
            if (succeeded > 0) {
                loadDir(kind, targetDir)
                scope.launch { accountStore.refreshQuota(kind) }
            }
            if (failed == 0) {
                onNotify("已上传 $succeeded 个文件到${kind.displayName}")
            } else {
                errorMessage = "上传完成：成功 $succeeded 个，失败 $failed 个（$lastErr）"
            }
        }
    }

    LaunchedEffect(pendingUploadFiles) {
        if (pendingUploadFiles.isNotEmpty()) {
            val copy = pendingUploadFiles.toList()
            onConsumePendingUploadFiles()
            if (selectedWebDrive == null) uploadLocalFiles(copy)
            else onNotify("请在${selectedWebDrive?.displayName}的官网上传文件")
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
            OutlinedTextField(
                value = driveSearch,
                onValueChange = { driveSearch = it },
                placeholder = { Text("搜索网盘") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(nativeMatches, key = { it.name }) { kind ->
                    val acc = accounts[kind]
                    val selected = selectedWebDrive == null && selectedDrive == kind
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedWebDrive = null
                            selectedDrive = kind
                        },
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
                if (webMatches.isNotEmpty()) {
                    item(key = "web-drive-heading") {
                        Text("网页版网盘", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
                    }
                    items(webMatches, key = { "web-${it.id}" }) { drive ->
                        val selected = selectedWebDrive == drive
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { selectedWebDrive = drive },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(drive.displayName, style = MaterialTheme.typography.titleSmall)
                                if (drive in webProfiles) DesktopPill("本机会话")
                            }
                        }
                    }
                }
                if (nativeMatches.isEmpty() && webMatches.isEmpty()) {
                    item(key = "empty-drive-search") {
                        Text("未找到匹配的网盘", modifier = Modifier.padding(6.dp))
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
                listOf(DriveAccountKind.GITHUB).forEach { kind ->
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

        val webDrive = selectedWebDrive
        if (webDrive != null) {
            DesktopWebDrivePanel(
                drive = webDrive,
                hasProfile = webDrive in webProfiles,
                sessions = webDriveSessions,
                onNotify = onNotify,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            return@Row
        }

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
                        if (currentAccount != null) {
                            DesktopPill(currentAccount.displayLabel)
                        }
                        if (driveAccounts.size > 1) {
                            driveAccounts.forEach { acc ->
                                FilterChip(
                                    selected = acc.isPrimary,
                                    onClick = {
                                        if (!acc.isPrimary) {
                                            accountStore.setPrimary(selectedDrive, acc.id)
                                            onNotify("已切换账号：${acc.displayLabel}")
                                        }
                                    },
                                    label = { Text(acc.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
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
                        if (driveService.supportsFolderMutation(selectedDrive)) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { showNewFolderDialog = true },
                            ) { Text("新建文件夹") }
                        }
                        if (driveService.supportsUpload(selectedDrive)) {
                            OutlinedButton(
                                enabled = !busy && activeUpload == null,
                                onClick = {
                                    val picked = pickUploadFiles()
                                    if (picked.isNotEmpty()) uploadLocalFiles(picked)
                                },
                            ) { Text("上传文件") }
                        }
                        if (driveService.supportsCreateShare(selectedDrive) && selectedFids.isNotEmpty()) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    shareTargets = files.filter { it.fid in selectedFids }
                                },
                            ) { Text("生成分享 (${selectedFids.size})") }
                        }
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

            activeUpload?.let { up ->
                val phaseText = when (up.phase) {
                    UploadPhase.PREPARING -> "计算摘要/预处理"
                    UploadPhase.UPLOADING -> "上传中"
                    UploadPhase.FINISHING -> "网盘确认中"
                }
                val ratio = (up.sentBytes.toFloat() / up.totalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "上传 (${up.index}/${up.totalCount}) · ${up.fileName} · $phaseText (${(ratio * 100).toInt()}%) · ${formatBytes(up.sentBytes)} / ${formatBytes(up.totalBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { activeUploadIo?.cancel() },
                            ) { Text("取消上传", color = MaterialTheme.colorScheme.error) }
                        }
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
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
                                    ContextMenuArea(
                                        items = {
                                            buildList {
                                                if (file.isdir) {
                                                    add(ContextMenuItem("打开文件夹") {
                                                        loadDir(selectedDrive, driveService.childDirId(selectedDrive, file), pushLabel = file.fname)
                                                    })
                                                    add(ContextMenuItem("下载整个文件夹") { downloadItems(listOf(file)) })
                                                } else {
                                                    add(ContextMenuItem("下载文件") { downloadItems(listOf(file)) })
                                                    add(ContextMenuItem("复制下载直链") {
                                                        scope.launch {
                                                            runCatching { driveService.resolveDownload(selectedDrive, file) }
                                                                .onSuccess { res ->
                                                                    copyToClipboard(res.url)
                                                                    onNotify("已复制直链：${file.fname}")
                                                                }
                                                                .onFailure { err -> errorMessage = err.message ?: "取链失败" }
                                                        }
                                                    })
                                                }
                                                if (driveService.supportsCreateShare(selectedDrive)) {
                                                    add(ContextMenuItem("生成分享链接") { shareTargets = listOf(file) })
                                                }
                                                if (driveService.supportsFolderMutation(selectedDrive)) {
                                                    add(ContextMenuItem("重命名") { renameTarget = file })
                                                    add(ContextMenuItem("删除") { deleteTarget = file })
                                                }
                                            }
                                        },
                                    ) {
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
                                                formatShareFileTime(file.modifyTime).takeIf(String::isNotEmpty)?.let(::add)
                                            }.joinToString(" · ")
                                            if (meta.isNotBlank()) {
                                                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (driveService.supportsCreateShare(selectedDrive)) {
                                            TextButton(onClick = { shareTargets = listOf(file) }) { Text("分享") }
                                        }
                                        if (driveService.supportsFolderMutation(selectedDrive)) {
                                            TextButton(onClick = { renameTarget = file }) { Text("重命名") }
                                            TextButton(onClick = { deleteTarget = file }) {
                                                Text("删除", color = MaterialTheme.colorScheme.error)
                                            }
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

    if (shareTargets.isNotEmpty()) {
        DesktopCreateShareDialog(
            kind = selectedDrive,
            files = shareTargets,
            driveService = driveService,
            onDismiss = { shareTargets = emptyList() },
            onNotify = onNotify,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopCreateShareDialog(
    kind: DriveAccountKind,
    files: List<ShareFile>,
    driveService: DesktopDriveService,
    onDismiss: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val expireOptions = remember(kind) { driveService.shareExpireOptions(kind) }
    var selectedExpire by remember(kind) { mutableStateOf(expireOptions.first().second) }
    var title by remember(files) {
        mutableStateOf(
            if (files.size == 1) files.first().fname else "${files.first().fname} 等 ${files.size} 项"
        )
    }
    var passcode by remember(kind, files) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ShareInfo?>(null) }
    val supportsCustomCode = kind in setOf(
        DriveAccountKind.QUARK,
        DriveAccountKind.UC,
        DriveAccountKind.BAIDU,
        DriveAccountKind.PAN123,
        DriveAccountKind.GUANGYA,
        DriveAccountKind.XUNLEI,
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("生成 ${kind.displayName} 分享链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val info = result
                if (info == null) {
                    Text(
                        text = "已选 ${files.size} 项：${files.take(3).joinToString("、") { it.fname }}${if (files.size > 3) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("分享名称") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (expireOptions.size > 1) {
                        Text("有效期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            expireOptions.forEach { (label, code) ->
                                FilterChip(
                                    selected = selectedExpire == code,
                                    onClick = { selectedExpire = code },
                                    enabled = !busy,
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    if (supportsCustomCode) {
                        OutlinedTextField(
                            value = passcode,
                            onValueChange = { passcode = it.take(6) },
                            label = { Text("提取码（留空默认随机或免密）") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("分享创建成功", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("链接：${info.shareUrl}", style = MaterialTheme.typography.bodySmall)
                            if (info.passcode.isNotBlank()) {
                                Text("提取码：${info.passcode}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                            if (!info.warning.isNullOrBlank()) {
                                Text(info.warning!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val info = result
            if (info == null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = ""
                        scope.launch {
                            val res = runCatching {
                                driveService.createShare(
                                    kind = kind,
                                    files = files,
                                    title = title,
                                    expiredType = selectedExpire,
                                    passcode = passcode,
                                )
                            }
                            busy = false
                            res.onSuccess { created ->
                                result = created
                            }.onFailure { err ->
                                error = err.message ?: "生成分享链接失败"
                            }
                        }
                    },
                ) { Text(if (busy) "生成中…" else "创建分享") }
            } else {
                Button(
                    onClick = {
                        val text = buildString {
                            append(info.shareUrl)
                            if (info.passcode.isNotBlank()) append(" 提取码：${info.passcode}")
                        }
                        copyToClipboard(text)
                        onNotify("已复制分享链接")
                        onDismiss()
                    },
                ) { Text("复制链接并关闭") }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
        },
    )
}
