/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.mikepenz.markdown.m3.Markdown
import com.yunget.app.data.db.PluginInstalledEntity
import com.yunget.app.data.db.PluginSourceEntity
import com.yunget.app.data.plugin.InstalledPlugin
import com.yunget.app.data.plugin.MarketClient
import com.yunget.app.data.plugin.MarketIndex
import com.yunget.app.data.plugin.MarketPlugin
import com.yunget.app.data.plugin.PluginRepository
import com.yunget.app.data.plugin.PluginTrust
import com.yunget.app.data.plugin.PluginUpdateChecker
import com.yunget.app.data.update.UpdateChecker
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.components.compactMarkdownTypography
import com.yunget.app.ui.resolve.formatSize
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 插件市场页（设置 → 插件市场）：管源、查更新、看源里有什么、装进来。
 *
 * ## 本页只做「从源安装」，不做导入
 *
 * 粘贴脚本、从文件导入、导入前校验都在 [PluginsScreen]（设置 → 插件）里，这里**一次都不重复实现**：
 * 两条导入链路各写一份校验逻辑，日后必然漂移，而漂移的那一侧会变成"某个入口能装进没验过的脚本"。
 *
 * ## 四个区块（自上而下，与用户的实际操作顺序一致）
 *
 * 1. **插件源**：登记 / 启停 / 刷新 / 移除源。源是"哪里有插件"的目录，不是"装了什么"；
 * 2. **插件更新**：对已装插件跨源比对，给出可更新项与各源各自的失败原因；
 * 3. **源里的插件**：按源列出索引内容（先上屏缓存、再拉新的），点进去看详情；
 * 4. **详情与安装**：详情弹窗给版本 / 权限 / 签名，安装前必须过一次权限确认弹窗。
 *
 * ## 「加源」与「装插件」是两件独立的事（设计文档硬要求）
 *
 * 添加一个源**只登记地址并拉取索引**，绝不顺手把里面的插件装进来 —— 界面上也是这么做的：
 * 添加源的表单里没有任何"顺便安装"的选项，主按钮的字面就是「只添加源」；加完之后用户还得自己点进
 * 某个插件、看过权限、再确认一次。这样"我从哪拿到这个脚本的"永远是用户主动做过的决定，
 * 而不是加源时的副作用。
 *
 * ## 源 id 由索引地址派生（不是随机数）
 *
 * 派生规则在 [PluginRepository.addSource] 内部（`"src." + sha256(索引地址小写).take(8)`），
 * 本页**不重复实现**那条规则：两处各算一次，一旦其中一处改了（大小写归一化、截断长度），
 * 同一个地址就会得到两个 id，"重复添加 = 覆盖同一条"的约定立刻失效。
 *
 * ## 线程
 *
 * 拉索引、下载脚本、落盘全在 [MarketClient] / [PluginRepository] 内部切 IO；
 * 本页只在自己的协程里等结果，UI 线程不做任何网络或文件操作。
 *
 * @param onBack 返回上一级（调用方提供）
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PluginRepository(context) }
    val client = remember { MarketClient(context) }
    val updateChecker = remember { PluginUpdateChecker(context) }
    // 与插件页共用**进程内唯一**那个运行时：装完立刻加载才能让用户看到"真的生效了"，
    // 而另建一个运行时会得到第二个 PluginHost（脚本会跑两份，见 sharedPluginRuntime 注释）
    val runtime = remember { sharedPluginRuntime(context) }

    // Flow 必须记住：listSources() / listInstalled() 每次都返回新 Flow，不记会在每次重组时重启收集
    val sourcesFlow = remember(repo) { repo.listSources() }
    val sources by sourcesFlow.collectAsStateWithLifecycle(emptyList())
    val installedFlow = remember(repo) { repo.listInstalled() }
    val installed by installedFlow.collectAsStateWithLifecycle(emptyList())

    // 每个源的索引（先来自本地缓存，拉取成功后替换成新的）
    var indexes by remember { mutableStateOf(emptyMap<String, MarketIndex>()) }
    // 每个源最近一次拉取失败的原因（拉到了就从 map 里去掉）
    var indexErrors by remember { mutableStateOf(emptyMap<String, String>()) }
    var loadingSources by remember { mutableStateOf(emptySet<String>()) }
    // 进页面自动拉过哪些源：每个源只自动拉一次，之后靠用户点「刷新」
    // （否则源列表每次变化都会重新联网，用户在设置页改点别的就触发一轮下载）
    var autoFetched by remember { mutableStateOf(emptySet<String>()) }

    var report by remember { mutableStateOf<PluginUpdateChecker.Report?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkingSource by remember { mutableStateOf<String?>(null) }
    var applyingIds by remember { mutableStateOf(emptySet<String>()) }

    var showAddSheet by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<PluginSourceEntity?>(null) }
    var detail by remember { mutableStateOf<PluginDetailTarget?>(null) }
    var pendingInstall by remember { mutableStateOf<PluginDetailTarget?>(null) }
    var installing by remember { mutableStateOf(false) }

    val installedById = remember(installed) { installed.associateBy { it.id } }
    val appVersion = remember { appVersionName(context) }

    /** 拉一个源的索引。**只拉不装** —— 拉回来的是列表，装不装由用户点了才算。 */
    val fetchSource: (PluginSourceEntity, Boolean) -> Unit = { source, announce ->
        if (source.id !in loadingSources) {
            loadingSources = loadingSources + source.id
            scope.launch {
                client.fetchIndex(source).fold(
                    onSuccess = { index ->
                        indexes = indexes + (source.id to index)
                        indexErrors = indexErrors - source.id
                        if (announce) {
                            SnackbarController.show("「${source.displayName}」索引已更新：${index.plugins.size} 个插件")
                        }
                    },
                    onFailure = { t ->
                        val reason = t.message ?: t.javaClass.simpleName
                        indexErrors = indexErrors + (source.id to reason)
                        if (announce) SnackbarController.show("拉取索引失败：$reason")
                    }
                )
                loadingSources = loadingSources - source.id
            }
        }
    }

    // 进页面（以及源列表变化时）：先把**缓存**的索引上屏，再对每个启用的源自动拉一次。
    // 缓存先上屏是有意的：离线或源临时挂掉时，用户仍然看得到上次拉到的列表，
    // 而不是一片空白加一句网络错误。
    LaunchedEffect(sources.map { it.id }) {
        for (source in sources) {
            if (indexes.containsKey(source.id)) continue
            val cached = repo.cachedSourceIndex(source.id) ?: continue
            MarketIndex.parse(cached).getOrNull()?.let { parsed ->
                indexes = indexes + (source.id to parsed)
            }
        }
        for (source in sources) {
            if (!source.enabled || source.id in autoFetched) continue
            autoFetched = autoFetched + source.id
            fetchSource(source, false)
        }
    }

    /** 检查更新：只读、跨源；某个源失败不影响其他源的结果（Report 把两者分开返回） */
    val runCheck: () -> Unit = {
        if (!checking) {
            checking = true
            checkingSource = null
            scope.launch {
                val result = updateChecker.check { name -> checkingSource = name }
                report = result
                checking = false
                checkingSource = null
                SnackbarController.show(
                    when {
                        result.updates.isNotEmpty() -> "${result.updates.size} 个插件可以更新"
                        result.sourceErrors.isNotEmpty() ->
                            "已检查 ${result.checkedCount} 个插件，没发现可更新的（有源没拉到，见下方）"

                        else -> "已检查 ${result.checkedCount} 个插件，都是最新的"
                    }
                )
            }
        }
    }

    /** 装 / 更新单个插件：成功后把它从「可更新」列表里摘掉（列表本身走 Flow，会自己刷新） */
    val applyUpdate: (PluginUpdateChecker.Available) -> Unit = { available ->
        if (available.installedId !in applyingIds) {
            applyingIds = applyingIds + available.installedId
            scope.launch {
                updateChecker.apply(available).fold(
                    onSuccess = { version ->
                        SnackbarController.show("已更新到 $version")
                        report = report?.let { r ->
                            r.copy(updates = r.updates.filterNot { it.installedId == available.installedId })
                        }
                    },
                    onFailure = { t ->
                        SnackbarController.show("更新失败：${t.message ?: t.javaClass.simpleName}")
                    }
                )
                applyingIds = applyingIds - available.installedId
            }
        }
    }

    /** 全部更新：逐个 apply（串行，避免并发下载与重复落盘），失败的原因收集起来一起给用户看 */
    val applyAll: () -> Unit = {
        val pending = report?.updates.orEmpty()
            .filterNot { it.requiresNewerApp }
            .filterNot { it.installedId in applyingIds }
        if (pending.isNotEmpty()) {
            applyingIds = applyingIds + pending.map { it.installedId }
            scope.launch {
                var ok = 0
                val failures = mutableListOf<String>()
                for (item in pending) {
                    updateChecker.apply(item).fold(
                        onSuccess = { version ->
                            ok++
                            report = report?.let { r ->
                                r.copy(updates = r.updates.filterNot { it.installedId == item.installedId })
                            }
                            SnackbarController.show("已更新到 $version")
                        },
                        onFailure = { t ->
                            failures += "${item.plugin.name}：${t.message ?: t.javaClass.simpleName}"
                        }
                    )
                    applyingIds = applyingIds - item.installedId
                }
                SnackbarController.show(
                    if (failures.isEmpty()) {
                        "$ok 个插件已更新"
                    } else {
                        "$ok 个已更新，${failures.size} 个失败：${failures.joinToString("；")}"
                    }
                )
            }
        }
    }

    /** 详情弹窗底部的按钮按下之后：弹权限确认 → 用户点了确认才真的下载 */
    val install: (PluginDetailTarget) -> Unit = { target ->
        if (!installing) {
            installing = true
            scope.launch {
                client.installOrUpdate(
                    plugin = target.plugin,
                    version = target.version,
                    sourceId = target.source.id,
                    trustLevel = target.trustLevel,
                    index = target.index,
                ).fold(
                    onSuccess = { row ->
                        // 装完立刻加载：否则会看到"已安装"但脚本没跑起来，那比不装更让人困惑
                        val error = runtime.load(row.id)
                        pendingInstall = null
                        detail = null
                        SnackbarController.show(
                            if (error == null) {
                                "已安装「${target.plugin.name}」v${target.version.version}"
                            } else {
                                "已安装「${target.plugin.name}」，但加载失败：$error"
                            }
                        )
                    },
                    onFailure = { t ->
                        SnackbarController.show("安装失败：${t.message ?: t.javaClass.simpleName}")
                    }
                )
                installing = false
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("插件市场", style = MaterialTheme.typography.titleLarge) },
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
            // ---- 区块 1：插件源 ----
            item(key = "sources:label") {
                MarketSectionLabel(if (sources.isEmpty()) "插件源" else "插件源 · ${sources.size} 个")
            }
            if (sources.isEmpty()) {
                item(key = "sources:empty") {
                    Text(
                        text = "还没有插件源。插件源就是一个放着 plugins.json 的地址 —— " +
                            "添加之后只登记地址、拉取列表，不会自动装任何东西。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            items(sources, key = { "source:" + it.id }) { source ->
                SourceRow(
                    source = source,
                    loading = source.id in loadingSources,
                    pluginCount = indexes[source.id]?.plugins?.size,
                    error = indexErrors[source.id],
                    onToggle = { enabled ->
                        scope.launch {
                            repo.setSourceEnabled(source.id, enabled).fold(
                                onSuccess = {
                                    SnackbarController.show(
                                        if (enabled) {
                                            "已启用「${source.displayName}」"
                                        } else {
                                            "已停用「${source.displayName}」"
                                        }
                                    )
                                },
                                onFailure = { t ->
                                    SnackbarController.show("切换失败：${t.message ?: t.javaClass.simpleName}")
                                }
                            )
                        }
                    },
                    onRefresh = { fetchSource(source, true) },
                    onRemove = { pendingRemove = source }
                )
            }
            item(key = "sources:add") {
                OutlinedButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加源", style = MaterialTheme.typography.labelLarge)
                }
            }

            // ---- 区块 2：插件更新 ----
            item(key = "updates:label") {
                MarketSectionLabel("插件更新")
            }
            item(key = "updates:action") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = runCheck,
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                checking -> checkingSource?.let { "正在拉取「$it」…" } ?: "正在检查…"
                                else -> "检查插件更新"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    report?.let { r ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "已检查 ${r.checkedCount} 个已装插件" +
                                if (sources.none { it.enabled }) "（没有启用的源，先在上面把源打开）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val installable = r.updates.count { !it.requiresNewerApp }
                        if (installable > 1) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = applyAll,
                                enabled = applyingIds.isEmpty(),
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Outlined.Update, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("全部更新（$installable 个）", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            // 可更新项：每项一个「更新」按钮；要求更高版本应用的显式提示并禁用按钮
            val updates = report?.updates.orEmpty()
            items(updates, key = { "update:" + it.installedId }) { item ->
                UpdateEntryCard(
                    available = item,
                    busy = item.installedId in applyingIds,
                    onApply = { applyUpdate(item) }
                )
            }
            val currentReport = report
            if (currentReport != null && updates.isEmpty() && currentReport.sourceErrors.isEmpty()) {
                item(key = "updates:none") {
                    Text(
                        text = if (installed.isEmpty()) {
                            "还没装插件，没什么可检查的。"
                        } else {
                            "已装插件都是最新的。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            // 失败的源单独列出来：**某个源失败不等于"检查失败"**，其他源的结果照常显示在上面
            if (currentReport != null && currentReport.sourceErrors.isNotEmpty()) {
                item(key = "updates:errors") {
                    val sourceName = sources.associate { it.id to it.displayName }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "${currentReport.sourceErrors.size} 个源没能拉到，它们里面的插件这次没参与比对",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        currentReport.sourceErrors.forEach { (sourceId, reason) ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "· ${sourceName[sourceId] ?: sourceId}：$reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // ---- 区块 3：源里的插件 ----
            item(key = "catalog:label") {
                MarketSectionLabel("源里的插件")
            }
            val enabledSources = sources.filter { it.enabled }
            if (enabledSources.isEmpty()) {
                item(key = "catalog:empty") {
                    Text(
                        text = "没有启用的插件源。在上面启用或添加一个源，这里就会列出它的插件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            for (source in enabledSources) {
                val index = indexes[source.id]
                // 源自己声明的等级与用户给它定的等级取保守的那个（索引可能自称 official）
                val sourceTrust = if (index != null) {
                    MarketIndex.effectiveTrust(index, source.trustLevel)
                } else {
                    source.trustLevel
                }
                item(key = "catalog:head:" + source.id) {
                    CatalogSourceHeader(
                        source = source,
                        index = index,
                        loading = source.id in loadingSources,
                        error = indexErrors[source.id],
                        onRefresh = { fetchSource(source, true) }
                    )
                }
                val plugins = index?.plugins.orEmpty()
                if (index != null && plugins.isEmpty()) {
                    item(key = "catalog:blank:" + source.id) {
                        Text(
                            text = "这个源的索引里没有插件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                }
                items(plugins, key = { "catalog:" + source.id + ":" + it.id }) { plugin ->
                    MarketPluginRow(
                        plugin = plugin,
                        trustLevel = PluginTrust.gradeFor(sourceTrust, plugin.newestVersion()?.keyId.orEmpty()),
                        local = installedById[plugin.id],
                        // 「可更新」直接取检查更新的结论，不在这里自己再比一次版本 ——
                        // 那样会出现"这一行说可更新、上面那段说没有"的自相矛盾
                        availableUpdate = report?.updates?.firstOrNull { it.installedId == plugin.id },
                        onOpen = {
                            val version = plugin.newestVersion()
                            if (version != null && index != null) {
                                detail = PluginDetailTarget(
                                    plugin = plugin,
                                    version = version,
                                    source = source,
                                    index = index,
                                    trustLevel = PluginTrust.gradeFor(sourceTrust, version.keyId),
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddSourceSheet(
            onAdd = { displayName, url, trust ->
                scope.launch {
                    repo.addSource(displayName = displayName, indexUrl = url, trustLevel = trust).fold(
                        onSuccess = { id ->
                            showAddSheet = false
                            SnackbarController.show("已添加插件源，正在拉取索引（只拉列表，不装任何插件）")
                            // 添加后立刻拉一次索引：只拉列表，不装任何插件（见文件头注释）
                            repo.getSource(id)?.let { added -> fetchSource(added, true) }
                        },
                        onFailure = { t ->
                            SnackbarController.show("添加失败：${t.message ?: t.javaClass.simpleName}")
                        }
                    )
                }
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // 移除源是不可逆的（登记与索引缓存都没了），必须二次确认；
    // 但要**明确告诉用户已装的插件不会被卸载** —— 不然没人敢点这个按钮
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除插件源") },
            text = {
                Column {
                    Text("会移除「${target.displayName}」的登记与本地索引缓存。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "从它装过的插件不会被卸载，仍然照常运行；只是以后不再从它这里查更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    pendingRemove = null
                    scope.launch {
                        repo.removeSource(id).fold(
                            onSuccess = {
                                indexes = indexes - id
                                indexErrors = indexErrors - id
                                SnackbarController.show("已移除「${target.displayName}」")
                            },
                            onFailure = { t ->
                                SnackbarController.show("移除失败：${t.message ?: t.javaClass.simpleName}")
                            }
                        )
                    }
                }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }

    detail?.let { target ->
        PluginMarketDetailSheet(
            target = target,
            local = installedById[target.plugin.id],
            appVersion = appVersion,
            onInstall = { pendingInstall = target },
            onDismiss = { detail = null }
        )
    }

    // 权限确认：**确认之前不下载**。要授予的能力与信任等级都在这里说清楚
    pendingInstall?.let { target ->
        InstallConfirmDialog(
            target = target,
            onConfirm = { install(target) },
            onDismiss = { if (!installing) pendingInstall = null }
        )
    }
}

// ---------------------------------------------------------------- 区块 1：源

/** 一个插件源一行：显示名、索引地址、信任说明、上次拉取、启停、刷新、移除 */
@Composable
private fun SourceRow(
    source: PluginSourceEntity,
    loading: Boolean,
    pluginCount: Int?,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.displayName.ifBlank { source.id },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = source.indexUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle, enabled = !loading)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = PluginTrust.describe(source.trustLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("上次拉取：")
                    append(if (source.lastFetchedAt <= 0L) "从未" else marketFormatTime(source.lastFetchedAt))
                    if (pluginCount != null) append(" · 索引里有 $pluginCount 个插件")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 失败原因出现 / 消失时让卡片高度平滑变化：拉取失败与刷新成功会在同一行来回切，
            // 直接跳变会让下面的按钮位置一起弹一下
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn(effectsDefault()),
                exit = fadeOut(effectsFast())
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "上次拉取失败：${error.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (loading) "拉取中…" else "刷新索引", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !loading,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("移除", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 添加源（底部弹窗）：**只有登记，没有安装**。
 *
 * 表单里刻意不给"顺便装点什么"的选项（设计文档硬要求：加源与装插件是两个独立动作）。
 * 用户在这里唯一能决定的，是这个地址叫什么名字、按哪一级信任它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceSheet(
    onAdd: (displayName: String, indexUrl: String, trustLevel: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // 默认社区：新加的源本来就还没被任何人核验过，默认给"已验证"等于替用户做了信任决定
    var trust by remember { mutableStateOf(PluginInstalledEntity.TRUST_COMMUNITY) }
    var trustMenu by remember { mutableStateOf(false) }

    val urlLooksOk = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text("添加插件源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "填一个返回 plugins.json 的地址（GitHub raw 或自建服务器都行）。添加只做两件事：" +
                    "登记这个地址、把它的索引拉下来存好 —— 不会自动安装里面的任何插件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("索引地址") },
                placeholder = { Text("https://raw.githubusercontent.com/…/plugins.json") },
                singleLine = true,
                isError = url.isNotBlank() && !urlLooksOk,
                supportingText = {
                    if (url.isNotBlank() && !urlLooksOk) {
                        Text("地址要以 http:// 或 https:// 开头")
                    } else {
                        Text("同一个地址重复添加会覆盖原来那一条，不会堆出重复的源")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("显示名") },
                placeholder = { Text("留空就用地址本身") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "信任等级",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Box {
                OutlinedButton(
                    onClick = { trustMenu = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    MarketTrustBadge(trust)
                    Spacer(Modifier.width(8.dp))
                    Text("${trustLabel(trust)} · $trust", style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = trustMenu, onDismissRequest = { trustMenu = false }) {
                    PluginInstalledEntity.TRUST_LEVELS.forEach { level ->
                        DropdownMenuItem(
                            text = { Text("${trustLabel(level)} · $level") },
                            onClick = {
                                trust = level
                                trustMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = PluginTrust.describe(trust),
                style = MaterialTheme.typography.bodySmall,
                color = if (PluginTrust.needsWarning(trust)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这一级只影响显示与提醒，不影响校验：无论哪一级，安装时都要求脚本的 sha256 与签名都对得上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onAdd(name, url, trust) },
                enabled = urlLooksOk,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("只添加源", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("取消")
            }
        }
    }
}

// ---------------------------------------------------------------- 区块 2：更新项

/** 一个可更新项：谁、从哪一版到哪一版、来自哪个源、这一版改了什么 */
@Composable
private fun UpdateEntryCard(
    available: PluginUpdateChecker.Available,
    busy: Boolean,
    onApply: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (available.requiresNewerApp) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = available.plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${available.installedVersion} → ${available.version.version}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MarketTrustBadge(available.trustLevel)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "来自「${available.sourceName}」· ${PluginTrust.describe(available.trustLevel)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (available.version.changelog.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = available.version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 装了也跑不起来时必须**明说**，而不是让用户点完再收到一个失败提示
            if (available.requiresNewerApp) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Error,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "需要先更新应用（要求 ${available.minHostVersion}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onApply,
                enabled = !busy && !available.requiresNewerApp,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (busy) "更新中…" else "更新到 ${available.version.version}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 区块 3：源里的插件

/** 每个源一段的小标题：源名 + 索引状态 + 刷新 */
@Composable
private fun CatalogSourceHeader(
    source: PluginSourceEntity,
    index: MarketIndex?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.displayName.ifBlank { source.id },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    error != null -> "索引没拉到：$error"
                    index != null -> "索引里的 ${index.plugins.size} 个插件" +
                        if (source.lastFetchedAt > 0L) " · ${marketFormatTime(source.lastFetchedAt)}" else ""

                    loading -> "正在拉取索引…"
                    else -> "还没有索引，点右边刷新一次"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新索引", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 索引里的一个插件一行：名称、summary、版本、协议、信任徽标、已装情况 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketPluginRow(
    plugin: MarketPlugin,
    trustLevel: String,
    local: InstalledPlugin?,
    availableUpdate: PluginUpdateChecker.Available?,
    onOpen: () -> Unit
) {
    val newest = plugin.newestVersion()
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
                        text = newest?.let { "v${it.version}" } ?: "没有可用版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MarketTrustBadge(trustLevel)
            }

            if (plugin.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = plugin.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (plugin.protocols.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    plugin.protocols.forEach { scheme ->
                        MarketBadge(
                            text = scheme,
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            content = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            val installText: String
            val installColor: Color
            when {
                availableUpdate != null -> {
                    installText = "已安装 v${availableUpdate.installedVersion} · " +
                        "可更新到 v${availableUpdate.version.version}（见上方「插件更新」）"
                    installColor = MaterialTheme.colorScheme.primary
                }

                local != null -> {
                    installText = "已安装 v${local.version}"
                    installColor = MaterialTheme.colorScheme.onSurfaceVariant
                }

                else -> {
                    installText = "未安装"
                    installColor = MaterialTheme.colorScheme.onSurfaceVariant
                }
            }
            Text(text = installText, style = MaterialTheme.typography.bodySmall, color = installColor)
        }
    }
}

// ---------------------------------------------------------------- 区块 4：详情

/**
 * 详情弹窗要展示的那一份东西：插件 + 选中的版本 + 它来自哪个源。
 *
 * [index] 要一起带着：安装时 `MarketClient` 靠它按 keyId 找非官方源的公钥。
 */
private data class PluginDetailTarget(
    val plugin: MarketPlugin,
    val version: MarketPlugin.Version,
    val source: PluginSourceEntity,
    val index: MarketIndex,
    val trustLevel: String,
)

/**
 * 插件详情（底部弹窗）：说明、作者 / 许可 / 主页、各版本与权限、签名原文，底部是安装 / 更新按钮。
 *
 * 按钮的三种形态与用户的实际处境一一对应：**没装=安装、装了但源里更新=更新、同版本=已安装（不可点）**。
 * 版本号比较复用 [UpdateChecker.compareVersions]（与更新检查、应用自更新同一套规则），
 * 不在这里另写一个"看起来差不多"的比较 —— 两套规则一定会对不上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginMarketDetailSheet(
    target: PluginDetailTarget,
    local: InstalledPlugin?,
    appVersion: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val typography = remember { compactMarkdownTypography() }
    val plugin = target.plugin
    val newest = plugin.newestVersion()
    val newestLabel = newest?.version.orEmpty()
    // 从新到旧排：用户最关心的永远是"最新那版改了什么"，而且列表顺序不该随索引书写顺序变化
    val versions = remember(plugin) {
        plugin.versions.sortedWith { a, b -> UpdateChecker.compareVersions(b.version, a.version) }
    }

    // 需要更高版本应用时不让点：点了也只会收到一句失败，不如当场说清楚
    val needsNewerApp = target.version.minHostVersion.isNotBlank() &&
        appVersion.isNotBlank() &&
        UpdateChecker.compareVersions(appVersion, target.version.minHostVersion) < 0
    val hasNewerThanLocal = local != null && UpdateChecker.compareVersions(newestLabel, local.version) > 0

    val actionLabel = when {
        !target.version.isVerifiable -> "这一版不能安装"
        needsNewerApp -> "需要先更新应用"
        local == null -> "安装"
        hasNewerThanLocal -> "更新到 v$newestLabel"
        else -> "已安装 v${local.version}"
    }
    val actionEnabled = target.version.isVerifiable && !needsNewerApp && (local == null || hasNewerThanLocal)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = plugin.id,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarketTrustBadge(target.trustLevel)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "来自「${target.source.displayName.ifBlank { target.source.id }}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = PluginTrust.describe(target.trustLevel),
                style = MaterialTheme.typography.bodySmall,
                color = if (PluginTrust.needsWarning(target.trustLevel)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (plugin.summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(plugin.summary, style = MaterialTheme.typography.bodyMedium)
            }

            // 说明正文走仓库里已有的 Markdown 渲染（与 README 预览同一套排版），不自己解析
            if (plugin.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Markdown(
                    content = plugin.description,
                    typography = typography,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            plugin.author.takeIf { it.isNotBlank() }?.let { MarketField("作者", it) }
            plugin.license.takeIf { it.isNotBlank() }?.let { MarketField("许可", it) }
            plugin.category.takeIf { it.isNotBlank() }?.let { MarketField("分类", it) }
            plugin.homepage.takeIf { it.isNotBlank() }?.let { MarketField("主页", it, mono = true) }
            plugin.sourceUrl.takeIf { it.isNotBlank() }?.let { MarketField("源码地址", it, mono = true) }
            if (plugin.tags.isNotEmpty()) {
                MarketField("标签", plugin.tags.joinToString("、"))
            }
            if (plugin.capabilities.isNotEmpty()) {
                MarketField("声明能力", plugin.capabilities.joinToString("、"))
            }
            if (plugin.protocols.isNotEmpty()) {
                MarketField("支持的协议", plugin.protocols.joinToString("、"))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (versions.size > 1) "版本 · ${versions.size} 个" else "版本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 每个版本都列出来（不只最新那版）：用户要判断"要不要从当前这版升上去"，
            // 看的就是中间几版各改了什么、要过哪些能力
            versions.forEach { version ->
                MarketVersionBlock(version = version, isNewest = version.version == newestLabel)
            }

            Spacer(Modifier.height(12.dp))
            if (needsNewerApp) {
                Text(
                    text = "这一版要求云取 ${target.version.minHostVersion} 或更高版本" +
                        if (appVersion.isNotBlank()) "（当前 $appVersion）" else "" +
                        "，先更新应用再回来装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = onInstall,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "安装前会先列出要授予的能力，确认之后才开始下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("关闭")
            }
        }
    }
}

/** 一个版本一块：版本号、发布时间、体积、能力、更新说明、签名与 keyId */
@Composable
private fun MarketVersionBlock(version: MarketPlugin.Version, isNewest: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "v${version.version}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            if (isNewest) {
                Spacer(Modifier.width(6.dp))
                MarketBadge(
                    text = "最新",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (version.sizeBytes > 0) formatSize(version.sizeBytes) else "未标注大小",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (version.releasedAt.isNotBlank()) {
            Text(
                text = "发布：${version.releasedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "能力：" + version.permissions.joinToString("、").ifBlank { "无额外声明" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (version.minHostVersion.isNotBlank()) {
            Text(
                text = "要求应用版本：${version.minHostVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (version.changelog.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(version.changelog, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        if (!version.isVerifiable) {
            // 缺校验材料的版本连装都装不了，直说，别让用户以为"点了就会装"
            Text(
                text = "这一版没有齐全的校验信息（downloadUrl / sha256 / 签名 / keyId），不能安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            MarketCopyField("sha256", version.sha256, context)
            MarketCopyField("keyId", version.keyId, context)
            MarketCopyField("签名", version.signature, context)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "签名覆盖脚本原始字节：下载后会先比对 sha256，再用上面的公钥验签，" +
                    "任何改动都会让校验失败、拒绝安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 一段可复制原文（等宽 + 可选中 + 复制按钮）：签名 / 摘要这类东西用户要能原样带走 */
@Composable
private fun MarketCopyField(label: String, value: String, context: Context) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    copyToClipboard(context, value)
                    SnackbarController.show("已复制 $label")
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "复制 $label",
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

// ---------------------------------------------------------------- 安装确认

/**
 * 安装确认：把**要授予的能力**与**信任等级**摆在按钮前面。
 *
 * 未验证（[PluginTrust.needsWarning]）时用 error 色明说风险 —— 措辞按设计文档第 26 章的要求，
 * 只说"没有签名、无法确认来源与完整性"，不写"安全 / 不安全"这类结论式的词。
 */
@Composable
private fun InstallConfirmDialog(
    target: PluginDetailTarget,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val warning = PluginTrust.needsWarning(target.trustLevel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装「${target.plugin.name}」v${target.version.version}") },
        text = {
            Column {
                Text(
                    text = "来自「${target.source.displayName.ifBlank { target.source.id }}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = PluginTrust.describe(target.trustLevel),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (warning) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "这个插件没有可用的签名，装进去的脚本内容无法确认是谁发布的、有没有被改过。" +
                            "它会在应用内运行，装之前请想清楚。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "要授予的能力",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                if (target.version.permissions.isEmpty()) {
                    Text(
                        text = "这一版没有声明额外能力（只有默认的 http / crypto / log / time）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    target.version.permissions.forEach { permission ->
                        Text(
                            text = "· $permission",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "确认后会先下载脚本，比对 sha256 并用签名公钥验签；对不上就不装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---------------------------------------------------------------- 小件与工具

/** 段标题：与插件页的段标题同款（labelMedium + 起点内缩），两页放在一起看不会跳档 */
@Composable
private fun MarketSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

/** 信任等级徽标：四色映射**复用插件页那一份**（[trustBadgeColors]），避免两页各写一套配色后走偏 */
@Composable
private fun MarketTrustBadge(level: String) {
    val (container, content) = trustBadgeColors(level)
    MarketBadge(text = trustLabel(level), container = container, content = content)
}

@Composable
private fun MarketBadge(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

/** 详情里的一行「标签 + 值」（等宽的用 SelectionContainer 包住，方便整段复制） */
@Composable
private fun MarketField(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val style = if (mono) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        }
        if (mono) {
            SelectionContainer { Text(text = value, style = style) }
        } else {
            Text(text = value, style = style)
        }
    }
}

/** 时间戳（0 由调用方处理成"从未"） */
private fun marketFormatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 当前应用版本名（与 `minHostVersion` 比较用） */
private fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("yunget_plugin_market", text))
}
