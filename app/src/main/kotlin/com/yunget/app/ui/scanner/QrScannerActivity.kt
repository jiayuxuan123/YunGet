package com.yunget.app.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunget.app.ui.theme.ComposeEmptyActivityTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeEmptyActivityTheme(dynamicColor = false) {
                QrScannerScreen(
                    onClose = { finish() },
                    onResult = ::deliverResult,
                )
            }
        }
    }

    private fun deliverResult(raw: String) {
        if (delivered) return
        delivered = true
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_SCAN_RESULT, raw.take(MAX_RESULT_CHARS)),
        )
        finish()
    }

    companion object {
        const val EXTRA_SCAN_RESULT = "qr_scan_result"
        private const val MAX_RESULT_CHARS = 16_384

        fun createIntent(context: Context): Intent =
            Intent(context, QrScannerActivity::class.java)
    }
}

@Composable
fun rememberQrScanLauncher(onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(QrScannerActivity.EXTRA_SCAN_RESULT)
                ?.takeIf { it.isNotBlank() }
                ?.let(onResult)
        }
    }
    return remember(launcher, context) {
        { launcher.launch(QrScannerActivity.createIntent(context)) }
    }
}

@Composable
private fun QrScannerScreen(
    onClose: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        requested = true
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onClose)

    if (!granted) {
        QrPermissionScreen(
            requested = requested,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onClose = onClose,
        )
        return
    }

    QrCameraContent(onClose = onClose, onResult = onResult)
}

@Composable
private fun QrCameraContent(
    onClose: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        future.addListener(
            {
                runCatching {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(
                                executor,
                                QrFrameAnalyzer { value ->
                                    if (!handled) {
                                        handled = true
                                        previewView.post { onResult(value) }
                                    }
                                },
                            )
                        }
                    camera = checkNotNull(provider).bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        checkNotNull(analysis),
                    )
                }.onFailure {
                    cameraError = it.message ?: "无法启动相机"
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            executor.shutdownNow()
        }
    }

    LaunchedEffect(camera, torchEnabled) {
        camera?.cameraControl?.enableTorch(torchEnabled)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        QrScannerOverlay()

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            color = Color.Black.copy(alpha = 0.44f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭扫码")
                }
                Text(
                    text = "扫描二维码",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { torchEnabled = !torchEnabled },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = if (torchEnabled) "关闭闪光灯" else "打开闪光灯",
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.58f),
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = cameraError ?: "将二维码放入取景框内，识别过程只在本机完成",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun QrScannerOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.68f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val dim = Color.Black.copy(alpha = 0.38f)

        drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(dim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(dim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(dim, topLeft = Offset(right, top), size = Size(size.width - right, side))

        val corner = side * 0.14f
        val strokeWidth = 4.dp.toPx()
        val lineColor = Color.White
        listOf(
            Pair(Offset(left, top), Offset(left + corner, top)),
            Pair(Offset(left, top), Offset(left, top + corner)),
            Pair(Offset(right, top), Offset(right - corner, top)),
            Pair(Offset(right, top), Offset(right, top + corner)),
            Pair(Offset(left, bottom), Offset(left + corner, bottom)),
            Pair(Offset(left, bottom), Offset(left, bottom - corner)),
            Pair(Offset(right, bottom), Offset(right - corner, bottom)),
            Pair(Offset(right, bottom), Offset(right, bottom - corner)),
        ).forEach { (start, end) ->
            drawLine(lineColor, start, end, strokeWidth, StrokeCap.Round)
        }
        drawRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(left, top),
            size = Size(side, side),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun QrPermissionScreen(
    requested: Boolean,
    onRequest: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(18.dp))
            Text("需要相机权限", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (requested) {
                    "相机权限未开启。你可以再次授权，或前往系统设置手动开启。"
                } else {
                    "扫码解析需要使用相机读取二维码。画面只在设备本机识别，不会上传。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("允许使用相机")
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                },
                modifier = Modifier.height(48.dp),
            ) {
                Text("打开应用设置")
            }
            TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
                Text("返回解析页")
            }
        }
    }
}

private class QrFrameAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer.duplicate()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val height = image.height
            val rowStride = plane.rowStride
            val dataWidth = if (data.size >= rowStride * height) rowStride else image.width
            QrDecoder.decodeLuminance(
                data = data,
                dataWidth = dataWidth,
                dataHeight = height,
                left = 0,
                top = 0,
                width = image.width,
                height = height,
            )?.let(onDecoded)
        } finally {
            busy.set(false)
            image.close()
        }
    }
}
