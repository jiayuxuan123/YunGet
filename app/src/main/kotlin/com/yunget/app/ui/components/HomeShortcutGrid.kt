/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * 本文件取自上游 YunX (https://github.com/CYQawa/YunX)
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

package com.yunget.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.db.BookmarkEntity
import com.yunget.app.ui.viewmodel.BookmarkViewModel

/**
 * 主页快捷方式区块：展示已「添加到主页」的收藏链接，点击直接解析，长按确认后从主页移除。
 *
 * 上游把它写在解析页（`ResolveScreen.kt`）的输入区里，本二次开发版把它抽成独立组件，
 * 便于接进本项目的「解析」Tab（见文件末尾的接入说明）。
 *
 * ★ 外层一定是 verticalScroll（解析页输入区就是），这里**不能用 LazyVerticalGrid**
 *   （同方向嵌套滚动会崩），因此用 chunked 手写行网格，末行用 Spacer 占位保证每个格子等宽。
 * ★ 数据源是 `BookmarkViewModel.homePinned`（数据库 `bookmark.homePinned = 1`），
 *   与收藏页「添加到主页 / 从主页移除」是同一个字段，两边天然同步。
 *
 * @param bookmarks 已固定到主页的收藏（一般直接传 `bookmarkViewModel.homePinned` 的收集值）
 * @param onOpen 点击快捷方式：解析这条收藏（调用方负责回填链接/提取码并开始解析）
 * @param onManage 右上角「管理」：打开收藏页
 * @param onRemove 长按确认后移除：调用方执行 `setHomePinned(id, false)`
 */
@Composable
fun HomeShortcutGrid(
    bookmarks: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onManage: () -> Unit,
    onRemove: (BookmarkEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // 待确认移除的快捷方式（长按触发，避免误触直接消失）
    var removing by remember { mutableStateOf<BookmarkEntity?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "快捷方式",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManage) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "管理", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (bookmarks.isEmpty()) {
            // 空态：引导到收藏页添加，避免这里只剩一片空白
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddToHomeScreen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "还没有主页快捷方式",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "在「收藏网盘链接」页长按一条收藏 → 添加到主页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookmarks.chunked(HOME_SHORTCUT_COLUMNS).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { bookmark ->
                            HomeShortcutTile(
                                bookmark = bookmark,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpen(bookmark) },
                                onLongClick = { removing = bookmark }
                            )
                        }
                        // 末行不足一列时补空位，保证每个格子宽度一致
                        repeat(HOME_SHORTCUT_COLUMNS - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    removing?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("从主页移除") },
            text = {
                Text(
                    text = "「${bookmark.title.ifBlank { bookmark.link }}」将不再显示在主页快捷方式中",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(bookmark)
                        removing = null
                    }
                ) {
                    Text(text = "移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/**
 * 便捷重载：直接吃 [BookmarkViewModel] —— 数据源（`homePinned`）与长按移除都自己处理，
 * 接入侧只需要三行（收集、点击解析、打开收藏页）。
 *
 * 与上面的状态提升版本共用同一份实现，所以两边行为不可能跑偏；
 * 需要自己接管数据流（比如顺带做排序/过滤）时用上面那个。
 */
@Composable
fun HomeShortcutGrid(
    viewModel: BookmarkViewModel,
    onOpen: (BookmarkEntity) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pinned by viewModel.homePinned.collectAsState()
    HomeShortcutGrid(
        bookmarks = pinned,
        onOpen = onOpen,
        onManage = onManage,
        onRemove = { viewModel.setHomePinned(it.id, false) },
        modifier = modifier
    )
}

/** 主页快捷方式列数（4 列在窄屏也能放下两字标题，观感贴近桌面图标网格） */
private const val HOME_SHORTCUT_COLUMNS = 4

/** 主页快捷方式单个瓦片：圆角色块（平台简称）+ 标题，长按移除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutTile(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val label = homeTileLabel(bookmark)
            if (label.isEmpty()) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = label,
                    // 字越多字号越小：4 个字（自定义上限）也能塞进 48dp 方块
                    style = when {
                        label.length <= 2 -> MaterialTheme.typography.titleMedium
                        label.length == 3 -> MaterialTheme.typography.labelLarge
                        else -> MaterialTheme.typography.labelSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FileNameText(
            text = bookmark.title.ifBlank { bookmark.link },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

/** 快捷方式色块最多显示几个字（标题截断、自定义文字都受它约束） */
internal const val HOME_LABEL_MAX_LENGTH = 4

/**
 * 快捷方式色块文字：自定义文字 > 标题前几个字 > 平台简称（标题为空时的兜底）。
 * 返回空串表示没有可显示的文字，调用处退回通用链接图标。
 * 收藏页的「自定义图标文字」弹窗也用它做占位提示，保证"自动文字"只有一个实现。
 */
internal fun homeTileLabel(bookmark: BookmarkEntity): String {
    val custom = bookmark.homeLabel.trim()
    if (custom.isNotEmpty()) return custom.take(HOME_LABEL_MAX_LENGTH)
    val title = bookmark.title.trim()
    if (title.isNotEmpty()) return title.take(HOME_LABEL_MAX_LENGTH)
    return (platformShortLabel(bookmark.platform) ?: "").take(HOME_LABEL_MAX_LENGTH)
}

/** 平台简称（色块兜底文字）；未知平台返回 null，调用处退回通用链接图标 */
private fun platformShortLabel(platform: String): String? = when (platform) {
    "QUARK" -> "夸克"
    "UC" -> "UC"
    "XUNLEI" -> "迅雷"
    "BAIDU" -> "百度"
    "C139" -> "139"
    "PAN123" -> "123"
    "PAN115" -> "115"
    "GUANGYA" -> "光鸭"
    "ILANZOU" -> "优享"
    "LANZOU" -> "蓝奏"
    "GITHUB" -> "GitHub"
    else -> null
}
