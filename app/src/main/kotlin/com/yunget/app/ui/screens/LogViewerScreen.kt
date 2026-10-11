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
 * 两个来源，用户可切：
 *
 *  - **实时**：[DiagLog.recent] 读进程内环形缓冲（400 行）。它不读文件，因为：
 *    文件里只有 INFO 以上，而现场排查常常需要 DEBUG 细节。
 *  - **落盘**：读已写盘的日志文件（错误汇总 / 全部分类）。环形缓冲重启就空，
 *    "上次为什么崩了、刚才哪一步报错了"必须看落盘 —— 崩溃报告由
 *    [com.yunget.app.util.DiagLog.recordCrash] 写进 boot.log 与 errors.log，
 *    所以重启后在这里切换到落盘来源就能看到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var minLevel by remember { mutableStateOf(DiagLog.Level.INFO) }
    var autoRefresh by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf(Source.LIVE) }

    // 系统返回键离开本页。2.7.5 漏了这条，在本页按返回会直接退出应用。
    androidx.activity.compose.BackHandler { onBack() }

    // 自动刷新：日志是"边跑边看"的东西，不刷新就失去意义。
    // 用 1.2s 间隔 —— 更短会让长列表频繁重组（观感是闪），更长则跟不上操作节奏。
    // 落盘来源同样轮询：文件在应用继续运行时还会被写入（复现问题时切过来能看到增量）。
    LaunchedEffect(minLevel, autoRefresh, source) {
        while (true) {
            lines = loadLines(context, source, minLevel)
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
                            scope.launch { lines = loadLines(context, source, minLevel) }
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
                            // 复制**当前正在显示**的内容：实时模式是缓冲，落盘模式是文件 ——
                            // 这样"我在落盘·错误里看到一条报错，复制它"拿到的就是那条，而不是另一份缓冲。
                            val text = lines.joinToString("\n")
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

            // 来源切换：实时缓冲只有本次运行的内容，重启就空；落盘文件重启后仍在 ——
            // "上次为什么崩了/刚才哪一步报错了"要看的是落盘，不是这份空的缓冲。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (s in Source.entries) {
                    FilterChip(
                        selected = source == s,
                        onClick = { source = s },
                        label = { Text(s.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            Text(
                text = buildString {
                    append("显示 ${lines.size} 行")
                    if (source == Source.LIVE) {
                        if (!autoRefresh) append("（已暂停自动刷新）")
                        append(" · 仅本次运行 · 历史上限 400 行")
                    } else {
                        append(" · 来自落盘日志文件（重启后仍可查看）")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (lines.isEmpty()) {
                Text(
                    text = if (source != Source.LIVE) {
                        "落盘日志还没有内容。用一下应用（下载、解析、开关插件）或发生崩溃后，" +
                            "这里就会有内容 —— 且重启应用后依然能看。"
                    } else if (minLevel.ordinal >= DiagLog.Level.WARN.ordinal) {
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

/** 数据来源。实时 = 进程内环形缓冲；落盘 = 已写盘的日志文件（重启后仍在）。 */
private enum class Source(val label: String) {
    LIVE("实时"),
    ERRORS_FILE("错误汇总 · 落盘"),
    ALL_FILES("全部分类 · 落盘"),
}

/** 按当前来源取一批行：实时走环形缓冲，落盘走文件（IO 切后台线程）。 */
private suspend fun loadLines(
    context: android.content.Context,
    source: Source,
    minLevel: DiagLog.Level,
): List<String> = withContext(Dispatchers.IO) {
    val raw = when (source) {
        Source.LIVE -> DiagLog.recent(minLevel = minLevel, limit = 300)
        Source.ERRORS_FILE -> (DiagLog.readErrors(context) ?: "").lines()
        Source.ALL_FILES -> (DiagLog.readAll(context) ?: "").lines()
    }
    raw.filter { passesLevel(it, minLevel) }.takeLast(300)
}

/**
 * 文件行的级别过滤，与 [DiagLog.recent] 的实时过滤同一判据：行内 ` [X] ` 标记。
 * 没有级别标记的行（readAll 的分类标题行、空行）原样放行 —— 过滤不该把"这段是哪个文件"藏掉。
 */
private fun passesLevel(line: String, min: DiagLog.Level): Boolean {
    val open = line.indexOf(" [")
    if (open < 0) return true
    val mark = line.getOrNull(open + 2) ?: return true
    val actual = DiagLog.Level.entries.firstOrNull { it.mark == mark } ?: return true
    return actual.ordinal >= min.ordinal
}
