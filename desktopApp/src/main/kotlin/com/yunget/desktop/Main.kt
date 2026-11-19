package com.yunget.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.yunget.app.ui.navigation.MainTab
import com.yunget.app.ui.navigation.SharedBottomBar
import com.yunget.app.ui.navigation.usesNavigationRail
import com.yunget.app.data.network.video.BrowserPageExtractorRegistry
import com.yunget.app.ui.theme.buildAppTypography
import com.yunget.app.ui.theme.darkScheme
import com.yunget.app.ui.theme.lightScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    try {
        loadDesktopNative()
    } catch (err: Throwable) {
        JOptionPane.showMessageDialog(
            null,
            "启动失败：无法加载 TurboDL 原生引擎库。\n${err.message.orEmpty()}",
            "YunGet 启动错误",
            JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    val dataDirectory = defaultDesktopDataDirectory()
    val (settingsStore, accountStore, resolveService, driveService, downloads, historyStore) = try {
        dataDirectory.mkdirs()
        DesktopLogStore.install(dataDirectory)
        DesktopBrowserLocator.cleanupStaleTempProfiles(dataDirectory)
        BrowserPageExtractorRegistry.install(DesktopCdpBrowserPageExtractor(dataDirectory))
        val settings = DesktopSettingsStore(dataDirectory)
        val accounts = DesktopAccountStore(dataDirectory)
        val resolve = DesktopResolveService(accounts)
        val drive = DesktopDriveService(accounts)
        val dl = DesktopDownloads(dataDirectory)
        val history = DesktopResolveHistoryStore(dataDirectory)
        StartupContext(settings, accounts, resolve, drive, dl, history)
    } catch (err: Throwable) {
        runCatching {
            dataDirectory.mkdirs()
            File(dataDirectory, "startup-error.log").writeText(err.stackTraceToString())
        }
        JOptionPane.showMessageDialog(
            null,
            "YunGet 初始化失败：\n${err.javaClass.simpleName}: ${err.message.orEmpty()}\n详情已写入 ${File(dataDirectory, "startup-error.log").absolutePath}",
            "YunGet 启动错误",
            JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    application {
        val config by settingsStore.config.collectAsState()
        val tasks by downloads.tasks.collectAsState()
        val activeCount = tasks.count { it.queuedOrActive }
        val totalSpeed = tasks.sumOf { if (it.status == "RUNNING") it.speedBytesPerSec else 0L }
        val appScope = rememberCoroutineScope()

        val windowTitle = if (activeCount > 0) {
            "YunGet — $activeCount 个任务进行中 · ${formatSpeed(totalSpeed)}"
        } else {
            "YunGet"
        }
        val windowState = rememberWindowState(
            size = DpSize(config.windowWidthDp.dp, config.windowHeightDp.dp),
        )
        val windowIcon = remember {
            runCatching {
                val stream = object {}.javaClass.getResourceAsStream("/app_icon.png") ?: return@runCatching null
                stream.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
            }.getOrNull()
        }
        val trayState = rememberTrayState()
        var windowVisible by remember { mutableStateOf(true) }
        var minimizedNotified by remember { mutableStateOf(false) }

        var currentTab by remember { mutableStateOf(MainTab.Resolve) }
        var showNewDownloadDialog by remember { mutableStateOf(false) }
        var showCommandPalette by remember { mutableStateOf(false) }
        var pendingResolveInput by remember { mutableStateOf<String?>(null) }
        var pendingUploadFiles by remember { mutableStateOf<List<File>>(emptyList()) }
        var externalBannerMessage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(config) {
            downloads.applyConfig(config)
        }

        // Track task status transitions for Windows system notifications.
        val previousStatuses = remember { mutableMapOf<String, String>() }
        LaunchedEffect(tasks, config.systemNotifications) {
            if (previousStatuses.isEmpty() && tasks.isNotEmpty()) {
                tasks.forEach { previousStatuses[it.id] = it.status }
                return@LaunchedEffect
            }
            for (task in tasks) {
                val prev = previousStatuses[task.id]
                if (prev != null && prev != task.status && config.systemNotifications && isTraySupported && windowIcon != null) {
                    when (task.status) {
                        "DONE" -> trayState.sendNotification(
                            Notification("下载完成", task.output.name, Notification.Type.Info)
                        )
                        "FAILED" -> trayState.sendNotification(
                            Notification("下载失败", "${task.output.name}：${task.message.ifBlank { "请查看任务详情" }}", Notification.Type.Error)
                        )
                    }
                }
                previousStatuses[task.id] = task.status
            }
            val aliveIds = tasks.mapTo(hashSetOf()) { it.id }
            previousStatuses.keys.retainAll(aliveIds)
        }

        DisposableEffect(Unit) {
            onDispose {
                val w = windowState.size.width.value.toInt()
                val h = windowState.size.height.value.toInt()
                if (w >= 720 && h >= 520) {
                    settingsStore.update { it.copy(windowWidthDp = w, windowHeightDp = h) }
                }
                downloads.close()
            }
        }

        if (isTraySupported && windowIcon != null) {
            Tray(
                icon = windowIcon,
                state = trayState,
                tooltip = windowTitle,
                onAction = { windowVisible = true },
                menu = {
                    Item("打开 YunGet 主窗口", onClick = { windowVisible = true })
                    Item("新建下载任务", onClick = {
                        windowVisible = true
                        showNewDownloadDialog = true
                    })
                    Separator()
                    Item("暂停全部下载", onClick = {
                        appScope.launch { downloads.pauseAll() }
                    })
                    Item("继续全部下载", onClick = {
                        appScope.launch { downloads.resumeAll(config.httpPolicy) }
                    })
                    Item("打开下载目录", onClick = {
                        revealInExplorer(File(config.downloadDirectory))
                    })
                    Separator()
                    Item("退出 YunGet", onClick = ::exitApplication)
                },
            )
        }

        Window(
            onCloseRequest = {
                if (config.minimizeToTray && isTraySupported && windowIcon != null) {
                    windowVisible = false
                    if (!minimizedNotified) {
                        minimizedNotified = true
                        trayState.sendNotification(
                            Notification("YunGet 已最小化到托盘", "后台任务将继续运行，右键托盘图标可恢复或退出。", Notification.Type.Info)
                        )
                    }
                } else {
                    exitApplication()
                }
            },
            visible = windowVisible,
            state = windowState,
            title = windowTitle,
            icon = windowIcon,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                if (event.key == Key.Escape && showCommandPalette) {
                    showCommandPalette = false
                    return@Window true
                }
                if (event.isCtrlPressed) {
                    when (event.key) {
                        Key.K -> { showCommandPalette = !showCommandPalette; true }
                        Key.One -> { currentTab = MainTab.Resolve; true }
                        Key.Two -> { currentTab = MainTab.Drive; true }
                        Key.Three -> { currentTab = MainTab.Download; true }
                        Key.Four -> { currentTab = MainTab.Settings; true }
                        Key.N -> { showNewDownloadDialog = true; true }
                        Key.P -> if (event.isShiftPressed) {
                            appScope.launch { downloads.pauseAll() }
                            externalBannerMessage = "已暂停全部下载任务"
                            true
                        } else false
                        Key.R -> if (event.isShiftPressed) {
                            appScope.launch { downloads.resumeAll(config.httpPolicy) }
                            externalBannerMessage = "已恢复全部下载任务"
                            true
                        } else false
                        else -> false
                    }
                } else false
            },
        ) {
            val latestTab by rememberUpdatedState(currentTab)
            val latestConfig by rememberUpdatedState(config)

            // Drag-and-drop support: .torrent/.metalink -> download, Drive files -> upload, URL/text -> resolve.
            DisposableEffect(window) {
                val dropTarget = DropTarget(
                    window,
                    DnDConstants.ACTION_COPY,
                    object : DropTargetAdapter() {
                        override fun drop(dtde: DropTargetDropEvent) {
                            runCatching {
                                dtde.acceptDrop(DnDConstants.ACTION_COPY)
                                val transferable = dtde.transferable
                                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                    @Suppress("UNCHECKED_CAST")
                                    val dropped = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>)
                                        .orEmpty()
                                        .filter { it.isFile }
                                    val taskFiles = dropped.filter { f ->
                                        val lower = f.name.lowercase()
                                        lower.endsWith(".torrent") || lower.endsWith(".metalink") || lower.endsWith(".meta4")
                                    }
                                    val normalFiles = dropped - taskFiles.toSet()
                                    SwingUtilities.invokeLater {
                                        if (taskFiles.isNotEmpty()) {
                                            appScope.launch {
                                                taskFiles.forEach { file ->
                                                    val kind = if (file.name.endsWith(".torrent", ignoreCase = true)) {
                                                        TransferKind.TORRENT
                                                    } else {
                                                        TransferKind.METALINK
                                                    }
                                                    downloads.submit(
                                                        source = file.absolutePath,
                                                        directory = File(latestConfig.downloadDirectory),
                                                        fileName = file.nameWithoutExtension.ifBlank { file.name },
                                                        kind = kind,
                                                        httpVersion = latestConfig.httpPolicy,
                                                        autoRename = latestConfig.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                                                        sourceLabel = defaultSourceLabel(kind),
                                                    )
                                                }
                                                externalBannerMessage = "已拖入并提交 ${taskFiles.size} 个种子/Metalink 任务"
                                                currentTab = MainTab.Download
                                            }
                                        } else if (normalFiles.isNotEmpty()) {
                                            if (latestTab == MainTab.Drive) {
                                                pendingUploadFiles = normalFiles
                                            } else {
                                                externalBannerMessage = "已拖入 ${normalFiles.size} 个本地文件：请切到「网盘」页后拖入即可上传"
                                            }
                                        }
                                    }
                                    dtde.dropComplete(true)
                                } else if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                                    val text = (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.trim().orEmpty()
                                    if (text.isNotEmpty()) {
                                        SwingUtilities.invokeLater {
                                            pendingResolveInput = text
                                            currentTab = MainTab.Resolve
                                        }
                                    }
                                    dtde.dropComplete(true)
                                } else {
                                    dtde.dropComplete(false)
                                }
                            }.onFailure {
                                runCatching { dtde.dropComplete(false) }
                            }
                        }
                    },
                    true,
                )
                onDispose {
                    runCatching { dropTarget.isActive = false }
                    runCatching { window.dropTarget = null }
                }
            }

            val darkTheme = when (config.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MaterialTheme(
                colorScheme = if (darkTheme) darkScheme else lightScheme,
                typography = buildAppTypography(),
            ) {
                YunGetDesktopShell(
                    dataDirectory = dataDirectory,
                    currentTab = currentTab,
                    onTabSelected = { currentTab = it },
                    config = config,
                    settingsStore = settingsStore,
                    accountStore = accountStore,
                    resolveService = resolveService,
                    driveService = driveService,
                    downloads = downloads,
                    historyStore = historyStore,
                    activeDownloadCount = activeCount,
                    totalSpeedBytesPerSec = totalSpeed,
                    showNewDownloadDialog = showNewDownloadDialog,
                    onShowNewDownloadDialogChange = { showNewDownloadDialog = it },
                    showCommandPalette = showCommandPalette,
                    onShowCommandPaletteChange = { showCommandPalette = it },
                    pendingResolveInput = pendingResolveInput,
                    onConsumePendingResolveInput = { pendingResolveInput = null },
                    pendingUploadFiles = pendingUploadFiles,
                    onConsumePendingUploadFiles = { pendingUploadFiles = emptyList() },
                    externalToastMessage = externalBannerMessage,
                    onConsumeExternalToast = { externalBannerMessage = null },
                )
            }
        }
    }
}

private data class StartupContext(
    val settingsStore: DesktopSettingsStore,
    val accountStore: DesktopAccountStore,
    val resolveService: DesktopResolveService,
    val driveService: DesktopDriveService,
    val downloads: DesktopDownloads,
    val historyStore: DesktopResolveHistoryStore,
)

private fun defaultDesktopDataDirectory(): File {
    // Use Roaming AppData on Windows so user data never collides with the per-user
    // MSI install location (%LOCALAPPDATA%\YunGet) or legacy Flutter state files.
    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
    if (appData != null) return File(appData, "YunGet")
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
    if (localAppData != null) return File(localAppData, "YunGetData")
    return File(System.getProperty("user.home"), ".yunget")
}

@Composable
internal fun YunGetDesktopShell(
    dataDirectory: File,
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    config: DesktopConfig,
    settingsStore: DesktopSettingsStore,
    accountStore: DesktopAccountStore,
    resolveService: DesktopResolveService,
    driveService: DesktopDriveService,
    downloads: DesktopDownloads,
    activeDownloadCount: Int,
    totalSpeedBytesPerSec: Long,
    showNewDownloadDialog: Boolean,
    onShowNewDownloadDialogChange: (Boolean) -> Unit,
    historyStore: DesktopResolveHistoryStore = remember(dataDirectory) { DesktopResolveHistoryStore(dataDirectory) },
    showCommandPalette: Boolean = false,
    onShowCommandPaletteChange: (Boolean) -> Unit = {},
    pendingResolveInput: String? = null,
    onConsumePendingResolveInput: () -> Unit = {},
    pendingUploadFiles: List<File> = emptyList(),
    onConsumePendingUploadFiles: () -> Unit = {},
    externalToastMessage: String? = null,
    onConsumeExternalToast: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val accounts by accountStore.accounts.collectAsState()
    var accountDialogKind by remember { mutableStateOf<DriveAccountKind?>(null) }
    var showLogViewer by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var clipboardCandidate by remember { mutableStateOf<String?>(null) }
    var internalResolveInput by remember { mutableStateOf<String?>(null) }
    val effectiveResolveInput = pendingResolveInput ?: internalResolveInput

    fun notify(message: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
    }

    fun triggerUpdateCheck() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            runCatching { DesktopUpdateChecker.check(config.githubMirrorPrefix) }
                .onSuccess { info ->
                    updateInfo = info
                    DesktopLogStore.info("update", "checked", "current=${info.currentVersion}, latest=${info.latestVersion}")
                }
                .onFailure { err ->
                    DesktopLogStore.warn("update", "check_failed", err.message.orEmpty())
                    notify(err.message ?: "检查更新失败")
                }
            checkingUpdate = false
        }
    }

    LaunchedEffect(externalToastMessage) {
        val msg = externalToastMessage
        if (!msg.isNullOrBlank()) {
            onConsumeExternalToast()
            notify(msg)
        }
    }

    // Optional clipboard monitor: detects newly copied supported links without stealing focus.
    LaunchedEffect(config.clipboardMonitor) {
        if (!config.clipboardMonitor) {
            clipboardCandidate = null
            return@LaunchedEffect
        }
        var lastSeen = readClipboardText().trim()
        while (true) {
            delay(1_200L)
            val currentClip = readClipboardText().trim()
            if (currentClip.isEmpty() || currentClip == lastSeen) continue
            lastSeen = currentClip
            if (currentClip.length > 2048) continue
            val detected = detectDesktopLinkTarget(currentClip)
            if (detected !is DesktopLinkTarget.Unsupported) {
                clipboardCandidate = currentClip
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = usesNavigationRail(maxWidth.value.toInt(), maxHeight.value.toInt())
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    if (!useRail) {
                        SharedBottomBar(currentTab = currentTab, onTabSelected = onTabSelected)
                    }
                },
            ) { innerPadding ->
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    if (useRail) {
                        DesktopNavRail(
                            currentTab = currentTab,
                            onTabSelected = onTabSelected,
                            activeDownloadCount = activeDownloadCount,
                            totalSpeedBytesPerSec = totalSpeedBytesPerSec,
                            onOpenCommandPalette = { onShowCommandPaletteChange(true) },
                        )
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        clipboardCandidate?.let { clip ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DesktopPill("剪贴板检测")
                                    Text(
                                        text = clip.replace('\n', ' ').take(100),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            clipboardCandidate = null
                                            internalResolveInput = clip
                                            onTabSelected(MainTab.Resolve)
                                        },
                                    ) { Text("一键解析") }
                                    TextButton(onClick = { clipboardCandidate = null }) { Text("忽略") }
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (currentTab) {
                                MainTab.Resolve -> DesktopResolveTab(
                                    config = config,
                                    accountStore = accountStore,
                                    resolveService = resolveService,
                                    driveService = driveService,
                                    downloads = downloads,
                                    historyStore = historyStore,
                                    onNotify = ::notify,
                                    onOpenAccountDialog = { accountDialogKind = it },
                                    onJumpToDownloads = { onTabSelected(MainTab.Download) },
                                    externalInput = effectiveResolveInput,
                                    onConsumeExternalInput = {
                                        if (pendingResolveInput != null) onConsumePendingResolveInput()
                                        internalResolveInput = null
                                    },
                                    clipboardMonitorEnabled = config.clipboardMonitor,
                                    onToggleClipboardMonitor = { enabled ->
                                        settingsStore.update { it.copy(clipboardMonitor = enabled) }
                                        notify(if (enabled) "已开启剪贴板监听" else "已关闭剪贴板监听")
                                    },
                                )
                                MainTab.Drive -> DesktopDriveTab(
                                    config = config,
                                    accountStore = accountStore,
                                    driveService = driveService,
                                    downloads = downloads,
                                    onNotify = ::notify,
                                    onOpenAccountDialog = { accountDialogKind = it },
                                    pendingUploadFiles = pendingUploadFiles,
                                    onConsumePendingUploadFiles = onConsumePendingUploadFiles,
                                )
                                MainTab.Download -> DesktopDownloadsTab(
                                    config = config,
                                    downloads = downloads,
                                    onNotify = ::notify,
                                    onOpenNewDownloadDialog = { onShowNewDownloadDialogChange(true) },
                                )
                                MainTab.Settings -> DesktopSettingsTab(
                                    dataDirectory = dataDirectory,
                                    config = config,
                                    settingsStore = settingsStore,
                                    accountStore = accountStore,
                                    capabilities = downloads.capabilities,
                                    onNotify = ::notify,
                                    onOpenAccountDialog = { accountDialogKind = it },
                                    onOpenLogViewer = { showLogViewer = true },
                                    onCheckUpdate = ::triggerUpdateCheck,
                                    checkingUpdate = checkingUpdate,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    accountDialogKind?.let { kind ->
        DesktopAccountDialog(
            kind = kind,
            existing = accounts[kind],
            accountStore = accountStore,
            onDismiss = { accountDialogKind = null },
            onSaved = ::notify,
        )
    }

    if (showNewDownloadDialog) {
        NewDownloadDialog(
            defaultDirectory = File(config.downloadDirectory),
            defaultHttpPolicy = config.httpPolicy,
            conflictPolicy = config.conflictPolicy,
            onDismiss = { onShowNewDownloadDialogChange(false) },
            onSubmit = { source, dir, name, kind, httpVer, sha, headers, autoRename ->
                downloads.submit(
                    source = source,
                    directory = dir,
                    fileName = name,
                    kind = kind,
                    httpVersion = httpVer,
                    sha256 = sha,
                    headers = headers,
                    autoRename = autoRename,
                    sourceLabel = defaultSourceLabel(kind),
                )
                notify("已加入下载队列：$name")
                onTabSelected(MainTab.Download)
            },
        )
    }

    if (showCommandPalette) {
        val commandActions = remember(config, accounts) {
            buildList {
                add(DesktopCommandAction("nav-resolve", "跳转到：智能解析", "解析网盘、视频、GitHub 与直链", "Ctrl+1", "resolve parse jiexi") {
                    onTabSelected(MainTab.Resolve)
                })
                add(DesktopCommandAction("nav-drive", "跳转到：个人网盘", "浏览、上传、分享与下载个人网盘", "Ctrl+2", "drive cloud wangpan") {
                    onTabSelected(MainTab.Drive)
                })
                add(DesktopCommandAction("nav-download", "跳转到：下载队列", "管理 TurboDL 多协议并发任务", "Ctrl+3", "download xiazai queue") {
                    onTabSelected(MainTab.Download)
                })
                add(DesktopCommandAction("nav-settings", "跳转到：设置与运维", "引擎、网络、外观、备份与日志", "Ctrl+4", "settings shezhi") {
                    onTabSelected(MainTab.Settings)
                })
                add(DesktopCommandAction("new-dl", "新建下载任务", "手动输入 HTTP / HLS / BT / Metalink / SFTP 链接", "Ctrl+N", "new task download") {
                    onShowNewDownloadDialogChange(true)
                })
                add(DesktopCommandAction("paste-resolve", "粘贴剪贴板并解析", "读取当前剪贴板内容并自动解析", "", "paste clipboard") {
                    val clip = readClipboardText().trim()
                    if (clip.isEmpty()) {
                        notify("剪贴板为空")
                    } else {
                        internalResolveInput = clip
                        onTabSelected(MainTab.Resolve)
                    }
                })
                add(DesktopCommandAction("pause-all", "暂停全部下载任务", "暂停所有活跃与排队中的任务", "Ctrl+Shift+P", "pause stop") {
                    scope.launch { downloads.pauseAll(); notify("已暂停全部任务") }
                })
                add(DesktopCommandAction("resume-all", "继续全部下载任务", "恢复已暂停或失败的下载任务", "Ctrl+Shift+R", "resume start") {
                    scope.launch { downloads.resumeAll(config.httpPolicy); notify("已恢复下载队列") }
                })
                add(DesktopCommandAction("open-dir", "打开默认下载目录", config.downloadDirectory, "", "folder explorer dir") {
                    revealInExplorer(File(config.downloadDirectory))
                })
                add(DesktopCommandAction(
                    "toggle-clip",
                    if (config.clipboardMonitor) "关闭剪贴板自动监听" else "开启剪贴板自动监听",
                    "自动检测新复制的网盘或视频链接",
                    "",
                    "clipboard monitor",
                ) {
                    val next = !config.clipboardMonitor
                    settingsStore.update { it.copy(clipboardMonitor = next) }
                    notify(if (next) "已开启剪贴板监听" else "已关闭剪贴板监听")
                })
                add(DesktopCommandAction("open-logs", "打开运行日志查看器", "查看、过滤并复制诊断日志", "", "log diagnostic debug") {
                    showLogViewer = true
                })
                add(DesktopCommandAction("check-update", "检查版本更新", "当前版本 ${DesktopAppMeta.currentVersion}", "", "update version release") {
                    triggerUpdateCheck()
                })
                ThemeMode.entries.forEach { tm ->
                    add(DesktopCommandAction("theme-${tm.name}", "切换外观主题：${tm.label}", "", "", "theme dark light system") {
                        settingsStore.update { it.copy(themeMode = tm) }
                        notify("已切换主题：${tm.label}")
                    })
                }
                DriveAccountKind.entries.forEach { kind ->
                    val bound = accounts[kind]
                    add(DesktopCommandAction(
                        "account-${kind.name}",
                        "账号管理：${kind.displayName}",
                        if (bound != null) "当前主账号：${bound.displayLabel}" else "点击登录或导入凭证",
                        "",
                        "account login ${kind.name} ${kind.displayName}",
                    ) {
                        accountDialogKind = kind
                    })
                }
            }
        }
        DesktopCommandPaletteDialog(
            actions = commandActions,
            onDismiss = { onShowCommandPaletteChange(false) },
        )
    }

    if (showLogViewer) {
        DesktopLogViewerDialog(
            onDismiss = { showLogViewer = false },
            onNotify = ::notify,
        )
    }

    updateInfo?.let { info ->
        DesktopUpdateDialog(
            info = info,
            onDismiss = { updateInfo = null },
            onDownloadMsi = { assetName, url ->
                scope.launch {
                    runCatching {
                        downloads.submit(
                            source = url,
                            directory = File(config.downloadDirectory),
                            fileName = sanitizeFileName(assetName, "YunGet-Setup.msi"),
                            kind = TransferKind.HTTP,
                            httpVersion = config.httpPolicy,
                            autoRename = config.conflictPolicy == ConflictPolicy.AUTO_RENAME,
                            sourceLabel = "YunGet 更新",
                        )
                    }.onSuccess {
                        notify("已开始下载安装包：$assetName")
                        onTabSelected(MainTab.Download)
                    }.onFailure { err ->
                        notify("提交更新下载失败：${err.message.orEmpty()}")
                    }
                }
            },
        )
    }
}

@Composable
private fun DesktopNavRail(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    activeDownloadCount: Int,
    totalSpeedBytesPerSec: Long,
    onOpenCommandPalette: () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
            header = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                ) {
                    Text(
                        "云取",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Windows",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            MainTab.entries.forEach { tab ->
                val selected = currentTab == tab
                NavigationRailItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        if (tab == MainTab.Download && activeDownloadCount > 0) {
                            BadgedBox(badge = { Badge { Text(activeDownloadCount.toString()) } }) {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title,
                                )
                            }
                        } else {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title,
                            )
                        }
                    },
                    label = { Text(tab.title) },
                    alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onOpenCommandPalette,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Text("Ctrl+K", style = MaterialTheme.typography.labelSmall)
            }
            if (activeDownloadCount > 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
                ) {
                    Text(
                        formatSpeed(totalSpeedBytesPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "$activeDownloadCount 个活跃",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }
}
