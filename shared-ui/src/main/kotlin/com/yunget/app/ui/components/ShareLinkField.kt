package com.yunget.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun ShareLinkField(
    link: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = link,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text("分享链接 / 直链") },
        placeholder = { Text("粘贴分享链接、文案或直链") },
        leadingIcon = { Icon(SharedIcons.link, contentDescription = null) },
        trailingIcon = {
            if (link.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                    Icon(SharedIcons.close, contentDescription = "清空链接和提取码")
                }
            }
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        minLines = 2,
        maxLines = 5,
        shape = RoundedCornerShape(12.dp)
    )
}
