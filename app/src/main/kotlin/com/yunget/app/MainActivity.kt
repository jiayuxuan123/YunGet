package com.yunget.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yunget.app.ui.MainScreen
import com.yunget.app.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {

    // Android 13+：下载前台服务通知需要动态授权，首次启动即引导（无论通知栏开关状态，授权后通知才可见）
    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen()
            }
        }
    }

    /** Android 13+ 申请通知权限；低版本（<33）系统自动授予，无需申请 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}