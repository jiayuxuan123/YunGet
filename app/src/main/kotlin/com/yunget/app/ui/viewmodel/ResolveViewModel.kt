package com.yunget.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunget.app.data.download.DownloadManager
import com.yunget.app.data.network.BaiduConstants
import com.yunget.app.data.network.C139Constants
import com.yunget.app.data.network.GuangYaConstants
import com.yunget.app.data.network.ILanzouConstants
import com.yunget.app.data.network.LanzouConstants
import com.yunget.app.data.network.Pan115Constants
import com.yunget.app.data.network.Pan123Constants
import com.yunget.app.data.network.QuarkConstants
import com.yunget.app.data.network.QuarkCdn
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.UCConstants
import com.yunget.app.data.network.XunleiConstants
import com.yunget.app.data.network.model.DownloadLink
import com.yunget.app.data.network.model.ShareFile
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.repository.BaiduAccountRepository
import com.yunget.app.data.repository.BaiduResolveRepository
import com.yunget.app.data.repository.C139AccountRepository
import com.yunget.app.data.repository.C139ResolveRepository
import com.yunget.app.data.repository.GuangYaAccountRepository
import com.yunget.app.data.repository.GuangYaResolveRepository
import com.yunget.app.data.repository.ILanzouAccountRepository
import com.yunget.app.data.repository.ILanzouResolveRepository
import com.yunget.app.data.repository.LanzouAccountRepository
import com.yunget.app.data.repository.LanzouResolveRepository
import com.yunget.app.data.repository.Pan115AccountRepository
import com.yunget.app.data.repository.Pan115ResolveRepository
import com.yunget.app.data.repository.Pan123AccountRepository
import com.yunget.app.data.repository.Pan123ResolveRepository
import com.yunget.app.data.repository.QuarkAccountRepository
import com.yunget.app.data.repository.QuarkResolveRepository
import com.yunget.app.data.repository.ShareResolveRepository
import com.yunget.app.data.repository.UCAccountRepository
import com.yunget.app.data.repository.UCResolveRepository
import com.yunget.app.data.repository.XunleiAccountRepository
import com.yunget.app.data.repository.XunleiResolveRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ResolveUiState {
    data object Idle : ResolveUiState
    data object Loading : ResolveUiState
    data class Detail(val session: ShareSession, val files: List<ShareFile>) : ResolveUiState
    data class Error(val message: String) : ResolveUiState
}

/** 普通直链下载默认 UA（部分站点缺 UA 会 403）。 */
private const val DIRECT_DOWNLOAD_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/**
 * 解析页 ViewModel：分享解析状态机 + 目录导航 + 下载直链。
 * 支持夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭云盘 / 蓝奏云优享版 / 蓝奏云，
 * 按链接自动路由到对应平台仓库与凭证。
 */
class ResolveViewModel(
    private val accountRepository: QuarkAccountRepository,
    private val resolveRepository: QuarkResolveRepository,
    private val ucAccountRepository: UCAccountRepository,
    private val ucResolveRepository: UCResolveRepository,
    private val xunleiAccountRepository: XunleiAccountRepository,
    private val xunleiResolveRepository: XunleiResolveRepository,
    private val baiduAccountRepository: BaiduAccountRepository,
    private val baiduResolveRepository: BaiduResolveRepository,
    private val c139AccountRepository: C139AccountRepository,
    private val c139ResolveRepository: C139ResolveRepository,
    private val pan123AccountRepository: Pan123AccountRepository,
    private val pan123ResolveRepository: Pan123ResolveRepository,
    private val pan115AccountRepository: Pan115AccountRepository,
    private val pan115ResolveRepository: Pan115ResolveRepository,
    private val guangyaAccountRepository: GuangYaAccountRepository,
    private val guangyaResolveRepository: GuangYaResolveRepository,
    private val ilanzouAccountRepository: ILanzouAccountRepository,
    private val ilanzouResolveRepository: ILanzouResolveRepository,
    private val lanzouAccountRepository: LanzouAccountRepository,
    private val lanzouResolveRepository: LanzouResolveRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    var uiState by mutableStateOf<ResolveUiState>(ResolveUiState.Idle)
        private set

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    var downloadError by mutableStateOf<String?>(null)
    private set

    /** 获取下载直链中（UI 显示加载弹窗） */
    var isFetchingDownloadLink by mutableStateOf(false)
        private set

    /** 待转存文件（转存弹窗）；null 表示未在转存流程 */
    var saveTarget by mutableStateOf<ShareFile?>(null)
        private set

    /** 转存中（UI 显示加载） */
    var isSaving by mutableStateOf(false)
        private set

    /** 转存结果消息（Toast） */
    var saveMessage by mutableStateOf<String?>(null)
        private set

    /** 当前分享是否支持转存（夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭） */
    val canSave: Boolean
        get() = currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.XUNLEI ||
            currentPlatform == SharePlatform.BAIDU ||
            currentPlatform == SharePlatform.C139 ||
            currentPlatform == SharePlatform.PAN123 ||
            currentPlatform == SharePlatform.PAN115 ||
            currentPlatform == SharePlatform.GUANGYA

    /** 当前分享是否为迅雷（UI 选择迅雷版转存目录选择器） */
    val isSaveXunlei: Boolean
        get() = currentPlatform == SharePlatform.XUNLEI

    /** 当前分享是否为百度（UI 选择百度版转存目录选择器） */
    val isSaveBaidu: Boolean
        get() = currentPlatform == SharePlatform.BAIDU

    /** 当前分享是否为百度（限速提示判断用） */
    val isBaidu: Boolean
        get() = currentPlatform == SharePlatform.BAIDU

    /** 当前分享是否为 139（UI 选择 139 版转存目录选择器） */
    val isSaveC139: Boolean
        get() = currentPlatform == SharePlatform.C139

    /** 当前分享是否为 UC（UI 选择 UC 版转存目录选择器） */
    val isSaveUC: Boolean
        get() = currentPlatform == SharePlatform.UC

    /** 当前分享是否为 123（UI 选择 123 版转存目录选择器） */
    val isSavePan123: Boolean
        get() = currentPlatform == SharePlatform.PAN123

    /** 当前分享是否为 115（UI 选择 115 版转存目录选择器） */
    val isSavePan115: Boolean
        get() = currentPlatform == SharePlatform.PAN115

    /** 当前分享是否为光鸭云盘（UI 选择光鸭版转存目录选择器） */
    val isSaveGuangYa: Boolean
        get() = currentPlatform == SharePlatform.GUANGYA

    /** 请求转存：记录目标文件并打开目录选择弹窗 */
    fun requestSave(file: ShareFile) {
        saveTarget = file
        saveMessage = null
    }

    fun dismissSave() {
        saveTarget = null
        isSaving = false
    }

    fun consumeSaveMessage() {
        saveMessage = null
    }

    /** 转存到网盘指定目录（保存成功自动关闭弹窗；夸克 / 迅雷 / 百度分平台实现） */
    fun saveToCloud(toDirFid: String) {
        val file = saveTarget ?: return
        val s = session ?: return
        viewModelScope.launch {
            isSaving = true
            try {
                when (currentPlatform) {
                    SharePlatform.XUNLEI -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录迅雷网盘"
                            return@launch
                        }
                        xunleiResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到迅雷网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.BAIDU -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录百度网盘"
                            return@launch
                        }
                        baiduResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到百度网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.C139 -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录139网盘"
                            return@launch
                        }
                        c139ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到139网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.UC -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录UC网盘"
                            return@launch
                        }
                        ucResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到UC网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.PAN123 -> {
                        // 123 保存到个人盘：copy/save（mshare 无需签名）+ 轮询 task
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录123云盘"
                            return@launch
                        }
                        pan123ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到123云盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.PAN115 -> {
                        // 115 保存到个人盘：share/receive（不回传新 fid，仓库内部转存后回查）
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录115网盘"
                            return@launch
                        }
                        pan115ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到115网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.GUANGYA -> {
                        // 光鸭保存到个人盘：restore_share（accessToken + fileIds + parentId）
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录光鸭云盘"
                            return@launch
                        }
                        guangyaResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到光鸭云盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    else -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录夸克网盘"
                            return@launch
                        }
                        resolveRepository.saveToCloud(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到夸克网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                }
            } finally {
                isSaving = false
            }
        }
    }

    /** 下载已入队事件：触发后由 UI 切换到下载页 */
    var downloadStarted by mutableStateOf(false)
        private set

    // ---------- 长按多选（解析页文件列表） ----------

    /** 多选模式（长按进入） */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    /** 批量处理中（UI 显示加载弹窗） */
    var isBatchWorking by mutableStateOf(false)
        private set

    /** 批量下载进度（如 "2/5"）；null 表示未显示进度 */
    var batchProgress by mutableStateOf<String?>(null)
        private set

    /** 批量处理中断请求（UI 点「中断」后置 true，批量循环中检查并跳出） */
    private var batchCancelRequested = false

    /** 中断当前批量处理（批量下载/批量转存） */
    fun cancelBatch() {
        batchCancelRequested = true
    }

    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    fun toggleSelectAll(files: List<ShareFile>) {
        if (_selected.size == files.size) _selected.clear()
        else {
            _selected.clear()
            _selected.addAll(files)
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
    }

    /** 批量转存到网盘根目录（夸克 / 迅雷 / 百度 / 115 / 光鸭 / 蓝奏优享分平台；仅支持转存的平台） */
    fun batchSaveToCloud() {
        val files = _selected.toList()
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchCancelRequested = false
            try {
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    downloadError = "请先登录${platformName()}"
                    return@launch
                }
                var okCount = 0
                var interrupted = false
                for (file in files) {
                    // 用户点击「中断」：停止剩余项，已转存的不回滚
                    if (batchCancelRequested) {
                        interrupted = true
                        downloadError = "已中断批量转存"
                        break
                    }
                    runCatching {
                        when (currentPlatform) {
                            SharePlatform.XUNLEI -> {
                                // 迅雷批量转存到根目录（parent_id 为空）
                                xunleiResolveRepository.transferFile(s, file, "", credential)
                            }
                            SharePlatform.BAIDU -> {
                                // 百度批量转存到根目录（绝对路径 "/"）
                                baiduResolveRepository.transferFile(s, file, "/", credential)
                            }
                            SharePlatform.C139 -> {
                                // 139 批量转存到根目录（fileId "/"）
                                c139ResolveRepository.transferFile(s, file, "/", credential)
                            }
                            SharePlatform.UC -> {
                                // UC 批量转存到根目录（pdir_fid "0"）
                                ucResolveRepository.transferFile(s, file, UCConstants.DEFAULT_PDIR_FID, credential)
                            }
                            SharePlatform.PAN123 -> {
                                // 123 批量转存到根目录（fileId "0"）
                                pan123ResolveRepository.transferFile(s, file, "0", credential)
                            }
                            SharePlatform.PAN115 -> {
                                // 115 批量转存到根目录（cid "0"）
                                pan115ResolveRepository.transferFile(s, file, Pan115Constants.ROOT_CID, credential)
                            }
                            SharePlatform.GUANGYA -> {
                                // 光鸭批量转存到根目录（parentId ""）
                                guangyaResolveRepository.transferFile(s, file, "", credential)
                            }
                            SharePlatform.ILANZOU -> {
                                // 蓝奏优享批量转存到根目录（folderId "0"）
                                ilanzouResolveRepository.transferFile(s, file, "0", credential)
                            }
                            else -> {
                                resolveRepository.saveToCloud(s, file, QuarkConstants.DEFAULT_PDIR_FID, credential)
                            }
                        }
                    }.onSuccess { okCount++ }
                }
                if (!interrupted) {
                    downloadError = if (okCount > 0) "已转存 $okCount 项到${platformName()}" else "转存失败"
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchCancelRequested = false
            }
        }
    }

    /** 批量下载：逐个取直链入队（选中文件夹时递归下载整个文件夹并保持目录结构，全部获取完再统一切到下载页） */
    fun batchDownload() {
        val files = _selected.toList()
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                // 游客模式：夸克/UC 的小文件直链不要求登录（走游客取链），
                // 光鸭/蓝奏优享/蓝奏云的分享列目录与取链同样匿名可用；其余平台必须登录
                val credential = currentCredential().orEmpty()
                if (credential.isBlank() && !supportsGuestDownload()) {
                    downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（直链签名绑定取链时刻的 __puus）
                val quarkCred = when (currentPlatform) {
                    SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                    SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                    else -> credential
                }
                // 展开选中项：文件直接加入，文件夹递归收集（相对路径 = 文件夹名/子/...）
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectShareFolder(s, file.fid, file.fname, quarkCred, tasks, 0)
                    } else {
                        tasks.add(file to "")
                    }
                }
                if (tasks.isEmpty()) {
                    downloadError = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                var interrupted = false
                for ((index, task) in tasks.withIndex()) {
                    // 用户点击「中断」：停止剩余项，已入队的任务保留下载
                    if (batchCancelRequested) {
                        interrupted = true
                        downloadError = "已中断批量下载"
                        break
                    }
                    val (file, relPath) = task
                    batchProgress = "${index + 1}/${tasks.size}"
                    runCatching {
                        // 游客态（凭据为空）走游客取链：夸克/UC/光鸭/蓝奏优享/蓝奏云的分享直链不要求账号
                        val linkResult = if (credential.isBlank()) {
                            currentRepo().getGuestShareDownloadLink(s, file)
                        } else {
                            currentRepo().getShareDownloadLink(s, file, quarkCred)
                        }
                        linkResult.getOrNull()?.let { link ->
                            // 文件夹内文件用相对路径（保持目录结构）；根目录文件用取链返回的文件名
                            enqueueDownload(link, quarkCred, if (relPath.isBlank()) link.filename else relPath)
                            okCount++
                        }
                    }
                }
                if (!interrupted) {
                    downloadError = if (okCount > 0) "已加入 $okCount 个下载任务" else "获取下载链接失败"
                    // 全部获取完再一次性切到下载页
                    if (okCount > 0) downloadStarted = true
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchProgress = null
                batchCancelRequested = false
            }
        }
    }

    /**
     * 递归收集分享文件夹内所有文件（保持目录结构）。
     * @param dirFid 分享内目录 fid
     * @param prefix 相对路径前缀（如 "文件夹A/子目录"）
     * @param result 输出：文件 + 相对路径（"文件夹A/子目录/文件.mp4"）
     */
    private suspend fun collectShareFolder(
        s: ShareSession,
        dirFid: String,
        prefix: String,
        credential: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val files = runCatching {
            currentRepo().listFiles(s, dirFid, credential).getOrNull() ?: emptyList()
        }.getOrDefault(emptyList())
        files.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        files.filter { it.isdir }.forEach {
            collectShareFolder(s, it.fid, "$prefix/${it.fname}", credential, result, depth + 1)
        }
    }

    fun consumeDownloadStarted() {
        downloadStarted = false
    }

    fun consumeDownloadError() {
        downloadError = null
    }

    private var session: ShareSession? = null
    private var currentDirFid = QuarkConstants.DEFAULT_PDIR_FID
    private val dirStack = ArrayDeque<String>()

    /** 当前目录路径名栈（用于面包屑显示），如 [辅助工具, 专用模组] */
    var pathNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前解析平台（QUARK / UC / XUNLEI），由链接自动检测 */
    private var currentPlatform: SharePlatform = SharePlatform.QUARK

    /** 当前平台凭证（夸克/UC/百度/139/115/蓝奏用 cookie，迅雷/123 用 access_token，光鸭用 accessToken，蓝奏优享用 appToken） */
    private suspend fun currentCredential(): String? = when (currentPlatform) {
        SharePlatform.UC -> ucAccountRepository.getAccount()?.cookie
        SharePlatform.XUNLEI -> xunleiAccountRepository.getAccount()?.accessToken
        SharePlatform.BAIDU -> baiduAccountRepository.getAccount()?.cookie
        SharePlatform.C139 -> c139AccountRepository.getAccount()?.cookie
        SharePlatform.PAN123 -> pan123AccountRepository.getAccount()?.accessToken
        SharePlatform.PAN115 -> pan115AccountRepository.getAccount()?.cookie
        SharePlatform.GUANGYA -> guangyaAccountRepository.ensureAccessToken()
        SharePlatform.ILANZOU -> ilanzouAccountRepository.getAccount()?.appToken
        SharePlatform.LANZOU -> lanzouAccountRepository.getAccount()?.cookie
        else -> accountRepository.getAccount()?.cookie
    }

    private fun currentRepo(): ShareResolveRepository = when (currentPlatform) {
        SharePlatform.UC -> ucResolveRepository
        SharePlatform.XUNLEI -> xunleiResolveRepository
        SharePlatform.BAIDU -> baiduResolveRepository
        SharePlatform.C139 -> c139ResolveRepository
        SharePlatform.PAN123 -> pan123ResolveRepository
        SharePlatform.PAN115 -> pan115ResolveRepository
        SharePlatform.GUANGYA -> guangyaResolveRepository
        SharePlatform.ILANZOU -> ilanzouResolveRepository
        SharePlatform.LANZOU -> lanzouResolveRepository
        else -> resolveRepository
    }

    private fun currentDefaultDirFid(): String = when (currentPlatform) {
        SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
        SharePlatform.XUNLEI -> "0"
        SharePlatform.BAIDU -> ""
        SharePlatform.C139 -> "0"
        SharePlatform.PAN123 -> "0"
        SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
        SharePlatform.GUANGYA -> ""
        SharePlatform.ILANZOU -> ""
        SharePlatform.LANZOU -> ""
        else -> QuarkConstants.DEFAULT_PDIR_FID
    }

    private fun platformName(): String = when (currentPlatform) {
        SharePlatform.UC -> "UC 网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "139 网盘"
        SharePlatform.PAN123 -> "123云盘"
        SharePlatform.PAN115 -> "115网盘"
        SharePlatform.GUANGYA -> "光鸭云盘"
        SharePlatform.ILANZOU -> "蓝奏云优享版"
        SharePlatform.LANZOU -> "蓝奏云"
        else -> "夸克网盘"
    }

    /**
     * 平台是否支持「未登录下载」：
     * - 夸克/UC：分享直链不要求登录态（夸克仅约 50MB 以内，且直链要带游客态 __pugs）；
     * - 光鸭：分享列目录与取链本身匿名可用（受限分享返回 207 时会提示登录转存）；
     * - 蓝奏云：分享解析与取链完全匿名。
     * 其余平台仍要求登录。
     */
    private fun supportsGuestDownload(): Boolean =
        currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.GUANGYA ||
            currentPlatform == SharePlatform.ILANZOU ||
            currentPlatform == SharePlatform.LANZOU

    /** 开始解析：链接 → token →（密码）→ 根目录列表 */
    fun startResolve(link: String, pwd: String?) {
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val parsed = ShareLinkParser.parse(link)
            if (parsed == null) {
                // 非网盘分享链接：若是普通 http(s) 直链，交 TurboDL 多线程下载器正常下载。
                val direct = extractDirectUrl(link)
                if (direct != null) {
                    uiState = ResolveUiState.Idle
                    enqueueDirectDownload(direct)
                    return@launch
                }
                uiState = ResolveUiState.Error("无法识别分享链接")
                return@launch
            }
            currentPlatform = parsed.platform
            val credential = currentCredential()
            if (credential.isNullOrBlank()) {
                uiState = ResolveUiState.Error("请先在「网盘」页登录${platformName()}")
                return@launch
            }
            val repo = currentRepo()
            repo.createSession(link, pwd, credential)
                .onSuccess { s ->
                    session = s
                    currentDirFid = currentDefaultDirFid()
                    dirStack.clear()
                    pathNames = emptyList()
                    loadFiles(s, currentDirFid, credential, repo)
                }
                .onFailure { e ->
                    uiState = ResolveUiState.Error(e.message ?: "解析失败")
                }
        }
    }

    /**
     * 从整段文案中提取一个可下载的普通 http(s) 直链。
     * 仅在 [ShareLinkParser] 未识别出任何网盘分享时调用。
     */
    private fun extractDirectUrl(text: String): String? {
        val raw = Regex("""https?://[^\s]+""").find(text.trim())?.value ?: return null
        return raw.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')
            .takeIf { it.length > "https://".length }
    }

    /** 从 URL 推导保存文件名（去查询串、URL 解码；无法推导时用时间戳兜底）。 */
    private fun fileNameFromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/')
        val decoded = runCatching {
            java.net.URLDecoder.decode(last, "UTF-8")
        }.getOrDefault(last)
        // 去掉文件名中的非法字符（保存到公共目录时 SAF/MediaStore 会拒绝）
        val cleaned = decoded.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /**
     * 普通直链下载：不做任何网盘解析，直接交下载器多线程下载。
     * 只带一个通用 UA（部分站点缺 UA 会 403）；不注入任何网盘 Cookie。
     */
    private suspend fun enqueueDirectDownload(url: String) {
        val name = fileNameFromUrl(url)
        downloadManager.enqueue(
            url = url,
            fileName = name,
            headers = mapOf("User-Agent" to DIRECT_DOWNLOAD_UA),
        )
        downloadError = "已加入下载：$name"
        downloadStarted = true
    }

    /** 进入文件夹 */
    fun openFolder(file: ShareFile) {
        val s = session ?: return
        dirStack.addLast(currentDirFid)
        pathNames = pathNames + file.fname
        currentDirFid = file.fid
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val credential = currentCredential()
            if (credential.isNullOrBlank()) {
                uiState = ResolveUiState.Error("登录已失效，请重新登录")
                return@launch
            }
            loadFiles(s, file.fid, credential, currentRepo())
        }
    }

    /** 返回上级目录 */
    fun goBack() {
        val s = session ?: return
        if (dirStack.isEmpty()) return
        currentDirFid = dirStack.removeLast()
        pathNames = pathNames.dropLast(1)
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val credential = currentCredential()
            if (credential.isNullOrBlank()) return@launch
            loadFiles(s, currentDirFid, credential, currentRepo())
        }
    }

    /** 返回：在子目录则返回上一级，在根目录则返回输入页 */
    fun navigateBack() {
        if (dirStack.isEmpty()) {
            backToInput()
        } else {
            goBack()
        }
    }

    /** 返回输入页 */
    fun backToInput() {
        session = null
        downloadLink = null
        pathNames = emptyList()
        uiState = ResolveUiState.Idle
    }

    /**
     * 面包屑导航：点击第 level 级（0=分享根目录）回退到该目录并刷新列表。
     * 当前所在层（level == pathNames.size）无需操作。
     */
    fun navigateToLevel(level: Int) {
        val s = session ?: return
        if (level < 0 || level > pathNames.size) return
        if (level == pathNames.size) return
        // 弹出目录栈直到对应层级；level=0 时回到分享根目录
        while (dirStack.size > level) dirStack.removeLast()
        currentDirFid = if (dirStack.isEmpty()) currentDefaultDirFid() else dirStack.last()
        pathNames = pathNames.take(level)
        viewModelScope.launch {
            val credential = currentCredential() ?: return@launch
            loadFiles(s, currentDirFid, credential, currentRepo())
        }
    }

    /** 获取文件下载直链（各平台实现不同：夸克转存后取 / UC 直接取 / 迅雷转存后取详情直链） */
    fun fetchDownloadLink(file: ShareFile) {
        viewModelScope.launch {
            downloadLink = null
            downloadError = null
            isFetchingDownloadLink = true
            try {
                val s = session
                if (s == null) {
                    downloadError = "请先解析分享"
                    return@launch
                }
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    // 游客模式：夸克/UC 的分享直链本身不要求登录（夸克约 50MB 以内，超出报 23018；
                    // UC 实测 4GB 文件都放行），光鸭/蓝奏优享/蓝奏云同样匿名可下载，走各自的游客取链；
                    // 其余平台必须登录
                    if (!supportsGuestDownload()) {
                        downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                        return@launch
                    }
                    currentRepo().getGuestShareDownloadLink(s, file)
                        .onSuccess { downloadLink = it }
                        .onFailure { downloadError = it.message ?: "获取下载链接失败" }
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链前确保新鲜（直链签名绑定取链时刻的 Cookie）
                val quarkCred = when (currentPlatform) {
                    SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                    SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                    else -> credential
                }
                currentRepo().getShareDownloadLink(s, file, quarkCred)
                    .onSuccess { downloadLink = it }
                    .onFailure { downloadError = it.message ?: "获取下载链接失败" }
            } finally {
                isFetchingDownloadLink = false
            }
        }
    }

    fun dismissDownloadDialog() {
        val link = downloadLink
        downloadLink = null
        // 弹窗被关闭（用户点「关闭」，未开始下载）：清理当前平台的云端临时转存（夸克/115/光鸭），避免残留
        if (link?.cleanupDirFid != null) {
            viewModelScope.launch {
                val credential = currentCredential() ?: return@launch
                link.cleanupDirFid?.let { dirFid ->
                    currentRepo().cleanupTempDir(dirFid, credential)
                }
            }
        }
    }

    /**
     * 将直链加入下载队列（携带对应平台凭证与 UA；夸克直链做 CDN 节点优选）。
     * 不触发切页 —— 与 startDownload 的区别：批量下载全部入队后才统一切到下载页。
     */
    private suspend fun enqueueDownload(
        link: DownloadLink,
        credential: String,
        fileName: String = link.filename
    ) {
        val isUC = currentPlatform == SharePlatform.UC
        val isXunlei = currentPlatform == SharePlatform.XUNLEI
        val isBaidu = currentPlatform == SharePlatform.BAIDU
        val isC139 = currentPlatform == SharePlatform.C139
        val isPan123 = currentPlatform == SharePlatform.PAN123
        val isPan115 = currentPlatform == SharePlatform.PAN115
        val isGuangYa = currentPlatform == SharePlatform.GUANGYA
        val isILanzou = currentPlatform == SharePlatform.ILANZOU
        val isLanzou = currentPlatform == SharePlatform.LANZOU
        val isQuark = currentPlatform == SharePlatform.QUARK
        // 注：上游在此按平台选 DownloadPlatform 随 enqueue 传入；本项目的 downloadManager.enqueue
        // 没有 platform 参数（线程数按全局设置），因此这里只保留请求头/直链处理。
        // 【关键修复】夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（AlistGo/alist#830 类缺陷）
        // getFreshCookie 有 90 分钟间隔保护，与取链处调用幂等，得到的是同一份。
        val effectiveCredential = when (currentPlatform) {
            SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
            SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
            else -> credential
        }
        // 游客直链（夸克/UC 小文件）：只有服务端随取链响应下发的游客态 __pugs，没有登录 Cookie。
        // 夸克缺它 CDN 直接 412；UC 缺它 CDN 403（RequestDeniedByCallback: require login）
        val guestCookie = link.guestCookie
        val isGuestLink = guestCookie.isNotBlank()
        // 迅雷直链 URL 自带签名，无需 Cookie；夸克/UC/百度需 Cookie + UA；139 直链为 CDN 签名地址；123 直链需 Referer
        // 115 直链需「登录 Cookie + 取链响应下发的 900 秒 CDN Cookie」双 Cookie（缺一即 CDN 403 no cookie value）
        val headers = when {
            isXunlei -> mapOf("User-Agent" to XunleiConstants.APP_UA) // 迅雷直链必须用官方 app UA，浏览器 UA 会触发 CDN 降级（200整文件）
            isBaidu -> mapOf(
                "Cookie" to credential,
                "User-Agent" to BaiduConstants.UA_NETDISK
            )
            isC139 -> mapOf("User-Agent" to C139Constants.PC_UA)
            // 115：CDN 同时校验登录 Cookie 与取链响应下发的 900 秒 CDN Cookie，
            // 缺任一直接 403 no cookie value（文档 §4.3），因此两份 Cookie 拼接后一起发；
            // UA 必须是客户端串（与取链一致），浏览器 UA 会被判「网页端」拒发大文件直链
            isPan115 -> mapOf(
                "Cookie" to Pan115Constants.mergeCookies(effectiveCredential, guestCookie),
                "User-Agent" to Pan115Constants.CLIENT_UA,
                "Referer" to Pan115Constants.DOWNLOAD_REFERER
            )
            // 123 分享/个人盘直链为 CDN 签名地址，下载必须带 Referer（文档 §5.3.1）
            isPan123 -> mapOf(
                "User-Agent" to Pan123Constants.WEB_UA,
                "Referer" to Pan123Constants.DOWNLOAD_REFERER
            )
            // 光鸭直链为签名 CDN 地址，下载带 UA + Referer，且不携带 Authorization/Cookie（文档 §5.6/§7.1）
            isGuangYa -> mapOf(
                "User-Agent" to GuangYaConstants.WEB_UA,
                "Referer" to GuangYaConstants.DOWNLOAD_REFERER
            )
            // 蓝奏优享直链为 CDN 地址，下载带 UA + Referer + Origin，不带 Cookie（文档 §4.1）
            isILanzou -> mapOf(
                "User-Agent" to ILanzouConstants.WEB_UA,
                "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
                "Origin" to ILanzouConstants.WEB_SITE
            )
            // 蓝奏云下载节点要求 Referer 为节点 origin，并注入 down_ip=1 Cookie（文档 §A4）
            isLanzou -> {
                val lanzouOrigin = runCatching { java.net.URI(link.downloadUrl) }.getOrNull()
                    ?.let { "${it.scheme}://${it.host}" } ?: "https://pan.lanzoui.com"
                mapOf(
                    "User-Agent" to LanzouConstants.WEB_UA,
                    "Referer" to "$lanzouOrigin/",
                    "Cookie" to LanzouConstants.DOWN_IP_COOKIE
                )
            }
            // UC：OSS 直链按 Referer 档位限速（缺 Referer 被 Callback 限到 ~100 KB/s），
            // 补官方 Web 客户端同款 Referer/Origin 即满速
            // （注：UC 仓库未实现游客取链，游客态在取链阶段即被拦下，这里不需要游客分支）
            isUC -> mapOf(
                "Cookie" to credential,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
            // 夸克：防盗链需固定 Referer（对齐 AList quark_uc）
            else -> if (isGuestLink) mapOf(
                "Cookie" to guestCookie,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            ) else mapOf(
                "Cookie" to effectiveCredential,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
        // 夸克直链：原样使用（关闭节点改写/探测，避免消耗直链额度与节点签名 412）
        val effectiveUrl = if (isQuark) {
            QuarkCdn.fastest(link.downloadUrl, if (isGuestLink) guestCookie else effectiveCredential)
        } else {
            link.downloadUrl
        }
        // 入队前先记住「哪个平台的仓库 + 哪份凭证」：下载完成时用户可能已经切到别的分享页
        val cleanupRepo = currentRepo()
        val cleanupCredential = credential
        downloadManager.enqueue(
            url = effectiveUrl,
            fileName = fileName,
            headers = headers,
            size = link.size
        ) {
            // 下载完成（master 版通过 onComplete 回调）：清理网盘临时转存目录；失败/取消不触发
            val dirFid = link.cleanupDirFid
            if (dirFid != null && cleanupCredential.isNotBlank()) {
                cleanupRepo.cleanupTempDir(dirFid, cleanupCredential)
            }
        }
    }

    /** 将直链加入下载队列（单文件下载：入队后立即切换到下载页） */
    fun startDownload(link: DownloadLink) {
        viewModelScope.launch {
            // 开始下载：先关闭弹窗（临时转存由下载完成 onComplete 清理，不在此时删）
            downloadLink = null
            // 游客模式：夸克/UC/光鸭/蓝奏优享/蓝奏云的小文件直链不要求登录（link 里已带游客 __pugs）
            val credential = currentCredential().orEmpty()
            if (credential.isBlank() && !supportsGuestDownload()) {
                downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                return@launch
            }
            enqueueDownload(link, credential)
            downloadStarted = true
        }
    }

    private suspend fun loadFiles(
        s: ShareSession,
        dirFid: String,
        credential: String,
        repo: ShareResolveRepository
    ) {
        repo.listFiles(s, dirFid, credential)
            .onSuccess { files ->
                uiState = ResolveUiState.Detail(s, files)
            }
            .onFailure { e ->
                uiState = ResolveUiState.Error(e.message ?: "获取文件列表失败")
            }
    }

    class Factory(
        private val accountRepository: QuarkAccountRepository,
        private val resolveRepository: QuarkResolveRepository,
        private val ucAccountRepository: UCAccountRepository,
        private val ucResolveRepository: UCResolveRepository,
        private val xunleiAccountRepository: XunleiAccountRepository,
        private val xunleiResolveRepository: XunleiResolveRepository,
        private val baiduAccountRepository: BaiduAccountRepository,
        private val baiduResolveRepository: BaiduResolveRepository,
        private val c139AccountRepository: C139AccountRepository,
        private val c139ResolveRepository: C139ResolveRepository,
        private val pan123AccountRepository: Pan123AccountRepository,
        private val pan123ResolveRepository: Pan123ResolveRepository,
        private val pan115AccountRepository: Pan115AccountRepository,
        private val pan115ResolveRepository: Pan115ResolveRepository,
        private val guangyaAccountRepository: GuangYaAccountRepository,
        private val guangyaResolveRepository: GuangYaResolveRepository,
        private val ilanzouAccountRepository: ILanzouAccountRepository,
        private val ilanzouResolveRepository: ILanzouResolveRepository,
        private val lanzouAccountRepository: LanzouAccountRepository,
        private val lanzouResolveRepository: LanzouResolveRepository,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ResolveViewModel::class.java))
            return ResolveViewModel(
                accountRepository, resolveRepository,
                ucAccountRepository, ucResolveRepository,
                xunleiAccountRepository, xunleiResolveRepository,
                baiduAccountRepository, baiduResolveRepository,
                c139AccountRepository, c139ResolveRepository,
                pan123AccountRepository, pan123ResolveRepository,
                pan115AccountRepository, pan115ResolveRepository,
                guangyaAccountRepository, guangyaResolveRepository,
                ilanzouAccountRepository, ilanzouResolveRepository,
                lanzouAccountRepository, lanzouResolveRepository,
                downloadManager
            ) as T
        }
    }
}
