package com.yunget.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * HTTP 版本策略选择器（三态）。
 *
 * 之所以单独做一个组件、而不是复用 [ParamStepper] 或两个开关：
 *
 *  - 数值步进器表达不了"离散三选一"；
 *  - 拆成两个布尔开关（"强制 h1" + "允许 h2"）会重新引入互斥问题 ——
 *    引擎之前的坑正是这样来的：`httpVersionPolicy` 为 AUTO 时还要看 `forceHttp1`，
 *    于是"策略选自动 + 强制关掉"实际变成 FORCE_HTTP2，用户以为改了却改错了方向。
 *
 * 原生核心有 HTTP/3 能力时提供 QUIC 优先策略。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HttpPolicySelector(
    value: Int,
    allowHttp3: Boolean,
    onChange: (Int) -> Unit,
) {
    val options = buildList {
        add(0 to "自动")
        add(1 to "HTTP/1.1")
        add(2 to "HTTP/2")
        if (allowHttp3) add(3 to "HTTP/3")
    }
    val effectiveValue = if (value == 3 && !allowHttp3) 0 else value
    val detail = when (effectiveValue) {
        1 -> "全部链路强制 HTTP/1.1。在会受 h2 多路复用影响的 CDN 上，有利于让分片使用独立连接；实际效果取决于服务器。"
        2 -> "全部链路允许 HTTP/2。仅当确知目标站点只支持 h2、或单连接反而更快时使用；多线程下载不一定更快。"
        3 -> "HTTPS 优先使用 HTTP/3。服务端或当前网络无法连接 QUIC 时，可以协商 HTTP/2 或 HTTP/1.1。"
        else -> "使用当前下载引擎的默认协议策略。"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "HTTP 版本策略",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = if (compact && options.size == 4) 2 else options.size,
                ) {
                    options.forEach { (idx, label) ->
                        val selected = effectiveValue == idx
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onChange(idx) },
                                    )
                                    .heightIn(min = 48.dp)
                                    .fillMaxWidth()
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

