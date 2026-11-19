package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
    data class GitHubRepoLoaded(val bundle: GitHubRepoBundle) : ResolveViewState()
    data class GitHubAccountLoaded(val owner: String, val repos: List<GitHubRepo>) : ResolveViewState()
    data class DirectReady(val direct: DesktopLinkTarget.Direct) : ResolveViewState()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopResolveTab(
    config: DesktopConfig,
    accountStore: DesktopAccountStore,
    resolveService: DesktopResolveService,
    downloads: DesktopDownloads,
    onNotify: (String) -> Unit,
    onOpenAccountDialog: (DriveAccountKind) -> Unit,
    onJumpToDownloads: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val accounts by accountStore.accounts.collectAsState()

    var rawInput by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var batchStatus by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var viewState by remember { mutableStateOf<ResolveViewState>(ResolveViewState.Idle) }

    val crumbs = remember { mutableStateListOf<ShareCrumb>() }
    var filterQuery by remember { mutableStateOf("") }
    var selectedFids by remember { mutableStateOf<Set<String>>(emptySet()) }
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
            errorMessage = "请先粘贴分享链接、GitHub 链接、直链或磁力链接"
            return
        }
        errorMessage = ""
        selectedFids = emptySet()
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
            is DesktopLinkTarget.CloudShare -> {
                busy = true
                scope.launch {
                    runCatching { resolveService.openShare(target.rawInput, passcode.ifBlank { null }) }
                        .onSuccess { opened ->
                            crumbs.clear()
                            crumbs.add(ShareCrumb(opened.rootDirId, "根目录"))
                            viewState = ResolveViewState.ShareLoaded(opened.platform, opened.session, opened.files)
                        }
                        .onFailure { err ->
                            errorMessage = err.message ?: "解析分享链接失败"
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
                    "支持 10 大网盘分享、GitHub 仓库/发行版、HTTP/3 直链、HLS/DASH 流媒体与 BT/磁力/Metalink",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                DesktopEmptyCard(
                    title = "粘贴链接即可开始解析",
                    subtitle = "点击上方任意网盘标签可快速导入 Cookie / Token 或账密登录；夸克、UC、光鸭、蓝奏、小飞机与城通均支持免登录解析。",
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
                                        FilledTonalButton(
                                            enabled = batchStatus.isEmpty(),
                                            onClick = { downloadShareItems(listOf(file)) },
                                        ) { Text("下载整个目录") }
                                    } else {
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
    }
}
