package com.yunget.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.yunget.app.ui.theme.ThemeController

@Composable
internal fun SettingsDescriptionsToggle(horizontalPadding: Dp = 16.dp) {
    val context = LocalContext.current
    val checked = ThemeController.showSettingDescriptions
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Switch) {
                ThemeController.setShowSettingDescriptions(context, it)
            }.padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("显示设置说明", style = MaterialTheme.typography.titleMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
