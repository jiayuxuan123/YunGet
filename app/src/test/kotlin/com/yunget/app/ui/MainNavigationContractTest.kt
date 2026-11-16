package com.yunget.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主界面翻页和设置子页面的结构回归。
 *
 * 这里读源码而不是做界面测试，是因为问题本身来自 Compose 的页面同步顺序：
 * 跨两个 Tab 切换时，中间页会短暂成为 currentPage；如果此时回写状态，
 * 正在执行的翻页动画会被截断。引擎页则必须在设置列表之前直接返回，
 * 不能作为同层 Scaffold 叠在设置页后面。
 */
class MainNavigationContractTest {
    private val mainScreen = File("src/main/kotlin/com/yunget/app/ui/MainScreen.kt").readText()
    private val settingsScreen = File("src/main/kotlin/com/yunget/app/ui/screens/SettingsScreen.kt").readText()

    @Test
    fun tabStateIsSynchronizedOnlyAfterPagerSettles() {
        assertTrue(
            "主 Tab 应在翻页停稳后从 settledPage 同步",
            mainScreen.contains("snapshotFlow { tabPager.settledPage }"),
        )
        assertFalse(
            "不能用 currentPage 回写 Tab，否则跨页会被中间页截断",
            mainScreen.contains("LaunchedEffect(tabPager.currentPage)"),
        )
        assertFalse(
            "外部 Tab 请求不能因为 pager 正在滚动而被忽略",
            mainScreen.contains("!tabPager.isScrollInProgress"),
        )
    }

    @Test
    fun engineScreenReplacesSettingsBeforeMainList() {
        val engineGuard = settingsScreen.indexOf("if (showDownloadEngine)")
        val activeConnections = settingsScreen.indexOf("val activeConns = activeConnectionsProvider()")
        assertTrue("应存在引擎页入口", engineGuard >= 0)
        assertTrue("引擎页判断必须位于设置列表创建之前", engineGuard < activeConnections)
        assertTrue(
            "引擎页显示后应直接结束设置页组合",
            settingsScreen.substring(engineGuard, activeConnections).contains("return"),
        )
    }
}
