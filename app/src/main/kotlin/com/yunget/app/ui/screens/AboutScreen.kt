package com.yunget.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.R
import com.yunget.app.data.download.EngineBuildInfo
import com.yunget.app.ui.SnackbarController
import com.yunget.app.util.AppLinks

/** 关于页仅组织现有产品信息与入口，不触发更新检查或其他后台请求。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPreviewOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val pkgInfo = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = pkgInfo?.versionName ?: "1.0"
    val versionCode = pkgInfo?.versionCode ?: 1
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val openLink: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { SnackbarController.show("无法打开链接，请检查是否已安装浏览器") }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("关于云取") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "brand") { AppHeader(versionName, versionCode) }
                item(key = "project_links") {
                    AboutPanel("项目与帮助", Icons.Outlined.Public) {
                        AboutActionRow(
                            Icons.Outlined.Code, "开源仓库", AppLinks.GITHUB_REPO_DISPLAY,
                            onClick = { openLink(AppLinks.GITHUB_REPO) }
                        )
                        AboutDivider()
                        AboutActionRow(
                            Icons.Outlined.Public, "云取官网", AppLinks.WEBSITE_DISPLAY,
                            onClick = { openLink(AppLinks.WEBSITE) }
                        )
                        AboutDivider()
                        AboutActionRow(
                            Icons.Outlined.Memory, "下载引擎官网（TurboDL）", AppLinks.ENGINE_WEBSITE_DISPLAY,
                            onClick = { openLink(AppLinks.ENGINE_WEBSITE) }
                        )
                        AboutDivider()
                        AboutActionRow(
                            Icons.Outlined.Info, "重新预览欢迎界面", "重新展示首次启动引导页",
                            onClick = onPreviewOnboarding, external = false
                        )
                    }
                }
                item(key = "introduction") {
                    AboutPanel("应用简介", Icons.Outlined.Cloud) {
                        Text(
                            "云取（YunGet）是一款网盘分享链接解析与高速下载工具（基于上游 YunX 的二次开发，重写升级了多线程下载引擎）。" +
                                "粘贴分享链接，登录网盘账号后即可浏览分享内容并直接高速下载文件。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item(key = "platforms") { PlatformCard() }
                item(key = "features") { FeatureCard() }
                item(key = "technology") { TechCard() }
                item(key = "legal") { DisclaimerCard() }
                item(key = "credits") {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "本项目基于 GNU AGPL-3.0 协议开源",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "云取 v$versionName · TurboDL ${EngineBuildInfo.TURBO_DL_VERSION}\nMade with ❤ and deepseek",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(versionName: String, versionCode: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(
                    painter = painterResource(R.drawable.yunget_mark_approved),
                    contentDescription = "云取图标",
                    modifier = Modifier.size(72.dp),
                    contentScale = ContentScale.Fit
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("云取", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("YunGet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("v$versionName ($versionCode)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("网盘链接解析与高速下载", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun AboutPanel(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AboutActionRow(
    icon: ImageVector, title: String, description: String, onClick: () -> Unit, external: Boolean = true
) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.heightIn(min = 64.dp).padding(vertical = 12.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (external) Icon(Icons.Outlined.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformCard() {
    val platforms = listOf(
        "quark" to "夸克网盘", "uc" to "UC 网盘", "xunlei" to "迅雷网盘",
        "baidu" to "百度网盘", "c139" to "139 网盘", "pan123" to "123 云盘",
        "pan115" to "115 网盘", "guangya" to "光鸭云盘",
        "ilanzou" to "蓝奏云优享版", "lanzou" to "蓝奏云"
    )
    AboutPanel("支持平台", Icons.Outlined.Storage) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEach { (id, name) ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.yunget.app.ui.items.platformLogoRes(id)?.let { logo ->
                            Image(painterResource(logo), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
                        }
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard() {
    val features = listOf(
        "一键解析分享链接" to "识别支持平台的分享链接，浏览目录并选择文件",
        "自适应高速下载" to "工作窃取调度 + 动态并发 + 自适应分块，HTTP/2 连接复用，断点续传",
        "取链即删" to "转存后立即清理，不留残留",
        "凭证本地化" to "Cookie 加密落库，仅存本机"
    )
    AboutPanel("功能特性", Icons.Outlined.CheckCircle) {
        features.forEachIndexed { index, (title, description) ->
            if (index > 0) AboutDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechCard() {
    AboutPanel("技术栈", Icons.Outlined.Code) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Kotlin", "Jetpack Compose", "Material 3", "Room", "OkHttp", "KSP").forEach { name ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    AboutPanel("免责声明", Icons.Outlined.Shield) {
        Text(
            "本应用仅供个人学习与技术交流使用，请勿用于任何商业用途。" +
                "下载内容版权归原作者所有，请于下载后 24 小时内删除。" +
                "本项目为基于上游 CYQawa/YunX 的二次开发（AGPL-3.0），与上游作者无隐性关联；" +
                "使用本应用产生的任何后果由使用者自行承担。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}