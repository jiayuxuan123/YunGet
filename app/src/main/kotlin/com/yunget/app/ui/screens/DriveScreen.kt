package com.yunget.app.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunget.app.ui.theme.ThemeController
import com.yunget.app.ui.theme.spatialDefault
import com.yunget.app.ui.theme.spatialFast

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import com.yunget.app.data.prefs.SettingsRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.ChevronRight

/**
 * 网盘账号展示模型。
 * TODO: 迅雷 / UC 后续接入 cookie 登录后，isLoggedIn 由真实登录态驱动。
 */
/**
 * 账号副标题：**备注优先**，没有备注才用平台昵称。
 *
 * 平台昵称往往是一串数字或与账号无关的网名，同一个人有多个账号时完全分不出谁是谁。
 * 备注是用户自己起的名字，优先显示它才有意义。
 */
private fun displayName(remark: String?, nickname: String?): String? =
    remark?.takeIf { it.isNotBlank() } ?: nickname?.takeIf { it.isNotBlank() }

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

/** 十个平台的账号、配额与文件浏览入口；业务状态由调用方与现有 ViewModel 持有。 */
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
    /** 修改夸克账号备注（空串 = 清除）。 */
    onQuarkUpdateRemark: ((String) -> Unit)? = null,
    /** 夸克的全部账号（多账号列表）。 */
    quarkAllAccounts: List<com.yunget.app.ui.items.MultiAccountRow> = emptyList(),
    onQuarkSelectPrimary: ((String) -> Unit)? = null,
    onQuarkDeleteAccount: ((String) -> Unit)? = null,
    /** 打开「账号管理」页（全平台多账号）。 */
    onManageAccounts: (() -> Unit)? = null,
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
    val orderContext = LocalContext.current
    val orderSettings = remember(orderContext) { SettingsRepository(orderContext) }
    var accountOrder by remember { mutableStateOf(orderSettings.driveAccountOrder) }
    var hideUnlogged by remember { mutableStateOf(orderSettings.hideUnloggedDrives) }
    var showOrder by rememberSaveable { mutableStateOf(false) }
    val orderItems = listOf(
        "quark" to "夸克网盘", "uc" to "UC 网盘", "xunlei" to "迅雷网盘",
        "baidu" to "百度网盘", "c139" to "139 网盘", "pan123" to "123 云盘",
        "pan115" to "115 网盘", "guangya" to "光鸭云盘", "ilanzou" to "蓝奏云优享版", "lanzou" to "蓝奏云"
    )
    if (showOrder) DriveOrderDialog(
        items = orderItems, order = accountOrder,
        onChange = { accountOrder = it; orderSettings.driveAccountOrder = it },
        onDismiss = { showOrder = false }
    )
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
        description = displayName(quarkAccount?.remark, quarkAccount?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "夸",
        isLoggedIn = quarkAccount != null
    )
    val uc = DriveAccount(
        id = "uc",
        name = "UC 网盘",
        description = displayName(ucAccount?.remark, ucAccount?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "UC",
        isLoggedIn = ucAccount != null
    )
    val xunlei = DriveAccount(
        id = "xunlei",
        name = "迅雷网盘",
        description = displayName(xunleiAccount?.remark, xunleiAccount?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "迅",
        isLoggedIn = xunleiAccount != null
    )
    val baidu = DriveAccount(
        id = "baidu",
        name = "百度网盘",
        description = displayName(baiduAccount?.remark, baiduAccount?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "度",
        isLoggedIn = baiduAccount != null
    )
    val c139 = DriveAccount(
        id = "c139",
        name = "139 网盘",
        description = displayName(c139Account?.remark, c139Account?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "139",
        isLoggedIn = c139Account != null
    )
    val pan123 = DriveAccount(
        id = "pan123",
        name = "123 云盘",
        description = displayName(pan123Account?.remark, pan123Account?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "123",
        isLoggedIn = pan123Account != null
    )
    val pan115 = DriveAccount(
        id = "pan115",
        name = "115 网盘",
        description = displayName(pan115Account?.remark, pan115Account?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "115",
        isLoggedIn = pan115Account != null
    )
    val guangya = DriveAccount(
        id = "guangya",
        name = "光鸭云盘",
        description = displayName(guangyaAccount?.remark, guangyaAccount?.nickname) ?: "登录后浏览文件与下载",
        avatarText = "光",
        isLoggedIn = guangyaAccount != null
    )
    val ilanzou = DriveAccount(
        id = "ilanzou",
        name = "蓝奏云优享版",
        description = displayName(ilanzouAccount?.remark, ilanzouAccount?.nickname)?.let { maskAccount(it) }
            ?: "登录后浏览文件与下载",
        avatarText = "蓝优",
        isLoggedIn = ilanzouAccount != null
    )
    val lanzou = DriveAccount(
        id = "lanzou",
        name = "蓝奏云",
        description = displayName(lanzouAccount?.remark, lanzouAccount?.nickname)?.let { maskAccount(it) }
            ?: "登录后浏览文件与下载",
        avatarText = "蓝",
        isLoggedIn = lanzouAccount != null
    )

    // 进入网盘页加载空间详情（仅已登录平台）
    LaunchedEffect(Unit) {
        driveQuotaViewModel.loadAll()
    }
    // 下拉刷新状态：绑定空间配额加载中状态
    val isRefreshing by driveQuotaViewModel.loading.collectAsStateWithLifecycle()
    // 各平台配额：在 LazyColumn 外读出来（列表内容 lambda 不是 composable，不能在里面 collectAsState）
    val quarkQuota by driveQuotaViewModel.quarkQuota.collectAsStateWithLifecycle()
    val ucQuota by driveQuotaViewModel.ucQuota.collectAsStateWithLifecycle()
    val xunleiQuota by driveQuotaViewModel.xunleiQuota.collectAsStateWithLifecycle()
    val baiduQuota by driveQuotaViewModel.baiduQuota.collectAsStateWithLifecycle()
    val c139Quota by driveQuotaViewModel.c139Quota.collectAsStateWithLifecycle()
    val pan123Quota by driveQuotaViewModel.pan123Quota.collectAsStateWithLifecycle()

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
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            when (ThemeController.motionLevel) {
                2 -> EnterTransition.None.togetherWith(ExitTransition.None)
                1 -> fadeIn(effectsDefault()).togetherWith(fadeOut(effectsFast()))
                else -> (fadeIn(effectsDefault()) + slideInHorizontally(spatialDefault()) { it / 12 })
                    .togetherWith(fadeOut(effectsFast()) + slideOutHorizontally(spatialFast()) { -it / 16 })
            }
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
                DriveListContent(
                    entries = entries,
                    accountOrder = accountOrder,
                    hideUnlogged = hideUnlogged,
                    onHideUnloggedChange = {
                        hideUnlogged = it
                        orderSettings.hideUnloggedDrives = it
                    },
                    onOrderClick = { showOrder = true },
                    onManageAccounts = onManageAccounts,
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                )
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
            onDismiss = { showQuarkSheet = false },
            onUpdateRemark = onQuarkUpdateRemark,
            allAccounts = quarkAllAccounts,
            onSelectPrimary = onQuarkSelectPrimary,
            onDeleteAccount = onQuarkDeleteAccount,
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

/**
 * 无数据层副作用的生产列表。排序只调整组内顺序，始终保留已登录／待登录分组。
 */
@Composable
private fun DriveListContent(
    entries: List<DriveEntry>,
    accountOrder: List<String>,
    hideUnlogged: Boolean,
    onHideUnloggedChange: (Boolean) -> Unit,
    onOrderClick: () -> Unit,
    onManageAccounts: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val ordered = entries.sortedBy { entry ->
        accountOrder.indexOf(entry.account.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
    val loggedIn = ordered.filter { it.account.isLoggedIn }
    val loggedOut = ordered.filterNot { it.account.isLoggedIn }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "overview") {
                DriveOverview(loggedIn.size, entries.size, onManageAccounts.takeIf { loggedIn.isNotEmpty() })
            }
            item(key = "display_options") {
                Surface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(4.dp)) {
                        Surface(onClick = onOrderClick, shape = RoundedCornerShape(16.dp), color = Color.Transparent) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Outlined.Sort, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("平台排序", style = MaterialTheme.typography.bodyLarge)
                                    Text("调整各分组内的显示顺序", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .toggleable(value = hideUnlogged, role = Role.Switch, onValueChange = onHideUnloggedChange)
                                .heightIn(min = 64.dp).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("隐藏未登录网盘", style = MaterialTheme.typography.bodyLarge)
                                Text("只显示已经登录的平台", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = hideUnlogged, onCheckedChange = null)
                        }
                    }
                }
            }
            if (loggedIn.isNotEmpty()) {
                item(key = "logged_in") { DriveAccountGroup("已登录", loggedIn) }
            }
            if (!hideUnlogged && loggedOut.isNotEmpty()) {
                item(key = "logged_out") {
                    DriveAccountGroup(if (loggedIn.isEmpty()) "全部网盘" else "待登录", loggedOut)
                }
            }
            if (hideUnlogged && loggedIn.isEmpty()) {
                item(key = "empty") {
                    Card(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            Text("还没有已登录账号", style = MaterialTheme.typography.titleMedium)
                            Text("显示全部网盘，选择一个平台登录后即可浏览文件与下载。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = { onHideUnloggedChange(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("显示全部网盘")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveOverview(loggedInCount: Int, platformCount: Int, onManageAccounts: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("网盘账号", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("$loggedInCount 个平台已登录 · 共 $platformCount 个平台", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                if (loggedInCount == 0) "选择平台登录，开始浏览与下载" else "点击平台浏览文件，在更多菜单中查看账号",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (onManageAccounts != null) {
                FilledTonalButton(
                    onClick = onManageAccounts, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.ManageAccounts, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("管理全部账号")
                }
            }
        }
    }
}

@Composable
private fun DriveAccountGroup(title: String, entries: List<DriveEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${entries.size} 个平台", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.padding(4.dp)) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DriveAccountCard(
                        account = entry.account, quota = entry.quota,
                        onClick = if (entry.account.isLoggedIn) entry.onClick else entry.onLogin,
                        onMoreClick = entry.onMoreClick.takeIf { entry.account.isLoggedIn }
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveAccountCard(account: DriveAccount, quota: QuotaInfo?, onClick: () -> Unit, onMoreClick: (() -> Unit)?) {
    val largeText = LocalDensity.current.fontScale > 1.2f
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp), color = Color.Transparent
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val logoRes = com.yunget.app.ui.items.platformLogoRes(account.id)
                if (logoRes != null) {
                    Image(painterResource(logoRes), contentDescription = null, modifier = Modifier.size(40.dp), contentScale = ContentScale.Fit)
                } else {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text(account.avatarText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                Text(account.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (account.isLoggedIn && onMoreClick != null) {
                    IconButton(onClick = onMoreClick, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "${account.name}账号选项", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Icon(
                        if (account.isLoggedIn) Icons.Outlined.ChevronRight else Icons.AutoMirrored.Outlined.Login,
                        contentDescription = if (account.isLoggedIn) "浏览文件" else "登录",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = if (largeText) 0.dp else 52.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(account.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (account.isLoggedIn && quota != null) QuotaInlineBar(quota)
            }
        }
    }
}

@Composable
private fun QuotaInlineBar(quota: QuotaInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (quota.total > 0) "已用 ${formatBytes(quota.used)} / ${formatBytes(quota.total)}"
            else "已用 ${formatBytes(quota.used)} · 总容量暂未获取",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (quota.total > 0) {
            LinearProgressIndicator(
                progress = { (quota.used.toFloat() / quota.total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp), color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

/** 官方 Screenshot Preview 使用真实生产布局和明确的假账号、假配额；不访问数据层。 */
@Composable
internal fun DrivePreviewContent() {
    val entries = remember {
        listOf(
            DriveEntry(
                DriveAccount("quark", "夸克网盘", "工作资料 · 主账号（预览）", "夸", true),
                QuotaInfo(used = 96L * 1024 * 1024 * 1024, total = 512L * 1024 * 1024 * 1024), {}, {}, {}
            ),
            DriveEntry(
                DriveAccount("baidu", "百度网盘", "照片与日常备份（预览）", "度", true),
                QuotaInfo(used = 320L * 1024 * 1024 * 1024, total = 1024L * 1024 * 1024 * 1024), {}, {}, {}
            ),
            DriveEntry(DriveAccount("uc", "UC 网盘", "登录后浏览文件与下载", "UC"), null, {}, null, {}),
            DriveEntry(DriveAccount("lanzou", "蓝奏云", "登录后浏览文件与下载", "蓝"), null, {}, null, {})
        )
    }
    DriveListContent(
        entries = entries, accountOrder = emptyList(), hideUnlogged = false,
        onHideUnloggedChange = {}, onOrderClick = {}, onManageAccounts = {}
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
}
