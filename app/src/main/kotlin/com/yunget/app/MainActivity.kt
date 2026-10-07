package com.yunget.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yunget.app.ui.MainScreen
import com.yunget.app.ui.screens.SafetyNoticeDialog
import com.yunget.app.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {

    // ★ 通知权限不再在启动时申请/引导：统一收到引导页第 3 页（见 ui/screens/OnboardingPermissionPage.kt），
    //   之后只有设置页里的手动入口（「通知栏下载进度」）会再申请，避免每次启动都弹窗打扰。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen()
                // 首次启动的安全提示（确认过就写标记，之后不再弹，见 SafetyNoticeDialog）
                SafetyNoticeDialog()
            }
        }
    }
}