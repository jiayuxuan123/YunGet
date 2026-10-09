package com.yunget.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunget.app.data.db.BaiduAccountEntity
import com.yunget.app.data.db.C139AccountEntity
import com.yunget.app.data.db.GuangYaAccountEntity
import com.yunget.app.data.db.ILanzouAccountEntity
import com.yunget.app.data.db.LanzouAccountEntity
import com.yunget.app.data.db.Pan115AccountEntity
import com.yunget.app.data.db.Pan123AccountEntity
import com.yunget.app.data.db.QuarkAccountEntity
import com.yunget.app.data.db.UCAccountEntity
import com.yunget.app.data.db.XunleiAccountEntity
import com.yunget.app.data.network.model.QuotaInfo
import com.yunget.app.ui.viewmodel.BaiduCloudViewModel
import com.yunget.app.ui.viewmodel.C139CloudViewModel
import com.yunget.app.ui.viewmodel.DriveQuotaViewModel
import com.yunget.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunget.app.ui.viewmodel.ILanzouCloudViewModel
import com.yunget.app.ui.viewmodel.LanzouCloudViewModel
import com.yunget.app.ui.viewmodel.Pan115CloudViewModel
import com.yunget.app.ui.viewmodel.Pan123CloudViewModel
import com.yunget.app.ui.viewmodel.QuarkCloudViewModel
import com.yunget.app.ui.viewmodel.UCCoudViewModel
import com.yunget.app.ui.viewmodel.XunleiCloudViewModel
import com.yunget.app.ui.theme.effectsDefault
import com.yunget.app.ui.theme.effectsFast

/**
 * 网盘账号展示模型。
 * TODO: 迅雷 / UC 后续接入 cookie 登录后，isLoggedIn 由真实登录态驱动。
 */
private data class DriveAccount(
    val id: String,
    val name: String,
    val description: String,
    val avatarText: String,
    val isLoggedIn: Boolean = false
)

/**
 * 网盘页列表里的一项：账号信息 + 已登录/未登录各自该做的事。
 *
 * [onClick] 是已登录时点卡片的动作（进浏览页），[onLogin] 是未登录时的动作（进登录页）。
 * 分开而不是在调用点写 `if (isLoggedIn) A else B`，是因为分组后列表要按登录态拆成两段，
 * 拆的时候不该再重新判断一次"这一项该干嘛"。
 */
private data class DriveEntry(
    val account: DriveAccount,
    val quota: QuotaInfo?,
    val onClick: () -> Unit,
    val onMoreClick: (() -> Unit)?,
    val onLogin: () -> Unit
)

/** 网盘分组标题（「已登录」/「未登录」）。 */
@Composable
private fun DriveGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 0.dp)
    )
}

/**
 * 网盘页：
 * - 夸克未登录：点击进入登录页；
 * - 夸克已登录：副标题显示昵称，点击弹出账号信息底部弹窗（可查看 Cookie / 退出登录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    quarkAccount: QuarkAccountEntity?,
    ucAccount: UCAccountEntity?,
    xunleiAccount: XunleiAccountEntity?,
    baiduAccount: BaiduAccountEntity?,
    c139Account: C139AccountEntity?,
    pan123Account: Pan123AccountEntity?,
    /** 115 网盘账号（MainScreen 接线后传入；默认 null 时卡片按未接线状态展示） */
    pan115Account: Pan115AccountEntity? = null,
    /** 光鸭云盘账号（同上） */
    guangyaAccount: GuangYaAccountEntity? = null,
    /** 蓝奏云优享版账号（同上） */
    ilanzouAccount: ILanzouAccountEntity? = null,
    /** 蓝奏云账号（同上） */
    lanzouAccount: LanzouAccountEntity? = null,
    /** 夸克云盘浏览 ViewModel（网盘 Tab 内切换展示，非全屏） */
    quarkCloudViewModel: QuarkCloudViewModel,
    /** UC 网盘云盘浏览 ViewModel */
    ucCloudViewModel: UCCoudViewModel,
    /** 迅雷网盘云盘浏览 ViewModel */
    xunleiCloudViewModel: XunleiCloudViewModel,
    /** 百度网盘云盘浏览 ViewModel */
    baiduCloudViewModel: BaiduCloudViewModel,
    /** 139 网盘云盘浏览 ViewModel */
    c139CloudViewModel: C139CloudViewModel,
    /** 123 云盘浏览 ViewModel */
    pan123CloudViewModel: Pan123CloudViewModel,
    /** 115 网盘浏览 ViewModel（MainScreen 尚未接线时为 null：卡片不进入浏览页，点击退回账号弹窗） */
    pan115CloudViewModel: Pan115CloudViewModel? = null,
    /** 光鸭云盘浏览 ViewModel（同上） */
    guangyaCloudViewModel: GuangYaCloudViewModel? = null,
    /** 蓝奏云优享版浏览 ViewModel（同上） */
    ilanzouCloudViewModel: ILanzouCloudViewModel? = null,
    /** 蓝奏云浏览 ViewModel（同上） */
    lanzouCloudViewModel: LanzouCloudViewModel? = null,
    /** 网盘空间详情 ViewModel（顶部空间总览） */
    driveQuotaViewModel: DriveQuotaViewModel,
    onQuarkLogin: () -> Unit,
    onQuarkLogout: () -> Unit,
    /** 夸克云盘下载入队后切换到「下载」Tab */
    onDownloadStarted: () -> Unit = {},
    onUCLogin: () -> Unit,
    onUCLogout: () -> Unit,
    onXunleiLogin: () -> Unit,
    onXunleiLogout: () -> Unit,
    onBaiduLogin: () -> Unit,
    onBaiduLogout: () -> Unit,
    onC139Login: () -> Unit,
    onC139Logout: () -> Unit,
    onPan123Login: () -> Unit,
    onPan123Logout: () -> Unit,
    /** 115 网盘登录/退出（MainScreen 接线后接管，默认空实现保证旧调用点仍可编译） */
    onPan115Login: () -> Unit = {},
    onPan115Logout: () -> Unit = {},
    /** 光鸭云盘登录/退出 */
    onGuangYaLogin: () -> Unit = {},
    onGuangYaLogout: () -> Unit = {},
    /** 蓝奏云优享版登录/退出 */
    onILanzouLogin: () -> Unit = {},
    onILanzouLogout: () -> Unit = {},
    /** 蓝奏云登录/退出 */
    onLanzouLogin: () -> Unit = {},
    onLanzouLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showQuarkSheet by remember { mutableStateOf(false) }
    var showUCSheet by remember { mutableStateOf(false) }
    var showXunleiSheet by remember { mutableStateOf(false) }
    var showBaiduSheet by remember { mutableStateOf(false) }
    var showC139Sheet by remember { mutableStateOf(false) }
    var showPan123Sheet by remember { mutableStateOf(false) }
    var showPan115Sheet by remember { mutableStateOf(false) }
    var showGuangYaSheet by remember { mutableStateOf(false) }
    var showILanzouSheet by remember { mutableStateOf(false) }
    var showLanzouSheet by remember { mutableStateOf(false) }
    // 夸克云盘浏览：网盘 Tab 内切换（非全屏），切 Tab 再回来仍保留
    var showCloud by rememberSaveable { mutableStateOf(false) }
    // UC 网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showUCCloud by rememberSaveable { mutableStateOf(false) }
    // 迅雷网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showXunleiCloud by rememberSaveable { mutableStateOf(false) }
    // 百度网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showBaiduCloud by rememberSaveable { mutableStateOf(false) }
    // 139 网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showC139Cloud by rememberSaveable { mutableStateOf(false) }
    // 123 云盘浏览：网盘 Tab 内切换（非全屏）
    var showPan123Cloud by rememberSaveable { mutableStateOf(false) }
    // 115 网盘浏览：网盘 Tab 内切换（非全屏）
    var showPan115Cloud by rememberSaveable { mutableStateOf(false) }
    // 光鸭云盘浏览：网盘 Tab 内切换（非全屏）
    var showGuangYaCloud by rememberSaveable { mutableStateOf(false) }
    // 蓝奏云优享版浏览：网盘 Tab 内切换（非全屏）
    var showILanzouCloud by rememberSaveable { mutableStateOf(false) }
    // 蓝奏云浏览：网盘 Tab 内切换（非全屏）
    var showLanzouCloud by rememberSaveable { mutableStateOf(false) }

    // 夸克：登录态由数据库驱动；已登录则副标题显示昵称
    val quark = DriveAccount(
        id = "quark",
        name = "夸克网盘",
        description = quarkAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "夸",
        isLoggedIn = quarkAccount != null
    )
    val uc = DriveAccount(
        id = "uc",
        name = "UC网盘",
        description = ucAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "UC",
        isLoggedIn = ucAccount != null
    )
    val xunlei = DriveAccount(
        id = "xunlei",
        name = "迅雷网盘",
        description = xunleiAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "迅",
        isLoggedIn = xunleiAccount != null
    )
    val baidu = DriveAccount(
        id = "baidu",
        name = "百度网盘",
        description = baiduAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "度",
        isLoggedIn = baiduAccount != null
    )
    val c139 = DriveAccount(
        id = "c139",
        name = "139网盘",
        description = c139Account?.nickname ?: "点击登录，支持解析下载",
        avatarText = "139",
        isLoggedIn = c139Account != null
    )
    val pan123 = DriveAccount(
        id = "pan123",
        name = "123云盘",
        description = pan123Account?.nickname ?: "点击登录，支持解析下载",
        avatarText = "123",
        isLoggedIn = pan123Account != null
    )
    val pan115 = DriveAccount(
        id = "pan115",
        name = "115网盘",
        description = pan115Account?.nickname ?: "点击登录，支持解析下载",
        avatarText = "115",
        isLoggedIn = pan115Account != null
    )
    val guangya = DriveAccount(
        id = "guangya",
        name = "光鸭云盘",
        description = guangyaAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "光",
        isLoggedIn = guangyaAccount != null
    )
    val ilanzou = DriveAccount(
        id = "ilanzou",
        name = "蓝奏云优享版",
        description = ilanzouAccount?.nickname?.let { maskAccount(it) } ?: "点击登录，支持解析下载",
        avatarText = "蓝优",
        isLoggedIn = ilanzouAccount != null
    )
    val lanzou = DriveAccount(
        id = "lanzou",
        name = "蓝奏云",
        description = lanzouAccount?.nickname?.let { maskAccount(it) } ?: "点击登录，支持解析下载",
        avatarText = "蓝",
        isLoggedIn = lanzouAccount != null
    )
    val others = remember {
        emptyList<DriveAccount>()
    }

    // 进入网盘页加载空间详情（仅已登录平台）
    LaunchedEffect(Unit) {
        driveQuotaViewModel.loadAll()
    }
    // 下拉刷新状态：绑定空间配额加载中状态
    val isRefreshing by driveQuotaViewModel.loading.collectAsState()
    // 各平台配额：在 LazyColumn 外读出来（列表内容 lambda 不是 composable，不能在里面 collectAsState）
    val quarkQuota by driveQuotaViewModel.quarkQuota.collectAsState()
    val ucQuota by driveQuotaViewModel.ucQuota.collectAsState()
    val xunleiQuota by driveQuotaViewModel.xunleiQuota.collectAsState()
    val baiduQuota by driveQuotaViewModel.baiduQuota.collectAsState()
    val c139Quota by driveQuotaViewModel.c139Quota.collectAsState()
    val pan123Quota by driveQuotaViewModel.pan123Quota.collectAsState()

    // 账号列表 ↔ 夸克云盘 ↔ UC 云盘 ↔ 迅雷云盘 ↔ 百度云盘 ↔ 139 云盘 ↔ 123 云盘 ↔ 115 云盘 ↔ 光鸭云盘 ↔ 蓝奏云优享版 ↔ 蓝奏云：平滑过渡（淡入 + 轻微缩放，不僵硬）
    AnimatedContent(
        targetState = when {
            showCloud -> 1
            showUCCloud -> 2
            showXunleiCloud -> 3
            showBaiduCloud -> 4
            showC139Cloud -> 5
            showPan123Cloud -> 6
            // 新平台浏览页需要对应 ViewModel 已接线，否则停留在账号列表（避免进入空白页）
            showPan115Cloud && pan115CloudViewModel != null -> 7
            showGuangYaCloud && guangyaCloudViewModel != null -> 8
            showILanzouCloud && ilanzouCloudViewModel != null -> 9
            showLanzouCloud && lanzouCloudViewModel != null -> 10
            else -> 0
        },
        transitionSpec = {
            (fadeIn(effectsDefault()) + scaleIn(tween(220), initialScale = 0.98f))
                .togetherWith(fadeOut(effectsFast()) + scaleOut(tween(150), targetScale = 0.98f))
        },
        label = "driveContent"
    ) { target ->
        when (target) {
            1 -> CloudDriveScreen(
                viewModel = quarkCloudViewModel,
                scrollBehavior = scrollBehavior,
                onExit = { showCloud = false },
                onDownloadStarted = onDownloadStarted
            )
            2 -> UCCoudScreen(
            viewModel = ucCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showUCCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        3 -> XunleiCloudScreen(
            viewModel = xunleiCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showXunleiCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        4 -> BaiduCloudScreen(
            viewModel = baiduCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showBaiduCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        5 -> C139CloudScreen(
            viewModel = c139CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showC139Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        6 -> Pan123CloudScreen(
            viewModel = pan123CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showPan123Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        7 -> pan115CloudViewModel?.let { vm ->
            Pan115CloudScreen(
                viewModel = vm,
                scrollBehavior = scrollBehavior,
                onExit = { showPan115Cloud = false },
                onDownloadStarted = onDownloadStarted
            )
        }
        8 -> guangyaCloudViewModel?.let { vm ->
            GuangYaCloudScreen(
                viewModel = vm,
                scrollBehavior = scrollBehavior,
                onExit = { showGuangYaCloud = false },
                onDownloadStarted = onDownloadStarted
            )
        }
        9 -> ilanzouCloudViewModel?.let { vm ->
            ILanzouCloudScreen(
                viewModel = vm,
                scrollBehavior = scrollBehavior,
                onExit = { showILanzouCloud = false },
                onDownloadStarted = onDownloadStarted
            )
        }
        10 -> lanzouCloudViewModel?.let { vm ->
            LanzouCloudScreen(
                viewModel = vm,
                scrollBehavior = scrollBehavior,
                onExit = { showLanzouCloud = false },
                onDownloadStarted = onDownloadStarted
            )
        }
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { driveQuotaViewModel.loadAll() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 已登录的排在前面、未登录的排后面，中间用分组标题分开。
                //
                // 【为什么分组】10 个网盘平铺一列时，常用的那一两个混在八个"点击登录"里，
                // 每次都要从头往下找。分完组，"我有哪些盘能用"和"还能加哪些盘"一眼分得开。
                // 一个都没登录时整屏都是未登录，此时标题写「全部网盘」而不是「未登录」——
                // 满屏"未登录"读起来像出了错。
                val entries = listOf(
                    DriveEntry(quark, quarkQuota, { showCloud = true }, { showQuarkSheet = true }, onQuarkLogin),
                    DriveEntry(uc, ucQuota, { showUCCloud = true }, { showUCSheet = true }, onUCLogin),
                    DriveEntry(xunlei, xunleiQuota, { showXunleiCloud = true }, { showXunleiSheet = true }, onXunleiLogin),
                    DriveEntry(baidu, baiduQuota, { showBaiduCloud = true }, { showBaiduSheet = true }, onBaiduLogin),
                    DriveEntry(c139, c139Quota, { showC139Cloud = true }, { showC139Sheet = true }, onC139Login),
                    DriveEntry(pan123, pan123Quota, { showPan123Cloud = true }, { showPan123Sheet = true }, onPan123Login),
                    // 以下 4 个平台暂未接入空间配额（DriveQuotaViewModel 只有 6 个平台），故 quota 为 null。
                    // 浏览 ViewModel 未接线时点击退回账号信息弹窗，避免进入空白页。
                    DriveEntry(
                        pan115, null,
                        when {
                            pan115CloudViewModel != null -> ({ showPan115Cloud = true })
                            else -> ({ showPan115Sheet = true })
                        },
                        { showPan115Sheet = true }, onPan115Login
                    ),
                    DriveEntry(
                        guangya, null,
                        when {
                            guangyaCloudViewModel != null -> ({ showGuangYaCloud = true })
                            else -> ({ showGuangYaSheet = true })
                        },
                        { showGuangYaSheet = true }, onGuangYaLogin
                    ),
                    DriveEntry(
                        ilanzou, null,
                        when {
                            ilanzouCloudViewModel != null -> ({ showILanzouCloud = true })
                            else -> ({ showILanzouSheet = true })
                        },
                        { showILanzouSheet = true }, onILanzouLogin
                    ),
                    DriveEntry(
                        lanzou, null,
                        when {
                            lanzouCloudViewModel != null -> ({ showLanzouCloud = true })
                            else -> ({ showLanzouSheet = true })
                        },
                        { showLanzouSheet = true }, onLanzouLogin
                    ),
                )
                val loggedIn = entries.filter { it.account.isLoggedIn }
                val loggedOut = entries.filterNot { it.account.isLoggedIn }

                if (loggedIn.isEmpty()) {
                    item(key = "hint_not_logged_in") {
                        Text(
                            text = "登录后即可自动携带凭证解析与下载",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                } else {
                    item(key = "header_logged_in") { DriveGroupLabel("已登录") }
                    items(loggedIn, key = { it.account.id }) { entry ->
                        DriveAccountCard(
                            account = entry.account,
                            quota = entry.quota,
                            onClick = entry.onClick,
                            onMoreClick = entry.onMoreClick
                        )
                    }
                }

                if (loggedOut.isNotEmpty()) {
                    item(key = "header_logged_out") {
                        DriveGroupLabel(if (loggedIn.isEmpty()) "全部网盘" else "未登录")
                    }
                    items(loggedOut, key = { it.account.id }) { entry ->
                        DriveAccountCard(
                            account = entry.account,
                            quota = entry.quota,
                            onClick = entry.onLogin,
                            onMoreClick = null
                        )
                    }
                }

                items(others, key = { it.id }) { account ->
                    DriveAccountCard(account = account)
                }
            }
            }
        }
    }

    // 已登录夸克：点击卡片弹出账号信息底部弹窗
    if (showQuarkSheet && quarkAccount != null) {
        QuarkAccountSheet(
            account = quarkAccount,
            onLogout = {
                onQuarkLogout()
                showQuarkSheet = false
            },
            onDismiss = { showQuarkSheet = false }
        )
    }

    // 已登录 UC：点击卡片弹出账号信息底部弹窗
    if (showUCSheet && ucAccount != null) {
        UCAccountSheet(
            account = ucAccount,
            onLogout = {
                onUCLogout()
                showUCSheet = false
            },
            onDismiss = { showUCSheet = false }
        )
    }

    // 已登录迅雷：点击卡片弹出账号信息底部弹窗
    if (showXunleiSheet && xunleiAccount != null) {
        XunleiAccountSheet(
            account = xunleiAccount,
            onLogout = {
                onXunleiLogout()
                showXunleiSheet = false
            },
            onDismiss = { showXunleiSheet = false }
        )
    }

    // 已登录百度：点击卡片弹出账号信息底部弹窗
    if (showBaiduSheet && baiduAccount != null) {
        BaiduAccountSheet(
            account = baiduAccount,
            onLogout = {
                onBaiduLogout()
                showBaiduSheet = false
            },
            onDismiss = { showBaiduSheet = false }
        )
    }

    // 已登录 139：点击卡片弹出账号信息底部弹窗
    if (showC139Sheet && c139Account != null) {
        C139AccountSheet(
            account = c139Account,
            onLogout = {
                onC139Logout()
                showC139Sheet = false
            },
            onDismiss = { showC139Sheet = false }
        )
    }

    // 已登录 123：点击卡片弹出账号信息底部弹窗
    if (showPan123Sheet && pan123Account != null) {
        Pan123AccountSheet(
            account = pan123Account,
            onLogout = {
                onPan123Logout()
                showPan123Sheet = false
            },
            onDismiss = { showPan123Sheet = false }
        )
    }

    // 已登录 115：点击卡片弹出账号信息底部弹窗
    if (showPan115Sheet && pan115Account != null) {
        Pan115AccountSheet(
            account = pan115Account,
            onLogout = {
                onPan115Logout()
                showPan115Sheet = false
            },
            onDismiss = { showPan115Sheet = false }
        )
    }

    // 已登录光鸭：点击卡片弹出账号信息底部弹窗
    if (showGuangYaSheet && guangyaAccount != null) {
        GuangYaAccountSheet(
            account = guangyaAccount,
            onLogout = {
                onGuangYaLogout()
                showGuangYaSheet = false
            },
            onDismiss = { showGuangYaSheet = false }
        )
    }

    // 已登录蓝奏云优享版：点击卡片弹出账号信息底部弹窗
    if (showILanzouSheet && ilanzouAccount != null) {
        ILanzouAccountSheet(
            account = ilanzouAccount,
            onLogout = {
                onILanzouLogout()
                showILanzouSheet = false
            },
            onDismiss = { showILanzouSheet = false }
        )
    }

    // 已登录蓝奏云：点击卡片弹出账号信息底部弹窗
    if (showLanzouSheet && lanzouAccount != null) {
        LanzouAccountSheet(
            account = lanzouAccount,
            onLogout = {
                onLanzouLogout()
                showLanzouSheet = false
            },
            onDismiss = { showLanzouSheet = false }
        )
    }
}

@Composable
private fun DriveAccountCard(
    account: DriveAccount,
    /** 网盘空间详情（已登录且有数据时在卡片内显示进度条）；null 不显示 */
    quota: QuotaInfo? = null,
    onClick: (() -> Unit)? = null,
    /** 已登录时右侧「三个点」更多按钮（打开账号弹窗）；null 则不显示 */
    onMoreClick: (() -> Unit)? = null
) {
    val cardShape = MaterialTheme.shapes.large
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
    val content: @Composable () -> Unit = {
        DriveAccountCardContent(
            account = account,
            quota = quota,
            clickable = onClick != null,
            onMoreClick = onMoreClick
        )
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    }
}

@Composable
private fun DriveAccountCardContent(
    account: DriveAccount,
    quota: QuotaInfo? = null,
    clickable: Boolean,
    onMoreClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌头像（暂用首字母，后续可替换为品牌图标）
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (account.isLoggedIn) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = account.avatarText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = account.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 已登录且有空间数据：卡片内展示剩余空间进度条（出现时淡入 + 纵向展开，避免突兀）
            AnimatedVisibility(
                visible = account.isLoggedIn && quota != null,
                enter = fadeIn(effectsDefault()) + expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(300)
                ),
                exit = fadeOut(effectsFast()) + shrinkVertically(animationSpec = tween(200))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    quota?.let { QuotaInlineBar(it) }
                }
            }
        }

        when {
            account.isLoggedIn && onMoreClick != null -> IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            account.isLoggedIn -> LoginBadge(isLoggedIn = true)
            clickable -> Text(
                text = "去登录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            else -> LoginBadge(isLoggedIn = false)
        }
    }
}

/** 网盘卡片内空间进度条：已用 / 总容量 + 细进度条 */
@Composable
private fun QuotaInlineBar(quota: QuotaInfo) {
    val ratio = if (quota.total > 0) {
        (quota.used.toFloat() / quota.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column {
        Text(
            text = "已用 ${formatBytes(quota.used)} / ${formatBytes(quota.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun LoginBadge(isLoggedIn: Boolean) {
    val (label, color) = if (isLoggedIn) {
        "已登录" to MaterialTheme.colorScheme.primary
    } else {
        "未登录" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 字节数格式化：B / KB / MB / GB / TB */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B"
    else String.format("%.1f %s", value, units[unit])
}