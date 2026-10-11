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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.plugin.SupportEntries
import com.yunget.app.data.plugin.SupportEntry
import com.yunget.app.data.plugin.sharedPluginRuntime
import com.yunget.app.ui.SnackbarController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 支持页（P7）—— **内容由插件提供，宿主只提供骨架**。
 *
 * ## 为什么改成现在这样
 *
 * 设计文档第 14 章要求"赞赏/支持属于非核心功能，可以实现为 Support Plugin，
 * 而不是直接写死在 Host"。改造前这里是一个 368 行的写死页面（固定的渐变头、
 * 固定的微信赞赏码卡片、固定的按钮）。那个版本的问题不只是"不符合设计文档"：
 *
 *  - 想换一种支持方式（比如换成另一个平台的码）就要改 App 发版；
 *  - 开发者之外的人无法替换它 —— 而这恰好是插件系统存在的理由。
 *
 * 现在这一页只做三件事：**读插件提供的条目 → 按类型渲染 → 没有条目时说实话**。
 *
 * ## 为什么没有条目时不回退到内置内容
 *
 * P7 的验收标准是"卸载该插件后入口消失且不影响其他功能"。若这里回退到一个内置的
 * 写死页面，那条标准就**无法验证** —— 卸载后看起来"内容还在"，只是换了来源。
 * 所以没条目就显示"当前没有支持入口"，这是诚实的、也是可验证的。
 *
 * ## 视觉上的克制
 *
 * 这一页有意做得**比其它页面朴素**（没有渐变头、没有大卡片）：它是插件内容的中性容器，
 * 宿主不该在这里表达自己的视觉主张，否则插件提供的条目会与背景打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 条目来自插件运行时。用 produceState 在后台读：读 manifest 与净化都可能涉及
    // 文件操作，不该占主线程；同时它会在重组时重新读一次 —— 用户"装完插件回到这页"
    // 就能看到新条目，不需要杀掉应用。
    val entries by produceState(initialValue = null as List<SupportEntries.SanitizedEntry>?) {
        value = withContext(Dispatchers.IO) {
            runCatching { sharedPluginRuntime(context).supportEntries() }.getOrDefault(emptyList())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("支持开发", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val list = entries
            when {
                // 正在读：一句话就够，不摆骨架屏（这页内容很少，闪一下比等待更烦）
                list == null -> Text(
                    text = "正在读取…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                list.isEmpty() -> NoSupportSource()

                else -> list.forEach { item -> SupportEntryCard(item) }
            }
        }
    }
}

/**
 * 没有任何插件提供支持入口时显示的内容。
 *
 * 措辞刻意**不道歉、不推销**：它不是说"很遗憾没有"，而是说清现状与怎么加一个。
 * 这正是设计文档第 26 章的口径 —— 不声称不存在的功能。
 */
@Composable
private fun NoSupportSource() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "当前没有支持入口",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "支持入口由插件提供 —— 装一个提供支持信息的插件，这里就会出现内容。" +
                    "没有插件时这一页保持为空，而不是显示一份写死在应用里的清单。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一条支持条目。按 [SupportEntry.kind] 决定渲染方式。 */
@Composable
private fun SupportEntryCard(item: SupportEntries.SanitizedEntry) {
    val context = LocalContext.current
    val entry = item.entry

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                // 来源要标出来：用户需要知道这段内容是**哪个插件**放的 ——
                // 它是第三方代码提供的信息，不是应用的官方声明。
                Text(
                    text = "来自 ${item.ownerPluginId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            when (entry.kind) {
                SupportEntry.Kind.TEXT -> Text(
                    text = entry.payload,
                    style = MaterialTheme.typography.bodyMedium,
                )

                SupportEntry.Kind.IMAGE -> SupportImage(entry.payload)

                SupportEntry.Kind.LINK -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = entry.payload,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        // 只走系统浏览器。条目里的非 http/https 链接在净化阶段已经挡掉了
                        // （见 SupportEntries），所以这里不会拿到 intent:// 之类的东西。
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(entry.payload),
                                )
                            )
                        }.onFailure { SnackbarController.show("打不开这个链接") }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开")
                    }
                }

                SupportEntry.Kind.COPYABLE -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = entry.payload,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { copyToClipboard(context, entry.payload) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                    }
                }
            }
        }
    }
}

/**
 * 显示插件提供的一张图片（如收款码）。
 *
 * 路径在净化阶段已经确认落在应用私有目录内；这里再判一次存在性 ——
 * 文件可能被清理掉，那时显示一句实话而不是一个破图。
 */
@Composable
private fun SupportImage(path: String) {
    val bitmap by produceState(initialValue = null as android.graphics.Bitmap?) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(path)
                if (!f.isFile) null else android.graphics.BitmapFactory.decodeFile(path)
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Text(
            text = "图片不在了（可能已被清理）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(220.dp),
            )
        }
    }
}

/** 复制到剪贴板并给出反馈。 */
private fun copyToClipboard(context: android.content.Context, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("YunGet", text))
        SnackbarController.show("已复制")
    }.onFailure { SnackbarController.show("复制失败") }
}
