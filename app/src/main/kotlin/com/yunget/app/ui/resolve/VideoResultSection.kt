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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.yunget.app.data.network.video.VideoMedia
import com.yunget.app.data.network.video.VideoMediaKind
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.items.logoRes
import com.yunget.app.ui.preview.MediaPlayerPane
import com.yunget.app.ui.preview.PreviewKind
import com.yunget.app.ui.preview.PreviewLauncher
import com.yunget.app.ui.preview.PreviewMedia
import com.yunget.app.ui.preview.previewNetworkHeaders
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.theme.spatialFast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 图集每页展示的张数；网格列数随可用宽度调整。 */
private const val IMAGE_PAGE_SIZE = 10

/**
 * 视频/图文解析结果区：平台信息 + 作品描述 + 媒体列表（或图集网格）+ 一键下载。
 *
 * 由解析页（[ResolveScreen]）在 `ResolveUiState.Video` 状态下直接内嵌，不是全屏覆盖层，
 * 因此用普通 Column 跟随解析页现有布局；保留 [Surface] 底色，
 * 避免深色模式下半透明背景透出后面内容发黑。
 *
 * ★ **图片笔记直接铺自适应缩略图**（不再是「一条图片一行 + 眼睛图标」）：图集的价值就是「看」，
 *   逼用户挨个点小眼睛才看得到图是没道理的。缩略图带解析时算好的请求头（[VideoMedia.headers]），
 *   点任意一张进全屏预览 —— 那里能左右翻页、能双指放大，顶栏有「下载」。
 *   **图片过多按页展示**（每页 [IMAGE_PAGE_SIZE] 张），否则一屏几百张、滚动条直接拉爆。
 *
 * ★ 「全部下载」固定在底部；多选时变为「下载所选」，始终参与测量而不遮住内容。
 */
@Composable
fun VideoResultSection(
    result: VideoParseResult,
    onDownloadMedia: (VideoMedia) -> Unit,
    /** 「仅下载音频」：产物是 `.m4a`，对视频行才显示入口 */
    onDownloadAudio: (VideoMedia) -> Unit,
    onDownloadAll: () -> Unit,
    onDownloadSelected: (List<VideoMedia>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 空直链的图片既预览不了也下载不了：直接不算数，否则格子里是个灰块、还会把下标算歪
    val images = remember(result) {
        result.medias.filter { it.kind == VideoMediaKind.IMAGE && it.url.isNotBlank() }
    }
    // 全是图片 = 图片笔记，走网格；只要含视频/音频就仍按行展示（清晰度/大小得用文字说明）
    val isImageNote = images.isNotEmpty() && result.medias.none { it.kind != VideoMediaKind.IMAGE }

    // 图集多选（长按缩略图进入）。用 **url 当键**而不是下标：切页、以及同 url 的重复项都不会串选
    var selectionMode by remember(result) { mutableStateOf(false) }
    var selectedUrls by remember(result) { mutableStateOf(emptySet<String>()) }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedUrls = emptySet()
    }
    // 系统返回键：多选时先退出多选，否则离开解析结果页回到输入页（输入框内容保留，与网盘详情页一致）
    // ★ 必须一直 enabled：此前只拦多选态，非多选时按返回键没人接 → Activity 被结束、直接退回桌面
    BackHandler {
        if (selectionMode) exitSelection() else onBack()
    }

    val mediaKeys = remember(result.medias) { mediaItemKeys(result.medias) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectionMode) {
                ImageSelectionTopBar(
                    selectedCount = selectedUrls.size,
                    allSelected = selectedUrls.size == images.map { it.url }.distinct().size,
                    onExit = exitSelection,
                    onToggleSelectAll = {
                        val allUrls = images.map { it.url }.toSet()
                        selectedUrls = if (selectedUrls == allUrls) emptySet() else allUrls
                    }
                )
            } else {
                VideoResultTopBar(result = result, onBack = onBack)
            }

            if (isImageNote) {
                ImageNoteContent(
                    result = result,
                    images = images,
                    selectionMode = selectionMode,
                    selectedUrls = selectedUrls,
                    onOpenImage = { clicked -> openMediaPreview(result, images, clicked, onDownloadMedia) },
                    onToggleSelect = { media ->
                        selectedUrls = if (media.url in selectedUrls) selectedUrls - media.url else selectedUrls + media.url
                    },
                    onEnterSelection = { media ->
                        selectionMode = true
                        selectedUrls = selectedUrls + media.url
                    },
                    onDownloadImage = onDownloadMedia,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "video_metadata", contentType = "metadata") { VideoMetaBlock(result) }
                    firstVideoOf(result)?.let { video ->
                        item(key = "inline_video", contentType = "player") {
                            InlineVideoPlayer(media = video.toPreviewMedia(result.title))
                        }
                    }
                    if (result.medias.isEmpty()) {
                        item(key = "video_empty", contentType = "empty") {
                            Text(
                                "没有可下载的媒体，可返回检查链接后重试",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    itemsIndexed(
                        items = result.medias,
                        key = { index, _ -> mediaKeys[index] },
                        contentType = { _, media -> media.kind }
                    ) { index, media ->
                        VideoMediaRow(
                            index = index,
                            count = result.medias.size,
                            media = media,
                            onDownload = { onDownloadMedia(media) },
                            onDownloadAudio = { onDownloadAudio(media) },
                            onPreview = { openMediaPreview(result, listOf(media), media, onDownloadMedia) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = effectsDefault(),
                                placementSpec = spatialDefault(),
                                fadeOutSpec = effectsFast()
                            )
                        )
                    }
                }
            }

            // 底栏参与布局，不覆盖图集；两种状态的尺寸与淡入统一跟随全局动效规格。
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = {
                    (fadeIn(effectsDefault()) + slideInVertically(spatialDefault()) { it / 4 })
                        .togetherWith(fadeOut(effectsFast()) + slideOutVertically(spatialFast()) { it / 4 })
                        .using(SizeTransform(sizeAnimationSpec = { _, _ -> spatialDefault() }))
                },
                label = "videoDownloadActions"
            ) { selecting ->
                if (selecting) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Button(
                            onClick = {
                                onDownloadSelected(images.filter { it.url in selectedUrls })
                                exitSelection()
                            },
                            enabled = selectedUrls.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("下载所选（${selectedUrls.size} 张）")
                        }
                    }
                } else {
                    DownloadAllBar(result = result, onDownloadAll = onDownloadAll)
                }
            }
        }
    }
}

/** 顶栏：返回 + 平台徽标 + 平台名 + 标题（固定不滚动，返回入口始终可见） */
@Composable
private fun VideoResultTopBar(result: VideoParseResult, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        // 平台 Logo：按图标自身形状原样画（不裁圆、不垫底色，理由见 PlatformLogos 的 KDoc）
        Image(
            painter = painterResource(result.platform.logoRes()),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.platform.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = result.title.ifBlank { "视频内容" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 多选态的顶栏：结构照搬网盘多选（`ShareDetailScreen`）—— 返回键变成「退出多选」，
 * 标题换成「已选 N 张」，右侧给「全选 / 取消全选」。
 *
 * ★ 与网盘保持同一套措辞与位置：用户在多选这件事上不该学两遍。
 */
@Composable
private fun ImageSelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleSelectAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(spatialFast()).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出多选")
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedContent(
                    targetState = selectedCount,
                    transitionSpec = {
                        fadeIn(effectsDefault()).togetherWith(fadeOut(effectsFast()))
                            .using(SizeTransform(sizeAnimationSpec = { _, _ -> spatialFast() }))
                    },
                    label = "imageSelectionCount"
                ) { count -> Text("已选 $count 张", style = MaterialTheme.typography.titleMedium) }
                Text(
                    text = if (allSelected) "已全选" else "点击图片继续选择",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onToggleSelectAll, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text(if (allSelected) "取消全选" else "全选")
        }
    }
}

/**
 * 图片笔记内容：可滚动作品信息与自适应缩略图网格，分页条固定在底部。
 *
 * 分页条放在网格外：翻页控件必须**始终够得着**，
 * 不然第二页以后要滚回顶部才能翻回去。
 */
@Composable
private fun ImageNoteContent(
    result: VideoParseResult,
    images: List<VideoMedia>,
    selectionMode: Boolean,
    selectedUrls: Set<String>,
    onOpenImage: (VideoMedia) -> Unit,
    onToggleSelect: (VideoMedia) -> Unit,
    onEnterSelection: (VideoMedia) -> Unit,
    onDownloadImage: (VideoMedia) -> Unit,
    modifier: Modifier = Modifier
) {
    val pageCount = (images.size + IMAGE_PAGE_SIZE - 1) / IMAGE_PAGE_SIZE
    // 以 images 为 key：换了笔记（新解析结果）就回到第一页，不留在上一个笔记的页码上。
    // ★ 用 rememberSaveable：从全屏看图返回时本页会随预览页一起离开组合，普通 remember 会把页码丢掉
    var page by rememberSaveable(images) { mutableStateOf(0) }
    val pageImages = remember(page, images) {
        images.drop(page * IMAGE_PAGE_SIZE).take(IMAGE_PAGE_SIZE)
    }

    val imageKeys = remember(images) { mediaItemKeys(images) }
    Column(modifier = modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 文案与缩略图一起滚动，长文案或大字体不会挤走整个图片区域。
            item(key = "image_metadata", contentType = "metadata", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoMetaBlock(result)
                    Text(
                        text = "共 ${images.size} 张 · 长按图片可多选",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(
                count = pageImages.size,
                key = { imageKeys[page * IMAGE_PAGE_SIZE + it] },
                contentType = { "image" }
            ) { i ->
                val media = pageImages[i]
                ImageGridCell(
                    media = media,
                    index = page * IMAGE_PAGE_SIZE + i,
                    total = images.size,
                    selectionMode = selectionMode,
                    selected = media.url in selectedUrls,
                    onClick = { if (selectionMode) onToggleSelect(media) else onOpenImage(media) },
                    onLongClick = { onEnterSelection(media) },
                    onDownload = { onDownloadImage(media) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = effectsDefault(),
                        placementSpec = spatialDefault(),
                        fadeOutSpec = effectsFast()
                    )
                )
            }
        }
        if (pageCount > 1) {
            ImagePagerBar(page = page, pageCount = pageCount, total = images.size, onPageChange = { page = it })
        }
    }
}

/** 分页始终可见，文字与操作分行以容纳系统大字体。 */
@Composable
private fun ImagePagerBar(page: Int, pageCount: Int, total: Int, onPageChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "共 $total 张 · 第 ${page + 1}/$pageCount 页",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = { onPageChange(page - 1) }, enabled = page > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("上一页") }
            TextButton(onClick = { onPageChange(page + 1) }, enabled = page < pageCount - 1, modifier = Modifier.heightIn(min = 48.dp)) { Text("下一页") }
        }
    }
}

/**
 * 图集缩略图：1:1 裁剪 + 左上角「3/9」角标 + 右下角下载按钮；**长按进入多选**。
 *
 * ★ 请求头必须和全屏大图**完全一致**（同一份 [VideoMedia.headers]）：图集 CDN 校验 Referer/Cookie，
 *   缩略图漏带就会是一片灰块 —— 而点开大图又能看，这种「只有网格是坏的」最难排查。
 * ★ 多选态下右下角的下载按钮让位给勾选框：此时点格子是「选中/取消」，再摆个下载按钮只会误触
 *   （要下多张走底部工具栏的「下载所选」）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridCell(
    media: VideoMedia,
    index: Int,
    total: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(context, media.url, media.headers) {
        ImageRequest.Builder(context)
            .data(media.url)
            .httpHeaders(previewNetworkHeaders(media.headers))
            .build()
    }

    val selectionOverlay by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.2f else 0f),
        animationSpec = effectsFast(),
        label = "imageSelectionOverlay"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .semantics { if (selectionMode) this.selected = selected }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "选择图片",
                role = if (selectionMode) Role.Checkbox else Role.Button
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Box {
            AsyncImage(
                model = request,
                contentDescription = "第 ${index + 1} 张图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(modifier = Modifier.fillMaxSize().background(selectionOverlay))

            CellBadge(
                text = "${index + 1}/$total",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )

            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    // null = 不接管点击：整格的 onClick 负责切换选中，避免点勾选框有两条路径
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp)
                )
            } else {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = "下载这一张",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 角标使用不透明的 Material 容器角色，保证明暗图片上的可读性。 */
@Composable
private fun CellBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 底部固定的「全部下载」：列表与网格两种布局共用同一个入口 */
@Composable
private fun DownloadAllBar(result: VideoParseResult, onDownloadAll: () -> Unit) {
    // 没有媒体时不占位（空结果通常意味着解析没拿到直链）
    if (result.medias.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Button(
            onClick = onDownloadAll,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text("全部下载（${result.medias.size} 项）")
        }
    }
}

/** 作品信息块：作者、发布时间、正文/文案（超长折叠，点击展开，可一键复制原文） */
@Composable
private fun VideoMetaBlock(result: VideoParseResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 元信息行：作者 / 发布时间（时间戳为 0 表示未知，不显示）
    val metaLine = buildList {
        if (result.author.isNotBlank()) add(result.author)
        val date = formatVideoDate(result.timestampSeconds)
        if (date.isNotBlank()) add(date)
        if (result.medias.isNotEmpty()) add("共 ${result.medias.size} 项")
    }.joinToString("  ·  ")

    Column(modifier = modifier.animateContentSize(spatialDefault()).padding(bottom = 12.dp)) {
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 文案：抖音/快手的分享文案、小红书笔记正文、B站简介都落在这里。
        // 个别平台的「文案」就是标题本身（解析器把它同时写进 text），此时仍照常展示，
        // 因为顶栏标题最多两行、必然会被截断，这里才是完整的原文。
        val caption = result.text.ifBlank { result.title }
        if (caption.isNotBlank()) {
            var expanded by remember(caption) { mutableStateOf(false) }
            var canExpand by remember(caption) { mutableStateOf(false) }
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) canExpand = it.hasVisualOverflow },
                modifier = Modifier
                    .padding(top = if (metaLine.isNotBlank()) 6.dp else 0.dp)
                    .clickable(enabled = canExpand || expanded) { expanded = !expanded }
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (canExpand || expanded) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (expanded) "收起" else "展开全文")
                    }
                }
                TextButton(onClick = { copyVideoCaption(context, caption) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制文案")
                }
            }
        }
    }
}

/** 结果里的第一条视频（内联播放器只放一条；多条视频时其余仍在下面的列表里，各自可预览） */
private fun firstVideoOf(result: VideoParseResult): VideoMedia? =
    result.medias.firstOrNull { it.kind == VideoMediaKind.VIDEO && it.url.isNotBlank() }

/**
 * 内联视频播放器：结果页里直接放一个播放器，**默认停住不播**（`active = false`）。
 *
 * ★ 高度按 16:9 固定：结果页在滚动容器里，播放器必须有确定高度；实际画面按内容等比缩放（留黑边），
 *   不猜视频的真实比例。
 * ★ 不给全屏按钮（`allowFullscreen = false`）：全屏要宿主页面配合，内联场景没有对应 UI；
 *   要看全屏走上面那条媒体行的「预览」进全屏预览页。
 */
@Composable
private fun InlineVideoPlayer(media: PreviewMedia, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(24.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        MediaPlayerPane(
            media = media,
            active = false,
            onFullscreenChanged = {},
            allowFullscreen = false,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** 单条媒体：规格与操作分层，48dp 操作区随字体和宽度换行。 */
@Composable
private fun VideoMediaRow(
    index: Int,
    count: Int,
    media: VideoMedia,
    onDownload: () -> Unit,
    onDownloadAudio: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtitle = buildList {
        if (media.quality.isNotBlank()) add(media.quality)
        // size <= 0 表示未知：不显示大小，而不是显示 "0 B"
        if (media.size > 0) add(formatSize(media.size))
    }.joinToString("  ·  ")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${index + 1}/$count · ${videoMediaKindLabel(media.kind)}",
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onPreview, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("预览")
                }
                if (media.kind == VideoMediaKind.VIDEO) {
                    TextButton(onClick = onDownloadAudio, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("仅下载音频")
                    }
                }
                FilledTonalButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("下载")
                }
            }
        }
    }
}

/** 重复媒体也有唯一键；不同媒体按内容标识，翻页或更新时不以当前页下标作为身份。 */
private fun mediaItemKeys(medias: List<VideoMedia>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return medias.map { media ->
        val identity = "${media.kind}:${media.url}:${media.fileName}:${media.quality}"
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        "media:$identity#$occurrence"
    }
}

/** 媒体类型中文标签 */
private fun videoMediaKindLabel(kind: VideoMediaKind): String = when (kind) {
    VideoMediaKind.VIDEO -> "视频"
    VideoMediaKind.IMAGE -> "图片"
    VideoMediaKind.AUDIO -> "音频"
}

/** 发布时间戳（秒）→ `yyyy-MM-dd`；为 0 或非法时返回空串（调用方据此不显示） */
private fun formatVideoDate(timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampSeconds * 1000L))
    }.getOrDefault("")
}

/**
 * 复制作品文案（复制的是**完整原文**，不是折叠后的显示文本 —— 长文案正是最需要复制出去的）。
 *
 * 走系统剪贴板 + 全局 Snackbar，与本应用其它「已复制」入口保持一致；Android 13 起系统自己会弹
 * 复制预览，这里仍给一句提示（系统提示可能被用户关掉）。
 */
private fun copyVideoCaption(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText("yunx_video_caption", text))
    }.onSuccess {
        SnackbarController.show("文案已复制")
    }
}

/**
 * 点「预览」/点缩略图：把媒体转成预览条目交给全局预览页（[PreviewLauncher]）。
 *
 * ★ [group] 是**可左右翻页的一组**（图集传全部图片，视频/音频只传自己），[clicked] 是用户点的那个；
 *   `startIndex` 用**引用相等**在组内定位，即便组里有同 url 的重复项也不会翻错。
 * ★ [onDownloadMedia] 经预览页「下载」按钮回传当前页下标后再换回原始 [VideoMedia] ——
 *   于是预览页不必认识解析结果的数据模型（见 `PreviewRequest.onDownload`）。
 */
private fun openMediaPreview(
    result: VideoParseResult,
    group: List<VideoMedia>,
    clicked: VideoMedia,
    onDownloadMedia: (VideoMedia) -> Unit
) {
    // 先滤掉空直链：PreviewLauncher 内部还会再滤一次，两边滤的结果不一致就会把下标错位到别的图上
    val source = group.filter { it.url.isNotBlank() }
    if (source.isEmpty()) return
    val startIndex = source.indexOfFirst { it === clicked }
    PreviewLauncher.openMedia(
        items = source.map { it.toPreviewMedia(result.title) },
        startIndex = if (startIndex >= 0) startIndex else 0,
        title = result.title.ifBlank { clicked.fileName },
        onDownload = { index -> source.getOrNull(index)?.let(onDownloadMedia) }
    )
}

/** VideoMedia → PreviewMedia：类型映射 + 请求头 / HLS 透传（标题缺失时回退作品标题） */
private fun VideoMedia.toPreviewMedia(workTitle: String): PreviewMedia {
    // 动图（Live Photo）：预览直接播「会动的那一半」（动图资源），比只显示一张静图更贴合用户预期
    val playMotion = motionUrl.isNotBlank()
    return PreviewMedia(
        url = if (playMotion) motionUrl else url,
        kind = when {
            playMotion -> PreviewKind.VIDEO
            kind == VideoMediaKind.VIDEO -> PreviewKind.VIDEO
            kind == VideoMediaKind.IMAGE -> PreviewKind.IMAGE
            else -> PreviewKind.AUDIO
        },
        title = fileName.ifBlank { workTitle },
        headers = headers,
        isHls = isHls,
        // B站 DASH 分轨：把合并音轨一起交给预览页，由它用 MergingMediaSource 流式合并（预览也有声音）
        audioUrl = audioUrl,
        audioHeaders = audioHeaders
    )
}
