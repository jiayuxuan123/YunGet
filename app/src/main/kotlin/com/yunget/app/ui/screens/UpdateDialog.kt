package com.yunget.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunget.app.data.update.UpdateChecker
import com.yunget.app.ui.components.YunGetLoading
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton

/**
 * 发现新版本弹窗。
 *
 * ## 为什么不用 AlertDialog（本次重做的原因）
 *
 * 旧版把更新说明塞进一个 **固定 160dp 高、`bodySmall`(12sp) 的可滚动小框**。
 * 用户的原话是"更新内容在一个框里面，字体非常小，有点不太好"。这不是审美问题，
 * 是三件事叠在一起：
 *
 *  1. `bodySmall` 12sp 读长文太吃力 —— GitHub Release 正文通常是 markdown，
 *     带着标题、列表、代码，12sp 下几乎没法看；
 *  2. 160dp 的固定高度制造了**一个框里的另一个框**，视觉上像"日志窗口"，
 *     而不是"这次更新了什么"；
 *  3. `AlertDialog` 的正文区宽度被它的内边距压缩，长行折得很碎。
 *
 * 新版改成 [Dialog] + 自绘面板：正文用 `bodyLarge`(16sp)、行高 24sp，
 * 高度随内容增长到屏幕的 60% 再滚动（内容短时不留空），并且**保留换行与列表结构**
 * —— Release 正文里的 `##`/`-` 会被渲染成层级，而不是原样吐出一堆符号。
 *
 * ## 为什么仍然保留"忽略本次"
 *
 * 这是"我不想再看到这个版本"的明确表达，与"稍后"（下次启动再提醒）是不同意图。
 * 首页的更新检查依赖 `ignored_version`，这个按钮直接写它。
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    release: UpdateChecker.Release,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    downloading: Boolean = false,
    /** 使用镜像站下载（可选）；为 null 时不显示镜像站按钮 */
    onDownloadMirror: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onLater,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                // ---------- 头部：图标 + 标题 + 版本 ----------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "发现新版本",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = release.tagName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "当前 $currentVersion",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ---------- 正文 ----------
                // 关键改动：不再套 Surface 小框、不再用 bodySmall。
                // 高度用 heightIn(max = 380.dp) 而不是固定高度：内容短时面板自然收紧，
                // 内容长时才滚动 —— 旧版固定 160dp 导致"两行说明也占一大块空白"。
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ReleaseBody(
                        body = release.body.ifBlank { "暂无更新说明" },
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ---------- 动作 ----------
                Button(
                    onClick = onDownload,
                    enabled = !downloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (downloading) {
                        YunGetLoading(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载中…")
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载更新", style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (onDownloadMirror != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onDownloadMirror,
                        enabled = !downloading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("使用镜像站下载")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onLater,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "稍后",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onIgnore,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "忽略本次",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 渲染 Release 正文。
 *
 * GitHub 的 Release 正文是 markdown，但项目已经在别处引入了 markdown 渲染器
 * （`multiplatform-markdown-renderer-m3`，用于 README）。这里不直接复用它，
 * 因为那个组件的样式是按 README 场景调的（居中、大图、代码块配色），
 * 塞进弹窗里会显得过重。
 *
 * 所以这里只做**够用的结构还原**：标题行、列表项、空行分段。
 * 文字统一 16sp / 24sp 行高 —— 这是本次修复的核心（旧版 12sp）。
 */
@Composable
fun ReleaseBody(body: String) {
    val lines = body.replace("\r\n", "\n").split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(modifier = Modifier.height(2.dp))

                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                line.startsWith("- ") || line.startsWith("* ") -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = inlineCodeToPlain(line.drop(2)),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 目录/编号条目
                line.matches(Regex("^\\s*\\d+\\..*")) -> Text(
                    text = inlineCodeToPlain(line.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                else -> Text(
                    text = inlineCodeToPlain(line),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 去掉行内 markdown 记号（`code`、**bold**），保留文字本身。 */
private fun inlineCodeToPlain(text: String): String =
    text.replace("`", "")
        .replace("**", "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
