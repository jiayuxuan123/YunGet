package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.GitHubLinkType
import com.yunget.app.data.network.GitHubRepo
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.network.video.VideoMediaKind
import com.yunget.app.data.network.video.VideoParseResult
import com.yunget.app.ui.components.ShareLinkField
import kotlinx.coroutines.launch
import java.io.File

private data class ShareCrumb(val dirId: String, val label: String)

private sealed class ResolveViewState {
    object Idle : ResolveViewState()
    data class ShareLoaded(
        val platform: SharePlatform,
        val session: ShareSession,
        val files: List<ShareFile>,
    ) : ResolveViewState()
    data class VideoLoaded(val result: VideoParseResult) : ResolveViewState()
    data class GitHubRepoLoaded(val bundle: GitHubRepoBundle) : ResolveViewState()
    data class GitHubAccountLoaded(val owner: String, val repos: List<GitHubRepo>) : ResolveViewState()
    data class DirectReady(val direct: DesktopLinkTarget.Direct) : ResolveViewState()
    data class WebDriveReady(val target: DesktopLinkTarget.WebDrive) : ResolveViewState()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopResolveTab(
    config: DesktopConfig,
    accountStore: DesktopAccountStore,
    resolveService: DesktopResolveService,
    driveService: DesktopDriveService,
    webDriveSessions: DesktopWebDriveSessions,
    downloads: DesktopDownloads,
    historyStore: DesktopResolveHistoryStore,
    onNotify: (String) -> Unit,
    onOpenAccountDialog: (DriveAccountKind) -> Unit,
    onJumpToDownloads: () -> Unit,
    externalInput: String? = null,
    onConsumeExternalInput: () -> Unit = {},
    clipboardMonitorEnabled: Boolean = false,
    onToggleClipboardMonitor: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val accounts by accountStore.accounts.collectAsState()
    val historyRecords by historyStore.history.collectAsState()
    val favoriteRecords by historyStore.favorites.collectAsState()

    var rawInput by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var batchStatus by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var viewState by remember { mutableStateOf<ResolveViewState>(ResolveViewState.Idle) }
    var historyPanelTab by remember { mutableIntStateOf(0) }
    var lastResolvedTitle by remember { mutableStateOf("") }
    var lastResolvedPlatform by remember { mutableStateOf("") }

    val crumbs = remember { mutableStateListOf<ShareCrumb>() }
    var filterQuery by remember { mutableStateOf("") }
    var selectedFids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveToDriveRequest by remember { mutableStateOf<Triple<SharePlatform, ShareSession, List<ShareFile>>?>(null) }
    var selectedVideoIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var ghSubTab by remember { mutableIntStateOf(0) }
    var ghTreePrefix by remember { mutableStateOf("") }

    // Direct target editable fields
    var directFileName by remember { mutableStateOf("") }
    var directSha256 by remember { mutableStateOf("") }
    var directHeaders by remember { mutableStateOf("") }
    var directShowAdvanced by remember { mutableStateOf(false) }

    fun enqueueResolved(resolved: ResolvedDownload, subDir: String = "") {
        scope.launch {
            val baseDir = File(config.downloadDirectory)
            val targetDir = if (subDir.isBlank()) baseDir else File(baseDir, subDir)
            runCatching {
                downloads.submit(
                    source = resolved.url,
                    directory = targetDir,
                    fileName = resolved.fileName,
                    kind = resolved.kind,
                    httpVersion = config.httpPolicy,
                    headers = resolved.headers,
                    autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                    sourceLabel = resolved.sourceLabel,
                    knownSize = resolved.size,
                    cleanupHook = resolved.cleanupHook,
                )
            }.onSuccess {
                onNotify("已加入下载队列：${resolved.fileName}")
            }.onFailure { err ->
                onNotify("加入下载失败：${err.message ?: "未知错误"}")
            }
        }
    }

    fun startResolve(inputOverride: String? = null) {
        val text = (inputOverride ?: rawInput).trim()
        if (text.isEmpty()) {
            errorMessage = "请先粘贴网盘链接/口令、视频链接、GitHub 链接、直链或磁力链接"
            return
        }
        errorMessage = ""
        selectedFids = emptySet()
        selectedVideoIndices = emptySet()
        filterQuery = ""
        val target = detectDesktopLinkTarget(text)
        when (target) {
            is DesktopLinkTarget.Unsupported -> {
                errorMessage = target.reason
            }
            is DesktopLinkTarget.Direct -> {
                directFileName = target.suggestedName
                directSha256 = ""
                directHeaders = ""
                directShowAdvanced = false
                viewState = ResolveViewState.DirectReady(target)
            }
            is DesktopLinkTarget.WebDrive -> {
                viewState = ResolveViewState.WebDriveReady(target)
            }
            is DesktopLinkTarget.CloudShare -> {
                busy = true
                scope.launch {
                    runCatching { resolveService.openShare(target.rawInput, passcode.ifBlank { null }) }
                        .onSuccess { opened ->
                            crumbs.clear()
                            crumbs.add(ShareCrumb(opened.rootDirId, "根目录"))
                            val title = opened.session.title.ifBlank { opened.files.firstOrNull()?.fname.orEmpty() }
                            val platformLabel = platformDisplayName(opened.platform)
                            lastResolvedTitle = title
                            lastResolvedPlatform = platformLabel
                            historyStore.recordHistory(target.rawInput, passcode, title, platformLabel)
                            viewState = ResolveViewState.ShareLoaded(opened.platform, opened.session, opened.files)
                        }
                        .onFailure { err ->
                            errorMessage = err.message ?: "解析分享链接失败"
                        }
                    busy = false
                }
            }
            is DesktopLinkTarget.Kouling -> {
                busy = true
                scope.launch {
                    runCatching { resolveService.openKouling(target, passcode.ifBlank { null }) }
                        .onSuccess { opened ->
                            crumbs.clear()
                            crumbs.add(ShareCrumb(opened.rootDirId, "根目录"))
                            val title = opened.session.title.ifBlank { opened.files.firstOrNull()?.fname.orEmpty() }
                            val platformLabel = platformDisplayName(opened.platform)
                            lastResolvedTitle = title
                            lastResolvedPlatform = platformLabel
                            historyStore.recordHistory(text, passcode, title, platformLabel)
                            viewState = ResolveViewState.ShareLoaded(opened.platform, opened.session, opened.files)
                        }
                        .onFailure { err ->
                            errorMessage = err.message ?: "解析口令失败"
                        }
                    busy = false
                }
            }
            is DesktopLinkTarget.Video -> {
                busy = true
                scope.launch {
                    runCatching { resolveService.parseVideo(target.detected) }
                        .onSuccess { parsed ->
                            selectedVideoIndices = emptySet()
                            lastResolvedTitle = parsed.title
                            lastResolvedPlatform = parsed.platform.displayName
                            historyStore.recordHistory(text, "", parsed.title, parsed.platform.displayName)
                            viewState = ResolveViewState.VideoLoaded(parsed)
                        }
                        .onFailure { err ->
                            errorMessage = err.message ?: "解析视频/图集失败"
                        }
                    busy = false
                }
            }
            is DesktopLinkTarget.GitHub -> {
                when (val gh = target.link) {
                    is GitHubLinkType.DirectFile -> {
                        val mirrored = resolveService.applyGitHubMirror(gh.url, config.githubMirrorPrefix)
                        val direct = DesktopLinkTarget.Direct(
                            source = mirrored,
                            kind = TransferKind.HTTP,
                            suggestedName = sanitizeFileName(gh.fileName, "github-file"),
                        )
                        directFileName = direct.suggestedName
                        directSha256 = ""
                        directHeaders = ""
                        viewState = ResolveViewState.DirectReady(direct)
                    }
                    is GitHubLinkType.Repository -> {
                        busy = true
                        scope.launch {
                            runCatching { resolveService.openGitHubRepo(gh.owner, gh.repo) }
                                .onSuccess { bundle ->
                                    ghTreePrefix = ""
                                    ghSubTab = if (bundle.releases.isNotEmpty()) 0 else 1
                                    lastResolvedTitle = bundle.repo.fullName
                                    lastResolvedPlatform = "GitHub"
                                    historyStore.recordHistory(text, "", bundle.repo.fullName, "GitHub")
                                    viewState = ResolveViewState.GitHubRepoLoaded(bundle)
                                }
                                .onFailure { err -> errorMessage = err.message ?: "加载 GitHub 仓库失败" }
                            busy = false
                        }
                    }
                    is GitHubLinkType.Account -> {
                        busy = true
                        scope.launch {
                            runCatching { resolveService.listGitHubAccountRepos(gh.owner) }
                                .onSuccess { repos ->
                                    lastResolvedTitle = "@${gh.owner}"
                                    lastResolvedPlatform = "GitHub"
                                    historyStore.recordHistory(text, "", "@${gh.owner}", "GitHub")
                                    viewState = ResolveViewState.GitHubAccountLoaded(gh.owner, repos)
                                }
                                .onFailure { err -> errorMessage = err.message ?: "加载 GitHub 仓库列表失败" }
                            busy = false
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(externalInput) {
        val incoming = externalInput?.trim().orEmpty()
        if (incoming.isNotEmpty()) {
            onConsumeExternalInput()
            rawInput = incoming
            startResolve(incoming)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("智能链接解析", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "支持 12 大网盘及口令、主流视频/图集（抖音/快手/B站/小红书/微博/Pixiv/AcFun）、GitHub、HTTP/3、HLS/DASH 与 BT/磁力",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = clipboardMonitorEnabled,
                    onClick = { onToggleClipboardMonitor(!clipboardMonitorEnabled) },
                    label = { Text(if (clipboardMonitorEnabled) "剪贴板监听：开" else "剪贴板监听：关") },
                )
                if (rawInput.isNotBlank()) {
                    val fav = favoriteRecords.any { it.input.equals(rawInput.trim(), ignoreCase = true) }
                    OutlinedButton(
                        onClick = {
                            val added = historyStore.toggleFavorite(
                                input = rawInput,
                                passcode = passcode,
                                title = lastResolvedTitle.ifBlank { rawInput.trim().take(48) },
                                platformLabel = lastResolvedPlatform.ifBlank { "链接" },
                            )
                            onNotify(if (added) "已加入收藏夹" else "已取消收藏")
                        },
                    ) { Text(if (fav) "已收藏 ★" else "收藏") }
                }
                if (viewState != ResolveViewState.Idle) {
                    OutlinedButton(onClick = { viewState = ResolveViewState.Idle }) { Text("历史 / 收藏") }
                }
                OutlinedButton(onClick = {
                    val clip = readClipboardText().trim()
                    if (clip.isEmpty()) {
                        onNotify("剪贴板为空")
                    } else {
                        rawInput = clip
                        startResolve(clip)
                    }
                }) { Text("粘贴并解析") }
                OutlinedButton(onClick = {
                    pickTaskFile()?.let { file ->
                        rawInput = file.absolutePath
                        startResolve(file.absolutePath)
                    }
                }) { Text("打开种子 / Metalink") }
            }
        }

        // Input card
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShareLinkField(
                        link = rawInput,
                        onChange = { rawInput = it; errorMessage = "" },
                        onClear = {
                            rawInput = ""
                            passcode = ""
                            errorMessage = ""
                            viewState = ResolveViewState.Idle
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = passcode,
                        onValueChange = { passcode = it },
                        label = { Text("提取码") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(120.dp),
                    )
                    Button(
                        enabled = !busy && rawInput.isNotBlank(),
                        onClick = { startResolve() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(54.dp).widthIn(min = 84.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("解析")
                    }
                }

                // Supported platforms status bar (click to manage account)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DriveAccountKind.entries.forEach { kind ->
                        val loggedIn = accounts.containsKey(kind)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (loggedIn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                            modifier = Modifier.clickable { onOpenAccountDialog(kind) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                StatusDot(active = loggedIn)
                                Text(
                                    text = kind.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (loggedIn) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (busy || batchStatus.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (batchStatus.isNotBlank()) {
                    Text(batchStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (errorMessage.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // Result body
        when (val state = viewState) {
            ResolveViewState.Idle -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (historyRecords.isEmpty() && favoriteRecords.isEmpty()) {
                        DesktopEmptyCard(
                            title = "粘贴链接即可开始解析",
                            subtitle = "点击上方任意平台标签可快速登录（支持弹窗扫码自动捕获）；支持直接拖入链接/种子文件，或开启右上角「剪贴板监听」。",
                        )
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = historyPanelTab == 0,
                                            onClick = { historyPanelTab = 0 },
                                            label = { Text("最近解析 (${historyRecords.size})") },
                                        )
                                        FilterChip(
                                            selected = historyPanelTab == 1,
                                            onClick = { historyPanelTab = 1 },
                                            label = { Text("收藏夹 (${favoriteRecords.size})") },
                                        )
                                    }
                                    if (historyPanelTab == 0 && historyRecords.isNotEmpty()) {
                                        TextButton(
                                            onClick = {
                                                historyStore.clearHistory()
                                                onNotify("已清空解析历史")
                                            },
                                        ) { Text("清空历史", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                val list = if (historyPanelTab == 0) historyRecords else favoriteRecords
                                if (list.isEmpty()) {
                                    Column(modifier = Modifier.padding(24.dp)) {
                                        Text(
                                            text = if (historyPanelTab == 0) "暂无解析历史，粘贴链接解析后会自动记录。" else "暂无收藏记录，解析链接后点击右上角「收藏」即可保存。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(list, key = { it.id }) { rec ->
                                            val isFav = favoriteRecords.any { it.input.equals(rec.input, ignoreCase = true) }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        rawInput = rec.input
                                                        passcode = rec.passcode
                                                        startResolve(rec.input)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                if (rec.platformLabel.isNotBlank()) {
                                                    DesktopPill(rec.platformLabel)
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = rec.title.ifBlank { rec.input },
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = rec.input + if (rec.passcode.isNotBlank()) "  [提取码: ${rec.passcode}]" else "",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                TextButton(
                                                    onClick = {
                                                        val nowFav = historyStore.toggleFavorite(rec.input, rec.passcode, rec.title, rec.platformLabel)
                                                        onNotify(if (nowFav) "已加入收藏夹" else "已取消收藏")
                                                    },
                                                ) { Text(if (isFav) "★" else "☆") }
                                                FilledTonalButton(
                                                    onClick = {
                                                        rawInput = rec.input
                                                        passcode = rec.passcode
                                                        startResolve(rec.input)
                                                    },
                                                ) { Text("解析") }
                                                TextButton(
                                                    onClick = {
                                                        if (historyPanelTab == 0) historyStore.removeHistory(rec.id)
                                                        else historyStore.removeFavorite(rec.id)
                                                    },
                                                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is ResolveViewState.VideoLoaded -> {
                val result = state.result
                val videoAccountKind = DriveAccountKind.fromVideoPlatform(result.platform)
                val isLoggedIn = videoAccountKind != null && accounts.containsKey(videoAccountKind)
                val subFolder = if (result.medias.size > 1) {
                    sanitizeFileName(result.title.ifBlank { result.platform.displayName }, "media-batch").take(60)
                } else ""

                fun enqueueVideoBatch(indices: List<Int>) {
                    if (indices.isEmpty()) return
                    scope.launch {
                        var added = 0
                        var failed = 0
                        var lastErr = ""
                        try {
                            for ((pos, mediaIndex) in indices.withIndex()) {
                                val media = result.medias.getOrNull(mediaIndex) ?: continue
                                val resolved = resolveService.buildVideoMediaDownload(result, media, mediaIndex)
                                batchStatus = "正在入队 (${pos + 1}/${indices.size})：${resolved.fileName}"
                                val baseDir = File(config.downloadDirectory)
                                val targetDir = if (subFolder.isBlank()) baseDir else File(baseDir, subFolder)
                                runCatching {
                                    downloads.submit(
                                        source = resolved.url,
                                        directory = targetDir,
                                        fileName = resolved.fileName,
                                        kind = resolved.kind,
                                        httpVersion = config.httpPolicy,
                                        headers = resolved.headers,
                                        autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                                        sourceLabel = resolved.sourceLabel,
                                        knownSize = resolved.size,
                                    )
                                    val audio = resolveService.buildVideoAudioDownload(result, media, mediaIndex)
                                    if (audio != null) {
                                        downloads.submit(
                                            source = audio.url,
                                            directory = targetDir,
                                            fileName = audio.fileName,
                                            kind = audio.kind,
                                            httpVersion = config.httpPolicy,
                                            headers = audio.headers,
                                            autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                                            sourceLabel = audio.sourceLabel,
                                            knownSize = audio.size,
                                        )
                                    }
                                }.onSuccess { added++ }
                                    .onFailure { err ->
                                        failed++
                                        lastErr = err.message.orEmpty()
                                    }
                            }
                        } finally {
                            batchStatus = ""
                        }
                        if (failed == 0) {
                            onNotify("已将 $added 项媒体加入下载队列")
                        } else {
                            errorMessage = "成功入队 $added 项，失败 $failed 项：$lastErr"
                        }
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                DesktopPill(
                                    text = result.platform.displayName,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                DesktopPill("${result.medias.size} 个媒体")
                                if (result.author.isNotBlank()) {
                                    Text(
                                        text = "@${result.author}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (videoAccountKind != null && !isLoggedIn) {
                                    OutlinedButton(onClick = { onOpenAccountDialog(videoAccountKind) }) {
                                        Text("配置${videoAccountKind.displayName} Cookie")
                                    }
                                }
                                if (result.medias.size > 1) {
                                    OutlinedButton(
                                        enabled = selectedVideoIndices.isNotEmpty() && batchStatus.isEmpty(),
                                        onClick = { enqueueVideoBatch(selectedVideoIndices.sorted()) },
                                    ) { Text("下载选中 (${selectedVideoIndices.size})") }
                                    FilledTonalButton(
                                        enabled = result.medias.isNotEmpty() && batchStatus.isEmpty(),
                                        onClick = { enqueueVideoBatch(result.medias.indices.toList()) },
                                    ) { Text("全部下载") }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (result.title.isNotBlank()) {
                                Text(
                                    text = result.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (result.text.isNotBlank() && result.text.trim() != result.title.trim()) {
                                Text(
                                    text = result.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (result.notice.isNotBlank()) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = result.notice,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            if (result.medias.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "多文件批量下载将自动归档到子文件夹：$subFolder",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = {
                                        selectedVideoIndices = if (selectedVideoIndices.size == result.medias.size) {
                                            emptySet()
                                        } else {
                                            result.medias.indices.toSet()
                                        }
                                    }) {
                                        Text(if (selectedVideoIndices.size == result.medias.size) "取消全选" else "全选")
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(
                                items = result.medias,
                                key = { idx, media -> "$idx:${media.url}" },
                            ) { idx, media ->
                                val resolved = resolveService.buildVideoMediaDownload(result, media, idx)
                                val audioResolved = resolveService.buildVideoAudioDownload(result, media, idx)
                                val motionResolved = resolveService.buildVideoMotionDownload(result, media, idx)
                                val checked = idx in selectedVideoIndices
                                val kindLabel = when (media.kind) {
                                    VideoMediaKind.VIDEO -> if (media.isHls) "HLS 视频" else "视频"
                                    VideoMediaKind.IMAGE -> if (motionResolved != null) "实况图片" else "图片"
                                    VideoMediaKind.AUDIO -> "音频"
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (result.medias.size > 1) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = {
                                                selectedVideoIndices = if (it) selectedVideoIndices + idx else selectedVideoIndices - idx
                                            },
                                        )
                                    }
                                    DesktopPill(kindLabel)
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = resolved.fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val meta = buildList {
                                            if (media.quality.isNotBlank()) add(media.quality)
                                            if (media.size > 0L) add(formatBytes(media.size))
                                            if (audioResolved != null) add("含独立音轨（桌面端分别下载视频与音轨）")
                                        }.joinToString(" · ")
                                        if (meta.isNotBlank()) {
                                            Text(
                                                text = meta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (audioResolved != null) {
                                            OutlinedButton(onClick = { enqueueResolved(audioResolved, subFolder) }) {
                                                Text("仅下音轨")
                                            }
                                            FilledTonalButton(onClick = {
                                                enqueueResolved(resolved, subFolder)
                                                enqueueResolved(audioResolved, subFolder)
                                            }) {
                                                Text("下音视频")
                                            }
                                        } else if (motionResolved != null) {
                                            OutlinedButton(onClick = { enqueueResolved(motionResolved, subFolder) }) {
                                                Text("下实况视频")
                                            }
                                            FilledTonalButton(onClick = { enqueueResolved(resolved, subFolder) }) {
                                                Text("下原图")
                                            }
                                        } else {
                                            FilledTonalButton(onClick = { enqueueResolved(resolved, subFolder) }) {
                                                Text("下载")
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }

            is ResolveViewState.WebDriveReady -> {
                val target = state.target
                DesktopEmptyCard(
                    title = target.drive.displayName,
                    subtitle = "在官网查看分享文件，使用本机保存的网页会话。",
                    actionLabel = if (busy) "正在打开…" else "打开官网分享",
                    onAction = if (busy) null else {
                        {
                            busy = true
                            errorMessage = ""
                            scope.launch {
                                try {
                                    webDriveSessions.open(target.drive, target.url)
                                } catch (err: kotlinx.coroutines.CancellationException) {
                                    throw err
                                } catch (err: Exception) {
                                    errorMessage = err.message ?: "无法打开官网"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                )
            }
            is ResolveViewState.DirectReady -> {
                val direct = state.direct
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            DesktopPill(
                                text = defaultSourceLabel(direct.kind),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = direct.source,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = directFileName,
                            onValueChange = { directFileName = it },
                            label = { Text(if (direct.kind == TransferKind.TORRENT) "保存目录名" else "保存文件名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "保存到：${config.downloadDirectory}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { directShowAdvanced = !directShowAdvanced }) {
                                Text(if (directShowAdvanced) "收起高级选项" else "SHA-256 / 请求头")
                            }
                        }
                        if (directShowAdvanced) {
                            OutlinedTextField(
                                value = directSha256,
                                onValueChange = { directSha256 = it.trim() },
                                label = { Text("预期 SHA-256（可选，64 位十六进制）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = directHeaders,
                                onValueChange = { directHeaders = it },
                                label = { Text("自定义请求头（每行 Key: Value，可选）") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching {
                                        downloads.submit(
                                            source = direct.source,
                                            directory = File(config.downloadDirectory),
                                            fileName = sanitizeFileName(directFileName, direct.suggestedName),
                                            kind = direct.kind,
                                            httpVersion = config.httpPolicy,
                                            sha256 = directSha256.takeIf { it.isNotBlank() },
                                            headers = parseHeaderLines(directHeaders),
                                            autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                                            sourceLabel = defaultSourceLabel(direct.kind),
                                        )
                                    }.onSuccess {
                                        onNotify("已开始下载：${sanitizeFileName(directFileName, direct.suggestedName)}")
                                        onJumpToDownloads()
                                    }.onFailure { err ->
                                        errorMessage = err.message ?: "提交下载失败"
                                    }
                                }
                            }) { Text("立即开始下载") }
                            OutlinedButton(onClick = {
                                copyToClipboard(direct.source)
                                onNotify("已复制源地址")
                            }) { Text("复制地址") }
                        }
                    }
                }
            }

            is ResolveViewState.ShareLoaded -> {
                val accountKind = DriveAccountKind.fromPlatform(state.platform)
                val isLoggedIn = accountKind != null && accounts.containsKey(accountKind)
                val visibleFiles = remember(state.files, filterQuery) {
                    val q = filterQuery.trim()
                    if (q.isEmpty()) state.files
                    else state.files.filter { it.fname.contains(q, ignoreCase = true) }
                }

                fun navigateTo(dirId: String, label: String, trimToIndex: Int? = null) {
                    busy = true
                    errorMessage = ""
                    scope.launch {
                        runCatching { resolveService.listShareDir(state.platform, state.session, dirId) }
                            .onSuccess { list ->
                                if (trimToIndex != null) {
                                    while (crumbs.size > trimToIndex + 1) crumbs.removeAt(crumbs.lastIndex)
                                } else {
                                    crumbs.add(ShareCrumb(dirId, label))
                                }
                                selectedFids = emptySet()
                                viewState = state.copy(files = list)
                            }
                            .onFailure { err -> errorMessage = err.message ?: "打开目录失败" }
                        busy = false
                    }
                }

                fun downloadShareItems(items: List<ShareFile>) {
                    if (items.isEmpty()) return
                    scope.launch {
                        var added = 0
                        var failed = 0
                        var lastErr = ""
                        batchStatus = "正在准备下载 ${items.size} 项…"
                        try {
                            val flatQueue = mutableListOf<Pair<ShareFile, String>>()
                            for (item in items) {
                                if (item.isdir) {
                                    batchStatus = "正在扫描文件夹：${item.fname}…"
                                    val nested = resolveService.collectShareFolderFiles(state.platform, state.session, item)
                                    flatQueue.addAll(nested)
                                } else {
                                    flatQueue.add(item to "")
                                }
                            }
                            for ((idx, pair) in flatQueue.withIndex()) {
                                val (file, subDir) = pair
                                batchStatus = "正在取链并入队 (${idx + 1}/${flatQueue.size})：${file.fname}"
                                runCatching {
                                    val resolved = resolveService.resolveShareDownload(state.platform, state.session, file)
                                    val baseDir = File(config.downloadDirectory)
                                    val targetDir = if (subDir.isBlank()) baseDir else File(baseDir, subDir)
                                    downloads.submit(
                                        source = resolved.url,
                                        directory = targetDir,
                                        fileName = resolved.fileName,
                                        kind = resolved.kind,
                                        httpVersion = config.httpPolicy,
                                        headers = resolved.headers,
                                        autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                                        sourceLabel = resolved.sourceLabel,
                                        knownSize = resolved.size,
                                        cleanupHook = resolved.cleanupHook,
                                    )
                                }.onSuccess { added++ }
                                    .onFailure { err ->
                                        failed++
                                        lastErr = err.message.orEmpty()
                                    }
                            }
                        } catch (err: Exception) {
                            lastErr = err.message ?: "扫描失败"
                            failed++
                        } finally {
                            batchStatus = ""
                        }
                        if (failed == 0) {
                            onNotify("已将 $added 个文件加入下载队列")
                        } else {
                            errorMessage = "成功入队 $added 个，失败 $failed 个：$lastErr"
                        }
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Share toolbar
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                DesktopPill(
                                    text = platformDisplayName(state.platform),
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text = state.session.title.ifBlank { "分享文件" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                DesktopPill(
                                    text = if (isLoggedIn) "已登录" else "游客态",
                                    containerColor = if (isLoggedIn) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = if (isLoggedIn) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!isLoggedIn && accountKind != null) {
                                    OutlinedButton(onClick = { onOpenAccountDialog(accountKind) }) {
                                        Text("登录${accountKind.displayName}")
                                    }
                                }
                                if (resolveService.supportsSaveToDrive(state.platform)) {
                                    OutlinedButton(
                                        enabled = selectedFids.isNotEmpty() && batchStatus.isEmpty(),
                                        onClick = {
                                            if (!isLoggedIn && accountKind != null) {
                                                onNotify("请先登录${accountKind.displayName}后再转存")
                                                onOpenAccountDialog(accountKind)
                                            } else {
                                                val picked = state.files.filter { it.fid in selectedFids }
                                                if (picked.isNotEmpty()) {
                                                    saveToDriveRequest = Triple(state.platform, state.session, picked)
                                                }
                                            }
                                        },
                                    ) { Text("转存选中 (${selectedFids.size})") }
                                }
                                FilledTonalButton(
                                    enabled = selectedFids.isNotEmpty() && batchStatus.isEmpty(),
                                    onClick = {
                                        val picked = state.files.filter { it.fid in selectedFids }
                                        downloadShareItems(picked)
                                    },
                                ) { Text("下载选中 (${selectedFids.size})") }
                            }
                        }

                        // Breadcrumbs & Search
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                crumbs.forEachIndexed { idx, crumb ->
                                    if (idx > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = crumb.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (idx == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable(enabled = idx < crumbs.lastIndex) {
                                            navigateTo(crumb.dirId, crumb.label, trimToIndex = idx)
                                        }.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = {
                                    selectedFids = if (selectedFids.size == visibleFiles.size && visibleFiles.isNotEmpty()) {
                                        emptySet()
                                    } else {
                                        visibleFiles.mapTo(linkedSetOf()) { it.fid }
                                    }
                                }) {
                                    Text(if (selectedFids.size == visibleFiles.size && visibleFiles.isNotEmpty()) "取消全选" else "全选")
                                }
                                OutlinedTextField(
                                    value = filterQuery,
                                    onValueChange = { filterQuery = it },
                                    placeholder = { Text("筛选当前目录…") },
                                    singleLine = true,
                                    modifier = Modifier.width(210.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // File list
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visibleFiles, key = { it.fid }) { file ->
                                val checked = file.fid in selectedFids
                                ContextMenuArea(
                                    items = {
                                        buildList {
                                            if (file.isdir) {
                                                add(ContextMenuItem("打开文件夹") {
                                                    navigateTo(resolveService.shareChildDirId(state.platform, file), file.fname)
                                                })
                                                add(ContextMenuItem("下载整个目录") { downloadShareItems(listOf(file)) })
                                            } else {
                                                add(ContextMenuItem("下载文件") {
                                                    scope.launch {
                                                        runCatching { resolveService.resolveShareDownload(state.platform, state.session, file) }
                                                            .onSuccess { res -> enqueueResolved(res) }
                                                            .onFailure { err -> errorMessage = err.message ?: "获取直链失败" }
                                                    }
                                                })
                                                add(ContextMenuItem("复制下载直链") {
                                                    scope.launch {
                                                        runCatching { resolveService.resolveShareDownload(state.platform, state.session, file) }
                                                            .onSuccess { res ->
                                                                copyToClipboard(res.url)
                                                                onNotify("已复制直链：${file.fname}")
                                                            }
                                                            .onFailure { err -> errorMessage = err.message ?: "获取直链失败" }
                                                    }
                                                })
                                            }
                                            if (resolveService.supportsSaveToDrive(state.platform)) {
                                                add(ContextMenuItem("转存到我的网盘") {
                                                    if (!isLoggedIn && accountKind != null) {
                                                        onNotify("请先登录${accountKind.displayName}后再转存")
                                                        onOpenAccountDialog(accountKind)
                                                    } else {
                                                        saveToDriveRequest = Triple(state.platform, state.session, listOf(file))
                                                    }
                                                })
                                            }
                                        }
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (file.isdir) {
                                                    navigateTo(resolveService.shareChildDirId(state.platform, file), file.fname)
                                                } else {
                                                    selectedFids = if (checked) selectedFids - file.fid else selectedFids + file.fid
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            selectedFids = if (isChecked) selectedFids + file.fid else selectedFids - file.fid
                                        },
                                    )
                                    DesktopPill(
                                        text = if (file.isdir) "目录" else file.fname.substringAfterLast('.', "文件").uppercase().take(6),
                                        containerColor = if (file.isdir) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = if (file.isdir) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.fname,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val meta = buildList {
                                            if (!file.isdir) add(formatBytes(file.fsize))
                                            if (file.modifyTime.isNotBlank()) add(file.modifyTime)
                                        }.joinToString(" · ")
                                        if (meta.isNotBlank()) {
                                            Text(
                                                text = meta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (file.isdir) {
                                        TextButton(onClick = {
                                            navigateTo(resolveService.shareChildDirId(state.platform, file), file.fname)
                                        }) { Text("打开") }
                                        if (resolveService.supportsSaveToDrive(state.platform)) {
                                            TextButton(
                                                enabled = batchStatus.isEmpty(),
                                                onClick = {
                                                    if (!isLoggedIn && accountKind != null) {
                                                        onNotify("请先登录${accountKind.displayName}后再转存")
                                                        onOpenAccountDialog(accountKind)
                                                    } else {
                                                        saveToDriveRequest = Triple(state.platform, state.session, listOf(file))
                                                    }
                                                },
                                            ) { Text("转存") }
                                        }
                                        FilledTonalButton(
                                            enabled = batchStatus.isEmpty(),
                                            onClick = { downloadShareItems(listOf(file)) },
                                        ) { Text("下载整个目录") }
                                    } else {
                                        if (resolveService.supportsSaveToDrive(state.platform)) {
                                            TextButton(
                                                enabled = batchStatus.isEmpty(),
                                                onClick = {
                                                    if (!isLoggedIn && accountKind != null) {
                                                        onNotify("请先登录${accountKind.displayName}后再转存")
                                                        onOpenAccountDialog(accountKind)
                                                    } else {
                                                        saveToDriveRequest = Triple(state.platform, state.session, listOf(file))
                                                    }
                                                },
                                            ) { Text("转存") }
                                        }
                                        TextButton(
                                            enabled = batchStatus.isEmpty(),
                                            onClick = {
                                                scope.launch {
                                                    runCatching { resolveService.resolveShareDownload(state.platform, state.session, file) }
                                                        .onSuccess { res ->
                                                            copyToClipboard(res.url)
                                                            onNotify("已复制直链：${file.fname}")
                                                        }
                                                        .onFailure { err -> errorMessage = err.message ?: "获取直链失败" }
                                                }
                                            },
                                        ) { Text("复制直链") }
                                        FilledTonalButton(
                                            enabled = batchStatus.isEmpty(),
                                            onClick = {
                                                scope.launch {
                                                    runCatching { resolveService.resolveShareDownload(state.platform, state.session, file) }
                                                        .onSuccess { res -> enqueueResolved(res) }
                                                        .onFailure { err -> errorMessage = err.message ?: "获取直链失败" }
                                                }
                                            },
                                        ) { Text("下载") }
                                    }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }

            is ResolveViewState.GitHubRepoLoaded -> {
                val bundle = state.bundle
                val repo = bundle.repo
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DesktopPill("GitHub", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(repo.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    DesktopPill("分支 ${repo.defaultBranch}")
                                    if (!repo.language.isNullOrBlank()) DesktopPill(repo.language.orEmpty())
                                    if (config.githubMirrorPrefix.isNotBlank()) DesktopPill("已启用镜像加速")
                                }
                                if (!repo.description.isNullOrBlank()) {
                                    Text(
                                        text = repo.description.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Button(onClick = {
                                val url = resolveService.buildGitHubArchiveUrl(repo, config.githubMirrorPrefix)
                                enqueueResolved(
                                    ResolvedDownload(
                                        url = url,
                                        fileName = sanitizeFileName("${repo.name}-${repo.defaultBranch}.zip"),
                                        size = -1L,
                                        kind = TransferKind.HTTP,
                                        headers = mapOf("User-Agent" to "YunGet"),
                                        sourceLabel = "GitHub 源码",
                                    )
                                )
                            }) { Text("下载源码 ZIP") }
                        }

                        PrimaryTabRow(selectedTabIndex = ghSubTab) {
                            Tab(selected = ghSubTab == 0, onClick = { ghSubTab = 0 }, text = { Text("发行版 Releases (${bundle.releases.size})") })
                            Tab(selected = ghSubTab == 1, onClick = { ghSubTab = 1 }, text = { Text("仓库文件 (${bundle.tree.size})") })
                        }

                        if (ghSubTab == 0) {
                            if (bundle.releases.isEmpty()) {
                                DesktopEmptyCard("暂无公开发行版", "可以切换到「仓库文件」直接下载单文件或下载源码 ZIP。", modifier = Modifier.padding(16.dp))
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(bundle.releases, key = { it.tagName }) { rel ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(rel.name?.takeIf { it.isNotBlank() } ?: rel.tagName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                                DesktopPill(rel.tagName)
                                                if (rel.prerelease) DesktopPill("Pre-release")
                                            }
                                            rel.assets.forEach { asset ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(asset.name, style = MaterialTheme.typography.bodyMedium)
                                                        Text(formatBytes(asset.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    TextButton(onClick = {
                                                        val url = resolveService.applyGitHubMirror(asset.downloadUrl, config.githubMirrorPrefix)
                                                        copyToClipboard(url)
                                                        onNotify("已复制下载链接")
                                                    }) { Text("复制链接") }
                                                    FilledTonalButton(onClick = {
                                                        val url = resolveService.applyGitHubMirror(asset.downloadUrl, config.githubMirrorPrefix)
                                                        enqueueResolved(
                                                            ResolvedDownload(
                                                                url = url,
                                                                fileName = sanitizeFileName(asset.name),
                                                                size = asset.size,
                                                                kind = TransferKind.HTTP,
                                                                headers = mapOf("User-Agent" to "YunGet"),
                                                                sourceLabel = "GitHub Release",
                                                            )
                                                        )
                                                    }) { Text("下载") }
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        } else {
                            val entriesAtPrefix = remember(bundle.tree, ghTreePrefix) {
                                val prefix = if (ghTreePrefix.isEmpty()) "" else "$ghTreePrefix/"
                                bundle.tree.filter { entry ->
                                    entry.path.startsWith(prefix) && !entry.path.removePrefix(prefix).contains('/')
                                }.sortedWith(compareByDescending<com.yunget.app.data.network.GitHubTreeEntry> { it.type == "tree" }.thenBy { it.path.lowercase() })
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (ghTreePrefix.isNotEmpty()) {
                                    TextButton(onClick = {
                                        ghTreePrefix = ghTreePrefix.substringBeforeLast('/', "")
                                    }) { Text("← 返回上级") }
                                }
                                Text(
                                    text = "/$ghTreePrefix",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(entriesAtPrefix, key = { it.path }) { entry ->
                                    val isDir = entry.type == "tree"
                                    val shortName = entry.path.substringAfterLast('/')
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = isDir) { ghTreePrefix = entry.path }
                                            .padding(horizontal = 16.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        DesktopPill(if (isDir) "目录" else "文件")
                                        Text(shortName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        if (!isDir && entry.size != null) {
                                            Text(formatBytes(entry.size ?: 0L), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (isDir) {
                                            TextButton(onClick = { ghTreePrefix = entry.path }) { Text("打开") }
                                        } else {
                                            FilledTonalButton(onClick = {
                                                val url = resolveService.buildGitHubRawFileUrl(repo, entry.path, config.githubMirrorPrefix)
                                                enqueueResolved(
                                                    ResolvedDownload(
                                                        url = url,
                                                        fileName = sanitizeFileName(shortName),
                                                        size = entry.size ?: -1L,
                                                        kind = TransferKind.HTTP,
                                                        headers = mapOf("User-Agent" to "YunGet"),
                                                        sourceLabel = "GitHub 文件",
                                                    )
                                                )
                                            }) { Text("下载") }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                }
                            }
                        }
                    }
                }
            }

            is ResolveViewState.GitHubAccountLoaded -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DesktopPill("GitHub 主页")
                            Text("@${state.owner} 的公开仓库 (${state.repos.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.repos, key = { it.fullName }) { repo ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            rawInput = "https://github.com/${repo.fullName}"
                                            startResolve(rawInput)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(repo.fullName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                        if (!repo.description.isNullOrBlank()) {
                                            Text(repo.description.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (!repo.language.isNullOrBlank()) DesktopPill(repo.language.orEmpty())
                                    DesktopPill("分支 ${repo.defaultBranch}")
                                    FilledTonalButton(onClick = {
                                        rawInput = "https://github.com/${repo.fullName}"
                                        startResolve(rawInput)
                                    }) { Text("浏览") }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        }

        saveToDriveRequest?.let { (platform, session, items) ->
            val kind = DriveAccountKind.fromPlatform(platform)
            if (kind != null) {
                DesktopSaveToDriveDialog(
                    platform = platform,
                    kind = kind,
                    session = session,
                    items = items,
                    resolveService = resolveService,
                    driveService = driveService,
                    onDismiss = { saveToDriveRequest = null },
                    onSaved = { msg ->
                        saveToDriveRequest = null
                        onNotify(msg)
                    },
                )
            } else {
                saveToDriveRequest = null
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopSaveToDriveDialog(
    platform: SharePlatform,
    kind: DriveAccountKind,
    session: ShareSession,
    items: List<ShareFile>,
    resolveService: DesktopResolveService,
    driveService: DesktopDriveService,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rootId = remember(kind) { driveService.rootDirId(kind) }
    val dirStack = remember(kind) { mutableStateListOf(ShareCrumb(rootId, "根目录")) }
    var folders by remember(kind) { mutableStateOf<List<ShareFile>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(false) }
    var saving by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf("") }
    var newFolderName by remember(kind) { mutableStateOf("") }
    var showNewFolderRow by remember(kind) { mutableStateOf(false) }

    val currentDirId = dirStack.lastOrNull()?.dirId ?: rootId

    fun loadFolders(dirId: String) {
        loading = true
        error = ""
        scope.launch {
            runCatching { driveService.listFiles(kind, dirId).filter { it.isdir } }
                .onSuccess { folders = it }
                .onFailure { err -> error = err.message ?: "读取个人网盘目录失败" }
            loading = false
        }
    }

    LaunchedEffect(kind, currentDirId) {
        loadFolders(currentDirId)
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("转存到 ${kind.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "待转存 ${items.size} 项：${items.take(2).joinToString("、") { it.fname }}${if (items.size > 2) "…" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    dirStack.forEachIndexed { idx, crumb ->
                        if (idx > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = crumb.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (idx == dirStack.lastIndex) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = idx < dirStack.lastIndex && !saving) {
                                while (dirStack.size > idx + 1) dirStack.removeAt(dirStack.lastIndex)
                            }.padding(horizontal = 3.dp),
                        )
                    }
                }
                if (showNewFolderRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = { Text("新文件夹名称") },
                            singleLine = true,
                            enabled = !saving && !loading,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            enabled = newFolderName.isNotBlank() && !saving && !loading,
                            onClick = {
                                val name = newFolderName.trim()
                                scope.launch {
                                    loading = true
                                    runCatching { driveService.createFolder(kind, currentDirId, name) }
                                        .onSuccess {
                                            newFolderName = ""
                                            showNewFolderRow = false
                                            loadFolders(currentDirId)
                                        }
                                        .onFailure { err ->
                                            loading = false
                                            error = err.message ?: "新建文件夹失败"
                                        }
                                }
                            },
                        ) { Text("创建") }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    if (loading) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在加载目录…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (folders.isEmpty()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("当前目录下没有子文件夹，可直接转存到此目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folders, key = { it.fid }) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !saving) {
                                            dirStack.add(ShareCrumb(driveService.childDirId(kind, folder), folder.fname))
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DesktopPill(
                                        text = "目录",
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(folder.fname, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && !loading,
                onClick = {
                    saving = true
                    error = ""
                    scope.launch {
                        val res = runCatching {
                            resolveService.saveShareFilesToDrive(platform, session, items, currentDirId)
                        }
                        saving = false
                        res.onSuccess { count ->
                            onSaved("已转存 $count 项到 ${kind.displayName}（${dirStack.last().label}）")
                        }.onFailure { err ->
                            error = err.message ?: "转存失败"
                        }
                    }
                },
            ) { Text(if (saving) "转存中…" else "转存到此目录") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (driveService.supportsFolderMutation(kind)) {
                    TextButton(enabled = !saving, onClick = { showNewFolderRow = !showNewFolderRow }) {
                        Text(if (showNewFolderRow) "收起新建" else "新建文件夹")
                    }
                }
                TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
