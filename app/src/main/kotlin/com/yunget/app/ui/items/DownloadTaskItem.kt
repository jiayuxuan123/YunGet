package com.yunget.app.ui.items

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.download.DownloadStats
import com.yunget.app.ui.components.FileNameText
import com.yunget.app.ui.preview.PreviewKind
import com.yunget.app.ui.preview.PreviewTypes
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.theme.spatialFast
import com.yunget.app.ui.theme.LocalUiMetrics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 列表结构只依赖这些低频字段；字节数、速度与曲线不进入页面的订阅。 */
@Immutable
internal data class DownloadTaskListItem(
    val id: Long,
    val fileName: String,
    val status: Int,
    val hasSavedFile: Boolean,
) {
    val isCompleted: Boolean get() = status == DownloadTaskEntity.STATUS_COMPLETED
    val isActive: Boolean get() = status == DownloadTaskEntity.STATUS_DOWNLOADING ||
        status == DownloadTaskEntity.STATUS_PENDING
    val isResumable: Boolean get() = status == DownloadTaskEntity.STATUS_PAUSED ||
        status == DownloadTaskEntity.STATUS_FAILED
}

internal fun DownloadTaskEntity.toDownloadListItem() = DownloadTaskListItem(
    id = id,
    fileName = fileName,
    status = status,
    hasSavedFile = savePath.isNotBlank(),
)

/** 普通任务和目录内任务共用同一组操作，避免目录内缺少详情、安装或视频预览入口。 */
@Composable
internal fun DownloadTaskItem(
    item: DownloadTaskListItem,
    tasks: StateFlow<List<DownloadTaskEntity>>,
    stats: StateFlow<Map<Long, DownloadStats>>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onInstall: (Long) -> Unit,
    onDetails: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
    inFolder: Boolean = false,
) {
    val ui = LocalUiMetrics.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(ui.space(13.dp)),
            verticalArrangement = Arrangement.spacedBy(ui.space(7.dp)),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FileNameText(
                        text = if (inFolder) item.fileName.substringAfter('/') else item.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DownloadTaskStatus(item.status)
                }
            }
            DownloadTaskProgress(item.id, item.status, tasks, stats)
            DownloadTaskActions(
                item = item,
                onPause = onPause,
                onResume = onResume,
                onOpen = onOpen,
                onInstall = onInstall,
                onRemove = onRemove,
                onDetails = onDetails,
            )
        }
    }
}

@Composable
internal fun DownloadTaskStatus(status: Int) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (status) {
            DownloadTaskEntity.STATUS_DOWNLOADING -> colors.primaryContainer
            DownloadTaskEntity.STATUS_FAILED -> colors.errorContainer
            DownloadTaskEntity.STATUS_COMPLETED -> colors.tertiaryContainer
            else -> colors.surfaceContainerHigh
        },
        animationSpec = effectsDefault(),
        label = "downloadStatusContainer",
    )
    val foreground by animateColorAsState(
        targetValue = when (status) {
            DownloadTaskEntity.STATUS_DOWNLOADING -> colors.onPrimaryContainer
            DownloadTaskEntity.STATUS_FAILED -> colors.onErrorContainer
            DownloadTaskEntity.STATUS_COMPLETED -> colors.onTertiaryContainer
            else -> colors.onSurfaceVariant
        },
        animationSpec = effectsFast(),
        label = "downloadStatusForeground",
    )
    Surface(color = container, contentColor = foreground, shape = RoundedCornerShape(6.dp)) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(effectsDefault()) + scaleIn(spatialFast(), initialScale = 0.94f))
                    .togetherWith(fadeOut(effectsFast()))
                    .using(SizeTransform { _, _ -> spatialDefault() })
            },
            label = "downloadStatus",
        ) { displayedStatus ->
            Text(
                text = DownloadTaskEntity.statusText(displayedStatus),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Immutable
private data class TaskProgress(
    val total: Long,
    val downloaded: Long,
    val averageSpeed: Long,
    val error: String,
)

private fun DownloadTaskEntity.progressSnapshot() = TaskProgress(
    total = totalSize.coerceAtLeast(0),
    downloaded = downloadedSize.coerceAtLeast(0),
    averageSpeed = avgSpeed,
    error = errorMsg,
)

/** 字节更新仅重组这一块；动画值在进度条绘制 lambda 内读取，不逐帧重组任务行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskProgress(
    id: Long,
    status: Int,
    tasks: StateFlow<List<DownloadTaskEntity>>,
    stats: StateFlow<Map<Long, DownloadStats>>,
) {
    val progressFlow = remember(tasks, id) {
        tasks.map { list -> list.firstOrNull { it.id == id }?.progressSnapshot() }
            .distinctUntilChanged()
    }
    val snapshot by progressFlow.collectAsState(
        initial = tasks.value.firstOrNull { it.id == id }?.progressSnapshot(),
    )
    val progress = snapshot ?: return
    val completed = status == DownloadTaskEntity.STATUS_COMPLETED
    val shown = if (progress.total > 0) {
        progress.downloaded.coerceAtMost(progress.total)
    } else progress.downloaded
    val fraction = when {
        completed -> 1f
        progress.total > 0 -> (shown.toDouble() / progress.total).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    val animatedProgress = animateFloatAsState(
        targetValue = fraction,
        animationSpec = effectsDefault(),
        label = "downloadProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!completed) {
            LinearProgressIndicator(
                progress = { animatedProgress.value.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = when (status) {
                    DownloadTaskEntity.STATUS_FAILED -> MaterialTheme.colorScheme.error
                    DownloadTaskEntity.STATUS_PAUSED -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when {
                    completed -> formatDownloadSize(progress.total.takeIf { it > 0 } ?: shown)
                    progress.total > 0 -> "${formatDownloadSize(shown)} / ${formatDownloadSize(progress.total)}"
                    else -> "已下载 ${formatDownloadSize(shown)} · 大小未知"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!completed && progress.total > 0) {
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (completed && progress.averageSpeed > 0) {
                Text(
                    text = "平均 ${formatDownloadSpeed(progress.averageSpeed)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status == DownloadTaskEntity.STATUS_DOWNLOADING) {
            DownloadLiveStatistics(id, stats)
        }
        if (status == DownloadTaskEntity.STATUS_FAILED && progress.error.isNotBlank()) {
            Text(
                text = progress.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** 独立订阅当前任务的统计，其他任务的速度变化不进入本任务的重组。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadLiveStatistics(id: Long, stats: StateFlow<Map<Long, DownloadStats>>) {
    val statsFlow = remember(stats, id) {
        stats.map { it[id] }.distinctUntilChanged()
    }
    val current by statsFlow.collectAsState(initial = stats.value[id])
    val value = current
    if (value == null || value.speed <= 0) {
        Text(
            text = "等待下载数据…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = formatDownloadSpeed(value.speed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "剩余 ${formatDownloadRemain(value.remainMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.connectionSummary(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadTaskActions(
    item: DownloadTaskListItem,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onInstall: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onDetails: ((Long) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spatialFast(),
        label = "downloadActionPress",
    )
    val failed = item.status == DownloadTaskEntity.STATUS_FAILED
    val container by animateColorAsState(
        targetValue = if (failed) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = effectsDefault(),
        label = "downloadActionContainer",
    )
    val foreground by animateColorAsState(
        targetValue = if (failed) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = effectsFast(),
        label = "downloadActionForeground",
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = {
                when {
                    item.isActive -> onPause(item.id)
                    item.isResumable -> onResume(item.id)
                    item.isCompleted -> onOpen(item.id)
                }
            },
            enabled = item.isActive || item.isResumable || (item.isCompleted && item.hasSavedFile),
            modifier = Modifier.heightIn(min = 48.dp).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = container,
                contentColor = foreground,
            ),
            interactionSource = interactionSource,
        ) {
            AnimatedContent(
                targetState = item.status,
                transitionSpec = {
                    (fadeIn(effectsDefault()) + scaleIn(spatialFast(), initialScale = 0.92f))
                        .togetherWith(fadeOut(effectsFast()))
                        .using(SizeTransform { _, _ -> spatialDefault() })
                },
                label = "downloadPrimaryAction",
            ) { status ->
                val (icon, label) = when (status) {
                    DownloadTaskEntity.STATUS_DOWNLOADING,
                    DownloadTaskEntity.STATUS_PENDING -> Icons.Outlined.Pause to "暂停"
                    DownloadTaskEntity.STATUS_PAUSED -> Icons.Outlined.PlayArrow to "继续"
                    DownloadTaskEntity.STATUS_FAILED -> Icons.Outlined.Refresh to "重试"
                    else -> Icons.Outlined.OpenInNew to "打开"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }
        if (item.isCompleted && item.hasSavedFile) {
            if (PreviewTypes.kindOf(item.fileName) == PreviewKind.VIDEO) {
                TextButton(
                    onClick = { onOpen(item.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("视频预览")
                }
            }
            if (item.fileName.endsWith(".apk", ignoreCase = true)) {
                TextButton(
                    onClick = { onInstall(item.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("安装")
                }
            }
        }
        if (onDetails != null) {
            TextButton(
                onClick = { onDetails(item.id) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("详情") }
        }
        IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除任务 ${item.fileName}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatDownloadSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format("%.1f %s", value, units[index])
}

internal fun formatDownloadSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    return "${formatDownloadSize(bytesPerSec)}/s"
}

internal fun formatDownloadRemain(millis: Long): String {
    if (millis < 0) return "计算中"
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3600}时${(seconds % 3600) / 60}分"
    }
}
