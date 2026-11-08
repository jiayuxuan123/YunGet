package com.yunget.app.ui.resolve

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.prefs.SettingsRepository
import com.yunget.app.ui.components.ScrollToTopButton
import com.yunget.app.ui.components.YunGetLoading
import com.yunget.app.ui.preview.CloudPreview
import com.yunget.app.ui.screens.BaiduSaveSheet
import com.yunget.app.ui.screens.C139SaveSheet
import com.yunget.app.ui.screens.GuangYaSaveSheet
import com.yunget.app.ui.screens.Pan115SaveSheet
import com.yunget.app.ui.screens.Pan123SaveSheet
import com.yunget.app.ui.screens.SaveToCloudSheet
import com.yunget.app.ui.screens.UCSaveSheet
import com.yunget.app.ui.screens.XunleiSaveSheet
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.theme.spatialFast
import com.yunget.app.ui.viewmodel.BaiduCloudViewModel
import com.yunget.app.ui.viewmodel.C139CloudViewModel
import com.yunget.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunget.app.ui.viewmodel.Pan115CloudViewModel
import com.yunget.app.ui.viewmodel.Pan123CloudViewModel
import com.yunget.app.ui.viewmodel.QuarkCloudViewModel
import com.yunget.app.ui.viewmodel.ResolveViewModel
import com.yunget.app.ui.viewmodel.UCCoudViewModel
import com.yunget.app.ui.viewmodel.XunleiCloudViewModel

/** 百度非会员限速阈值：>300MB 提示 */
private const val BAIDU_LIMIT_BYTES = 300L * 1024 * 1024

/**
 * 分享详情页：展示分享标题与文件列表，支持进入文件夹、点击文件获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDetailScreen(
    session: ShareSession,
    files: List<ShareFile>,
    viewModel: ResolveViewModel,
    /** 夸克云盘浏览 ViewModel（转存目录选择用；与网盘页同一实例） */
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
    guangYaCloudViewModel: GuangYaCloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    /** 顶部左上角返回：退出文件页回到输入页（输入框内容保留） */
    onExit: () -> Unit,
    /** 列表「返回上一级」：子目录回上级，根目录回输入页 */
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pathNames = viewModel.pathNames
    // 百度 >300MB 限速提示（解析页百度分享下载）
    val context = LocalContext.current
    val baiduSettings = remember { SettingsRepository(context) }
    var baiduLimitDismissed by remember { mutableStateOf(baiduSettings.baiduLimitHintDismissed) }
    var showBaiduLimitDialog by remember { mutableStateOf(false) }
    var pendingBaiduAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** 百度分享下载前检查：>300MB 且未忽略时弹提示，确认后执行 */
    fun checkBaiduLimit(file: ShareFile, proceed: () -> Unit) {
        if (viewModel.isBaidu && !baiduLimitDismissed && file.fsize > BAIDU_LIMIT_BYTES) {
            pendingBaiduAction = proceed
            showBaiduLimitDialog = true
        } else {
            proceed()
        }
    }
    // 多选时先退出选择；普通状态保持目录返回契约。
    BackHandler {
        if (viewModel.multiSelectMode) viewModel.exitMultiSelect() else onBack()
    }
    val listState = rememberLazyListState()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "share_summary", contentType = "summary") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().animateContentSize(spatialDefault()),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (viewModel.multiSelectMode) viewModel.exitMultiSelect() else onExit() },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = if (viewModel.multiSelectMode) "取消选择" else "返回解析首页"
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        AnimatedContent(
                                            targetState = if (viewModel.multiSelectMode) "已选 ${viewModel.selected.size} 项" else session.title.ifBlank { "分享内容" },
                                            transitionSpec = {
                                                fadeIn(effectsDefault()).togetherWith(fadeOut(effectsFast()))
                                                    .using(SizeTransform(sizeAnimationSpec = { _, _ -> spatialFast() }))
                                            },
                                            label = "shareSelectionTitle"
                                        ) { title ->
                                            Text(
                                                text = title,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = if (viewModel.multiSelectMode) {
                                                if (viewModel.selected.size == files.size) "已全选" else "点击文件继续选择"
                                            } else "共 ${files.size} 项 · 长按文件可多选",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (viewModel.multiSelectMode) {
                                    TextButton(
                                        onClick = { viewModel.toggleSelectAll(files) },
                                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                                    ) {
                                        Text(if (viewModel.selected.size == files.size) "取消全选" else "全选")
                                    }
                                } else {
                                    CrumbBar(
                                        rootTitle = session.title.ifBlank { "分享内容" },
                                        pathNames = pathNames,
                                        onNavigate = { viewModel.navigateToLevel(it) }
                                    )
                                }
                            }
                        }
                    }
                    if (pathNames.isNotEmpty()) {
                        item(key = "share_parent", contentType = "navigation") {
                            BackToParentItem(onClick = onBack)
                        }
                    }
                    if (files.isEmpty()) {
                        item(key = "share_empty", contentType = "empty") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("此目录为空", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    items(
                        items = files,
                        key = { "share_file:${it.fid}" },
                        contentType = { if (it.isdir) "folder" else "file" }
                    ) { file ->
                        ShareFileRow(
                            file = file,
                            modifier = Modifier.animateItem(
                                fadeInSpec = effectsDefault(),
                                placementSpec = spatialDefault(),
                                fadeOutSpec = effectsFast()
                            ),
                            onClick = {
                                if (viewModel.multiSelectMode) {
                                    viewModel.toggleSelect(file)
                                } else if (file.isdir) {
                                    viewModel.openFolder(file)
                                } else {
                                    checkBaiduLimit(file) { viewModel.fetchDownloadLink(file) }
                                }
                            },
                            onPreview = if (!file.isdir && CloudPreview.canPreview(file)) ({ viewModel.openPreview(file) }) else null,
                            onSave = if (!viewModel.multiSelectMode && viewModel.canSave) {
                                { viewModel.requestSave(file) }
                            } else null,
                            onLongClick = if (!viewModel.multiSelectMode) {
                                { viewModel.enterMultiSelect(file) }
                            } else null,
                            selected = viewModel.selected.contains(file),
                            showCheckbox = viewModel.multiSelectMode
                        )
                    }
                }
                ScrollToTopButton(
                    listState = listState,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                )
            }
            // 栏位参与测量，不叠在列表上；大字体换行后也不会遮住最后一项。
            AnimatedVisibility(
                visible = viewModel.multiSelectMode,
                enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault()),
                exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast())
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (viewModel.canSave) {
                            OutlinedButton(
                                onClick = { viewModel.batchSaveToCloud() },
                                enabled = viewModel.selected.isNotEmpty(),
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("转存所选")
                            }
                        }
                        Button(
                            onClick = {
                                val hasBig = viewModel.selected.any { it.fsize > BAIDU_LIMIT_BYTES }
                                if (viewModel.isBaidu && !baiduLimitDismissed && hasBig) {
                                    pendingBaiduAction = { viewModel.batchDownload() }
                                    showBaiduLimitDialog = true
                                } else {
                                    viewModel.batchDownload()
                                }
                            },
                            enabled = viewModel.selected.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("下载所选（${viewModel.selected.size}）")
                        }
                    }
                }
            }
        }
    }

    // 百度 >300MB 限速提示弹窗（解析页百度分享下载，可勾选不再显示）
    if (showBaiduLimitDialog) {
        var neverShow by remember { mutableStateOf(baiduLimitDismissed) }
        AlertDialog(
            onDismissRequest = { showBaiduLimitDialog = false },
            title = { Text("下载大文件提示") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "百度网盘非会员超过300MB会被限速，下载速度可能较慢。是否继续下载？",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = neverShow,
                            role = Role.Checkbox,
                            onValueChange = { neverShow = it }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = neverShow, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("不再显示此提示", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        showBaiduLimitDialog = false
                        baiduSettings.baiduLimitHintDismissed = neverShow
                        baiduLimitDismissed = neverShow
                        pendingBaiduAction?.invoke()
                        pendingBaiduAction = null
                    }
                ) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { showBaiduLimitDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            }
        )
    }

    // 批量处理中：加载弹窗（批量下载显示获取进度，如 "正在获取下载链接 2/5"；可中断）
    if (viewModel.isBatchWorking) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBatch() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("中断", color = MaterialTheme.colorScheme.error)
                }
            },
            title = { Text("批量处理中") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    YunGetLoading(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = viewModel.batchProgress?.let { "正在获取下载链接 $it" }
                            ?: "正在批量处理，请稍候…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // 转存弹窗：浏览网盘目录并保存（单文件转存；夸克/迅雷/百度按平台选目录选择器）
    if (viewModel.saveTarget != null) {
        when {
            viewModel.isSaveXunlei -> XunleiSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = xunleiCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveBaidu -> BaiduSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = baiduCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveC139 -> C139SaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = c139CloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveUC -> UCSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = ucCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSavePan123 -> Pan123SaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = pan123CloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            // 115 与光鸭的 ViewModel 早就在 ResolveScreen 里了，只是一直没接到这一层 ——
            // 结果这两家的"转存"会掉进下面的 else，弹出一个**夸克**的目录选择器（点下去必然失败）。
            viewModel.isSavePan115 -> Pan115SaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = pan115CloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveGuangYa -> GuangYaSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = guangYaCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            else -> SaveToCloudSheet(
                resolveViewModel = viewModel,
                cloudViewModel = quarkCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
        }
    }
}

@Composable
internal fun BackToParentItem(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("返回上一级", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
    }
}

/** 可横向滚动的目录路径；每个可点击层级均有 48dp 触摸目标。 */
@Composable
internal fun CrumbBar(
    rootTitle: String,
    pathNames: List<String>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val crumbs = remember(rootTitle, pathNames) { listOf(rootTitle.ifBlank { "根目录" }) + pathNames }
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size, crumbs.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { i, name ->
            if (i < crumbs.lastIndex) {
                TextButton(
                    onClick = { onNavigate(i) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            } else {
                Row(
                    modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShareFileRow(
    file: ShareFile,
    onClick: () -> Unit,
    /** 非空时行尾显示「转存」按钮 */
    onSave: (() -> Unit)? = null,
    /** 非空时行尾显示「更多」按钮（打开文件操作菜单） */
    onMore: (() -> Unit)? = null,
    onPreview: (() -> Unit)? = null,
    /** 长按进入多选（多选模式下为 null） */
    onLongClick: (() -> Unit)? = null,
    /** 多选模式：是否选中 */
    selected: Boolean = false,
    /** 是否显示行首复选框（仅多选模式列表传 true；移动/转存等选择器不显示） */
    showCheckbox: Boolean = false,
    /**
     * 行形状。网盘页的列表组传 `listGroupShape(index, count)`
     * （首/末项大圆角、中间项小圆角，形成「整组一张卡片」的观感）；
     * 不传时为整行统一大圆角。
     */
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    /** 列表项动画等（调用方传入 Modifier.animateItem()） */
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = effectsFast(),
        label = "shareFileSelection"
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spatialFast())
            .clip(shape)
            .semantics { if (showCheckbox) this.selected = selected }
            .combinedClickable(
                onClick = onClick,
                onClickLabel = if (showCheckbox) "切换选择" else if (file.isdir) "打开文件夹" else "获取下载链接",
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "选择文件" else null,
                role = if (showCheckbox) Role.Checkbox else Role.Button
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showCheckbox) {
                    // 整行统一处理点击，避免复选框和行同时切换两次。
                    Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(48.dp))
                }
                FileLeadingIcon(file)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 完整名称静态换行；列表中不再运行永久 marquee。
                    Text(text = file.fname, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (file.isdir) "文件夹" else formatSize(file.fsize),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (file.isdir) Icons.Outlined.ChevronRight else Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onPreview != null || onSave != null || onMore != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onPreview != null) {
                        IconButton(onClick = onPreview, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Visibility, contentDescription = "预览${file.fname}")
                        }
                    }
                    if (onSave != null) {
                        IconButton(onClick = onSave, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.SaveAlt, contentDescription = "转存${file.fname}", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (onMore != null) {
                        IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "${file.fname}的更多操作")
                        }
                    }
                }
            }
        }
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}
