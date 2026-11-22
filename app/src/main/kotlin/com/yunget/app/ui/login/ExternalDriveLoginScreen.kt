package com.yunget.app.ui.login

import android.app.Dialog
import android.os.Message
import android.view.ViewGroup
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
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.yunget.app.data.account.ExternalAccountStore
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.download.DownloadSubmissionContext
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.data.network.OfficialWebDrive
import com.yunget.app.ui.SnackbarController
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLDecoder

internal data class ExternalDrive(
    val title: String,
    val avatar: String,
    val url: String? = null,
    val desktopUserAgent: Boolean = false,
    val additionalSessionSites: List<String> = emptyList(),
)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalDriveLoginScreen(
    platform: String,
    downloadManager: DownloadManager,
    initialUrl: String? = null,
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
    val title = "${drive?.title ?: "网盘"}登录"
    var pageError by remember(startUrl) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var clearingSession by remember { mutableStateOf(false) }
    var webPageGeneration by remember { mutableStateOf(0) }

    fun openInBrowser() {
        if (startUrl == null) return
        runCatching { uriHandler.openUri(startUrl) }.onFailure {
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

    BackHandler(enabled = !saving && !clearingSession) { onBack() }

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
                    TextButton(
                        onClick = { if (platform == "feijipan") saveFeijipan() else saveWebAccount() },
                        enabled = !saving && !clearingSession && pageError == null,
                    ) { Text("保存") }
                    if (webUrl != null) {
                        IconButton(onClick = { menuOpen = true }, enabled = !clearingSession) {
                            Icon(Icons.Default.MoreVert, contentDescription = "网页操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("重新加载网页") }, onClick = {
                                menuOpen = false
                                pageError = null
                                webPageGeneration++
                            })
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
                    TextButton(onClick = { pageError = null }) { Text("重试") }
                    TextButton(onClick = ::openInBrowser) { Text("在浏览器打开") }
                } else {
                    Text(
                        "请在官网完成登录。网页触发的下载会交给云取，登录后可点“保存”保留入口。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    key(startUrl, webPageGeneration) {
                        var loginWindow by remember { mutableStateOf<Dialog?>(null) }
                        val supportsLoginPopup = drive?.url == OfficialWebDrive.WEIYUN.entryUrl
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onRelease = {
                                loginWindow?.dismiss()
                                it.destroy()
                            },
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    settings.setSupportMultipleWindows(supportsLoginPopup)
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    if (drive?.desktopUserAgent == true) {
                                        chromeVersionPattern.find(settings.userAgentString)?.value?.let { chrome ->
                                            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                                "AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
                                        }
                                    }
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onCreateWindow(
                                            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
                                        ): Boolean {
                                            if (!supportsLoginPopup || !isUserGesture || loginWindow != null) return false
                                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                            val dialog = Dialog(viewContext)
                                            val child = WebView(viewContext).apply {
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.allowFileAccess = false
                                                settings.allowContentAccess = false
                                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                                settings.userAgentString = view.settings.userAgentString
                                                settings.useWideViewPort = true
                                                settings.loadWithOverviewMode = true
                                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                                        request.isForMainFrame && request.url.scheme != "https"
                                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
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
                                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                            if (request.isForMainFrame) pageError = "网页未能加载，请检查网络后重试。"
                                        }

                                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
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
