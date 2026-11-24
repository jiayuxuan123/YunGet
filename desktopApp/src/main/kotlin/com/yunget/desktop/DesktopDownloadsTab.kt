package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

private enum class DownloadFilter(val label: String) {
    ALL("全部"),
    ACTIVE("进行中"),
    DONE("已完成"),
    ISSUES("异常 / 暂停"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopDownloadsTab(
    config: DesktopConfig,
    downloads: DesktopDownloads,
    onNotify: (String) -> Unit,
    onOpenNewDownloadDialog: () -> Unit,
    onOpenProviderConcurrency: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val tasks by downloads.tasks.collectAsState()
    val speedHistory by downloads.speedHistory.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<DesktopTask?>(null) }
    var detailTaskId by remember { mutableStateOf<String?>(null) }

    val totalSpeed = tasks.sumOf { if (it.status == "RUNNING") it.speedBytesPerSec else 0L }
    val activeCount = tasks.count { it.queuedOrActive }
    val doneCount = tasks.count { it.status == "DONE" }
    val issueCount = tasks.count { it.status == "FAILED" || it.status == "PAUSED" || it.status == "READY_TO_SAVE" }

    val visibleTasks = remember(tasks, filter, searchQuery) {
        val byStatus = when (filter) {
            DownloadFilter.ALL -> tasks
            DownloadFilter.ACTIVE -> tasks.filter { it.queuedOrActive }
            DownloadFilter.DONE -> tasks.filter { it.status == "DONE" }
            DownloadFilter.ISSUES -> tasks.filter { it.status == "FAILED" || it.status == "PAUSED" || it.status == "READY_TO_SAVE" }
        }
        val q = searchQuery.trim()
        if (q.isEmpty()) byStatus else byStatus.filter {
            it.output.name.contains(q, ignoreCase = true) ||
                it.sourceLabel.contains(q, ignoreCase = true) ||
                it.source.contains(q, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header & Global Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("下载队列", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (activeCount > 0) {
                        "总速率 ${formatSpeed(totalSpeed)} · $activeCount 个进行中 · $doneCount 个已完成"
                    } else {
                        "当前空闲 · 共 ${tasks.size} 个任务（$doneCount 个已完成）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenNewDownloadDialog) { Text("新建任务") }
                OutlinedButton(
                    enabled = activeCount > 0,
                    onClick = { scope.launch { downloads.pauseAll(); onNotify("已暂停全部任务") } },
                ) { Text("全部暂停") }
                OutlinedButton(
                    enabled = tasks.any { it.status == "PAUSED" || it.status == "FAILED" },
                    onClick = { scope.launch { downloads.resumeAll(config.httpPolicy); onNotify("已恢复任务队列") } },
                ) { Text("全部继续") }
                OutlinedButton(
                    enabled = doneCount > 0,
                    onClick = { scope.launch { downloads.clearCompleted(); onNotify("已清理已完成记录") } },
                ) { Text("清理已完成") }
                OutlinedButton(onClick = { revealInExplorer(File(config.downloadDirectory)) }) {
                    Text("打开下载目录")
                }
            }
        }

        // Filter chips + Limit badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == DownloadFilter.ALL,
                    onClick = { filter = DownloadFilter.ALL },
                    label = { Text("${DownloadFilter.ALL.label} (${tasks.size})") },
                )
                FilterChip(
                    selected = filter == DownloadFilter.ACTIVE,
                    onClick = { filter = DownloadFilter.ACTIVE },
                    label = { Text("${DownloadFilter.ACTIVE.label} ($activeCount)") },
                )
                FilterChip(
                    selected = filter == DownloadFilter.DONE,
                    onClick = { filter = DownloadFilter.DONE },
                    label = { Text("${DownloadFilter.DONE.label} ($doneCount)") },
                )
                FilterChip(
                    selected = filter == DownloadFilter.ISSUES,
                    onClick = { filter = DownloadFilter.ISSUES },
                    label = { Text("${DownloadFilter.ISSUES.label} ($issueCount)") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DesktopPill("并发上限 ${config.maxConcurrentTasks}")
                DesktopPill("单任务 ${config.connectionsPerTask} 线程")
                OutlinedButton(onClick = onOpenProviderConcurrency) {
                    val customCount = config.providerThreadOverrides.size
                    Text(
                        when {
                            !config.providerSafeCapEnabled -> "分盘并发：已关兜底"
                            customCount > 0 -> "分盘并发 ($customCount 自定义)"
                            else -> "分盘并发"
                        }
                    )
                }
                if (config.speedLimitMbPerSec > 0.0) {
                    DesktopPill("限速 ${config.speedLimitMbPerSec} MB/s")
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索任务…") },
                    singleLine = true,
                    modifier = Modifier.width(190.dp),
                )
            }
        }

        if (visibleTasks.isEmpty()) {
            DesktopEmptyCard(
                title = if (tasks.isEmpty()) "暂无下载任务" else "当前筛选下没有任务",
                subtitle = if (tasks.isEmpty()) "在「解析」或「网盘」页选择文件下载，或点击右上角「新建任务」直接输入链接/种子。"
                else "尝试切换上方筛选条件查看其他任务。",
                actionLabel = if (tasks.isEmpty()) "新建下载任务" else null,
                onAction = if (tasks.isEmpty()) onOpenNewDownloadDialog else null,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleTasks, key = { it.id }) { row ->
                    DesktopTaskCard(
                        row = row,
                        httpPolicy = config.httpPolicy,
                        downloads = downloads,
                        onNotify = onNotify,
                        onOpenDetail = { detailTaskId = row.id },
                        onRequestDelete = { deleteTarget = row },
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { target ->
        var deleteLocal by remember(target.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("移除下载任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定移除任务「${target.output.name}」吗？未完成的临时缓存将被清理。")
                    if (target.status == "DONE" && target.output.exists()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteLocal, onCheckedChange = { deleteLocal = it })
                            Text("同时删除本地已下载文件", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val removeFile = deleteLocal
                    deleteTarget = null
                    scope.launch {
                        downloads.deleteTask(target.id, deleteLocalFile = removeFile)
                        onNotify("已移除任务：${target.output.name}")
                    }
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    detailTaskId?.let { id ->
        val liveTask = tasks.firstOrNull { it.id == id }
        if (liveTask != null) {
            DesktopTaskDetailDialog(
                task = liveTask,
                samples = speedHistory[id].orEmpty(),
                onDismiss = { detailTaskId = null },
                onNotify = onNotify,
            )
        } else {
            detailTaskId = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopTaskCard(
    row: DesktopTask,
    httpPolicy: Int,
    downloads: DesktopDownloads,
    onNotify: (String) -> Unit,
    onOpenDetail: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val (statusLabel, statusBg, statusFg) = when (row.status) {
        "RUNNING" -> Triple("下载中", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        "QUEUED" -> Triple("排队中", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        "PAUSING" -> Triple("暂停中", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        "PAUSED" -> Triple("已暂停", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        "DONE" -> Triple("已完成", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        "READY_TO_SAVE" -> Triple("等待保存", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        else -> Triple("失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }

    ContextMenuArea(
        items = {
            buildList {
                when (row.status) {
                    "RUNNING", "QUEUED" -> add(ContextMenuItem("暂停任务") { scope.launch { downloads.pause(row.id) } })
                    "PAUSED", "FAILED" -> add(ContextMenuItem("继续 / 重试") { scope.launch { downloads.resume(row.id, httpPolicy) } })
                    "DONE" -> {
                        add(ContextMenuItem("打开文件") { openLocalFile(row.output) })
                        add(ContextMenuItem("在资源管理器中定位") { revealInExplorer(row.output) })
                    }
                }
                add(ContextMenuItem("查看详情与速度曲线") { onOpenDetail() })
                add(ContextMenuItem("复制任务源链接") {
                    copyToClipboard(row.source)
                    onNotify("已复制任务源地址")
                })
                add(ContextMenuItem("移除任务") { onRequestDelete() })
            }
        },
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DesktopPill(statusLabel, statusBg, statusFg)
                        if (row.sourceLabel.isNotBlank()) DesktopPill(row.sourceLabel)
                        Text(
                            text = row.output.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = row.output.parentFile?.absolutePath ?: row.output.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.status == "RUNNING" && row.isLive) {
                        OutlinedButton(onClick = { scope.launch { downloads.finishRecording(row.id) } }) {
                            Text("结束录制")
                        }
                    }
                    when (row.status) {
                        "RUNNING", "QUEUED" -> {
                            FilledTonalButton(onClick = { scope.launch { downloads.pause(row.id) } }) { Text("暂停") }
                        }
                        "PAUSING" -> {
                            OutlinedButton(enabled = false, onClick = {}) { Text("暂停中…") }
                        }
                        "READY_TO_SAVE" -> {
                            Button(onClick = { scope.launch { downloads.saveWithAutoRename(row.id) } }) {
                                Text("自动重命名保存")
                            }
                            OutlinedButton(onClick = { scope.launch { downloads.resume(row.id, httpPolicy) } }) {
                                Text("重试原名")
                            }
                        }
                        "DONE" -> {
                            FilledTonalButton(onClick = { openLocalFile(row.output) }) { Text("打开文件") }
                            OutlinedButton(onClick = { revealInExplorer(row.output) }) { Text("定位目录") }
                        }
                        else -> {
                            FilledTonalButton(onClick = { scope.launch { downloads.resume(row.id, httpPolicy) } }) {
                                Text(if (row.status == "FAILED") "重试" else "继续")
                            }
                        }
                    }
                    TextButton(onClick = onOpenDetail) { Text("详情") }
                    TextButton(onClick = {
                        copyToClipboard(row.source)
                        onNotify("已复制任务源地址")
                    }) { Text("复制链接") }
                    TextButton(onClick = onRequestDelete) {
                        Text("移除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Progress bar
            if (row.status != "DONE") {
                if (row.total > 0L) {
                    val fraction = (row.downloaded.toDouble() / row.total).coerceIn(0.0, 1.0).toFloat()
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else if (row.status == "RUNNING") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Metrics row
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val sizeText = if (row.total > 0L) {
                    val pct = ((row.downloaded.toDouble() / row.total).coerceIn(0.0, 1.0) * 100.0).toInt()
                    "${formatBytes(row.downloaded)} / ${formatBytes(row.total)} ($pct%)"
                } else {
                    formatBytes(row.downloaded)
                }
                Text(sizeText, style = MaterialTheme.typography.labelMedium)
                if (row.status == "RUNNING") {
                    Text(formatSpeed(row.speedBytesPerSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    if (row.etaSeconds >= 0L) {
                        Text("剩余 ${formatEta(row.etaSeconds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (row.activeConnections > 0) {
                        Text("${row.activeConnections} 连接", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.isLive && row.recordedMs > 0L) {
                    Text("已录制 ${formatEta(row.recordedMs / 1000L)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                val protocols = formatProtocols(row.httpVersions)
                if (protocols.isNotEmpty()) {
                    Text(protocols.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.firstByteMicros > 0L) {
                    Text("TTFB ${formatLatencyMs(row.firstByteMicros)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (row.message.isNotBlank()) {
                Text(row.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        }
    }
}

@Composable
private fun DesktopTaskDetailDialog(
    task: DesktopTask,
    samples: List<Long>,
    onDismiss: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val peakSpeed = samples.maxOrNull() ?: task.speedBytesPerSec
    val avgSpeed = if (samples.isNotEmpty()) samples.average().toLong() else task.speedBytesPerSec
    val protocols = formatProtocols(task.httpVersions).joinToString(" · ").ifBlank { "未协商 / 非 HTTP" }
    val resumeNote = when {
        task.status == "DONE" -> "已完成并保存至目标位置"
        task.partial.isFile -> "存在未完成缓存（${formatBytes(task.partial.length())}），支持断点续传"
        task.status == "QUEUED" -> "排队等待引擎调度"
        else -> "等待探测服务器 Range 能力"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务详情与速率诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(task.output.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("实时速率曲线", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "当前 ${formatSpeed(task.speedBytesPerSec)} · 峰值 ${formatSpeed(peakSpeed)} · 均值 ${formatSpeed(avgSpeed)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Canvas(modifier = Modifier.fillMaxWidth().height(92.dp)) {
                            val w = size.width
                            val h = size.height
                            drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)
                            val pts = if (samples.size >= 2) samples else listOf(0L, task.speedBytesPerSec)
                            val maxVal = (pts.maxOrNull() ?: 1L).coerceAtLeast(1024L).toFloat()
                            val stepX = w / (pts.size - 1).coerceAtLeast(1)
                            val strokePath = Path()
                            val fillPath = Path()
                            pts.forEachIndexed { idx, value ->
                                val x = idx * stepX
                                val y = h - (value.toFloat() / maxVal).coerceIn(0f, 1f) * (h - 8f) - 4f
                                if (idx == 0) {
                                    strokePath.moveTo(x, y)
                                    fillPath.moveTo(x, h)
                                    fillPath.lineTo(x, y)
                                } else {
                                    strokePath.lineTo(x, y)
                                    fillPath.lineTo(x, y)
                                }
                            }
                            fillPath.lineTo(w, h)
                            fillPath.close()
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(primary.copy(alpha = 0.28f), primary.copy(alpha = 0.02f)),
                                ),
                            )
                            drawPath(
                                path = strokePath,
                                color = primary,
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("状态：${task.status} · 协议类型：${task.kind.name} · 协商协议：$protocols", style = MaterialTheme.typography.bodySmall)
                    Text("进度：${formatBytes(task.downloaded)} / ${if (task.total > 0L) formatBytes(task.total) else "未知大小"} · 当前/峰值连接：${task.activeConnections} / ${task.peakConnections}", style = MaterialTheme.typography.bodySmall)
                    if (task.firstByteMicros > 0L) {
                        Text("首字节延迟 (TTFB)：${formatLatencyMs(task.firstByteMicros)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("续传诊断：$resumeNote", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.expectedHash.isNotBlank()) {
                        Text("SHA-256 校验：${task.expectedHash}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (task.headers.isNotEmpty()) {
                        Text("自定义请求头：${task.headers.keys.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("保存路径：${task.output.absolutePath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("源地址：${task.source.take(160)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.message.isNotBlank()) {
                        Text("诊断信息：${task.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(
                            "文件: ${task.output.name}\n状态: ${task.status}\n进度: ${task.downloaded}/${task.total}\n协议: $protocols\n续传: $resumeNote\n源地址: ${task.source}\n信息: ${task.message}"
                        )
                        onNotify("已复制任务诊断信息")
                    },
                ) { Text("复制诊断") }
                Button(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
