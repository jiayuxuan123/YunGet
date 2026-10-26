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

package com.yunget.app.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 某平台下的账号列表。
 *
 * ## 为什么是"列表"而不是每行一个开关
 *
 * 多账号的核心问题是**身份选择**：列目录、转存、上传都要用某一个确定账号。
 * 用单选（点一行设为主账号）表达"当前用谁"，比一堆开关清楚得多 ——
 * 开关只能表达"启用/停用"，表达不了"这些里挑一个"。
 *
 * ## 为什么用真实 logo 做头像
 *
 * 同一平台下的多个账号头像全一样，唯一能区分的是备注与昵称。
 * 头像位置留给平台 logo，让用户一眼确认"这是哪个平台"，
 * 而备注/昵称承担"这是哪个账号" —— 两个信息不抢同一个位置。
 */
@Composable
fun MultiAccountList(
    /** 平台标识（`"quark"` 等），用于取 logo。 */
    platformId: String,
    accounts: List<MultiAccountRow>,
    onSelectPrimary: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (accounts.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "该平台的账号",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${accounts.size} 个",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                accounts.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    AccountRow(
                        platformId = platformId,
                        row = row,
                        canDelete = accounts.size > 1,
                        onSelectPrimary = { onSelectPrimary(row.id) },
                        onDelete = { onDelete(row.id) },
                    )
                }
            }
        }
    }
}

/** 一行账号的展示数据。各平台实体字段不同，由调用方映射成这个形状。 */
data class MultiAccountRow(
    val id: String,
    /** 备注（用户起的名字），可空。 */
    val remark: String,
    /** 平台昵称。 */
    val nickname: String,
    val isPrimary: Boolean,
    /** 该账号的简短附加信息（如脱敏后的手机号），可空。 */
    val detail: String = "",
)

@Composable
private fun AccountRow(
    platformId: String,
    row: MultiAccountRow,
    canDelete: Boolean,
    onSelectPrimary: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点 = 设为主账号。这是这一页最主要的动作，
            // 不该让用户去找一个小圆点。
            .clickable(enabled = !row.isPrimary, onClick = onSelectPrimary)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像：平台 logo（明确"这是哪个平台"）；登录态不需要额外标记 ——
        // 能出现在这个列表里就说明有凭证。
        val logo = platformLogoRes(platformId)
        if (logo != null) {
            androidx.compose.foundation.Image(
                painter = painterResource(logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayOf(row).take(1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayOf(row),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (row.isPrimary) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isPrimary) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "主账号",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (row.detail.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // 右侧：主账号显示对勾；其余显示删除。
        // 只剩一个账号时不给删除入口 —— 删完该平台就没有账号了，
        // 那是"退出登录"的语义，应该走原来的退出按钮（它会清理 Cookie 等副作用）。
        if (row.isPrimary) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (canDelete) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除该账号",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 备注优先，没有则昵称，都没有给个占位。 */
private fun displayOf(row: MultiAccountRow): String = when {
    row.remark.isNotBlank() -> row.remark
    row.nickname.isNotBlank() -> row.nickname
    else -> "未命名账号"
}
