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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.R
import com.yunget.app.data.plugin.SupportEntries
import com.yunget.app.data.plugin.SupportEntry
import com.yunget.app.data.plugin.sharedPluginRuntime
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.components.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar

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
 * 现在这一页只做三件事：**读插件提供的条目 → 按类型渲染 → 没有条目时显示内置兜底**
 * （原赞赏码，见 [BuiltinSupport]）。
 *
 * ## 插件优先，内置兜底
 *
 * 正确的关系是：**插件有内容就用插件的，没有就用内置的**。
 *
 * 曾经写成"没有插件条目就显示空" —— 理由是"卸载插件后入口应当消失"。
 * 那个推理是错的：它把**验收标准**当成了**默认行为**，于是发布后打开支持页
 * 是一片空白，比改之前更差。
 *
 * 内置内容的作用是**兜底**，与 P0 网盘插件化里"内置实现保留作回退"是同一个道理：
 * 渐进迁移期间，任何依赖新插件的功能都不能因此变得不可用。
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

    // 系统返回键离开本页。2.7.5 漏了这条，在本页按返回会直接退出应用。
    androidx.activity.compose.BackHandler { onBack() }

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

                // 没有插件内容时用内置兜底，而不是空白页。
                list.isEmpty() -> BuiltinSupport()

                else -> list.forEach { item -> SupportEntryCard(item) }
            }
        }
    }
}

/**
 * 没有插件提供内容时显示的**内置兜底**。
 *
 * 保留原来内置页面的实质内容（赞赏方式），但样式跟随当前设计语言：
 * 没有渐变头、没有大插画，条目化呈现 —— 因为插件内容也走同一套渲染，
 * 两者的观感应该一致，用户分不出哪个是内置、哪个是插件，那才是对的。
 *
 * 措辞按设计文档第 26 章：说清事实，不写"绝对安全"这类空话。
 */
/**
 * 没有插件提供内容时显示的**内置兜底**。
 *
 * 内容就是改造前内置页的实质部分（微信赞赏码，`res/drawable/weixin.png` 一直都在）——
 * 曾经写成"一张解释当前为什么是空的的卡片"，结果用户打开支持页看到的是**占位说明**
 * 而不是任何实际内容：那等于把"赞赏入口由插件提供"当成了"没有插件就什么都不给"。
 * 兜底的意义与 P0 网盘插件化的"内置实现保留作回退"相同：迁移期不能让功能消失。
 *
 * 装了提供支持条目的插件后，插件条目**整体取代**本兜底（见上方 when 分支）——
 * 插件优先、内置兜底，两者不混排，用户不会看到两份赞赏入口。
 *
 * 旧的"保存二维码到相册"按钮没有搬回来：截图后微信扫一扫支持从相册识别，
 * 为一个按钮引入存储权限流程不值得。
 */
@Composable
private fun BuiltinSupport() {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                Text(
                    text = "微信扫码赞赏（本二次开发版）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "你的支持，是持续维护与更新的动力",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Image(
                    painter = painterResource(R.drawable.weixin),
                    contentDescription = "微信赞赏码",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "截图后在微信「扫一扫」里从相册识别即可赞赏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "此赞赏码仅用于支持本二次开发（多线程升级）版本的维护。若想支持上游原项目作者，" +
                "请移步原仓库 CYQawa/YunX 的捐赠渠道，两者互不相关。" +
                "装了提供支持信息的插件后，这一页会改为显示插件提供的内容。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

                SupportEntry.Kind.IMAGE -> if (SupportEntries.isHttpUrl(entry.payload)) {
                    // 插件没法在 manifest 里带二进制 —— 远程 URL 是它提供一张收款码的唯一途径。
                    // 走公共图片加载器（缓存/降采样与 README 正文图片同一套）；URL 的
                    // scheme 合法性在净化阶段已经把关（见 SupportEntries）。
                    RemoteImage(
                        url = entry.payload,
                        contentDescription = entry.title,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                        autoHeight = true,
                        placeholderRatio = 1f,
                    )
                } else {
                    SupportImage(entry.payload)
                }

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
