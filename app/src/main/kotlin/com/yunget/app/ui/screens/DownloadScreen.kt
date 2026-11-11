package com.yunget.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.download.DownloadStats
import com.yunget.app.data.download.TaskTransferPolicies
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.components.FileNameText
import com.yunget.app.ui.components.AppChoiceGroup
import com.yunget.app.ui.components.SpeedChart
import com.yunget.app.ui.items.DownloadLiveStatistics
import com.yunget.app.ui.items.DownloadTaskActions
import com.yunget.app.ui.items.DownloadTaskItem
import com.yunget.app.ui.items.DownloadTaskListItem
import com.yunget.app.ui.items.DownloadTaskStatus
import com.yunget.app.ui.items.formatDownloadSize
import com.yunget.app.ui.items.formatDownloadSpeed
import com.yunget.app.ui.items.toDownloadListItem
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.theme.LocalUiMetrics
import com.yunget.app.ui.theme.spatialFast
import com.yunget.app.ui.viewmodel.DownloadViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 页面只观察列表结构；任务进度、实时速度和曲线各自在使用处订阅。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    if (com.yunget.app.ui.theme.LocalAppUiStyle.current == com.yunget.app.ui.theme.AppUiStyle.CLASSIC) {
        com.yunget.app.ui.classic.DownloadScreen(
            scrollBehavior = scrollBehavior,
            viewModel = viewModel,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val listFlow = remember(viewModel) {
        viewModel.tasks.map { tasks -> tasks.map { it.toDownloadListItem() } }.distinctUntilChanged()
    }
    val listItems by listFlow.collectAsState(initial = viewModel.tasks.value.map { it.toDownloadListItem() })
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDeleteAllConfirm by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showAddDialog = true
        else SnackbarController.show("需要存储权限才能保存到下载目录")
    }
    // Android 9 及更早的系统仍需先申请公共下载目录写权限。
    val requestAddDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) showAddDialog = true
        else permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val onPause: (Long) -> Unit = remember(viewModel) { { viewModel.pause(it) } }
    val onResume: (Long) -> Unit = remember(viewModel) { { viewModel.resume(it) } }
    // 在操作发生时取最新实体，避免使用列表缓存的旧保存路径。
    val onOpen: (Long) -> Unit = remember(viewModel, context) {
        { id ->
            val task = viewModel.tasks.value.firstOrNull { it.id == id }
            if (task != null) openSavedFile(context, task.savePath, task.fileName)
        }
    }
    val onInstall: (Long) -> Unit = remember(viewModel, context) {
        { id ->
            val task = viewModel.tasks.value.firstOrNull { it.id == id }
            if (task != null) installApk(context, task.savePath, task.fileName)
        }
    }
    val onDetails: (Long) -> Unit = { detailId = it }
    val onRemove: (Long) -> Unit = { detailId = null; pendingDeleteId = it }

    DownloadPageContent(
        listItems = listItems,
        tasks = viewModel.tasks,
        stats = viewModel.stats,
        onAdd = requestAddDownload,
        onPause = onPause,
        onResume = onResume,
        onOpen = onOpen,
        onInstall = onInstall,
        onDetails = onDetails,
        onRemove = onRemove,
        onPauseAll = { viewModel.pauseAll() },
        onResumeAll = { viewModel.resumeAll() },
        onDeleteAll = { showDeleteAllConfirm = true },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    )
    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, name -> showAddDialog = false; viewModel.enqueue(url, name) },
        )
    }
    detailId?.let { id ->
        DownloadTaskDetails(
            id, viewModel.tasks, viewModel.stats, viewModel.speedSeries,
            onDismiss = { detailId = null },
            onPause = onPause, onResume = onResume, onOpen = onOpen,
            onInstall = onInstall, onRemove = onRemove,
        )
    }
    pendingDeleteId?.let { id ->
        DeleteTaskDialogHost(
            id = id,
            tasks = viewModel.tasks,
            onDismiss = { pendingDeleteId = null },
            onConfirm = { deleteLocal -> pendingDeleteId = null; viewModel.remove(id, deleteLocal) },
        )
    }
    if (showDeleteAllConfirm) {
        DeleteAllConfirmDialog(
            taskCount = listItems.size,
            hasCompletedFile = listItems.any { it.isCompleted && it.hasSavedFile },
            onDismiss = { showDeleteAllConfirm = false },
            onConfirm = { deleteLocal -> showDeleteAllConfirm = false; viewModel.removeAll(deleteLocal) },
        )
    }
}

/** 生产页和截图 Preview 共享此结构；只接收状态与回调，不依赖 ViewModel 或 Room。 */
@Composable
private fun DownloadPageContent(
    listItems: List<DownloadTaskListItem>,
    tasks: StateFlow<List<DownloadTaskEntity>>,
    stats: StateFlow<Map<Long, DownloadStats>>,
    onAdd: () -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onInstall: (Long) -> Unit,
    onDetails: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
    initialCompletedTab: Boolean = false,
) {
    val ui = LocalUiMetrics.current
    var completedTab by rememberSaveable { mutableStateOf(initialCompletedTab) }
    var collapsedFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val activeListState = rememberLazyListState()
    val completedListState = rememberLazyListState()
    val completedCount = listItems.count { it.isCompleted }
    val activeCount = listItems.size - completedCount
    val (rootTasks, folderGroups) = remember(listItems, completedTab) {
        val visible = listItems.filter { it.isCompleted == completedTab }
        val (folders, roots) = visible.partition { it.fileName.contains('/') }
        roots to folders.groupBy { it.fileName.substringBefore('/') }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                DownloadTabs(
                    completedTab = completedTab,
                    activeCount = activeCount,
                    completedCount = completedCount,
                    onSelect = { completedTab = it },
                    modifier = Modifier.padding(horizontal = ui.space(14.dp), vertical = ui.space(4.dp)),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = if (completedTab) completedListState else activeListState,
                    contentPadding = PaddingValues(
                        start = ui.space(14.dp), end = ui.space(14.dp),
                        top = ui.space(4.dp), bottom = ui.space(96.dp),
                    ),
                    verticalArrangement = Arrangement.spacedBy(ui.space(8.dp)),
                ) {
                    if (listItems.isNotEmpty()) {
                        item(key = "download_overview", contentType = "overview") {
                            DownloadOverview(listItems, completedTab, stats, onPauseAll, onResumeAll, onDeleteAll)
                        }
                    }
                    if (rootTasks.isEmpty() && folderGroups.isEmpty()) {
                        item(key = "download_empty", contentType = "empty") {
                            EmptyDownloadState(
                                completedTab = completedTab,
                                hasOtherTasks = if (completedTab) activeCount > 0 else completedCount > 0,
                                onSwitchTab = { completedTab = !completedTab },
                                onAdd = onAdd,
                            )
                        }
                    }
                    items(rootTasks, key = { "download:${it.id}" }, contentType = { "task" }) { task ->
                        DownloadTaskItem(
                            item = task, tasks = tasks, stats = stats,
                            onPause = onPause, onResume = onResume, onOpen = onOpen,
                            onInstall = onInstall, onDetails = onDetails, onRemove = onRemove,
                            // 无入场动画，只响应真实排序、目录展开和任务移除的位置变化。
                            modifier = Modifier.animateItem(
                                fadeInSpec = null, fadeOutSpec = null, placementSpec = spatialDefault(),
                            ),
                        )
                    }
                    folderGroups.forEach { (folder, children) ->
                        val expanded = folder !in collapsedFolders
                        item(key = "folder:$folder", contentType = "folder") {
                            DownloadFolderHeader(
                                folder = folder, children = children, tasks = tasks, expanded = expanded,
                                onToggle = {
                                    collapsedFolders = if (expanded) collapsedFolders + folder
                                    else collapsedFolders - folder
                                },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null, fadeOutSpec = null, placementSpec = spatialDefault(),
                                ),
                            )
                        }
                        if (expanded) {
                            // 子任务独立懒加载；展开大目录也不一次性组合全部任务。
                            items(children, key = { "download:${it.id}" }, contentType = { "task" }) { task ->
                                DownloadTaskItem(
                                    item = task, tasks = tasks, stats = stats,
                                    onPause = onPause, onResume = onResume, onOpen = onOpen,
                                    onInstall = onInstall, onDetails = onDetails, onRemove = onRemove,
                                    inFolder = true,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = null, fadeOutSpec = null, placementSpec = spatialDefault(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加下载任务")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadTabs(
    completedTab: Boolean,
    activeCount: Int,
    completedCount: Int,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppChoiceGroup(
        labels = listOf("下载  $activeCount", "已完成  $completedCount"),
        selected = if (completedTab) 1 else 0,
        onSelected = { onSelect(it == 1) },
        modifier = modifier,
        role = Role.Tab,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadOverview(
    listItems: List<DownloadTaskListItem>,
    completedTab: Boolean,
    stats: StateFlow<Map<Long, DownloadStats>>,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val ui = LocalUiMetrics.current
    val downloading = listItems.count { it.status == DownloadTaskEntity.STATUS_DOWNLOADING }
    val pending = listItems.count { it.status == DownloadTaskEntity.STATUS_PENDING }
    val paused = listItems.count { it.status == DownloadTaskEntity.STATUS_PAUSED }
    val failed = listItems.count { it.status == DownloadTaskEntity.STATUS_FAILED }
    val completed = listItems.count { it.isCompleted }
    val activeIds = remember(listItems) {
        listItems.filter { it.status == DownloadTaskEntity.STATUS_DOWNLOADING }.map { it.id }.toSet()
    }
    val headline = when {
        completedTab -> "$completed 项已完成"
        downloading > 0 -> "$downloading 项正在下载"
        pending > 0 -> "$pending 项等待下载"
        failed > 0 -> "$failed 项下载需要重试"
        paused > 0 -> "$paused 项下载已暂停"
        else -> "下载队列已完成"
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(headline, style = MaterialTheme.typography.titleSmall)
                if (!completedTab && pending + paused + failed > 0) {
                    Text(listOfNotNull(
                        "等待 $pending".takeIf { pending > 0 },
                        "暂停 $paused".takeIf { paused > 0 },
                        "失败 $failed".takeIf { failed > 0 },
                    ).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDeleteAll, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除全部任务",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!completedTab) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                DownloadTotalSpeed(activeIds, stats)
                TextButton(
                    onClick = onPauseAll, enabled = downloading + pending > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("全部暂停") }
                TextButton(
                    onClick = onResumeAll, enabled = paused + failed > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("全部开始") }
            }
        }
    }
}

@Composable
private fun DownloadTotalSpeed(activeIds: Set<Long>, stats: StateFlow<Map<Long, DownloadStats>>) {
    val speedFlow = remember(stats, activeIds) {
        stats.map { values -> activeIds.sumOf { values[it]?.speed?.coerceAtLeast(0) ?: 0L } }.distinctUntilChanged()
    }
    val speed by speedFlow.collectAsState(initial = activeIds.sumOf { stats.value[it]?.speed?.coerceAtLeast(0) ?: 0L })
    Text(
        text = "总速度 ${formatDownloadSpeed(speed)}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DownloadFolderHeader(
    folder: String,
    children: List<DownloadTaskListItem>,
    tasks: StateFlow<List<DownloadTaskEntity>>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spatialFast(), label = "downloadFolderExpansion",
    )
    val description = if (expanded) "已展开，点按收起" else "已收起，点按展开"
    Surface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FileNameText(folder, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${children.size} 个文件 · ${children.count { it.isCompleted }} 个已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.ExpandMore, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                )
            }
            DownloadFolderProgress(children, tasks)
        }
    }
}

private data class FolderProgress(val downloaded: Long, val total: Long, val hasUnknownSize: Boolean)

@Composable
private fun DownloadFolderProgress(children: List<DownloadTaskListItem>, tasks: StateFlow<List<DownloadTaskEntity>>) {
    val ids = remember(children) { children.map { it.id }.toSet() }
    val progressFlow = remember(tasks, ids) {
        tasks.map { list -> folderProgress(list.filter { it.id in ids }) }.distinctUntilChanged()
    }
    val progress by progressFlow.collectAsState(initial = folderProgress(tasks.value.filter { it.id in ids }))
    val fraction = if (!progress.hasUnknownSize && progress.total > 0) {
        (progress.downloaded.toDouble() / progress.total).toFloat().coerceIn(0f, 1f)
    } else 0f
    val animatedProgress = animateFloatAsState(
        targetValue = fraction, animationSpec = effectsDefault(), label = "downloadFolderProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!progress.hasUnknownSize && progress.total > 0) {
            LinearProgressIndicator(
                progress = { animatedProgress.value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        Text(
            text = if (progress.hasUnknownSize) {
                "已下载 ${formatDownloadSize(progress.downloaded)} · 部分文件大小未知"
            } else "${formatDownloadSize(progress.downloaded)} / ${formatDownloadSize(progress.total)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun folderProgress(tasks: List<DownloadTaskEntity>) = FolderProgress(
    downloaded = tasks.sumOf {
        if (it.totalSize > 0) it.downloadedSize.coerceIn(0, it.totalSize) else it.downloadedSize.coerceAtLeast(0)
    },
    total = tasks.sumOf { it.totalSize.coerceAtLeast(0) },
    hasUnknownSize = tasks.any { it.totalSize <= 0 },
)

@Composable
private fun EmptyDownloadState(
    completedTab: Boolean,
    hasOtherTasks: Boolean,
    onSwitchTab: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(
            text = if (completedTab) "还没有已完成的文件" else "暂无下载任务",
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Text(
            text = if (completedTab) "下载完成后，文件会显示在这里，可直接打开或预览。"
            else "解析分享链接后选择文件，或手动添加文件直链。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = if (hasOtherTasks) onSwitchTab else onAdd,
            modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (hasOtherTasks) {
                if (completedTab) "查看下载任务" else "查看已完成"
            } else "手动添加下载")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadTaskDetails(
    id: Long,
    tasks: StateFlow<List<DownloadTaskEntity>>,
    stats: StateFlow<Map<Long, DownloadStats>>,
    speedSeries: StateFlow<Map<Long, List<Long>>>,
    onDismiss: () -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onInstall: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val taskFlow = remember(tasks, id) { tasks.map { it.firstOrNull { task -> task.id == id } }.distinctUntilChanged() }
    val task by taskFlow.collectAsState(initial = tasks.value.firstOrNull { it.id == id })
    val current = task
    if (current == null) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("任务详情", style = MaterialTheme.typography.titleLarge)
            val pipelineStatusLabel = if (
                current.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                current.status == DownloadTaskEntity.STATUS_FAILED
            ) {
                when (current.pipelineStage) {
                    "PROCESSING" -> if (current.status == DownloadTaskEntity.STATUS_FAILED) "处理失败" else "处理中"
                    "SAVING" -> if (current.status == DownloadTaskEntity.STATUS_FAILED) "保存失败" else "保存中"
                    "SAVE_FAILED" -> "保存失败"
                    else -> null
                }
            } else {
                null
            }
            if (pipelineStatusLabel != null) {
                DownloadDetailStatus(current.status, pipelineStatusLabel)
            } else {
                DownloadTaskStatus(current.status)
            }
            DownloadDetailField("文件名", current.fileName)
            val shown = if (current.totalSize > 0) current.downloadedSize.coerceIn(0, current.totalSize)
            else current.downloadedSize.coerceAtLeast(0)
            DownloadDetailField(
                "下载进度",
                if (current.totalSize > 0) "${formatDownloadSize(shown)} / ${formatDownloadSize(current.totalSize)}"
                else "已下载 ${formatDownloadSize(shown)} · 大小未知",
            )
            if (current.status == DownloadTaskEntity.STATUS_DOWNLOADING) DownloadLiveStatistics(id, stats)
            DownloadDiagnosticsRows(id, stats)
            if (current.avgSpeed > 0) DownloadDetailField("平均速度", formatDownloadSpeed(current.avgSpeed))
            DownloadSpeedHistory(id, speedSeries)
            if (current.errorMsg.isNotBlank()) DownloadDetailField("失败原因", current.errorMsg, isError = true)
            DownloadDetailField("保存位置", current.savePath.ifBlank { "完成后保存到设置的下载目录" })
            DownloadDetailField("下载地址", current.url)
            if (current.platform.isNotBlank()) DownloadDetailField("来源", current.platform)
            if (current.transferPolicyJson != "{}") {
                val policyLabel = remember(current.transferPolicyJson) { TaskTransferPolicies.label(current.transferPolicyJson) }
                DownloadDetailField("传输参数", policyLabel)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DownloadTaskActions(
                item = current.toDownloadListItem(),
                onPause = onPause, onResume = onResume, onOpen = onOpen,
                onInstall = onInstall, onRemove = onRemove,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun DownloadDetailField(label: String, value: String, isError: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value, style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DownloadDetailStatus(status: Int, text: String) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (status) {
            DownloadTaskEntity.STATUS_DOWNLOADING -> colors.primaryContainer
            DownloadTaskEntity.STATUS_FAILED -> colors.errorContainer
            DownloadTaskEntity.STATUS_COMPLETED -> colors.tertiaryContainer
            else -> colors.surfaceContainerHigh
        },
        animationSpec = effectsDefault(),
        label = "downloadDetailStatusContainer",
    )
    val foreground by animateColorAsState(
        targetValue = when (status) {
            DownloadTaskEntity.STATUS_DOWNLOADING -> colors.onPrimaryContainer
            DownloadTaskEntity.STATUS_FAILED -> colors.onErrorContainer
            DownloadTaskEntity.STATUS_COMPLETED -> colors.onTertiaryContainer
            else -> colors.onSurfaceVariant
        },
        animationSpec = effectsFast(),
        label = "downloadDetailStatusForeground",
    )
    Surface(color = container, contentColor = foreground, shape = RoundedCornerShape(6.dp)) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(effectsDefault()) + scaleIn(spatialFast(), initialScale = 0.94f))
                    .togetherWith(fadeOut(effectsFast()))
                    .using(SizeTransform { _, _ -> spatialDefault() })
            },
            label = "downloadDetailStatus",
        ) { displayedText ->
            Text(
                text = displayedText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DownloadDiagnosticsRows(
    id: Long,
    stats: StateFlow<Map<Long, DownloadStats>>,
) {
    val diagnosticsFlow = remember(stats, id) {
        stats.map { it[id]?.diagnostics }.distinctUntilChanged()
    }
    val diagnostics by diagnosticsFlow.collectAsState(initial = stats.value[id]?.diagnostics)
    val current = diagnostics ?: return
    DownloadDetailField("开始接收", formatDiagnosticsDuration(current.firstByteMillis))
    DownloadDetailField("传输用时", formatDiagnosticsDuration(current.transferMillis))
}

private fun formatDiagnosticsDuration(millis: Long?): String {
    if (millis == null) return "--"
    val safeMillis = millis.coerceAtLeast(0L)
    if (safeMillis < 1_000L) return "$safeMillis ms"
    val seconds = String.format(Locale.ROOT, "%.1f", safeMillis / 1_000.0).removeSuffix(".0")
    return "$seconds 秒"
}

/** 曲线仅在详情打开时订阅和创建，不在长列表中常驻图表。 */
@Composable
private fun DownloadSpeedHistory(id: Long, speedSeries: StateFlow<Map<Long, List<Long>>>) {
    val seriesFlow = remember(speedSeries, id) { speedSeries.map { it[id].orEmpty() }.distinctUntilChanged() }
    val samples by seriesFlow.collectAsState(initial = speedSeries.value[id].orEmpty())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("最近一分钟速度", style = MaterialTheme.typography.labelLarge)
        if (samples.size >= 2) SpeedChart(samples = samples, modifier = Modifier.fillMaxWidth())
        else Text(
            "下载开始后会显示速度曲线",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteTaskDialogHost(
    id: Long,
    tasks: StateFlow<List<DownloadTaskEntity>>,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val taskFlow = remember(tasks, id) { tasks.map { it.firstOrNull { task -> task.id == id } }.distinctUntilChanged() }
    val task by taskFlow.collectAsState(initial = tasks.value.firstOrNull { it.id == id })
    val current = task
    if (current == null) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    DeleteConfirmDialog(current, onDismiss, onConfirm)
}

@Composable
private fun DeleteAllConfirmDialog(
    taskCount: Int,
    hasCompletedFile: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteLocal by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("删除全部 $taskCount 项任务？") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("这会删除「下载」和「已完成」中的全部任务记录，并清除未完成任务的临时文件。记录无法恢复。")
                if (hasCompletedFile) {
                    DeleteLocalFileOption(deleteLocal) { deleteLocal = it }
                    Text(
                        "勾选后会同时删除已保存的文件，无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteLocal && hasCompletedFile) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("全部删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        },
    )
}

@Composable
private fun DeleteLocalFileOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text("同时删除本地文件", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** 截图演示数据只存在于此入口；无数据库、ViewModel、网络请求或下载启动。 */
@Composable
internal fun DownloadPreviewContent() {
    val demoTasks = remember {
        listOf(
            DownloadTaskEntity(
                id = 1, url = "https://example.com/film.mp4", fileName = "山野之间 · 旅行影像.mp4",
                totalSize = 1_610_612_736, downloadedSize = 612_032_840,
                status = DownloadTaskEntity.STATUS_DOWNLOADING, createTime = 0,
            ),
            DownloadTaskEntity(
                id = 2, url = "https://example.com/archive.zip", fileName = "设计资料与项目归档.zip",
                totalSize = 268_435_456, downloadedSize = 134_217_728,
                status = DownloadTaskEntity.STATUS_PAUSED, createTime = 0,
            ),
            DownloadTaskEntity(
                id = 3, url = "https://example.com/completed.mp4", fileName = "周末骑行记录.mp4",
                totalSize = 104_857_600, downloadedSize = 104_857_600,
                status = DownloadTaskEntity.STATUS_COMPLETED, savePath = "/preview/Download/周末骑行记录.mp4",
                avgSpeed = 8_388_608, createTime = 0,
            ),
        )
    }
    val tasks = remember { MutableStateFlow(demoTasks) }
    val stats = remember {
        MutableStateFlow(mapOf(1L to DownloadStats(speed = 12_582_912, remainMillis = 79_000, chunkCount = 8, connectionLimit = 16)))
    }
    DownloadPageContent(
        listItems = demoTasks.map { it.toDownloadListItem() }, tasks = tasks, stats = stats,
        onAdd = {}, onPause = {}, onResume = {}, onOpen = {}, onInstall = {},
        onDetails = {}, onRemove = {}, onPauseAll = {}, onResumeAll = {}, onDeleteAll = {},
    )
}

/** Completed-only preview verifies open, preview, details and remove actions remain reachable. */
@Composable
internal fun DownloadCompletedPreviewContent() {
    val completed = remember {
        listOf(
            DownloadTaskEntity(
                id = 11,
                url = "https://example.com/ride.mp4",
                fileName = "周末骑行记录.mp4",
                totalSize = 104_857_600,
                downloadedSize = 104_857_600,
                status = DownloadTaskEntity.STATUS_COMPLETED,
                savePath = "/preview/Download/周末骑行记录.mp4",
                avgSpeed = 8_388_608,
                createTime = 0,
            ),
            DownloadTaskEntity(
                id = 12,
                url = "https://example.com/app.apk",
                fileName = "示例应用.apk",
                totalSize = 28_311_552,
                downloadedSize = 28_311_552,
                status = DownloadTaskEntity.STATUS_COMPLETED,
                savePath = "/preview/Download/示例应用.apk",
                avgSpeed = 5_242_880,
                createTime = 0,
            ),
        )
    }
    val tasks = remember { MutableStateFlow(completed) }
    val stats = remember { MutableStateFlow(emptyMap<Long, DownloadStats>()) }
    DownloadPageContent(
        listItems = completed.map { it.toDownloadListItem() },
        tasks = tasks,
        stats = stats,
        onAdd = {}, onPause = {}, onResume = {}, onOpen = {}, onInstall = {},
        onDetails = {}, onRemove = {}, onPauseAll = {}, onResumeAll = {},
        onDeleteAll = {}, initialCompletedTab = true,
    )
}

@Composable
private fun DeleteConfirmDialog(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit,
    onConfirm: (deleteLocal: Boolean) -> Unit
) {
    var deleteLocal by rememberSaveable(task.id) { mutableStateOf(false) }
    val hasLocalFile = task.status == DownloadTaskEntity.STATUS_COMPLETED && task.savePath.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("删除下载任务") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "确定删除「${task.fileName}」吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (hasLocalFile) {
                    DeleteLocalFileOption(deleteLocal) { deleteLocal = it }
                }
                Text(
                    text = if (hasLocalFile) {
                        "勾选后将一并删除已保存的本地文件，且不可恢复。"
                    } else if (task.status == DownloadTaskEntity.STATUS_COMPLETED) {
                        "该任务没有已完成的本地文件。"
                    } else {
                        "该任务尚未完成，删除后将同时清除已下载的临时文件，且不可恢复。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteLocal && hasLocalFile) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}

private fun openSavedFile(context: android.content.Context, savePath: String, fileName: String) {
    if (savePath.isBlank()) {
        SnackbarController.show("文件不存在或已被删除")
        return
    }
    runCatching {
        val uri = if (savePath.startsWith("content://")) {
            Uri.parse(savePath)
        } else {
            val file = File(savePath)
            check(file.isFile) { "文件不存在或已被删除" }
            // Android 7.0+ 禁止暴露 file:// URI，必须经 FileProvider 转 content://
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val kind = com.yunget.app.ui.preview.PreviewTypes.kindOf(fileName) ?: when {
            mime.startsWith("video/") -> com.yunget.app.ui.preview.PreviewKind.VIDEO
            mime.startsWith("audio/") -> com.yunget.app.ui.preview.PreviewKind.AUDIO
            mime.startsWith("image/") -> com.yunget.app.ui.preview.PreviewKind.IMAGE
            else -> null
        }
        if (kind != null && kind != com.yunget.app.ui.preview.PreviewKind.TEXT) {
            com.yunget.app.ui.preview.PreviewLauncher.openMedia(listOf(
                com.yunget.app.ui.preview.PreviewMedia(uri.toString(), kind, title = fileName)
            ), title = fileName)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    }.onFailure {
        SnackbarController.show("文件不存在或无法打开")
    }
}

/** 安装 APK：检查「安装未知来源应用」权限（Android 8+），ACTION_VIEW 调起系统安装器 */
private fun installApk(context: android.content.Context, savePath: String, fileName: String) {
    if (savePath.isBlank()) {
        SnackbarController.show("文件不存在")
        return
    }
    // Android 8+：需先授予「安装未知来源应用」
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        SnackbarController.show("请先允许安装未知来源应用")
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }.onFailure {
            SnackbarController.show("无法打开设置")
        }
        return
    }
    runCatching {
        val uri = if (savePath.startsWith("content://")) {
            Uri.parse(savePath)
        } else {
            val file = File(savePath)
            check(file.isFile) { "文件不存在或已被删除" }
            // Android 7.0+ 禁止暴露 file:// URI，必须经 FileProvider 转 content://
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "安装应用"))
    }.onFailure {
        SnackbarController.show("无法打开安装器")
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("添加下载任务") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "保存到设置的下载目录，默认是 ${Environment.DIRECTORY_DOWNLOADS}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        if (name.isBlank()) name = it.substringAfterLast('/').take(80)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文件直链 URL") },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("保存文件名") },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url.trim(), name.trim()) },
                enabled = url.isNotBlank() && name.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}
