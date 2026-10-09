/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunget.app.data.db.PluginInstalledEntity
import com.yunget.app.data.plugin.InstalledPlugin
import com.yunget.app.data.plugin.JsEngineProbe
import com.yunget.app.data.plugin.PluginRepository
import com.yunget.app.data.plugin.PluginRuntime
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.resolve.formatSize
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import dev.turbodl.plugin.js.JsScriptPlugin
import dev.turbodl.plugin.js.JsScriptValidator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 插件页（设置 → 插件）：JS 脚本插件的导入、启停、运行状态、详情与卸载全在这一页。
 *
 * 本页只做**展示与操作**：脚本落盘与入库是 [PluginRepository] 的事，把脚本交给引擎并让它跑起来是
 * [PluginRuntime] 的事，这里一次都不碰文件与引擎内部。所以本文件里没有任何"自己判断能不能装"的逻辑 ——
 * 装之前能不能用由 `validate` 回答，装不装得下由 `install*` 回答，跑不跑得起来由引擎回答。
 *
 * ## 页面的三段（按重要性自上而下）
 *
 * 1. **引擎状态**：进页面就跑一次 [JsEngineProbe]。插件跑在这个引擎上，引擎起不来所有插件都是摆设 ——
 *    所以这条在最上面，且**不可用时用错误色并直说后果**（插件无法运行），而不是只报哪个检查项没过。
 * 2. **已安装列表**：来源徽标 + 信任等级徽标 + 运行状态（从 `livePlugins()` 按 id 取）+ 启停开关 + 失败原因。
 * 3. **底部入口**：从文件导入 / 粘贴脚本（带校验）/ 引擎自检；有泄漏运行时另给一条警告。
 *
 * ## 运行状态为什么是轮询而不是 Flow
 *
 * `PluginRuntime.livePlugins()` 是引擎侧**同步快照**，不是 Flow（引擎没有状态回调给宿主侧）。
 * 而列表要显示的内存占用、在飞数量是会一直变的，所以这里按 1.5s 取一次快照 ——
 * 取快照本身只是读几个字段，代价可以忽略。
 *
 * ## 信任等级必须一眼看出差别
 *
 * 四级用四种视觉（官方=主色实心、已验证=次色容器、社区=中性、未验证=错误色容器）。
 * 用户决定要不要启用一个来路不明的脚本时，"这是谁给的、可信到什么程度"就是主要依据，
 * 不能四个等级长得一模一样。
 *
 * @param onBack 返回上一级（调用方提供）
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PluginRepository(context) }
    // 运行时复用**进程内唯一**那一个（理由见 [sharedPluginRuntime] 的注释）；
    // remember 保证同一次组合里也只取一次
    val runtime = remember { sharedPluginRuntime(context) }

    // Flow 要记住：listInstalled() 每次都返回新 Flow，直接写进 collectAsStateWithLifecycle
    // 会让它每次重组都重启一次收集。
    val installedFlow = remember(repo) { repo.listInstalled() }
    val installed by installedFlow.collectAsStateWithLifecycle(emptyList())

    // 引擎侧快照（同步取，见类注释）
    var livePlugins by remember { mutableStateOf(emptyList<JsScriptPlugin.Info>()) }
    var leakedCount by remember { mutableStateOf(0) }
    // 正在切换的插件：切的过程中开关不能再点，避免"启用"与"停用"两个请求交错
    var busyIds by remember { mutableStateOf(emptySet<String>()) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showPasteSheet by remember { mutableStateOf(false) }
    // 编辑器（新建 / 编辑已装插件）。null = 不显示；空串 = 新建；非空 = 编辑这个 id
    var editorFor by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<JsEngineProbe.Result?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeNonce by remember { mutableStateOf(0) }
    // 手动点「引擎自检」时用 Snackbar 报结论、并把逐项结果展开；进页面的自动检测安静地跑（结论就在卡上）
    var announceProbe by remember { mutableStateOf(false) }
    // 逐项结果的展开状态提升到这里：底部的「引擎自检」要能把它展开给用户看。
    // null = 用户没手动点过（此时跟随结论：不可用就自动展开，那正是要看原因的时候）
    var probeStepsExpanded by remember { mutableStateOf<Boolean?>(null) }

    val refreshLive: () -> Unit = {
        livePlugins = runtime.livePlugins()
        leakedCount = runtime.leakedRuntimeCount()
    }

    // 引擎自检：probeAndLog 内部已切到 IO，并会把结论写进日志（随「导出日志」回传）
    LaunchedEffect(probeNonce) {
        probing = true
        val result = JsEngineProbe.probeAndLog(context)
        probe = result
        probing = false
        if (announceProbe) {
            announceProbe = false
            SnackbarController.show(
                (if (result.available) "JS 引擎可用：" else "JS 引擎不可用：") + result.detail
            )
        }
    }

    // 进页面把已启用的插件装进引擎，并把加载失败的那些报出来
    LaunchedEffect(Unit) {
        val results = runtime.start()
        refreshLive()
        val failed = results.filterValues { it != null }
        if (failed.isNotEmpty()) {
            SnackbarController.show("${failed.size} 个插件没能加载，原因见列表")
        }
    }

    // 运行状态轮询（见类注释）
    LaunchedEffect(Unit) {
        while (true) {
            refreshLive()
            delay(1500)
        }
    }

    /** 启用 = 写库 + 装载；停用 = 卸载 + 写库。装载返回值非空就是失败原因，要弹给用户看。 */
    val togglePlugin: (InstalledPlugin, Boolean) -> Unit = { plugin, enabled ->
        if (plugin.id !in busyIds) {
            busyIds = busyIds + plugin.id
            scope.launch {
                repo.setEnabled(plugin.id, enabled).fold(
                    onSuccess = {
                        if (enabled) {
                            val error = runtime.load(plugin.id)
                            SnackbarController.show(
                                if (error == null) {
                                    "已启用「${plugin.name}」"
                                } else {
                                    "「${plugin.name}」加载失败：$error"
                                }
                            )
                        } else {
                            // 停用只从引擎里摘掉，不删脚本：用户随时可以再打开
                            runtime.unload(plugin.id)
                            SnackbarController.show("已停用「${plugin.name}」")
                        }
                    },
                    onFailure = { t ->
                        SnackbarController.show("切换失败：${t.message ?: t.javaClass.simpleName}")
                    }
                )
                busyIds = busyIds - plugin.id
                refreshLive()
            }
        }
    }

    // .js 的 MIME 在各家文件管理器里不统一，用 */* 让用户自己挑文件
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                repo.installFromFile(uri).fold(
                    onSuccess = { id ->
                        val error = runtime.load(id)
                        refreshLive()
                        SnackbarController.show(
                            if (error == null) "已安装并启用：$id" else "已安装 $id，但加载失败：$error"
                        )
                    },
                    onFailure = { t ->
                        SnackbarController.show("导入失败：${t.message ?: t.javaClass.simpleName}")
                    }
                )
            }
        }
    }

    // 详情跟着库里那一行走：卸载后记录没了，详情自己就关了
    val detailPlugin = detailId?.let { id -> installed.firstOrNull { it.id == id } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("插件", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "engine") {
                EngineStatusCard(
                    probe = probe,
                    probing = probing,
                    stepsExpanded = probeStepsExpanded,
                    // 从**生效值**翻转（用户没点过时生效值是"不可用就展开"）
                    onToggleSteps = {
                        probeStepsExpanded = !(probeStepsExpanded ?: (probe?.available == false))
                    },
                    onRecheck = {
                        announceProbe = true
                        probeNonce++
                    }
                )
            }

            if (leakedCount > 0) {
                item(key = "leak") {
                    LeakWarningCard(leakedCount)
                }
            }

            item(key = "label") {
                PluginsSectionLabel(
                    if (installed.isEmpty()) "已安装插件" else "已安装插件 · ${installed.size} 个"
                )
            }

            if (installed.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没装插件。可以从文件导入一个 .js 脚本，或者直接粘贴脚本内容 —— " +
                            "粘贴时先校验一次，能看出它会注册什么、要哪些能力，再决定装不装。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // 前缀是必须的：插件 id 的合法字符集里有纯小写字母，真出现一个叫 "engine" 或 "label" 的
            // 插件就会和上面的段 key 撞名（LazyColumn 撞 key 是直接抛异常）
            items(installed, key = { "plugin:" + it.id }) { plugin ->
                PluginRow(
                    plugin = plugin,
                    live = livePlugins.firstOrNull { it.id == plugin.id },
                    busy = plugin.id in busyIds,
                    onOpen = { detailId = plugin.id },
                    onToggle = { enabled -> togglePlugin(plugin, enabled) }
                )
            }

            item(key = "actions") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                // 选择器 Activity 缺失（ROM 精简/被冻结）会抛 ActivityNotFoundException，
                                // 不兜住就是点击即崩 —— 与设置页选目录同一处理
                                runCatching { filePicker.launch(arrayOf("*/*")) }
                                    .onFailure { SnackbarController.show("本机没有可用的文件选择器") }
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("从文件导入", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            // 编辑器是"从零写"的入口；粘贴是"别人给的脚本直接装"的入口。
                            // 两者都要，因为这两种场景确实不同（自己写要看校验与行号，
                            // 别人给的只想快速装上并看清它要什么权限）。
                            onClick = { editorFor = null; showEditor = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("写脚本", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = { showPasteSheet = true },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("粘贴脚本", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    // 引擎自检：结论走 Snackbar，逐项结果在上面那张卡里展开给用户看
                    TextButton(
                        onClick = {
                            announceProbe = true
                            probeStepsExpanded = true
                            probeNonce++
                        }
                    ) {
                        Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("引擎自检")
                    }
                }
            }
        }
    }

    if (showPasteSheet) {
        PasteScriptSheet(
            validate = { text -> runtime.validate(text) },
            install = { text -> repo.installFromText(text, null) },
            onInstalled = { id ->
                showPasteSheet = false
                scope.launch {
                    val error = runtime.load(id)
                    refreshLive()
                    SnackbarController.show(
                        if (error == null) "已安装并启用：$id" else "已安装 $id，但加载失败：$error"
                    )
                }
            },
            onDismiss = { showPasteSheet = false }
        )
    }

    // 编辑器：新建（editorFor=null）或编辑已装插件（editorFor=<id>）
    if (showEditor) {
        PluginEditorScreen(
            onBack = { showEditor = false; editorFor = null },
            initialPluginId = editorFor
        )
    }

    detailPlugin?.let { plugin ->
        PluginDetailSheet(
            plugin = plugin,
            live = livePlugins.firstOrNull { it.id == plugin.id },
            busy = plugin.id in busyIds,
            onToggle = { enabled -> togglePlugin(plugin, enabled) },
            onRequestUninstall = { pendingUninstall = plugin },
            onEdit = { id -> detailId = null; editorFor = id; showEditor = true },
            onDismiss = { detailId = null }
        )
    }

    // 卸载是不可逆的（脚本文件会被删掉），必须二次确认，且文案要把这件事说清楚
    pendingUninstall?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载插件") },
            text = {
                Column {
                    Text("会删除「${target.name}」以及它的脚本文件，之后不再加载。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "想再用回来，得重新导入一次脚本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    pendingUninstall = null
                    busyIds = busyIds + id
                    scope.launch {
                        // 先把它从引擎里摘掉再删文件：反过来的话，脚本文件已经没了而实例还挂着
                        runtime.unload(id)
                        repo.uninstall(id).fold(
                            onSuccess = { SnackbarController.show("已卸载「${target.name}」") },
                            onFailure = { t ->
                                SnackbarController.show("卸载失败：${t.message ?: t.javaClass.simpleName}")
                            }
                        )
                        busyIds = busyIds - id
                        refreshLive()
                    }
                }) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}

// ---------------------------------------------------------------- 顶部：引擎状态

/**
 * 引擎状态卡：可用与否 + 结论 + 逐项结果。
 *
 * 不可用时整卡换成错误色系，并**直说后果**（插件无法运行）—— 用户点进来是想装插件，
 * 这时最该知道的是"现在装了也没用"，而不是哪个检查项失败了。
 */
@Composable
private fun EngineStatusCard(
    probe: JsEngineProbe.Result?,
    probing: Boolean,
    /** null = 跟随结论（不可用时自动展开）；非 null = 用户手动定过 */
    stepsExpanded: Boolean?,
    onToggleSteps: () -> Unit,
    onRecheck: () -> Unit
) {
    val failed = probe != null && !probe.available
    val expanded = stepsExpanded ?: failed

    val container = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val onContainer = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subColor = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (!failed) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (failed) Icons.Outlined.Error else Icons.Outlined.Science,
                        contentDescription = null,
                        tint = if (failed) subColor else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            probing && probe == null -> "JS 引擎：检测中…"
                            probe == null -> "JS 引擎：未检测"
                            probe.available -> "JS 引擎：可用"
                            else -> "JS 引擎：不可用"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = onContainer
                    )
                    probe?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it.detail, style = MaterialTheme.typography.bodySmall, color = subColor)
                    }
                    if (failed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "插件没法运行 —— 已装好的会一直停在「未运行」，先解决上面的问题再试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = subColor
                        )
                    }
                }
                if (probing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            probe?.steps?.takeIf { it.isNotEmpty() }?.let { steps ->
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onToggleSteps) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("逐项结果")
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(effectsDefault()),
                    exit = fadeOut(effectsFast())
                ) {
                    Column {
                        steps.forEach { (name, ok) ->
                            Text(
                                text = (if (ok) "✓ " else "✗ ") + name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) subColor else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRecheck,
                enabled = !probing,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (probing) "检测中…" else "重新检测", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 泄漏运行时警告：脚本没能在卸载时停干净，引擎拒绝对它关闭（引擎的刻意取舍，见 PluginRuntime 注释） */
@Composable
private fun LeakWarningCard(count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(14.dp)
    ) {
        Text(
            text = "有 $count 个脚本在卸载时还在执行，运行时没能关闭",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "引擎会等它把手上的活干完再释放，过一会儿或重启应用即可恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

// ---------------------------------------------------------------- 列表项

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    live: JsScriptPlugin.Info?,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = plugin.id + if (plugin.version.isBlank()) "" else " · v${plugin.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onToggle,
                    enabled = !busy
                )
            }

            Spacer(Modifier.height(8.dp))
            BadgeRow(sourceKind = plugin.sourceKind, trustLevel = plugin.trustLevel)

            Spacer(Modifier.height(6.dp))
            PluginRunState(plugin = plugin, live = live)

            if (plugin.entity.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plugin.entity.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 来源徽标 + 信任等级徽标（信任等级用四套不同配色，见文件头注释） */
@Composable
private fun BadgeRow(sourceKind: String, trustLevel: String) {
    val (trustContainer, trustContent) = trustBadgeColors(trustLevel)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 来源用中性色：它回答"从哪来"，不回答"可不可信"，别和信任徽标抢注意力
        PluginBadge(
            text = sourceKindLabel(sourceKind),
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PluginBadge(text = trustLabel(trustLevel), container = trustContainer, content = trustContent)
    }
}

/** 运行状态一行：引擎视角的状态 + 注册项数 + 内存占用；不在引擎里就说清为什么 */
@Composable
private fun PluginRunState(plugin: InstalledPlugin, live: JsScriptPlugin.Info?) {
    val text: String
    val color: Color
    when {
        !plugin.scriptExists -> {
            text = "脚本文件不在了（可能被系统清理），要重新导入一次"
            color = MaterialTheme.colorScheme.error
        }

        live == null -> {
            text = if (plugin.enabled) "未运行（引擎里没有它）" else "已停用"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }

        else -> {
            val (stateLabel, stateColor) = runtimeStateLabel(live.state)
            text = "$stateLabel · 注册 ${live.registrations.size} 项 · 占用 ${formatSize(live.memoryUsedBytes)}"
            color = stateColor
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** 小徽标：来源与信任等级共用（配色由调用方给，两者必须看起来不一样） */
@Composable
private fun PluginBadge(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PluginsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

// ---------------------------------------------------------------- 粘贴脚本

/**
 * 粘贴脚本弹窗：先校验，再安装。
 *
 * 「校验」这一步是**给用户的安全感**：装之前就能看到脚本会注册什么、要哪些能力、多少行，
 * 所以结果整块展示而不是只给个"通过/失败"。安装按钮只在**当前这份文本**校验通过后才可点 ——
 * 文本一改就把结果清掉，避免出现"校验过的是 A、装上去的是 B"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteScriptSheet(
    validate: suspend (String) -> JsScriptValidator.Report,
    install: suspend (String) -> Result<String>,
    onInstalled: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var script by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<JsScriptValidator.Report?>(null) }
    // 校验通过时的那份文本；与当前文本不一致就要求重新校验
    var validatedText by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }

    val installable = report?.ok == true && validatedText == script && script.isNotBlank() && !installing

    ModalBottomSheet(
        // 安装进行中不许下滑关闭：脚本已经写进去了，弹窗先消失会让用户以为没装
        onDismissRequest = { if (!installing) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Text("粘贴脚本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "把 .js 脚本内容贴进来。插件身份靠脚本自己声明（plugin.defineMeta({ id, name, version })）；" +
                    "没写 id 就用文件名兜底，那样换个脚本容易覆盖到同一个 id。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = script,
                onValueChange = {
                    script = it
                    // 文本变了，上一份校验结果就不再对应要装的东西
                    report = null
                    validatedText = null
                },
                label = { Text("脚本内容") },
                placeholder = { Text("plugin.defineMeta({ id: 'parser.example', name: '示例', version: '1.0' })") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                enabled = !installing
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            validating = true
                            val text = script
                            val result = validate(text)
                            report = result
                            validatedText = text.takeIf { result.ok }
                            validating = false
                        }
                    },
                    enabled = script.isNotBlank() && !validating && !installing,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (validating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (validating) "校验中…" else "校验", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = {
                        scope.launch {
                            installing = true
                            install(script).fold(
                                onSuccess = { id ->
                                    installing = false
                                    onInstalled(id)
                                },
                                onFailure = { t ->
                                    installing = false
                                    SnackbarController.show("安装失败：${t.message ?: t.javaClass.simpleName}")
                                }
                            )
                        }
                    },
                    enabled = installable,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (installing) "安装中…" else "安装", style = MaterialTheme.typography.labelLarge)
                }
            }

            report?.let { ValidationReport(it) }
        }
    }
}

/** 校验结果整块展示：结论 + 语法错误 + 会注册什么 + 要哪些能力 + 提醒 */
@Composable
private fun ValidationReport(report: JsScriptValidator.Report) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (report.ok) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
            .padding(12.dp)
    ) {
        Text(
            text = if (report.ok) "语法通过 · ${report.lineCount} 行" else "语法不通过",
            style = MaterialTheme.typography.titleSmall,
            color = if (report.ok) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
        )
        // 语法错误在引擎侧是可空的（`ok = true` 时为 null），所以这里显式兜空串再判断
        val syntaxError = report.syntaxError.orEmpty()
        if (!report.ok && syntaxError.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = syntaxError,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (report.registrationKinds.isEmpty()) {
                "会注册：没看到注册调用 —— 装上去可能什么都不会做"
            } else {
                "会注册：${report.registrationKinds.joinToString("、")}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (report.declaredPermissions.isEmpty()) {
                "声明能力：无（默认只有 http / crypto / log / time）"
            } else {
                "声明能力：${report.declaredPermissions.joinToString("、")}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        report.warnings.forEach { warning ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "提醒：$warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ---------------------------------------------------------------- 详情

/**
 * 插件详情（底部弹窗）：列表里放不下的都在这里 —— 脚本路径与摘要、声明与实际授予的能力、
 * 引擎侧现况（状态 / ABI / 注册项 / 内存 / 在飞）、两次时间戳、清单元数据。
 *
 * 卸载不在这个弹窗里直接做：它是不可逆的，二次确认由页面层的 AlertDialog 负责
 * （弹窗套弹窗在 M3 里体验很差，这一点本仓库其它页面已有先例）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailSheet(
    plugin: InstalledPlugin,
    live: JsScriptPlugin.Info?,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestUninstall: () -> Unit,
    /** 打开编辑器改这个插件的脚本。 */
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var manifestExpanded by remember { mutableStateOf(false) }
    val entity = plugin.entity

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            BadgeRow(sourceKind = plugin.sourceKind, trustLevel = plugin.trustLevel)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        live == null -> if (plugin.enabled) "未运行" else "已停用"
                        else -> {
                            val (stateLabel, _) = runtimeStateLabel(live.state)
                            "$stateLabel · ABI ${live.abiVersion} · 在飞 ${live.inFlight}"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = plugin.enabled, onCheckedChange = onToggle, enabled = !busy)
            }

            if (live != null) {
                DetailField("已注册", live.registrations.joinToString("、").ifBlank { "无" })
                DetailField("实际授予能力", live.grantedPermissions.joinToString("、").ifBlank { "无" })
                DetailField("内存占用", formatSize(live.memoryUsedBytes))
            }

            Spacer(Modifier.height(8.dp))
            DetailField("插件 id", entity.id, mono = true)
            DetailField("版本", entity.version.ifBlank { "未声明" })
            DetailField("来源", entity.sourceUri.ifBlank { "未记录" }, mono = true)
            DetailField("脚本路径", entity.scriptPath, mono = true)
            DetailField("脚本摘要", entity.scriptSha256.ifBlank { "未记录" }, mono = true)
            DetailField(
                label = "脚本文件",
                value = if (plugin.scriptExists) "在" else "不在了（可能被系统清理，要重新导入）",
                color = if (plugin.scriptExists) null else MaterialTheme.colorScheme.error
            )
            DetailField(
                label = "声明能力",
                value = plugin.declaredPermissions.joinToString("、")
                    .ifBlank { "无（默认只有 http / crypto / log / time）" }
            )
            DetailField("安装时间", formatTimestamp(entity.installedAt))
            DetailField("最近变更", formatTimestamp(entity.updatedAt))
            if (entity.lastError.isNotBlank()) {
                DetailField("最近一次错误", entity.lastError, color = MaterialTheme.colorScheme.error)
            }

            TextButton(onClick = { manifestExpanded = !manifestExpanded }) {
                Icon(
                    imageVector = if (manifestExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("清单元数据")
            }
            AnimatedVisibility(
                visible = manifestExpanded,
                enter = fadeIn(effectsDefault()),
                exit = fadeOut(effectsFast())
            ) {
                if (entity.manifestJson.isBlank()) {
                    Text(
                        text = "没有清单原文（从文件或粘贴导入的脚本通常没有，市场安装的才有）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = entity.manifestJson,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onEdit(entity.id) },
                enabled = !busy && plugin.scriptExists,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (plugin.scriptExists) "编辑脚本" else "脚本已丢失，无法编辑")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRequestUninstall,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("卸载插件")
            }
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("关闭")
            }
        }
    }
}

/** 详情里的一行「标签 + 值」；[mono] 的用等宽并可整段选中复制（路径/摘要排查时全靠它） */
@Composable
private fun DetailField(
    label: String,
    value: String,
    mono: Boolean = false,
    color: Color? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val valueStyle = if (mono) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        }
        val valueColor = color ?: MaterialTheme.colorScheme.onSurface
        if (mono) {
            SelectionContainer { Text(text = value, style = valueStyle, color = valueColor) }
        } else {
            Text(text = value, style = valueStyle, color = valueColor)
        }
    }
}

// ---------------------------------------------------------------- 文案与视觉映射

/** 来源徽标文案（取值见 [PluginInstalledEntity] 的 SOURCE_* 常量） */
internal fun sourceKindLabel(kind: String): String = when (kind) {
    PluginInstalledEntity.SOURCE_FILE -> "本地文件"
    PluginInstalledEntity.SOURCE_PASTE -> "粘贴"
    PluginInstalledEntity.SOURCE_MARKET -> "市场"
    else -> "来源未知"
}

/**
 * 信任等级文案与配色。
 *
 * 四级必须**一眼看出差别**（设计上的硬要求）：官方=主色实心、已验证=次色容器、社区=中性、
 * 未验证=错误色容器。判断一个第三方脚本能不能信，用户能用的就这一眼。
 *
 * 插件市场页（[PluginMarketScreen]）也用它：同一套等级在两页必须长得一模一样，
 * 各写一份配色迟早会走偏。
 */
internal fun trustLabel(level: String): String = when (level) {
    PluginInstalledEntity.TRUST_OFFICIAL -> "官方"
    PluginInstalledEntity.TRUST_VERIFIED -> "已验证"
    PluginInstalledEntity.TRUST_COMMUNITY -> "社区"
    PluginInstalledEntity.TRUST_UNTRUSTED -> "未验证"
    // 库里只允许存白名单取值；真遇到脏值按最不可信展示，别给用户一个"看着挺安全"的默认
    else -> "未验证"
}

@Composable
internal fun trustBadgeColors(level: String): Pair<Color, Color> = when (level) {
    PluginInstalledEntity.TRUST_OFFICIAL ->
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary

    PluginInstalledEntity.TRUST_VERIFIED ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer

    PluginInstalledEntity.TRUST_COMMUNITY ->
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant

    else ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

/** 引擎侧生命周期状态（取值见引擎的 `JsLifecycleState`，另有 DISPOSED / UNSTARTED 兜底） */
@Composable
private fun runtimeStateLabel(state: String): Pair<String, Color> {
    val running = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    // LEAKED 是"卸载时脚本还在跑、引擎拒绝关闭"：这是异常但不是运行中，用错误色提醒
    val abnormal = MaterialTheme.colorScheme.error
    return when (state) {
        "ACTIVE" -> "运行中" to running
        "STOPPING" -> "正在停止" to idle
        "DRAINING" -> "正在收尾（等手上的调用跑完）" to idle
        "DISPOSED" -> "已卸载" to idle
        "UNSTARTED" -> "未运行" to idle
        "LEAKED" -> "没关干净" to abnormal
        else -> state to idle
    }
}

private fun formatTimestamp(millis: Long): String =
    if (millis <= 0L) "未记录" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

// ---------------------------------------------------------------- 运行时实例

/** 进程内唯一的插件运行时（理由见 [sharedPluginRuntime]） */
private var pluginRuntimeRef: PluginRuntime? = null

/**
 * 拿插件运行时 —— **整个进程只有一个**，而不是每次进页面 new 一个。
 *
 * 为什么不能只是 `remember { PluginRuntime(context) }`：`PluginRuntime` 每次装载都会新建一个
 * `PluginHost`，脚本实例活在**那个宿主**里。如果本页每次进都 new 一个，退出时旧宿主就被丢掉了 ——
 * 里面的脚本还在跑，却再没人持有它去 `shutdown()`；进来几次就叠几份。所以这里按进程复用，
 * 与仓库里 `GopeedEngine` / `TurboDownloadManager` 用 `object` 做单例是同一个理由
 * （`PluginRuntime` 是 `class`，此处用等价的文件内持有者）。
 *
 * 用 applicationContext：本对象活得比任何一个 Composable 都久，绝不能持有 Activity。
 *
 * 可见性为 internal：插件市场页（[PluginMarketScreen]）装完 / 更新完要立刻把脚本装进**同一个**宿主，
 * 各自 new 一个 PluginRuntime 会得到两个 PluginHost，同一个脚本跑两份。
 */
internal fun sharedPluginRuntime(context: Context): PluginRuntime =
    pluginRuntimeRef ?: synchronized(PluginRuntimeHolderLock) {
        pluginRuntimeRef ?: PluginRuntime(context.applicationContext).also { pluginRuntimeRef = it }
    }

private val PluginRuntimeHolderLock = Any()
