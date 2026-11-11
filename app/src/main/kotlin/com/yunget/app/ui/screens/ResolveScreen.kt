package com.yunget.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Button
import com.yunget.app.ui.components.HomeShortcutGrid
import com.yunget.app.ui.components.YunGetLoading
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import com.yunget.app.ui.theme.LocalUiMetrics
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.theme.spatialFast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.resolve.DownloadLinkDialog
import com.yunget.app.ui.resolve.ShareDetailScreen
import com.yunget.app.ui.scanner.rememberQrScanLauncher
import com.yunget.app.ui.viewmodel.BaiduCloudViewModel
import com.yunget.app.ui.viewmodel.BookmarkViewModel
import com.yunget.app.ui.viewmodel.C139CloudViewModel
import com.yunget.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunget.app.ui.viewmodel.ILanzouCloudViewModel
import com.yunget.app.ui.viewmodel.LanzouCloudViewModel
import com.yunget.app.ui.viewmodel.Pan115CloudViewModel
import com.yunget.app.ui.viewmodel.Pan123CloudViewModel
import com.yunget.app.ui.viewmodel.QuarkCloudViewModel
import com.yunget.app.ui.viewmodel.ResolveUiState
import com.yunget.app.ui.viewmodel.ResolveViewModel
import com.yunget.app.ui.viewmodel.UCCoudViewModel
import com.yunget.app.ui.viewmodel.XunleiCloudViewModel
import com.yunget.app.ui.resolve.VideoResultSection

/**
 * 解析页：输入分享链接与提取码 → 解析 → 展示分享详情 → 获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ResolveViewModel,
    /** 夸克云盘浏览 ViewModel（分享文件转存目录选择用） */
    quarkCloudViewModel: QuarkCloudViewModel,
    /** 迅雷网盘云盘浏览 ViewModel（迅雷分享转存目录选择用） */
    xunleiCloudViewModel: XunleiCloudViewModel,
    /** 百度网盘云盘浏览 ViewModel（百度分享转存目录选择用） */
    baiduCloudViewModel: BaiduCloudViewModel,
    /** 139 网盘云盘浏览 ViewModel（139 分享转存目录选择用） */
    c139CloudViewModel: C139CloudViewModel,
    /** UC 网盘云盘浏览 ViewModel（UC 分享转存目录选择用） */
    ucCloudViewModel: UCCoudViewModel,
    /** 123 云盘浏览 ViewModel（123 分享转存目录选择用） */
    pan123CloudViewModel: Pan123CloudViewModel,
    /** 115 网盘浏览 ViewModel（115 分享转存目录选择用） */
    pan115CloudViewModel: Pan115CloudViewModel,
    /** 光鸭云盘浏览 ViewModel（光鸭分享转存目录选择用） */
    guangyaCloudViewModel: GuangYaCloudViewModel,
    /** 蓝奏云优享版浏览 ViewModel（转存目录选择用） */
    ilanzouCloudViewModel: ILanzouCloudViewModel,
    /** 蓝奏云浏览 ViewModel（转存目录选择用） */
    lanzouCloudViewModel: LanzouCloudViewModel,
    /** 收藏 ViewModel（主页快捷方式网格的数据源） */
    bookmarkViewModel: BookmarkViewModel,
    /** 点快捷方式网格的「管理」：切到收藏页 */
    onOpenBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (com.yunget.app.ui.theme.LocalAppUiStyle.current == com.yunget.app.ui.theme.AppUiStyle.CLASSIC) {
        com.yunget.app.ui.classic.ResolveScreen(
            scrollBehavior = scrollBehavior,
            viewModel = viewModel,
            quarkCloudViewModel = quarkCloudViewModel,
            xunleiCloudViewModel = xunleiCloudViewModel,
            baiduCloudViewModel = baiduCloudViewModel,
            c139CloudViewModel = c139CloudViewModel,
            ucCloudViewModel = ucCloudViewModel,
            pan123CloudViewModel = pan123CloudViewModel,
            pan115CloudViewModel = pan115CloudViewModel,
            guangyaCloudViewModel = guangyaCloudViewModel,
            ilanzouCloudViewModel = ilanzouCloudViewModel,
            lanzouCloudViewModel = lanzouCloudViewModel,
            bookmarkViewModel = bookmarkViewModel,
            onOpenBookmarks = onOpenBookmarks,
            modifier = modifier,
        )
        return
    }

    val ui = LocalUiMetrics.current
    val state = viewModel.uiState
    val downloadLink = viewModel.downloadLink
    val downloadError = viewModel.downloadError
    val context = LocalContext.current

    // 输入框状态提升到页面层：进入详情/文件夹再返回时不清空
    var link by rememberSaveable { mutableStateOf("") }
    var pwd by rememberSaveable { mutableStateOf("") }
    var pwdEdited by rememberSaveable { mutableStateOf(false) }

    // 剪贴板分享链接提示状态：待提示的剪贴板文本 + 已忽略的文本
    // 用 rememberSaveable：切换 Tab 后返回仍保留（避免「忽略后切页回来又弹」）
    var clipboardSuggestion by rememberSaveable { mutableStateOf<String?>(null) }
    var ignoredClipboard by rememberSaveable { mutableStateOf<String?>(null) }

    // 检测函数：读取剪贴板，满足条件则设置提示（三重触发：组合时 / ON_RESUME / 剪贴板变化）
    // 网盘分享链接、普通 http(s) 直链均提示（后者粘贴后直接走多线程下载）
    val maybeSuggestClipboard: () -> Unit = {
        val text = if (com.yunget.app.ui.theme.ThemeController.clipboardSuggestEnabled) readClipboardSafely(context) else null
        if (text != null &&
            state is ResolveUiState.Idle &&
            text.isNotBlank() &&
            text != link &&
            text != ignoredClipboard &&
            (ShareLinkParser.parse(text) != null || com.yunget.app.data.network.C139KoulingParser.parse(text) != null || looksLikeDirectUrl(text))
        ) {
            clipboardSuggestion = text
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    DisposableEffect(lifecycleOwner, clipboard) {
        // 剪贴板变化立即检测（前台最灵敏，复制即提示）
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            maybeSuggestClipboard()
            // 部分 ROM 剪贴板内容写入有延迟，300ms 后重试一次
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                maybeSuggestClipboard()
            }, 300)
        }
        clipboard.addPrimaryClipChangedListener(clipListener)
        // 打开应用 / 从后台切回时检测
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) maybeSuggestClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 冷启动兜底：组合完成立即检测一次（避免 ON_RESUME 早于 observer 注册导致漏检）
        maybeSuggestClipboard()
        onDispose {
            clipboard.removePrimaryClipChangedListener(clipListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Android 11 及以下：轻量轮询兜底（2s 一次）。
    // 部分 ROM（如 vivo）剪贴板监听不触发时仍能识别；Android 12+ 读剪贴板会弹系统提示，不轮询。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                maybeSuggestClipboard()
            }
        }
    }

    // 链接变化时自动匹配提取码（用户未手动输入时）
    LaunchedEffect(link) {
        if (!pwdEdited && pwd.isEmpty()) {
            ShareLinkParser.parse(link)?.pwd?.let { pwd = it }
        }
    }

    // 下载错误提示
    LaunchedEffect(downloadError) {
        downloadError?.let {
            SnackbarController.show(it)
            viewModel.consumeDownloadError()
        }
    }

    val clipboardContent: @Composable () -> Unit = {
        // 提示放入可滚动工作区，避免覆盖输入框和主操作。
        // animatedSuggestion 保留最后提示内容，保证退出动画期间卡片不消失
        var animatedSuggestion by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(com.yunget.app.ui.theme.ThemeController.clipboardSuggestEnabled) {
            if (!com.yunget.app.ui.theme.ThemeController.clipboardSuggestEnabled) clipboardSuggestion = null
        }
        LaunchedEffect(clipboardSuggestion) {
            clipboardSuggestion?.let { animatedSuggestion = it }
        }
        AnimatedVisibility(
            visible = state is ResolveUiState.Idle && clipboardSuggestion != null,
            enter = fadeIn(effectsDefault()) +
                slideInVertically(spatialDefault()) { -it / 2 } +
                scaleIn(spatialDefault()),
            exit = fadeOut(effectsFast()) +
                slideOutVertically(spatialFast()) { -it / 2 } +
                scaleOut(spatialFast()),
            modifier = Modifier
                .fillMaxWidth()
        ) {
            animatedSuggestion?.let { suggestion ->
                val parsed = ShareLinkParser.parse(suggestion)
                val command = com.yunget.app.data.network.C139KoulingParser.parse(suggestion)
                ClipboardSuggestCard(
                    platformName = parsed?.platform?.let { platformLabel(it) } ?: if (command != null) "139 网盘口令" else "下载",
                    isDirectLink = parsed == null && command == null,
                    onPaste = {
                        link = suggestion
                        pwd = parsed?.pwd ?: command?.password.orEmpty()
                        pwdEdited = true
                        clipboardSuggestion = null
                        // 网盘链接 → 解析；非网盘直链 → ViewModel 内部自动转为多线程下载
                        viewModel.startResolve(suggestion, parsed?.pwd)
                    },
                    onDismiss = {
                        ignoredClipboard = suggestion
                        clipboardSuggestion = null
                    }
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 状态切换过渡：输入态/加载/详情/错误之间平滑淡入淡出 + 轻微位移
        AnimatedContent(
            targetState = state,
            modifier = Modifier.fillMaxSize(),
            // 文件列表内容更新不重建整页，Idle / Error 共用输入工作区。
            contentKey = {
                when (it) {
                    is ResolveUiState.Detail -> "detail"
                    is ResolveUiState.Video -> "video"
                    is ResolveUiState.Loading -> "loading"
                    else -> "input"
                }
            },
            transitionSpec = {
                (fadeIn(effectsDefault()) +
                    slideInVertically(spatialDefault()) { it / 24 } +
                    scaleIn(spatialDefault(), initialScale = 0.98f))
                    .togetherWith(fadeOut(effectsFast()) + scaleOut(spatialFast(), targetScale = 0.98f))
                    .using(SizeTransform(sizeAnimationSpec = { _, _ -> spatialDefault() }))
            },
            label = "resolveState"
        ) { s ->
            when (s) {
                is ResolveUiState.Detail -> ShareDetailScreen(
                    session = s.session,
                    files = s.files,
                    viewModel = viewModel,
                    quarkCloudViewModel = quarkCloudViewModel,
                    xunleiCloudViewModel = xunleiCloudViewModel,
                    baiduCloudViewModel = baiduCloudViewModel,
                    c139CloudViewModel = c139CloudViewModel,
                    ucCloudViewModel = ucCloudViewModel,
                    pan123CloudViewModel = pan123CloudViewModel,
                    pan115CloudViewModel = pan115CloudViewModel,
                    guangYaCloudViewModel = guangyaCloudViewModel,
                    scrollBehavior = scrollBehavior,
                    // 顶部左上角返回：退出文件页回到输入页（输入框内容保留）
                    onExit = { viewModel.backToInput() },
                    // 列表「返回上一级」：子目录回上级，根目录回输入页
                    onBack = { viewModel.navigateBack() }
                )
                is ResolveUiState.Loading -> LoadingContent()
                is ResolveUiState.Video -> VideoResultSection(
                    result = s.result,
                    modifier = Modifier.fillMaxSize(),
                    onDownloadMedia = { media -> viewModel.downloadVideoMedia(media) },
                    onDownloadAudio = { viewModel.downloadVideoAudio(it) },
                    onDownloadSelected = { viewModel.downloadVideoMedias(it) },
                    onBack = { viewModel.backToInput() },
                    onDownloadAll = { viewModel.downloadAllVideoMedia() },
                )

                else -> ResolveInputContent(
                    viewModel = viewModel,
                    scrollBehavior = scrollBehavior,
                    state = s,
                    link = link,
                    onLinkChange = { link = it },
                    pwd = pwd,
                    onPwdChange = {
                        pwd = it
                        pwdEdited = true
                    },
                    onClearLink = {
                        link = ""
                        pwd = ""
                        pwdEdited = false
                    },
                    onClearPwd = { pwd = "" },
                    clipboardContent = clipboardContent,
                    bookmarkViewModel = bookmarkViewModel,
                    onOpenBookmarks = onOpenBookmarks
                )
            }
        }
    }

    // 获取下载直链加载弹窗（转存/取链需要时间，避免无反馈）
    if (viewModel.isFetchingDownloadLink) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelFetchDownloadLink() },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelFetchDownloadLink() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("取消")
                }
            },
            confirmButton = {},
            title = { Text("获取下载链接") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    YunGetLoading(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "正在获取下载链接，请稍候…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // 下载直链弹窗
    downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = { viewModel.startDownload(link) },
            onDismiss = { viewModel.dismissDownloadDialog() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveInputContent(
    viewModel: ResolveViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    state: ResolveUiState,
    link: String,
    onLinkChange: (String) -> Unit,
    pwd: String,
    onPwdChange: (String) -> Unit,
    onClearLink: () -> Unit,
    onClearPwd: () -> Unit,
    clipboardContent: @Composable () -> Unit,
    /** 收藏 ViewModel：主页快捷方式网格的数据源与「管理」入口 */
    bookmarkViewModel: BookmarkViewModel,
    /** 点「管理」：切到收藏页 */
    onOpenBookmarks: () -> Unit
) {
    val context = LocalContext.current
    var showPassword by rememberSaveable { mutableStateOf(pwd.isNotBlank()) }
    LaunchedEffect(pwd) {
        if (pwd.isNotBlank()) showPassword = true
    }
    val pasteLink = {
        readClipboardSafely(context)?.takeIf { it.isNotBlank() }?.let { text ->
            onLinkChange(text)
            ShareLinkParser.parse(text)?.pwd?.let(onPwdChange)
        }
        Unit
    }
    val launchQrScanner = rememberQrScanLauncher { text ->
        onLinkChange(text)
        ShareLinkParser.parse(text)?.pwd?.let(onPwdChange)
        com.yunget.app.data.analytics.AnonymousAnalytics.track(
            "qr_scan_success",
            mapOf("kind" to if (ShareLinkParser.parse(text) != null) "share" else "other"),
        )
        SnackbarController.show("已识别二维码内容，确认后即可解析")
    }

    ResolveLandingContent(
        link = link,
        pwd = pwd,
        showPassword = showPassword,
        errorMessage = (state as? ResolveUiState.Error)?.message,
        onLinkChange = onLinkChange,
        onPwdChange = onPwdChange,
        onClearLink = onClearLink,
        onClearPwd = onClearPwd,
        onPasteLink = pasteLink,
        onScanQr = launchQrScanner,
        onTogglePassword = { showPassword = !showPassword },
        onResolve = { viewModel.startResolve(link, pwd) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        clipboardContent = clipboardContent,
        shortcutsContent = {
            HomeShortcutGrid(
                viewModel = bookmarkViewModel,
                onManage = onOpenBookmarks,
                onOpen = { bookmark ->
                    onLinkChange(bookmark.link)
                    onPwdChange(bookmark.pwd)
                    viewModel.startResolve(bookmark.link, bookmark.pwd)
                }
            )
        }
    )
}

/** 生产与截图共用的输入界面；数据、导航及业务操作全部由调用方注入。 */
@Composable
internal fun ResolveLandingContent(
    link: String,
    pwd: String,
    showPassword: Boolean,
    errorMessage: String?,
    onLinkChange: (String) -> Unit,
    onPwdChange: (String) -> Unit,
    onClearLink: () -> Unit,
    onClearPwd: () -> Unit,
    onPasteLink: () -> Unit,
    onScanQr: () -> Unit = {},
    onTogglePassword: () -> Unit,
    onResolve: () -> Unit,
    modifier: Modifier = Modifier,
    clipboardContent: @Composable () -> Unit = {},
    shortcutsContent: @Composable () -> Unit = {}
) {
    val ui = LocalUiMetrics.current
    val passwordRotation by animateFloatAsState(
        targetValue = if (showPassword) 180f else 0f,
        animationSpec = spatialFast(),
        label = "resolvePasswordArrow"
    )
    val isDirect = remember(link) {
        ShareLinkParser.parse(link) == null && looksLikeDirectUrl(link)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = ui.space(16.dp), vertical = ui.space(12.dp)),
        verticalArrangement = Arrangement.spacedBy(ui.space(12.dp))
    ) {
        item(key = "resolve_workspace", contentType = "workspace") {
            Surface(
                modifier = Modifier.fillMaxWidth().animateContentSize(spatialDefault()),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier.padding(ui.space(14.dp)),
                    verticalArrangement = Arrangement.spacedBy(ui.space(10.dp))
                ) {
                    Text(
                        text = "解析链接",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    clipboardContent()

                    OutlinedTextField(
                        value = link,
                        onValueChange = onLinkChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分享链接 / 直链") },
                        placeholder = { Text("粘贴分享链接、文案或直链") },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        trailingIcon = {
                            if (link.isNotEmpty()) {
                                IconButton(onClick = onClearLink, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "清空链接和提取码")
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(12.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = onPasteLink, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("粘贴链接")
                        }
                        TextButton(onClick = onScanQr, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("扫码")
                        }
                        TextButton(
                            onClick = onTogglePassword,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (pwd.isBlank()) "提取码（可选）" else "提取码 · 已填写")
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (showPassword) "收起提取码" else "展开提取码",
                                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = passwordRotation }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showPassword,
                        enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault()),
                        exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast())
                    ) {
                        OutlinedTextField(
                            value = pwd,
                            onValueChange = onPwdChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("提取码") },
                            placeholder = { Text("自动识别，也可手动填写") },
                            trailingIcon = {
                                if (pwd.isNotEmpty()) {
                                    IconButton(onClick = onClearPwd, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "清空提取码")
                                    }
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = errorMessage != null,
                        enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault()),
                        exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast())
                    ) {
                        if (errorMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(24.dp))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("解析未完成", style = MaterialTheme.typography.titleSmall)
                                        Text(errorMessage, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }

                    // 输入工作区仅此一个主操作；分享/直链沿用原有 ViewModel 分流。
                    Button(
                        onClick = onResolve,
                        modifier = Modifier.fillMaxWidth().heightIn(min = maxOf(48f, ui.space(52.dp).value).dp),
                        enabled = link.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = if (isDirect) Icons.Outlined.Download else Icons.Outlined.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isDirect) "开始下载" else "开始解析", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item(key = "resolve_shortcuts", contentType = "shortcuts") {
            shortcutsContent()
        }
    }
}

/** 官方 Compose Preview Screenshot 的入口：仅本地示例状态，无 ViewModel / Room 访问。 */
@Composable
internal fun ResolveLandingPreviewContent() {
    var previewLink by remember { mutableStateOf("https://pan.quark.cn/s/preview") }
    var previewPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        ResolveLandingContent(
            link = previewLink,
            pwd = previewPassword,
            showPassword = showPassword,
            errorMessage = null,
            onLinkChange = { previewLink = it },
            onPwdChange = { previewPassword = it },
            onClearLink = { previewLink = ""; previewPassword = "" },
            onClearPwd = { previewPassword = "" },
            onPasteLink = { previewLink = "https://pan.quark.cn/s/preview" },
            onTogglePassword = { showPassword = !showPassword },
            onResolve = {},
            shortcutsContent = {
                HomeShortcutGrid(
                    bookmarks = emptyList(),
                    onOpen = {},
                    onManage = {},
                    onRemove = {}
                )
            }
        )
    }
}

/** 全屏加载中（进入文件夹/解析中展示，避免闪回输入页） */
@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                YunGetLoading(modifier = Modifier.size(48.dp))
                Text("正在加载内容", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "正在解析链接或读取目录，请稍候…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 安全读取剪贴板最新文本；失败返回 null（部分 ROM 可能限制剪贴板访问） */
private fun readClipboardSafely(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}.getOrNull()

/** 平台名称（提示卡片展示） */
private fun platformLabel(platform: SharePlatform): String = when (platform) {
    SharePlatform.QUARK -> "夸克网盘"
    SharePlatform.UC -> "UC 网盘"
    SharePlatform.XUNLEI -> "迅雷网盘"
    SharePlatform.BAIDU -> "百度网盘"
    SharePlatform.C139 -> "139 网盘"
    SharePlatform.PAN123 -> "123云盘"
    SharePlatform.PAN115 -> "115网盘"
    SharePlatform.GUANGYA -> "光鸭云盘"
    SharePlatform.ILANZOU -> "蓝奏云优享版"
    SharePlatform.LANZOU -> "蓝奏云"
    SharePlatform.GITHUB -> "GitHub"
}

/**
 * 是否看上去是一个可直接下载的普通 http(s) 链接。
 * 仅用于剪贴板提示判定；真正的分流在 ResolveViewModel.startResolve 里做。
 */
private fun looksLikeDirectUrl(text: String): Boolean {
    val url = Regex("""https?://[^\s]+""").find(text.trim())?.value ?: return false
    return url.length > "https://".length
}

/** 剪贴板分享链接提示卡片：检测到分享链接/直链时，询问是否粘贴处理 */
@Composable
private fun ClipboardSuggestCard(
    platformName: String,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** true = 非网盘普通直链（粘贴后直接多线程下载，不做解析） */
    isDirectLink: Boolean = false
) {
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(spatialFast()),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isDirectLink) "剪贴板中有下载链接" else "剪贴板中有 $platformName 分享",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (isDirectLink) "可直接添加到下载任务" else "可直接粘贴并解析分享内容",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("忽略", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                TextButton(onClick = onPaste, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (isDirectLink) "粘贴并下载" else "粘贴并解析")
                }
            }
        }
    }
}
