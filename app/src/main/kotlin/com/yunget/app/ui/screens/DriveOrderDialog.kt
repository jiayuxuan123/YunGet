package com.yunget.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DriveOrderDialog(
    items: List<Pair<String, String>>,
    order: List<String>,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ids = (order + items.map { it.first }).distinct().filter { id -> items.any { it.first == id } }
    fun move(index: Int, destination: Int) {
        if (destination !in ids.indices) return
        val updated = ids.toMutableList()
        updated.add(destination, updated.removeAt(index))
        onChange(updated)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号排序") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                    val title = items.first { it.first == id }.second
                    Row(Modifier.fillMaxWidth().animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f))
                        IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Outlined.ArrowUpward, "上移$title")
                        }
                        IconButton(onClick = { move(index, index + 1) }, enabled = index < ids.lastIndex) {
                            Icon(Icons.Outlined.ArrowDownward, "下移$title")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { onChange(emptyList()) }) { Text("恢复默认") } },
    )
}
