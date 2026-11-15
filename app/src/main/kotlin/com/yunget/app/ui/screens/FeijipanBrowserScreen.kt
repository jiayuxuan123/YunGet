package com.yunget.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.account.ExternalAccountStore
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.download.DownloadSubmissionContext
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.ui.SnackbarController
import kotlinx.coroutines.launch
import org.json.JSONObject

private val ddlDownloadHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
    "Origin" to "https://www.feijipan.com",
    "Referer" to "https://www.feijipan.com/",
    "Accept-Language" to "zh-CN",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeijipanBrowserScreen(
    downloadManager: DownloadManager,
    onBack: () -> Unit,
    onDownloaded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { FeijipanApi() }
    val payload = remember {
        runCatching {
            JSONObject(ExternalAccountStore(context).load("feijipan")?.payload.orEmpty())
        }.getOrNull()
    }
    val token = payload?.optString("token").orEmpty()
    val uuid = payload?.optString("uuid").orEmpty()
    val userId = payload?.optString("userId").orEmpty()
    val nickname = payload?.optString("nickname").orEmpty().ifBlank { "小飞机网盘" }
    val folders = remember { mutableListOf<Pair<String, String>>() }
    var currentFolder by remember { mutableStateOf("0") }
    var title by remember { mutableStateOf(nickname) }
    var files by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var downloading by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token, uuid, currentFolder, reload) {
        if (token.isBlank() || uuid.isBlank()) {
            SnackbarController.show("请先登录小飞机网盘")
            onBack()
            return@LaunchedEffect
        }
        loading = true
        runCatching { api.personalFiles(token, uuid, currentFolder) }
            .onSuccess { files = it }
            .onFailure { SnackbarController.show(it.message ?: "读取小飞机文件失败") }
        loading = false
    }

    BackHandler {
        if (folders.isNotEmpty()) {
            val previous = folders.removeLast()
            currentFolder = previous.first
            title = previous.second
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (folders.isNotEmpty()) {
                            val previous = folders.removeLast()
                            currentFolder = previous.first
                            title = previous.second
                        } else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { reload++ }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, "刷新")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (loading) {
                item { Text("正在读取目录…", modifier = Modifier.padding(20.dp)) }
            } else if (files.isEmpty()) {
                item { Text("这个目录是空的", modifier = Modifier.padding(20.dp)) }
            } else {
                items(files, key = { it.fid }) { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = downloading == null) {
                                if (file.isdir) {
                                    folders += currentFolder to title
                                    currentFolder = file.fid
                                    title = file.fname
                                } else {
                                    downloading = file.fid
                                    scope.launch {
                                        runCatching {
                                            val link = api.personalDownload(
                                                token, uuid, userId, file.fid,
                                            )
                                            downloadManager.enqueue(
                                                url = link.downloadUrl,
                                                fileName = link.filename.ifBlank { file.fname },
                                                headers = ddlDownloadHeaders,
                                                size = if (link.size > 0) link.size else file.fsize,
                                                platform = "feijipan",
                                                submission = DownloadSubmissionContext(
                                                    refreshReferenceJson = JSONObject()
                                                        .put("fileId", file.fid)
                                                        .put("folderId", currentFolder)
                                                        .toString(),
                                                ),
                                            )
                                        }.onSuccess {
                                            SnackbarController.show("已加入下载")
                                            onDownloaded()
                                        }.onFailure {
                                            SnackbarController.show(it.message ?: "获取下载地址失败")
                                        }
                                        downloading = null
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            if (file.isdir) Icons.Outlined.Folder else Icons.Outlined.Description,
                            contentDescription = null,
                            tint = if (file.isdir) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                file.fname,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!file.isdir) {
                                Text(
                                    formatBytes(file.fsize) +
                                        if (downloading == file.fid) " · 正在取链接" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "大小未知"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
}
