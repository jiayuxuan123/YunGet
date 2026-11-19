package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class DesktopCommandAction(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val shortcut: String = "",
    val keywords: String = "",
    val onExecute: () -> Unit,
)

@Composable
internal fun DesktopCommandPaletteDialog(
    actions: List<DesktopCommandAction>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(actions, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) actions else actions.filter { act ->
            act.title.lowercase().contains(q) ||
                act.subtitle.lowercase().contains(q) ||
                act.keywords.lowercase().contains(q)
        }
    }

    LaunchedEffect(filtered.size) {
        if (selectedIndex >= filtered.size) {
            selectedIndex = (filtered.size - 1).coerceAtLeast(0)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("命令面板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                DesktopPill("Ctrl+K")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (filtered.isNotEmpty()) {
                                    selectedIndex = (selectedIndex + 1) % filtered.size
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (filtered.isNotEmpty()) {
                                    selectedIndex = if (selectedIndex <= 0) filtered.lastIndex else selectedIndex - 1
                                }
                                true
                            }
                            Key.Enter -> {
                                filtered.getOrNull(selectedIndex)?.let { act ->
                                    onDismiss()
                                    act.onExecute()
                                }
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selectedIndex = 0
                    },
                    placeholder = { Text("输入命令、页面或网盘名称（↑/↓ 选择，Enter 执行）…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp),
                ) {
                    if (filtered.isEmpty()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("没有匹配的命令", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn {
                            itemsIndexed(filtered, key = { _, it -> it.id }) { idx, act ->
                                val isSelected = idx == selectedIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.surfaceContainerLow
                                        )
                                        .clickable {
                                            onDismiss()
                                            act.onExecute()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = act.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (act.subtitle.isNotBlank()) {
                                            Text(
                                                text = act.subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (act.shortcut.isNotBlank()) {
                                        DesktopPill(act.shortcut)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭 (Esc)") }
        },
    )
}
