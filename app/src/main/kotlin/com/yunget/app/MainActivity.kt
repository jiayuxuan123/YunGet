package com.yunget.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.yunget.app.ui.MainScreen
import com.yunget.app.ui.screens.SafetyNoticeDialog
import com.yunget.app.ui.theme.ComposeEmptyActivityTheme
import com.yunget.app.util.DeepLinks

class MainActivity : ComponentActivity() {

    /**
     * 从深链接 / 系统分享 / 选中文本解析拿到的待处理内容。
     *
     * 用 Compose 状态而不是普通字段：它在 `setContent` **之前** 赋值，
     * 组合后 MainScreen 读到的就是首次 intent 的内容；
     * 应用已在前台时 `onNewIntent` 再赋值，MainScreen 会因为状态变化而重组并消费。
     *
     * 消费后必须置 null（见 MainScreen 的回调），否则每次重组都会重新解析一遍。
     */
    private val pendingLink = mutableStateOf<String?>(null)

    // ★ 通知权限不再在启动时申请/引导：统一收到引导页第 3 页（见 ui/screens/OnboardingPermissionPage.kt），
    //   之后只有设置页里的手动入口（「通知栏下载进度」）会再申请，避免每次启动都弹窗打扰。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 只在**首次创建**时消费 intent：
        // 屏幕旋转 / 深色模式切换 / 进程重建都会重新走 onCreate，
        // 那时 savedInstanceState 非空，再解析一次会让用户莫名其妙被弹回解析页。
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen(
                    incomingLink = pendingLink.value,
                    onIncomingLinkConsumed = { pendingLink.value = null },
                )
                // 首次启动的安全提示（确认过就写标记，之后不再弹，见 SafetyNoticeDialog）
                SafetyNoticeDialog()
            }
        }
    }

    /**
     * 应用已在前台时再点链接：`launchMode` 决定系统把 intent 送到这里
     * （而不是再起一个实例），赋值后由 MainScreen 消费。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * 从 intent 三要素里取出待解析文本。
     *
     * 覆盖三类入口：
     *  - `VIEW`：浏览器点开网盘分享链接（12 个 activity-alias 各自过滤域名）；
     *  - `SEND`：其它应用「分享 → 云取」；
     *  - `PROCESS_TEXT`：选中文本后的系统菜单「云取」。
     *
     * 磁力链接单独处理（见 [DeepLinks.textFrom] 的注释）：它只能交给下载引擎，
     * 走解析链路会被当成"无法识别"。
     */
    private fun handleDeepLink(intent: Intent?) {
        val text = DeepLinks.textFrom(
            action = intent?.action,
            data = intent?.dataString,
            extraText = when (intent?.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_PROCESS_TEXT ->
                    intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                else -> null
            },
        ) ?: return
        pendingLink.value = text
    }
}
