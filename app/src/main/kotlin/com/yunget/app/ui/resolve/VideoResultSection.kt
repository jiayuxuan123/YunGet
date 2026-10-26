/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.ui.resolve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.video.VideoMedia
import com.yunget.app.data.network.video.VideoMediaKind
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.ui.components.RemoteImage

/**
 * 视频平台解析结果。
 *
 * ## 与网盘详情页的差别
 *
 * 网盘解析出来的是**目录**，要逐级进入、勾选、再下载。
 * 视频平台一次就给出**这个作品的全部媒体**（一个视频，或一组图集，或分轨的音视频），
 * 所以这里直接平铺成列表，每条一个下载动作，没有目录层级。
 *
 * ## 为什么每条单独一个下载按钮
 *
 * 图集可能几十张，用户往往只想要其中几张；一键全下会产生几十个任务把队列塞满。
 * 全下按钮保留在顶部，但**默认不是唯一入口**。
 */
@Composable
fun VideoResultSection(
    result: VideoParseResult,
    modifier: Modifier = Modifier,
    onDownload: (VideoMedia) -> Unit,
    onDownloadAll: (List<VideoMedia>) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------- 作品信息 ----------
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = result.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (result.author.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = result.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = result.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 非致命提示（如"去水印原图不可用，已回退"）：只提示一次，不改列表里的地址
                if (result.notice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = result.notice,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        // ---------- 封面 ----------
        if (result.cover.isNotBlank()) {
            item {
                RemoteImage(
                    url = result.cover,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                    autoHeight = true,
                    placeholderRatio = 16f / 9f,
                )
            }
        }

        // ---------- 汇总 + 全下 ----------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("共 ${result.medias.size} 项")
                        val v = result.videoCount
                        val i = result.imageCount
                        if (v > 0) append(" · $v 个视频")
                        if (i > 0) append(" · $i 张图片")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (result.medias.size > 1) {
                    Text(
                        text = "全部下载",
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDownloadAll(result.medias) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---------- 媒体列表 ----------
        items(result.medias.size) { index ->
            val media = result.medias[index]
            MediaRow(
                media = media,
                index = index,
                onDownload = { onDownload(media) },
            )
        }
    }
}

/** 一条媒体（视频 / 图片 / 音频）。 */
@Composable
private fun MediaRow(
    media: VideoMedia,
    index: Int,
    onDownload: () -> Unit,
) {
    val (icon, kindLabel) = when {
        media.isHls -> Icons.Outlined.Videocam to "视频流"
        media.kind == VideoMediaKind.VIDEO -> Icons.Outlined.Videocam to "视频"
        media.kind == VideoMediaKind.IMAGE && media.motionUrl.isNotBlank() ->
            Icons.Outlined.Image to "动态照片"
        media.kind == VideoMediaKind.IMAGE -> Icons.Outlined.Image to "图片"
        else -> Icons.Outlined.MusicNote to "音频"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.fileName.ifBlank { "$kindLabel ${index + 1}" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(kindLabel)
                        if (media.quality.isNotBlank()) append(" · ${media.quality}")
                        if (media.size > 0) append(" · ${formatBytes(media.size)}")
                        if (media.audioUrl.isNotBlank()) append(" · 含音轨")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = "下载",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
