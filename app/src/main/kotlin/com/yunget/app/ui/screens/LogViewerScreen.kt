/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
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

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunget.app.ui.SnackbarController
import com.yunget.app.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实时日志（本次日志系统升级的核心）。
 *
 * ## 为什么要有这一页
 *
 * 旧版只有"导出日志"一个入口：想知道发生了什么，必须**先导出、再把文件拿给别人看**。
 * 而绝大多数时候用户（或开发者自己）只是想确认"刚才那步有没有报错"——
 * 为此走一遍导出流程显然过重。
 *
 * 更实际的问题是：**错误分散在按时间排列的长日志里**，靠肉眼找。所以这一页提供
 * 级别过滤 —— 点一下「错误」就只剩 ERROR，那是用户报"有问题"时最该先看的东西。
 *
 * ## 数据来自哪里
 *
 * [DiagLog.recent] 读的是进程内环形缓冲（400 行）。它不读文件，因为：
 *  - 文件里只有 INFO 以上，而现场排查常常需要 DEBUG 细节；
 *  - 读文件要处理截断、多分类合并这些事，而"看最近发生了什么"不需要那些。
 *
 * 环形缓冲只在进程内，所以**重启后这一页是空的** —— 那一页的说明里写清楚了，
 * 持久化的内容要靠导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var minLevel by remember { mutableStateOf(DiagLog.Level.INFO) }
    var autoRefresh by remember { mutableStateOf(true) }

    // 自动刷新：日志是"边跑边看"的东西，不刷新就失去意义。
    // 用 1.2s 间隔 —— 更短会让长列表频繁重组（观感是闪），更长则跟不上操作节奏。
    LaunchedEffect(minLevel, autoRefresh) {
        while (true) {
            lines = DiagLog.recent(minLevel = minLevel, limit = 300)
            if (!autoRefresh) break
            delay(1200)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("日志", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (autoRefresh) {
                            autoRefresh = false
                        } else {
                            autoRefresh = true
                            lines = DiagLog.recent(minLevel = minLevel, limit = 300)
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = if (autoRefresh) "暂停自动刷新" else "恢复自动刷新",
                            tint = if (autoRefresh) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                DiagLog.recent(minLevel = null, limit = 400).joinToString("\n")
                            }
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val cm = context.getSystemService(
                                        android.content.ClipboardManager::class.java
                                    )
                                    cm?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("YunGet 日志", text)
                                    )
                                    true
                                }.getOrDefault(false)
                            }
                            SnackbarController.show(if (ok) "已复制 ${lines.size} 行" else "复制失败")
                        }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制当前日志")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { DiagLog.clear(context) }
                            lines = DiagLog.recent(minLevel = minLevel, limit = 300)
                            SnackbarController.show("已清空诊断日志（本次页面仍显示缓冲内容）")
                        }
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "清空")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 级别过滤：这一排是这一页最有用的东西 —— 出问题时点「错误」即可。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (lv in DiagLog.Level.entries) {
                    FilterChip(
                        selected = minLevel == lv,
                        onClick = { minLevel = lv },
                        label = { Text(lv.label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (lv == DiagLog.Level.ERROR) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                    )
                }
            }

            Text(
                text = buildString {
                    append("显示 ${lines.size} 行")
                    if (!autoRefresh) append("（已暂停自动刷新）")
                    append(" · 仅本次运行 · 历史上限 400 行")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (lines.isEmpty()) {
                Text(
                    text = if (minLevel.ordinal >= DiagLog.Level.WARN.ordinal) {
                        "当前级别下没有日志。说明到现在为止没出现过这个级别的记录 —— 换个级别看看。"
                    } else {
                        "还没有日志。用一下应用（下载、解析、开关插件），这里就会开始出现内容。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(12.dp),
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (line in lines) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            // 错误单独着色：快速扫一眼就能看出有没有出事。
                            color = if (line.contains(" [E] ")) {
                                MaterialTheme.colorScheme.error
                            } else if (line.contains(" [W] ")) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
