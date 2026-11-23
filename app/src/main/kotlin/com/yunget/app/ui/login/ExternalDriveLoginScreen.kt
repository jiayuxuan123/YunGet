package com.yunget.app.ui.login

import android.app.Dialog
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yunget.app.data.account.ExternalAccountStore
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.download.DownloadSubmissionContext
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.OfficialWebDrive
import com.yunget.app.data.network.video.ClientHints
import com.yunget.app.data.network.video.alignClientHintsWith
import com.yunget.app.ui.SnackbarController
import com.yunget.app.util.DiagnosticLog
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal data class ExternalDrive(
    val title: String,
    val avatar: String,
    val url: String? = null,
    val desktopUserAgent: Boolean = false,
    val additionalSessionSites: List<String> = emptyList(),
)

internal sealed interface ExternalDriveNavigationAction {
    data object Allow : ExternalDriveNavigationAction
    data class RedirectHttps(val url: String) : ExternalDriveNavigationAction
    data object Block : ExternalDriveNavigationAction
}

internal fun resolveExternalDriveNavigation(rawUrl: String): ExternalDriveNavigationAction {
    val trimmed = rawUrl.trim()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return when (scheme) {
        "https" -> ExternalDriveNavigationAction.Allow
        "http" -> {
            val rest = trimmed.substringAfter(':', "").trimStart('/')
            if (rest.isBlank()) ExternalDriveNavigationAction.Block
            else ExternalDriveNavigationAction.RedirectHttps("https://$rest")
        }
        else -> ExternalDriveNavigationAction.Block
    }
}

internal fun isWeiyunSessionTarget(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path?.lowercase().orEmpty()
    val isWeiyunHost = host == "weiyun.com" || host.endsWith(".weiyun.com")
    return isWeiyunHost && (path == "/disk" || path.startsWith("/disk/"))
}

internal fun externalDriveScreenTitle(driveTitle: String?, browseMode: Boolean): String {
    val name = driveTitle ?: "网盘"
    return if (browseMode) name else "${name}登录"
}

internal val externalDrives = mapOf(
    "feijipan" to ExternalDrive("小飞机网盘", "飞"),
) + OfficialWebDrive.entries.associate { drive ->
    val avatar = when (drive) {
        OfficialWebDrive.CTFILE -> "城"
        OfficialWebDrive.ALIYUN -> "阿"
        OfficialWebDrive.TIANYI -> "翼"
        OfficialWebDrive.WEIYUN -> "微"
        OfficialWebDrive.WOPAN -> "沃"
        OfficialWebDrive.PIKPAK -> "P"
    }
    val extraSites = when (drive) {
        OfficialWebDrive.ALIYUN -> listOf("aliyundrive.com")
        OfficialWebDrive.TIANYI -> listOf("dlife.cn")
        OfficialWebDrive.WEIYUN -> listOf("qq.com", "open.weixin.qq.com")
        else -> emptyList()
    }
    drive.id to ExternalDrive(
        drive.displayName, avatar, drive.entryUrl,
        desktopUserAgent = drive != OfficialWebDrive.CTFILE && drive != OfficialWebDrive.WOPAN && drive != OfficialWebDrive.TIANYI,
        additionalSessionSites = extraSites,
    )
}

internal fun clearExternalDriveSession(platform: String, onFinished: (Boolean) -> Unit) {
    val web = externalDrives[platform]
    val url = web?.url
    if (web == null || url == null) {
        onFinished(true)
        return
    }
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
        onFinished(false)
        return
    }
    val sites = listOf(url) + web.additionalSessionSites
    var remaining = sites.size
    for (site in sites) {
        WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), site) {
            if (--remaining == 0) onFinished(true)
        }
    }
}

private val chromeVersionPattern = Regex("""Chrome/[\d.]+""")

private fun configureDriveWebView(
    webView: WebView,
    desktopMode: Boolean,
    supportMultipleWindows: Boolean,
) {
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(supportMultipleWindows)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    if (desktopMode) {
        chromeVersionPattern.find(settings.userAgentString)?.value?.let { chrome ->
            val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
            settings.userAgentString = desktopUa
            settings.alignClientHintsWith(desktopUa)
            val platformName = ClientHints.fromUserAgent(desktopUa).navigatorPlatform
            if (platformName != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val platformJs = "(function(){try{Object.defineProperty(navigator,'platform'," +
                    "{get:function(){return '$platformName';},configurable:true});}catch(e){}})()"
                WebViewCompat.addDocumentStartJavaScript(webView, platformJs, setOf("*"))
            }
        }
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalDriveLoginScreen(
    platform: String,
    downloadManager: DownloadManager,
    initialUrl: String? = null,
    browseMode: Boolean = false,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDownloaded: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ExternalAccountStore(context) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var saving by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val drive = externalDrives[platform]
    val webUrl = drive?.url
    val startUrl = initialUrl ?: webUrl
    val title = externalDriveScreenTitle(drive?.title, browseMode = browseMode)
    var pageError by remember(startUrl) { mutableStateOf<String?>(null) }
    var loadProgress by remember(startUrl) { mutableIntStateOf(0) }
    var useDesktopMode by remember(platform) { mutableStateOf(drive?.desktopUserAgent == true) }
    var webViewRef by remember(startUrl) { mutableStateOf<WebView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var clearingSession by remember { mutableStateOf(false) }
    var webPageGeneration by remember { mutableIntStateOf(0) }

    fun openInBrowser() {
        val target = webViewRef?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: startUrl
        if (target == null) return
        runCatching { uriHandler.openUri(target) }.onFailure {
            SnackbarController.show("无法打开浏览器")
        }
    }

    LaunchedEffect(clearingSession) {
        if (!clearingSession) return@LaunchedEffect
        clearExternalDriveSession(platform) { cleared ->
            clearingSession = false
            if (cleared) {
                store.delete(platform)
                SnackbarController.show("已清除${drive?.title}的网页登录状态")
                onSaved()
            } else {
                SnackbarController.show("当前系统 WebView 无法单独清除网站数据，请在网页内退出或更新 WebView")
            }
        }
    }

    fun saveFeijipan() {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                val login = FeijipanApi().login(user, password)
                val payload = JSONObject()
                    .put("token", login.token)
                    .put("uuid", login.uuid)
                    .put("username", user.trim())
                    .put("password", password)
                    .put("userId", login.account.userId)
                    .put("nickname", login.account.nickname)
                    .put("used", login.account.usedBytes)
                    .put("total", login.account.totalBytes)
                    .toString()
                store.save("feijipan", login.account.nickname, payload)
            }.onSuccess {
                SnackbarController.show("小飞机登录成功")
                onSaved()
            }.onFailure {
                SnackbarController.show(it.message ?: "登录失败")
            }
            saving = false
        }
    }

    fun saveWebAccount() {
        if (saving || drive == null || webUrl == null) return
        CookieManager.getInstance().flush()
        val cookie = if (platform == "ctfile") {
            CookieManager.getInstance().getCookie(webUrl).orEmpty()
        } else ""
        if (platform == "ctfile" && cookie.isBlank()) {
            SnackbarController.show("未检测到${drive.title}登录状态")
            return
        }
        saving = true
        runCatching {
            val nickname = drive.title
            val payload = JSONObject().put("nickname", nickname).apply {
                if (platform == "ctfile") put("cookie", cookie)
            }.toString()
            store.save(platform, nickname, payload)
        }.onSuccess {
            SnackbarController.show("${drive.title}网页入口已保存")
            onSaved()
        }.onFailure {
            SnackbarController.show(it.message ?: "保存失败")
        }
        saving = false
    }

    BackHandler(enabled = !saving && !clearingSession) {
        val currentWebView = webViewRef
        if (currentWebView != null && currentWebView.canGoBack()) {
            currentWebView.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving && !clearingSession) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!browseMode || platform == "feijipan") {
                        TextButton(
                            onClick = { if (platform == "feijipan") saveFeijipan() else saveWebAccount() },
                            enabled = !saving && !clearingSession && pageError == null,
                        ) { Text("保存") }
                    }
                    if (webUrl != null) {
                        IconButton(onClick = { menuOpen = true }, enabled = !clearingSession) {
                            Icon(Icons.Default.MoreVert, contentDescription = "网页操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("重新加载网页") }, onClick = {
                                menuOpen = false
                                pageError = null
                                webViewRef?.clearCache(true)
                                webPageGeneration++
                            })
                            DropdownMenuItem(text = { Text("回到网盘首页") }, onClick = {
                                menuOpen = false
                                pageError = null
                                val current = webViewRef
                                if (current != null) current.loadUrl(webUrl)
                                else webPageGeneration++
                            })
                            DropdownMenuItem(
                                text = { Text(if (useDesktopMode) "切换为移动版网页" else "切换为桌面版网页") },
                                onClick = {
                                    menuOpen = false
                                    pageError = null
                                    useDesktopMode = !useDesktopMode
                                    webPageGeneration++
                                },
                            )
                            DropdownMenuItem(text = { Text("在浏览器打开") }, onClick = {
                                menuOpen = false
                                openInBrowser()
                            })
                            DropdownMenuItem(text = { Text("退出网页账号") }, onClick = {
                                menuOpen = false
                                clearingSession = true
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (platform == "feijipan") {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("使用小飞机账号登录后，可浏览个人文件和分享文件。")
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { saveFeijipan() },
                    enabled = !saving && user.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("登录小飞机")
                }
            }
        } else if (startUrl != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                val error = pageError
                if (clearingSession) {
                    Text("正在退出网页账号…", Modifier.padding(16.dp))
                } else if (error != null) {
                    Text(error, Modifier.padding(16.dp))
                    TextButton(onClick = {
                        pageError = null
                        webPageGeneration++
                    }) { Text("重试") }
                    TextButton(onClick = ::openInBrowser) { Text("在浏览器打开") }
                } else {
                    if (!browseMode) {
                        Text(
                            "请在官网完成登录。网页触发的下载会交给云取，登录后可点“保存”保留入口。",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (loadProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { loadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    key(startUrl, useDesktopMode, webPageGeneration) {
                        var loginWindow by remember { mutableStateOf<Dialog?>(null) }
                        val supportsLoginPopup = drive?.url == OfficialWebDrive.WEIYUN.entryUrl
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onRelease = {
                                loginWindow?.dismiss()
                                if (webViewRef === it) webViewRef = null
                                it.destroy()
                            },
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    webViewRef = this
                                    configureDriveWebView(
                                        webView = this,
                                        desktopMode = useDesktopMode,
                                        supportMultipleWindows = supportsLoginPopup,
                                    )
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                        }

                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                                DiagnosticLog.webview(
                                                    "external_console_error",
                                                    consoleMessage.sourceId(),
                                                    code = consoleMessage.lineNumber(),
                                                    summary = "platform=$platform msg=${consoleMessage.message()}",
                                                )
                                            }
                                            return super.onConsoleMessage(consoleMessage)
                                        }

                                        override fun onCreateWindow(
                                            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
                                        ): Boolean {
                                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                            if (!supportsLoginPopup || loginWindow != null) {
                                                // 非弹窗窗口或已有弹窗时，将新窗口导航回收到当前主 WebView，避免静默丢弃跳转
                                                val relay = WebView(viewContext).apply {
                                                    webViewClient = object : WebViewClient() {
                                                        override fun shouldOverrideUrlLoading(
                                                            relayView: WebView,
                                                            request: WebResourceRequest,
                                                        ): Boolean {
                                                            val target = request.url?.toString().orEmpty()
                                                            when (val action = resolveExternalDriveNavigation(target)) {
                                                                ExternalDriveNavigationAction.Allow -> view.loadUrl(target)
                                                                is ExternalDriveNavigationAction.RedirectHttps -> view.loadUrl(action.url)
                                                                ExternalDriveNavigationAction.Block -> Unit
                                                            }
                                                            relayView.stopLoading()
                                                            relayView.post { relayView.destroy() }
                                                            return true
                                                        }
                                                    }
                                                }
                                                transport.webView = relay
                                                resultMsg.sendToTarget()
                                                return true
                                            }
                                            val dialog = Dialog(viewContext)
                                            val child = WebView(viewContext).apply {
                                                configureDriveWebView(
                                                    webView = this,
                                                    desktopMode = useDesktopMode,
                                                    supportMultipleWindows = false,
                                                )
                                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        childView: WebView,
                                                        request: WebResourceRequest,
                                                    ): Boolean {
                                                        if (!request.isForMainFrame) return false
                                                        val target = request.url?.toString().orEmpty()
                                                        return when (val action = resolveExternalDriveNavigation(target)) {
                                                            ExternalDriveNavigationAction.Allow -> {
                                                                if (isWeiyunSessionTarget(target)) {
                                                                    CookieManager.getInstance().flush()
                                                                    dialog.dismiss()
                                                                    view.loadUrl(target)
                                                                    true
                                                                } else {
                                                                    false
                                                                }
                                                            }
                                                            is ExternalDriveNavigationAction.RedirectHttps -> {
                                                                if (isWeiyunSessionTarget(action.url)) {
                                                                    CookieManager.getInstance().flush()
                                                                    dialog.dismiss()
                                                                    view.loadUrl(action.url)
                                                                } else {
                                                                    childView.loadUrl(action.url)
                                                                }
                                                                true
                                                            }
                                                            ExternalDriveNavigationAction.Block -> true
                                                        }
                                                    }

                                                    override fun onPageFinished(childView: WebView?, url: String?) {
                                                        CookieManager.getInstance().flush()
                                                        val finishedUrl = url.orEmpty()
                                                        if (isWeiyunSessionTarget(finishedUrl)) {
                                                            dialog.dismiss()
                                                            view.loadUrl(finishedUrl)
                                                        }
                                                    }

                                                    override fun onRenderProcessGone(childView: WebView, detail: RenderProcessGoneDetail): Boolean {
                                                        dialog.dismiss()
                                                        return true
                                                    }
                                                }
                                                webChromeClient = object : WebChromeClient() {
                                                    override fun onCloseWindow(window: WebView) = dialog.dismiss()
                                                }
                                            }
                                            dialog.setContentView(child)
                                            dialog.setOnDismissListener {
                                                loginWindow = null
                                                CookieManager.getInstance().flush()
                                                (child.parent as? ViewGroup)?.removeView(child)
                                                child.destroy()
                                            }
                                            loginWindow = dialog
                                            dialog.show()
                                            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                            transport.webView = child
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean {
                                            if (!request.isForMainFrame) return false
                                            val target = request.url?.toString().orEmpty()
                                            return when (val action = resolveExternalDriveNavigation(target)) {
                                                ExternalDriveNavigationAction.Allow -> false
                                                is ExternalDriveNavigationAction.RedirectHttps -> {
                                                    view.loadUrl(action.url)
                                                    true
                                                }
                                                ExternalDriveNavigationAction.Block -> true
                                            }
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            pageError = null
                                            DiagnosticLog.webview("external_page_started", url, summary = "platform=$platform desktop=$useDesktopMode")
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            loadProgress = 100
                                            CookieManager.getInstance().flush()
                                            DiagnosticLog.webview("external_page_finished", url, summary = "platform=$platform desktop=$useDesktopMode")
                                        }

                                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                            DiagnosticLog.webview(
                                                "external_page_error",
                                                request.url?.toString(),
                                                code = error.errorCode,
                                                summary = "platform=$platform main=${request.isForMainFrame} desc=${error.description}",
                                            )
                                            if (request.isForMainFrame) pageError = "网页未能加载，请检查网络后重试。"
                                        }

                                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                            DiagnosticLog.webview(
                                                "external_http_error",
                                                request.url?.toString(),
                                                code = response.statusCode,
                                                summary = "platform=$platform main=${request.isForMainFrame}",
                                            )
                                            if (request.isForMainFrame) pageError = "网页返回 HTTP ${response.statusCode}，可以重试或改用浏览器。"
                                        }

                                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                            (view.parent as? ViewGroup)?.removeView(view)
                                            pageError = "网页渲染已停止，请重试。"
                                            return true
                                        }
                                    }
                                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                            .ifBlank { URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }
                                        scope.launch {
                                            runCatching {
                                                downloadManager.enqueue(
                                                    url = url,
                                                    fileName = fileName.ifBlank { "download.bin" },
                                                    headers = buildMap {
                                                        if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                                                        if (cookie.isNotBlank()) put("Cookie", cookie)
                                                        put("Referer", startUrl)
                                                    },
                                                    platform = platform,
                                                    submission = DownloadSubmissionContext(),
                                                )
                                            }.onSuccess {
                                                SnackbarController.show("已加入下载")
                                                onDownloaded()
                                            }.onFailure {
                                                SnackbarController.show(it.message ?: "下载失败")
                                            }
                                        }
                                    }
                                    loadUrl(startUrl)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
