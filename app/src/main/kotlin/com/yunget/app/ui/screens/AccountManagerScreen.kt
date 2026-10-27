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

package com.yunget.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.ui.items.MultiAccountList
import com.yunget.app.ui.items.MultiAccountRow
import com.yunget.app.ui.items.platformLogoRes

/**
 * 账号管理页：所有平台的多账号一屏看完。
 *
 * ## 为什么单独做一页，而不是塞进每个平台的账号弹窗
 *
 * 十个平台的账号弹窗结构本来就不一样（有的 2 个按钮、有的 6 个），
 * 逐个改意味着十次重复改动 + 十种不一致的风险。
 *
 * 而多账号的**操作对象是同一个**（切换主账号、删除账号），
 * 把它收进一页，十个平台就共享同一套交互，也没必要在每个弹窗里再放一遍。
 *
 * ## 与"账号弹窗"的分工
 *
 * - 账号弹窗：看这个平台的当前账号详情（Cookie、登录时间、退出）；
 * - 这一页：管多个账号（谁是主账号、删哪一个、再加一个）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagerScreen(
    /** 平台 id → (显示名, 该平台的全部账号)。按传入顺序展示。 */
    platforms: List<PlatformAccounts>,
    onBack: () -> Unit,
    onSelectPrimary: (platformId: String, accountId: String) -> Unit,
    onDeleteAccount: (platformId: String, accountId: String) -> Unit,
    /**
     * 为某个平台**再登录一个账号**。
     *
     * ★ 这一页原先只能看、切、删 —— 想加一个号必须退回「网盘」页再点那个平台，
     *   而网盘页对"已经登录过"的平台只显示「已登录」，用户看不出再点一次
     *   是新增还是覆盖。多账号做完了却没有新增的入口，等于只做了一半。
     */
    onAddAccount: (platformId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("账号管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        // 已登录的平台排在前面（多账号管理是主用途），未登录的排在后面
        // —— 它们的卡片上只有「添加账号」按钮，没有账号列表。
        val loggedIn = platforms.filter { it.accounts.isNotEmpty() }
        val notLoggedIn = platforms.filter { it.accounts.isEmpty() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loggedIn.isEmpty()) {
                item {
                    Text(
                        text = "还没有登录任何网盘账号。从下面挑一个平台登录即可。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(count = loggedIn.size) { index ->
                    val p = loggedIn[index]
                    PlatformCard(
                        platform = p,
                        onSelectPrimary = { id -> onSelectPrimary(p.platformId, id) },
                        onDelete = { id -> onDeleteAccount(p.platformId, id) },
                        onAdd = { onAddAccount(p.platformId) },
                    )
                }
            }

            // 未登录的平台：给一个「添加账号」入口，这一页才真的能"加账号"。
            // 之前这些平台在这一页完全不出现，用户只能退回网盘页找。
            if (notLoggedIn.isNotEmpty()) {
                item {
                    Text(
                        text = "其他平台",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(count = notLoggedIn.size) { index ->
                    val p = notLoggedIn[index]
                    EmptyPlatformRow(
                        platform = p,
                        onAdd = { onAddAccount(p.platformId) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "「主账号」是列目录、转存、上传时使用的身份。" +
                        "同一平台有多个账号时，点某一行即可切换主账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一个平台的账号集合。 */
data class PlatformAccounts(
    val platformId: String,
    val displayName: String,
    val accounts: List<MultiAccountRow>,
)

@Composable
private fun PlatformCard(
    platform: PlatformAccounts,
    onSelectPrimary: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // 平台标题行：logo + 名称 + 账号数
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val logo = platformLogoRes(platform.platformId)
                if (logo != null) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(logo),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = platform.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${platform.accounts.size} 个账号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            MultiAccountList(
                platformId = platform.platformId,
                accounts = platform.accounts,
                onSelectPrimary = onSelectPrimary,
                onDelete = onDelete,
            )
            Spacer(Modifier.height(8.dp))
            AddAccountButton(onAdd)
        }
    }
}

/** 未登录的平台：一行提示 + 添加账号按钮。 */
@Composable
private fun EmptyPlatformRow(
    platform: PlatformAccounts,
    onAdd: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            val logo = platformLogoRes(platform.platformId)
            if (logo != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = platform.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAdd) { Text("添加账号") }
        }
    }
}

/** 「再登录一个账号」按钮 —— 多账号的入口，不该只藏在网盘页里。 */
@Composable
private fun AddAccountButton(onAdd: () -> Unit) {
    TextButton(onClick = onAdd) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text("添加账号")
    }
}
