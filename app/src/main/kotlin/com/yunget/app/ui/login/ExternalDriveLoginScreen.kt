package com.yunget.app.ui.login

import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunget.app.data.account.ExternalAccountStore
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.download.DownloadSubmissionContext
import com.yunget.app.data.network.FeijipanApi
import com.yunget.app.ui.SnackbarController
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLDecoder

private data class WebDrive(
    val title: String,
    val url: String,
    val desktopUserAgent: Boolean = false,
)

private fun webDrive(platform: String): WebDrive? = when (platform) {
    "ctfile" -> WebDrive("城通网盘", "https://www.ctfile.com/")
    "aliyun" -> WebDrive("阿里云盘", "https://www.alipan.com/sign/in", desktopUserAgent = true)
    "tianyi" -> WebDrive("天翼云盘", "https://cloud.189.cn/web/login.html")
    "weiyun" -> WebDrive("腾讯微云", "https://www.weiyun.com/", desktopUserAgent = true)
    "wopan" -> WebDrive("联通沃盘", "https://pan.wo.cn/")
    "pikpak" -> WebDrive("PikPak", "https://mypikpak.com/", desktopUserAgent = true)
    else -> null
}

private const val DESKTOP_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
    var saving by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val web = webDrive(platform)
    val title = if (platform == "feijipan") "小飞机网盘登录" else "${web?.title ?: "网盘"}登录"

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

    fun saveCtfile() {
        if (saving) return
        val cookie = buildList {
            CookieManager.getInstance().getCookie(web?.url.orEmpty())?.let(::add)
            web?.let { CookieManager.getInstance().getCookie("https://${it.url.substringAfter("://").substringBefore('/')}")?.let(::add) }
        }.joinToString("; ").trim()
        if (cookie.isBlank()) {
            SnackbarController.show("未检测到城通登录状态")
            return
        }
        saving = true
        runCatching {
            val nickname = web?.title ?: "网页账号"
            val payload = JSONObject().put("cookie", cookie).put("nickname", nickname).toString()
            store.save(platform, nickname, payload)
        }.onSuccess {
            SnackbarController.show("城通账号已保存")
            onSaved()
        }.onFailure {
            SnackbarController.show(it.message ?: "保存失败")
        }
        saving = false
    }

    BackHandler(enabled = !saving) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { if (platform == "feijipan") saveFeijipan() else saveCtfile() },
                        enabled = !saving,
                    ) { Text("保存") }
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
        } else {
            val webView = remember {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    if (web?.desktopUserAgent == true) {
                        settings.userAgentString = DESKTOP_WEB_USER_AGENT
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
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
                                        put("Referer", initialUrl ?: web?.url ?: url)
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
                    loadUrl(initialUrl ?: web?.url ?: return@apply)
                }
            }
            DisposableEffect(Unit) { onDispose { webView.destroy() } }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "请在网页完成登录，或浏览文件；网页触发的下载会交给云取。点右上角“保存”可记住账号。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                AndroidView({ webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
