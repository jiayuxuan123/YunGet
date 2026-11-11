package com.yunget.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunget.app.data.analytics.AnonymousAnalytics

@Composable
fun AnalyticsDisclosureDialog(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(
                Icons.Outlined.Analytics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("匿名使用统计") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("为了判断哪些功能和网盘需要优先修，云取会发送少量匿名统计。")
                Text("• 只记录功能结果、版本、系统版本、引擎和粗粒度文件大小/耗时。")
                Text("• 不记录分享链接、文件名、账号、Cookie、二维码内容、IP 或精确文件大小。")
                Text("• 匿名安装标识只用于去重，服务端保存的是不可逆摘要。")
                Text("• 可随时在“设置 → 关于与更新 → 匿名使用统计”关闭，关闭后会清空待发送队列。")
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://yunget.yunl.us.ci/legal/privacy/"),
                            ),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("查看完整隐私说明")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    AnonymousAnalytics.completeDisclosure(context, true)
                    com.yunget.app.ui.theme.ThemeController.syncAnonymousAnalyticsState(true)
                    onFinished()
                },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("允许匿名统计")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    AnonymousAnalytics.completeDisclosure(context, false)
                    com.yunget.app.ui.theme.ThemeController.syncAnonymousAnalyticsState(false)
                    onFinished()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("关闭统计并继续")
            }
        },
    )
}
