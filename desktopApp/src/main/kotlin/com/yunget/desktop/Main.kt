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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yunget.app.ui.navigation.MainTab
import com.yunget.app.ui.navigation.SharedBottomBar
import com.yunget.app.ui.navigation.usesNavigationRail
import com.yunget.app.ui.theme.buildAppTypography
import com.yunget.app.ui.theme.darkScheme
import com.yunget.app.ui.theme.lightScheme
import kotlinx.coroutines.launch
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JOptionPane
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
    val (settingsStore, accountStore, resolveService, driveService, downloads) = try {
        dataDirectory.mkdirs()
        val settings = DesktopSettingsStore(dataDirectory)
        val accounts = DesktopAccountStore(dataDirectory)
        val resolve = DesktopResolveService(accounts)
        val drive = DesktopDriveService(accounts)
        val dl = DesktopDownloads(dataDirectory)
        StartupContext(settings, accounts, resolve, drive, dl)
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

        var currentTab by remember { mutableStateOf(MainTab.Resolve) }
        var showNewDownloadDialog by remember { mutableStateOf(false) }

        LaunchedEffect(config) {
            downloads.applyConfig(config)
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

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = windowTitle,
            icon = windowIcon,
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                    when (event.key) {
                        Key.One -> { currentTab = MainTab.Resolve; true }
                        Key.Two -> { currentTab = MainTab.Drive; true }
                        Key.Three -> { currentTab = MainTab.Download; true }
                        Key.Four -> { currentTab = MainTab.Settings; true }
                        Key.N -> { showNewDownloadDialog = true; true }
                        else -> false
                    }
                } else false
            },
        ) {
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
                    activeDownloadCount = activeCount,
                    totalSpeedBytesPerSec = totalSpeed,
                    showNewDownloadDialog = showNewDownloadDialog,
                    onShowNewDownloadDialogChange = { showNewDownloadDialog = it },
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
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val accounts by accountStore.accounts.collectAsState()
    var accountDialogKind by remember { mutableStateOf<DriveAccountKind?>(null) }

    fun notify(message: String) {
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
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
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (currentTab) {
                            MainTab.Resolve -> DesktopResolveTab(
                                config = config,
                                accountStore = accountStore,
                                resolveService = resolveService,
                                downloads = downloads,
                                onNotify = ::notify,
                                onOpenAccountDialog = { accountDialogKind = it },
                                onJumpToDownloads = { onTabSelected(MainTab.Download) },
                            )
                            MainTab.Drive -> DesktopDriveTab(
                                config = config,
                                accountStore = accountStore,
                                driveService = driveService,
                                downloads = downloads,
                                onNotify = ::notify,
                                onOpenAccountDialog = { accountDialogKind = it },
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
                            )
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
}

@Composable
private fun DesktopNavRail(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    activeDownloadCount: Int,
    totalSpeedBytesPerSec: Long,
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
