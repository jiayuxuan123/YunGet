package com.yunget.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.yunget.app.data.community.CommunityApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityWebScreen(
    initialUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    val communityHost = remember { Uri.parse(CommunityApi.BASE_URL).host.orEmpty() }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var trustedPage by remember { mutableStateOf(true) }
    var alive by remember { mutableStateOf(true) }
    var pageReady by remember { mutableStateOf(false) }
    var pendingPickedFiles by remember { mutableStateOf<List<String>?>(null) }
    var pageUrl by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var pickerUrl by rememberSaveable(initialUrl) { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == Activity.RESULT_OK) {
            buildList {
                val intent = result.data
                intent?.data?.let { add(it) }
                intent?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { add(it) }
                }
            }.filter { it.scheme == "content" }.distinct().take(4).toTypedArray()
        } else emptyArray()
        val callback = fileCallback
        val samePage = trustedPage && pickerUrl == pageUrl
        if (callback != null) {
            callback.onReceiveValue(selected.takeIf { samePage && it.isNotEmpty() })
            fileCallback = null
            pickerUrl = null
        } else if (pickerUrl != null && selected.isNotEmpty()) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    selected.mapNotNull { webImageDataUrl(context, it) }
                }
                if (alive && encoded.isNotEmpty()) pendingPickedFiles = encoded
                else pickerUrl = null
            }
        } else pickerUrl = null
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            settings.setSupportMultipleWindows(false)
            settings.loadsImagesAutomatically = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    val current = view.url?.let(Uri::parse)
                    if (current?.scheme != "https" || current.host != communityHost) return false
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    pickerUrl = current.toString()
                    pageUrl = current.toString()
                    return try {
                        imagePicker.launch(params.createIntent().apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                        true
                    } catch (_: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        pickerUrl = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                    pageReady = false
                    val current = url?.let(Uri::parse)
                    trustedPage = current?.scheme == "https" && current.host == communityHost
                    if (trustedPage && !url.isNullOrBlank()) pageUrl = url
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    val current = url?.let(Uri::parse)
                    if (current?.scheme == "https" && current.host == communityHost) pageUrl = current.toString()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    pageReady = true
                    CookieManager.getInstance().flush()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    val host = uri.host.orEmpty()
                    val handledInside = host == communityHost ||
                        host == "challenges.cloudflare.com" ||
                        host.endsWith(".cloudflareaccess.com")
                    if (uri.scheme == "https" && handledInside) return false
                    if (uri.scheme !in setOf("http", "https", "mailto")) return true
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }
            }
            val restored = Uri.parse(pageUrl)
            val initial = if (restored.getQueryParameter("embed") == "app") restored
                else restored.buildUpon().appendQueryParameter("embed", "app").build()
            loadUrl(initial.toString())
        }
    }
    LaunchedEffect(pageReady, pageUrl, pendingPickedFiles) {
        val files = pendingPickedFiles ?: return@LaunchedEffect
        val target = pickerUrl ?: return@LaunchedEffect
        if (!pageReady || !trustedPage || target != pageUrl) return@LaunchedEffect
        val href = JSONObject.quote(target)
        val payload = JSONArray(files).toString()
        repeat(20) {
            val accepted = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                webView.evaluateJavascript(
                    "location.href === $href && typeof window.yungetReceivePickedFiles === 'function' && window.yungetReceivePickedFiles($payload)",
                ) { value -> if (continuation.isActive) continuation.resumeWith(Result.success(value == "true")) }
            }
            if (accepted) {
                pendingPickedFiles = null
                pickerUrl = null
                return@LaunchedEffect
            }
            delay(250)
        }
        pendingPickedFiles = null
        pickerUrl = null
    }

    fun navigateBack() {
        fun leavePage() { if (webView.canGoBack()) webView.goBack() else onBack() }
        if (trustedPage) webView.evaluateJavascript("Boolean(window.yungetCommunityBack && window.yungetCommunityBack())") { handled ->
            if (alive && handled != "true") leavePage()
        } else leavePage()
    }
    BackHandler { navigateBack() }
    DisposableEffect(Unit) {
        onDispose {
            alive = false
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            CookieManager.getInstance().flush()
            webView.destroy()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("云取社区") },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新社区")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter))
            }
        }
    }
}

private fun webImageDataUrl(context: android.content.Context, uri: Uri): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (bounds.outWidth / sample > 2880 || bounds.outHeight / sample > 2880) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null

    try {
        val maxEdge = maxOf(decoded.width, decoded.height)
        val scaled = if (maxEdge > 1440) {
            val ratio = 1440f / maxEdge
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded
        try {
            repeat(5) { attempt ->
                val output = ByteArrayOutputStream()
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                scaled.compress(format, 82 - attempt * 4, output)
                if (output.size() in 1..(480 * 1024)) {
                    return@runCatching "data:image/webp;base64," +
                        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
        }
    } finally {
        decoded.recycle()
    }
    null
}.getOrNull()
