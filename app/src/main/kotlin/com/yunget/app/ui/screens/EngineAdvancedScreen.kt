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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.ui.theme.ListGroupGap
import com.yunget.app.ui.theme.listGroupShape

/**
 * 引擎高级参数（TurboDL 的真实旋钮）。
 *
 * ## 为什么要有这一页
 *
 * 这些降级开关此前**只有代码、没有入口**：值被硬编码在
 * `TurboDownloadManager.buildConfig()` 里，遇到特定的服务端行为
 * （HTTP/2 把分片塌缩到单连接、CDN 反复 502/503、按连接数封禁）
 * 用户没有任何自救手段，只能等发新版。
 *
 * 现在它们全部落盘并可调；**默认值与旧硬编码完全一致**，
 * 所以不动这些开关时行为与之前逐字节相同。
 *
 * ## 为什么默认收起、且带风险提示
 *
 * 这 12 项大多不是"越大越好"：
 *  - 关掉「强制 HTTP/1.1」会让几十个线程挤在一条 TCP 上，速度反而暴跌；
 *  - 调大「每连接预分块数」会让分片数暴涨，建连与合并开销翻倍；
 *  - 「背压阈值」开启后在频繁 502 的 CDN 上会让并发只降不升。
 *
 * 所以每一项都写了「什么时候该改」和「改错的后果」，而不是只给一个开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineAdvancedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }

    // 用 Compose 状态包一层：SharedPreferences 本身不参与快照，
    // 直接读 getter 不会触发重组。
    var forceHttp1 by remember { mutableStateOf(repo.forceHttp1) }
    var dynamicSegmentation by remember { mutableStateOf(repo.dynamicSegmentation) }
    var segmentsPerConnection by remember { mutableStateOf(repo.segmentsPerConnection) }
    var tailAssist by remember { mutableStateOf(repo.tailAssist) }
    var adaptiveConcurrency by remember { mutableStateOf(repo.adaptiveConcurrency) }
    var overlapMerge by remember { mutableStateOf(repo.overlapMerge) }
    var bufferedSegmentWrite by remember { mutableStateOf(repo.bufferedSegmentWrite) }
    var backpressureFailures by remember { mutableStateOf(repo.backpressureFailures) }
    var maxConnectionsPerHost by remember { mutableStateOf(repo.maxConnectionsPerHost) }
    var stallTimeoutSec by remember { mutableStateOf((repo.stallTimeoutMs / 1000L).toInt()) }
    var trustWeakValidator by remember { mutableStateOf(repo.trustWeakValidator) }
    var skipProbeWhenSizeKnown by remember { mutableStateOf(repo.skipProbeWhenSizeKnown) }

    var showAdaptiveWarning by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("引擎高级参数") },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ---------- 顶部说明 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "默认值 = 推荐值",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "这些开关的默认值与内置下载器的既有行为完全一致。" +
                            "改错方向通常会让下载**变慢**，而不是变快 —— " +
                            "每项下面都写了「什么时候该动它」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        forceHttp1 = true
                        dynamicSegmentation = true
                        segmentsPerConnection = 4
                        tailAssist = true
                        adaptiveConcurrency = true
                        overlapMerge = false
                        bufferedSegmentWrite = false
                        backpressureFailures = 0
                        maxConnectionsPerHost = 0
                        stallTimeoutSec = 45
                        trustWeakValidator = false
                        skipProbeWhenSizeKnown = true

                        repo.forceHttp1 = true
                        repo.dynamicSegmentation = true
                        repo.segmentsPerConnection = 4
                        repo.tailAssist = true
                        repo.adaptiveConcurrency = true
                        repo.overlapMerge = false
                        repo.bufferedSegmentWrite = false
                        repo.backpressureFailures = 0
                        repo.maxConnectionsPerHost = 0
                        repo.stallTimeoutMs = 45_000L
                        repo.trustWeakValidator = false
                        repo.skipProbeWhenSizeKnown = true
                    }) {
                        Text("全部恢复默认")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel("连接与分片")

            // 1. 强制 HTTP/1.1
            ParamSwitch(
                title = "强制 HTTP/1.1",
                description = "开启后每个分片走独立连接。关掉会允许 h2 —— " +
                    "h2 把所有分片多路复用到同一条 TCP，共享拥塞窗口，" +
                    "开几十个线程也只有单连接速度。",
                checked = forceHttp1,
                onCheckedChange = {
                    forceHttp1 = it
                    repo.forceHttp1 = it
                }
            )

            // 2. 动态分片
            ParamSwitch(
                title = "动态分片",
                description = "把快完成的分片再切开交给空闲连接，消除慢连接长尾。" +
                    "关掉后分片数固定、行为更可预测，适合排查「并发为什么上不去」。",
                checked = dynamicSegmentation,
                onCheckedChange = {
                    dynamicSegmentation = it
                    repo.dynamicSegmentation = it
                }
            )

            // 3. 每连接预分块数
            ParamStepper(
                title = "每连接预分块数",
                description = "块数 = 连接数 × 本值。块越多，慢连接被窃取的机会越大；" +
                    "但块太小会增加建连与合并开销。不要为了减少请求数调大它。",
                value = segmentsPerConnection,
                range = 1..16,
                unit = "块",
                onChange = {
                    segmentsPerConnection = it
                    repo.segmentsPerConnection = it
                }
            )

            // 4. 收尾托管
            ParamSwitch(
                title = "收尾托管",
                description = "队列空但仍有分片在跑时，把最大的尾部让给空闲连接。" +
                    "只在收尾阶段触发，关掉一般只会让最后几秒变慢。",
                checked = tailAssist,
                onCheckedChange = {
                    tailAssist = it
                    repo.tailAssist = it
                }
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel("吞吐与并发")

            // 5. 吞吐自适应（带已知缺陷警告）
            ParamSwitch(
                title = "吞吐自适应",
                description = "按实测吞吐寻找「够用的最低并发」。" +
                    "⚠️ 已知缺陷：真实链路上它可能把并发一路降到 1 —— " +
                    "遇到过「下载越跑越慢、线程只降不升」就先关掉这一项。",
                checked = adaptiveConcurrency,
                onCheckedChange = { on ->
                    adaptiveConcurrency = on
                    repo.adaptiveConcurrency = on
                    if (!on) showAdaptiveWarning = true
                },
                accentIcon = Icons.Outlined.Warning
            )

            // 6. 下载中预先合并
            ParamSwitch(
                title = "下载中预先合并",
                description = "把完成的分片立刻写进目标文件，让合并 I/O 与网络等待重叠。" +
                    "开启会增加磁盘写入量（每个分片写两次）。",
                checked = overlapMerge,
                onCheckedChange = {
                    overlapMerge = it
                    repo.overlapMerge = it
                }
            )

            // 7. 分片缓冲写入
            ParamSwitch(
                title = "分片缓冲写入",
                description = "分片先缓冲再落盘，减少小写入次数。机械盘/低速 SD 卡上更稳，" +
                    "但在大内存设备上收益不明显。",
                checked = bufferedSegmentWrite,
                onCheckedChange = {
                    bufferedSegmentWrite = it
                    repo.bufferedSegmentWrite = it
                }
            )

            // 8. 背压降并发阈值
            ParamStepper(
                title = "背压降并发阈值",
                description = "连续 N 个分片失败就把并发减半。默认 0 = 关闭：" +
                    "网盘 CDN 频繁 502/503，开了会让线程只降难升。" +
                    "只有服务端确实按并发限流时才该打开。",
                value = backpressureFailures,
                range = 0..16,
                unit = "次",
                zeroLabel = "关闭",
                onChange = {
                    backpressureFailures = it
                    repo.backpressureFailures = it
                }
            )

            // 9. 单主机连接上限
            ParamStepper(
                title = "单主机连接上限",
                description = "单个下载的所有分片都来自同一主机，设小值会把它直接限死" +
                    "（表现为「设 64 只跑 16」）。仅用于对抗明确按连接数封禁的 CDN。",
                value = maxConnectionsPerHost,
                range = 0..256,
                step = if (maxConnectionsPerHost >= 64) 16 else 4,
                unit = "连接",
                zeroLabel = "不限",
                onChange = {
                    maxConnectionsPerHost = it
                    repo.maxConnectionsPerHost = it
                }
            )

            // 10. 停滞断开阈值
            ParamStepper(
                title = "停滞断开阈值",
                description = "服务端接受连接却不发数据时，多久断开重试。" +
                    "没有它就要一路等到读超时（默认 60 秒）。",
                value = stallTimeoutSec,
                range = 0..300,
                step = 5,
                unit = "秒",
                zeroLabel = "关闭",
                onChange = {
                    stallTimeoutSec = it
                    repo.stallTimeoutMs = it * 1000L
                }
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel("校验与探测")

            // 11. 弱校验器续传
            ParamSwitch(
                title = "弱校验器续传",
                description = "服务器只回 Content-Length、没有 ETag/Last-Modified 时，" +
                    "续传令牌退化为 len=N，检测不到「同尺寸的新文件」，" +
                    "旧分片会被复用 → **合并出静默损坏的文件**。默认关闭是安全选择。",
                checked = trustWeakValidator,
                onCheckedChange = {
                    trustWeakValidator = it
                    repo.trustWeakValidator = it
                }
            )

            // 12. 已知大小时跳过探测
            ParamSwitch(
                title = "已知大小时跳过探测",
                description = "任务已记录文件总大小时，不再额外发一次探测请求。" +
                    "省一次往返，但会失去「服务端文件已变更」的早期信号。",
                checked = skipProbeWhenSizeKnown,
                onCheckedChange = {
                    skipProbeWhenSizeKnown = it
                    repo.skipProbeWhenSizeKnown = it
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "修改立即生效，无需重启下载引擎。",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // 关掉「吞吐自适应」后的提示：这不是普通开关，用户需要知道后果
    if (showAdaptiveWarning) {
        AlertDialog(
            onDismissRequest = { showAdaptiveWarning = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("已关闭吞吐自适应") },
            text = {
                Text(
                    text = "并发现在由你手动控制的线程数决定，引擎不再自动下调。" +
                        "如果仍遇到「越下越慢」，可以再试试把「动态分片」也关掉，" +
                        "让分片数保持固定。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showAdaptiveWarning = false }) { Text("知道了") }
            }
        )
    }
}

/** 分组标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 开关型参数：标题 + 说明 + 右侧 Switch。
 *
 * 说明文字是这一页的核心价值 —— 每项都写清"什么时候该动它"，
 * 所以用 bodySmall 完整显示，不截断也不折叠。
 */
@Composable
private fun ParamSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ListGroupGap),
        shape = listGroupShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (accentIcon != null) {
                        Icon(
                            imageVector = accentIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * 数值型参数：标题 + 说明 + 一行步进器。
 *
 * [zeroLabel] 不为空时，值为 0 显示它（"关闭"/"不限"），
 * 因为这些参数里 0 是"不启用"而不是"零次"。
 */
@Composable
private fun ParamStepper(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    unit: String,
    onChange: (Int) -> Unit,
    step: Int = 1,
    zeroLabel: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ListGroupGap),
        shape = listGroupShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepButton(
                    label = "−",
                    enabled = value > range.first,
                    onClick = { onChange((value - step).coerceAtLeast(range.first)) }
                )
                Text(
                    text = if (value == 0 && zeroLabel != null) zeroLabel else "$value $unit",
                    modifier = Modifier.width(88.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                StepButton(
                    label = "+",
                    enabled = value < range.last,
                    onClick = { onChange((value + step).coerceAtMost(range.last)) }
                )
            }
        }
    }
}

/** 步进器上的圆形按钮。 */
@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        }
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
    }
}
