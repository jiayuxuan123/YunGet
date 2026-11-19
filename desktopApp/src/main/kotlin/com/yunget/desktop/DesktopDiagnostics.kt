package com.yunget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.data.network.CoreHttp
import com.yunget.app.data.network.CoreLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal object DesktopAppMeta {
    const val FALLBACK_VERSION = "2.7.43"
    const val RELEASES_API_URL = "https://api.github.com/repos/jiayuxuan123/YunGet/releases?per_page=30"
    const val RELEASES_WEB_URL = "https://github.com/jiayuxuan123/YunGet/releases"

    val currentVersion: String
        get() = System.getProperty("jpackage.app-version")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: FALLBACK_VERSION
}

internal enum class DesktopLogLevel(val label: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
}

internal data class DesktopLogEntry(
    val timestamp: Long,
    val level: DesktopLogLevel,
    val category: String,
    val tag: String,
    val message: String,
) {
    fun formatLine(): String {
        val ts = LOG_TIME_FMT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        val suffix = if (message.isNotBlank()) " · $message" else ""
        return "$ts [${level.label}] [$category/$tag]$suffix"
    }

    companion object {
        private val LOG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

internal object DesktopLogStore {
    private const val MAX_ENTRIES = 600
    private const val MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L

    private val lock = Any()
    private val entriesFlow = MutableStateFlow<List<DesktopLogEntry>>(emptyList())
    val entries: StateFlow<List<DesktopLogEntry>> = entriesFlow.asStateFlow()

    @Volatile
    private var logFile: File? = null

    fun install(dataDirectory: File) {
        dataDirectory.mkdirs()
        val target = File(dataDirectory, "yunget-desktop.log")
        if (target.isFile && target.length() > MAX_LOG_FILE_BYTES) {
            runCatching {
                val backup = File(dataDirectory, "yunget-desktop.log.1")
                if (backup.exists()) backup.delete()
                target.renameTo(backup)
            }
        }
        logFile = target
        CoreLog.install(
            onEvent = { cat, tag, summary -> log(DesktopLogLevel.INFO, cat, tag, summary) },
            onWarn = { cat, tag, summary -> log(DesktopLogLevel.WARN, cat, tag, summary) },
            onError = { cat, tag, summary, err ->
                val msg = buildString {
                    if (summary.isNotBlank()) append(summary)
                    if (err != null) {
                        if (isNotEmpty()) append(" | ")
                        append(err.javaClass.simpleName)
                        err.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    }
                }
                log(DesktopLogLevel.ERROR, cat, tag, msg)
            },
        )
        info("desktop", "startup", "YunGet Desktop ${DesktopAppMeta.currentVersion} initialized")
    }

    fun currentLogFile(): File? = logFile

    fun info(category: String, tag: String, message: String = "") =
        log(DesktopLogLevel.INFO, category, tag, message)

    fun warn(category: String, tag: String, message: String = "") =
        log(DesktopLogLevel.WARN, category, tag, message)

    fun error(category: String, tag: String, message: String = "", err: Throwable? = null) {
        val full = buildString {
            if (message.isNotBlank()) append(message)
            if (err != null) {
                if (isNotEmpty()) append(" | ")
                append(err.javaClass.simpleName)
                err.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }
        log(DesktopLogLevel.ERROR, category, tag, full)
    }

    fun clear() {
        synchronized(lock) {
            entriesFlow.value = emptyList()
        }
    }

    fun exportText(): String = entriesFlow.value.joinToString("\n") { it.formatLine() }

    private fun log(level: DesktopLogLevel, category: String, tag: String, message: String) {
        val entry = DesktopLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            tag = tag,
            message = message.replace('\r', ' ').replace('\n', ' ').trim(),
        )
        synchronized(lock) {
            val cur = entriesFlow.value
            entriesFlow.value = if (cur.size >= MAX_ENTRIES) {
                cur.drop(cur.size - MAX_ENTRIES + 1) + entry
            } else {
                cur + entry
            }
            logFile?.let { file ->
                runCatching { file.appendText(entry.formatLine() + "\n") }
            }
        }
    }
}

internal data class DesktopUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val prerelease: Boolean,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val msiAssetName: String?,
    val msiDownloadUrl: String?,
)

internal object DesktopUpdateChecker {
    suspend fun check(mirrorPrefix: String = ""): DesktopUpdateInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(DesktopAppMeta.RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunGet-Desktop/${DesktopAppMeta.currentVersion}")
            .get()
            .build()
        val body = CoreHttp.client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("检查更新失败（HTTP ${resp.code}）")
            resp.body?.string().orEmpty()
        }
        val releases = JSONArray(body)
        var bestObj: org.json.JSONObject? = null
        var bestVersion = ""
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            val tag = rel.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (tag.isBlank()) continue
            if (bestObj == null || compareVersions(tag, bestVersion) > 0) {
                bestObj = rel
                bestVersion = tag
            }
        }
        val latest = bestObj ?: error("未查询到可用的发布版本")
        var msiName: String? = null
        var msiUrl: String? = null
        val assets = latest.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val dl = asset.optString("browser_download_url")
                if (name.endsWith(".msi", ignoreCase = true) && dl.isNotBlank()) {
                    msiName = name
                    msiUrl = applyMirror(dl, mirrorPrefix)
                    break
                }
            }
        }
        val current = DesktopAppMeta.currentVersion
        DesktopUpdateInfo(
            currentVersion = current,
            latestVersion = bestVersion,
            hasUpdate = compareVersions(bestVersion, current) > 0,
            prerelease = latest.optBoolean("prerelease", false),
            title = latest.optString("name").ifBlank { "v$bestVersion" },
            notes = latest.optString("body").ifBlank { "暂无更新日志" },
            htmlUrl = latest.optString("html_url").ifBlank { DesktopAppMeta.RELEASES_WEB_URL },
            msiAssetName = msiName,
            msiDownloadUrl = msiUrl,
        )
    }

    internal fun compareVersions(a: String, b: String): Int {
        val pa = parseParts(a)
        val pb = parseParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun parseParts(v: String): List<Int> =
        v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.toIntOrNull() }

    private fun applyMirror(url: String, prefix: String): String {
        val p = prefix.trim()
        if (p.isEmpty()) return url
        val norm = if (p.endsWith("/")) p else "$p/"
        return if (url.startsWith(norm)) url else "$norm$url"
    }
}

@Composable
internal fun DesktopLogViewerDialog(
    onDismiss: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val logs by DesktopLogStore.entries.collectAsState()
    var levelFilter by remember { mutableStateOf<DesktopLogLevel?>(null) }
    var query by remember { mutableStateOf("") }

    val visible = remember(logs, levelFilter, query) {
        val q = query.trim().lowercase()
        logs.asReversed().filter { entry ->
            (levelFilter == null || entry.level == levelFilter) &&
                (q.isEmpty() || entry.formatLine().lowercase().contains(q))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("运行日志查看器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                DesktopPill("${visible.size} / ${logs.size} 条")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = levelFilter == null,
                        onClick = { levelFilter = null },
                        label = { Text("全部") },
                    )
                    DesktopLogLevel.entries.forEach { lv ->
                        FilterChip(
                            selected = levelFilter == lv,
                            onClick = { levelFilter = lv },
                            label = { Text(lv.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("按关键词、模块或错误信息过滤日志…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                ) {
                    if (visible.isEmpty()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("暂无匹配的日志记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(visible) { item ->
                                val color = when (item.level) {
                                    DesktopLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    DesktopLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                    DesktopLogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(
                                    text = item.formatLine(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = color,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(DesktopLogStore.exportText())
                        onNotify("已复制全部日志到剪贴板")
                    },
                ) { Text("复制全部") }
                DesktopLogStore.currentLogFile()?.let { file ->
                    OutlinedButton(
                        onClick = { revealInExplorer(file) },
                    ) { Text("定位日志文件") }
                }
                TextButton(
                    onClick = {
                        DesktopLogStore.clear()
                        onNotify("已清空内存日志缓冲")
                    },
                ) { Text("清空") }
                Button(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
internal fun DesktopUpdateDialog(
    info: DesktopUpdateInfo,
    onDismiss: () -> Unit,
    onDownloadMsi: ((name: String, url: String) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (info.hasUpdate) "发现新版本 ${info.latestVersion}" else "当前已是最新版本")
                if (info.prerelease) DesktopPill("预发布")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前版本：${info.currentVersion} · 最新版本：${info.latestVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(info.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(info.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.msiDownloadUrl != null && info.msiAssetName != null && onDownloadMsi != null) {
                    Button(
                        onClick = {
                            onDownloadMsi(info.msiAssetName, info.msiDownloadUrl)
                            onDismiss()
                        },
                    ) { Text("下载 MSI 安装包") }
                }
                OutlinedButton(
                    onClick = {
                        runCatching { Desktop.getDesktop().browse(URI(info.htmlUrl)) }
                    },
                ) { Text("打开发布页") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

internal fun pickExportJsonFile(defaultName: String = "yunget-desktop-config.json"): File? {
    val chooser = JFileChooser(File(System.getProperty("user.home"), "Desktop")).apply {
        dialogTitle = "导出 YunGet 配置备份"
        selectedFile = File(currentDirectory, defaultName)
        fileFilter = FileNameExtensionFilter("JSON 配置文件 (*.json)", "json")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val f = chooser.selectedFile ?: return null
    return if (f.name.endsWith(".json", ignoreCase = true)) f else File(f.parentFile, "${f.name}.json")
}

internal fun pickImportJsonFile(): File? {
    val chooser = JFileChooser(File(System.getProperty("user.home"), "Desktop")).apply {
        dialogTitle = "选择 YunGet 配置备份文件"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("JSON 配置文件 (*.json)", "json")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
