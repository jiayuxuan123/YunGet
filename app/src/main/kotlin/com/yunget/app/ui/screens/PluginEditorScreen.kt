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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunget.app.data.plugin.PluginIds
import com.yunget.app.data.plugin.PluginRepository
import com.yunget.app.data.plugin.sharedPluginRuntime
import com.yunget.app.ui.SnackbarController
import com.yunget.app.ui.components.YunGetWavyLoading
import com.yunget.app.ui.theme.effectsDefault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 插件编辑器：写脚本、**装之前先校验**、保存/安装、以及怎么把它发布出去。
 *
 * ## 这个页面存在的理由
 *
 * 插件是跑在应用里的用户代码，所以"我写的这段到底能不能被引擎接受"这件事必须在**装之前**
 * 就能回答。校验用的是引擎自己的解析器（`JsScriptValidator`：只解析不执行），
 * 所以它给出的结论与加载器实际会不会接受是一致的 —— 而不是另写一套宽松的检查来哄用户。
 *
 * ## 两条不肯让步的规则
 *
 * 1. **保存按钮与校验结果绑定**：文本一改就清掉上次校验、禁用保存。否则会出现
 *    "校验的是 A、装上去的是 B" —— 那是这个页面最容易犯、也最危险的错。
 * 2. **校验是挂起函数**：每次校验都要创建一个 QuickJS 运行时（几十毫秒到几百毫秒），
 *    所以它不能在每次按键时跑（会自动防抖），也不能在 UI 线程上跑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginEditorScreen(
    onBack: () -> Unit,
    initialPluginId: String? = null,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PluginRepository(context) }
    val runtime = remember { sharedPluginRuntime(context) }
    val clipboard = LocalClipboardManager.current

    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(initialPluginId == null) }
    var report by remember { mutableStateOf<dev.turbodl.plugin.js.JsScriptValidator.Report?>(null) }
    /** 上次校验时的那份文本；与 [text] 不一致就说明校验结果已过期。 */
    var validatedText by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showPublishSheet by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }

    // 编辑已装插件：把脚本原文读进来
    LaunchedEffect(initialPluginId) {
        val id = initialPluginId ?: return@LaunchedEffect
        runCatching {
            repo.getInstalled(id)?.let { p ->
                val f = java.io.File(p.entity.scriptPath)
                if (f.isFile) text = f.readText()
            }
        }
        loaded = true
    }

    val meta = remember(text) { PluginIds.readSelfReportedMeta(text) }
    val stale = report != null && validatedText != text

    /** 跑一次校验。**校验结果与当时的文本一起记住**，供上面的过期判断使用。 */
    suspend fun runValidate(snapshot: String) {
        validating = true
        val r = runCatching { runtime.validate(snapshot) }.getOrNull()
        report = r
        validatedText = snapshot
        validating = false
    }

    // 自动校验：停止输入 800ms 后才跑（每次校验都要新建一个 JS 运行时，不能按键就跑）
    LaunchedEffect(text) {
        if (text.isBlank()) {
            report = null
            validatedText = null
            return@LaunchedEffect
        }
        delay(800)
        runValidate(text)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (initialPluginId == null) "新建插件" else "编辑插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showPublishSheet = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "如何发布")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ---------- 元信息行 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "行 ${text.count { it == '\n' } + 1} · ${text.length} 字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = buildString {
                        append("id ")
                        append(meta.id ?: "（未声明）")
                        append(" · v")
                        append(meta.version ?: "（未声明）")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meta.id == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else if (PluginIds.isValid(meta.id)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            // ---------- 编辑区（带行号，共享同一个滚动状态） ----------
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            ) {
                if (!loaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { YunGetWavyLoading() }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(8.dp)
                    ) {
                        // 行号列：与输入框共用 scroll，所以两侧始终对齐
                        Text(
                            text = (1..(text.count { it == '\n' } + 1)).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        androidx.compose.foundation.text.BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 240.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 校验结果 ----------
            ValidateReportCard(
                report = report,
                stale = stale,
                validating = validating,
            )

            Spacer(Modifier.height(8.dp))

            // ---------- 操作 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showTemplateDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("插入示例") }

                OutlinedButton(
                    onClick = { scope.launch { runValidate(text) } },
                    enabled = !validating && text.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Spellcheck, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("校验")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                // 只有"校验通过 + 校验的就是当前文本"才允许保存 —— 见类注释的第 1 条规则
                enabled = !busy && report?.ok == true && !stale,
                onClick = {
                    val snapshot = text
                    busy = true
                    scope.launch {
                        val fileName = (meta.id?.let { "$it.js" } ?: "pasted.js")
                        val result = repo.installFromText(snapshot, fileName)
                        result.fold(
                            onSuccess = { id ->
                                // 编辑已装插件时顺手重载：用户点了保存就该立刻生效，
                                // 而不是"显示已保存但跑的还是旧脚本"
                                if (initialPluginId != null) {
                                    runtime.unload(id)
                                    val err = runtime.load(id)
                                    SnackbarController.show(
                                        if (err == null) "已保存并重新加载：$id" else "已保存，但加载失败：$err"
                                    )
                                } else {
                                    SnackbarController.show("已安装：$id（到「插件」页启用）")
                                }
                            },
                            onFailure = { SnackbarController.show("保存失败：${it.message.orEmpty()}") }
                        )
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    YunGetWavyLoading(modifier = Modifier.height(18.dp))
                } else {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            stale -> "文本已改动，请先重新校验"
                            report?.ok != true -> "校验通过后才能保存"
                            initialPluginId == null -> "安装为插件"
                            else -> "保存并重新加载"
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // ---------- 插入示例 ----------
    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("插入内容") },
            text = {
                Column {
                    Text(
                        "示例插件是一份**能跑通**的最小实现，包含权限声明、自报版本与解析器。" +
                            "建议先插入它、校验通过、装上去跑一遍，再改成你要的样子。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "插入会**替换**当前编辑区内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text = SAMPLE_PLUGIN
                    showTemplateDialog = false
                }) { Text("替换为示例插件") }
            },
            dismissButton = {
                TextButton(onClick = {
                    text = text + "\n\n" + PERMISSIONS_SNIPPET
                    showTemplateDialog = false
                }) { Text("只追加权限声明") }
            }
        )
    }

    // ---------- 发布引导 ----------
    if (showPublishSheet) {
        PublishGuideSheet(
            pluginId = meta.id,
            onCopy = { cmd ->
                clipboard.setText(AnnotatedString(cmd))
                SnackbarController.show("命令已复制")
            },
            onDismiss = { showPublishSheet = false }
        )
    }
}

/**
 * 校验结果卡片：语法错误 / 通过 / 提醒 / 声明的权限 / 注册项。
 *
 * 刻意把"声明的权限"单独用 chip 列出来 —— 这是用户装之前最该看到的东西，
 * 埋在提醒列表里就失去意义了。
 */
@Composable
private fun ValidateReportCard(
    report: dev.turbodl.plugin.js.JsScriptValidator.Report?,
    stale: Boolean,
    validating: Boolean,
) {
    val container = when {
        validating -> MaterialTheme.colorScheme.surfaceContainerHighest
        report == null -> MaterialTheme.colorScheme.surfaceContainerHighest
        report.ok -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        when {
            validating -> Text("正在校验…", style = MaterialTheme.typography.bodySmall)
            report == null -> Text(
                "还没有校验结果。写完点「校验」，或等它自动跑一次。",
                style = MaterialTheme.typography.bodySmall
            )
            else -> {
                Text(
                    text = if (stale) "校验结果已过期（文本改过了）" else if (report.ok) "校验通过" else "校验未通过",
                    style = MaterialTheme.typography.titleSmall
                )
                report.syntaxError?.let {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (report.declaredPermissions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "声明的权限：${report.declaredPermissions.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (report.registrationKinds.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "注册了：${report.registrationKinds.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                report.warnings.forEach {
                    Spacer(Modifier.height(2.dp))
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * 发布引导：从"脚本写好了"到"别人能装到"的完整步骤。
 *
 * 内容与 `docs/PLUGIN-PUBLISH.md` 保持一致 —— 那个文件是给仓库读者看的，
 * 这里是给"正在用应用的人"看的，同一套步骤两处都要能照着做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublishGuideSheet(
    pluginId: String?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val id = pluginId?.takeIf { PluginIds.isValid(it) } ?: "<你的插件 id>"
    val signCommand =
        "python tools/sign_plugin.py sign $id.js --key keys/<你的私钥>.key --key-id <keyId>"

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
            Text("怎么把它发出去", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            Step(1, "确认脚本自报身份：defineMeta({ id, version }) 里的 version 必须是 semver，且每次改动都要递增 —— 别人是靠比版本号才知道该更新的。")
            Step(2, "用签名工具签名（覆盖的是**脚本原始字节**，所以签完不要再动文件）：")
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(signCommand, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                IconButton(onClick = { onCopy(signCommand) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制命令")
                }
                Text("复制命令", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
            }

            Step(3, "把脚本放进 YunGet-Plugins 仓库的 plugins/<id>/ 目录，跑 python tools/sign_plugin.py index 重新生成 plugins.json（手抄容易出错）。")
            Step(4, "提 PR 到官方源；或者自建一个源：把 plugins.json、脚本、公钥放在你自己的仓库里，别人在「插件市场 → 添加源」填索引地址就能看到。")
            Spacer(Modifier.height(10.dp))
            Text(
                "三件要记住的事",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "· 「添加源」和「安装插件」是两个独立动作 —— 加了源不会自动装任何东西。\n" +
                    "· 自建源默认按**不受信**处理，用户会看到明确提示；这不是限制你，是让每个人都知道自己在装什么。\n" +
                    "· 签名只能证明「脚本内容和某个密钥持有者发布的一致」，它证明不了脚本安全。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { onCopy("https://github.com/jiayuxuan123/YunGet-Plugins") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("复制官方源仓库地址")
            }
        }
    }
}

@Composable
private fun Step(n: Int, body: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 最小示例插件。
 *
 * **与 `docs/PLUGIN-DEV.md` 里的示例保持一致**：两处代码不同会让人以为文档过期，
 * 所以改这里就顺手改文档（反之亦然）。
 */
private val SAMPLE_PLUGIN = """
// 一个最小可用的插件：把 example:// 链接解析成一条 https 下载请求。
// 它不是真实协议（example.com 是 IANA 的文档域名），存在的意义是"能跑通"。
plugin.requires({ permissions: ['http', 'crypto'], abiMajor: 1 });

plugin.defineMeta({ id: 'parser.example', name: '示例解析器', version: '1.0.0' });

plugin.registerParser({
  parse: function (input) {
    var prefix = 'example://';
    if (input.slice(0, prefix.length).toLowerCase() !== prefix) {
      return null;   // 不是我的链接，交给别的插件
    }
    var rest = input.slice(prefix.length);
    if (!rest) return null;

    // 文件名来自链接，属于不可信输入：去掉路径分隔符与控制字符，只留一个文件名
    var name = rest.split('?')[0].split('/').pop() || 'file.bin';
    name = name.replace(/[\\\/<>:"|?*\x00-\x1f]/g, '_').replace(/^\.+/, '').slice(0, 80) || 'file.bin';

    return [{ url: 'https://example.com/' + rest, fileName: name }];
  }
});
""".trimIndent()

/** 只追加权限声明时用的小片段。 */
private val PERMISSIONS_SNIPPET =
    "plugin.requires({ permissions: ['http', 'crypto', 'log', 'time'], abiMajor: 1 });"
