/*
 * YunGet L2 网盘 Provider 插件 —— 委托宿主实现的公共基类。
 *
 * ## 首批插件为什么"委托"而不是自带 HTTP 实现
 *
 * 插件运行在宿主进程里，父类加载器就是宿主本身 —— 各网盘的 API/仓库类对插件可见。
 * 首批迁移的实质是**把路由搬到插件侧**：认领（handles）、健康检查（health）、
 * 装载生命周期、市场分发与启停全部由插件承担；解析执行暂时调用宿主的既有实现
 * （它们与内置回退是同一份代码，行为天然一致）。后续版本再把各网盘的 HTTP 逻辑
 * 真正搬进插件包、从宿主删除 —— 那时通道已经过验证，搬迁是纯机械工作。
 *
 * ## 错误语义
 *
 * 宿主仓库的错误是 Result + 中文消息（供解析页直接显示）。契约的 CloudError
 * 需要结构化分类，但消息来源是既有仓库 —— 所以这里统一包成
 * [CloudError.UNEXPECTED] 携带原文，宿主适配器（ProviderBackedShareRepository）
 * 对 UNEXPECTED 的展示就是**原样显示 detail**，用户看到的文案与内置路径一致。
 * CancellationException 必须重新抛出：吞掉它会破坏协程取消语义
 * （CloudProviderResolver 的注释里专门记过这一条）。
 */

package com.yunget.plugin.provider

import com.yunget.app.data.cloud.CloudCapability
import com.yunget.app.data.cloud.CloudError
import com.yunget.app.data.cloud.CloudFile
import com.yunget.app.data.cloud.CloudFilePage
import com.yunget.app.data.cloud.CloudDownloadInfo
import com.yunget.app.data.cloud.CloudProvider
import com.yunget.app.data.cloud.CloudResult
import com.yunget.app.data.cloud.CloudShare
import com.yunget.app.data.cloud.CloudShareRequest
import com.yunget.app.data.network.ShareLinkParser
import com.yunget.app.data.network.model.ShareSession
import com.yunget.app.data.plugin.YunHostBridge
import com.yunget.app.data.repository.ShareResolveRepository
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import org.json.JSONObject

abstract class HostDelegatingProvider(
    /** 稳定插件 id（市场条目 / 宿主路由日志都用它）。 */
    final override val providerId: String,
    /** 界面展示名。 */
    final override val displayName: String,
    /** 认领判定用的内置平台枚举名（SharePlatform.name，如 "QUARK"）。 */
    private val platformId: String,
) : Plugin, CloudProvider {

    @Volatile
    private var bridge: YunHostBridge? = null

    @Volatile
    private var cachedRepo: ShareResolveRepository? = null

    // ---- Plugin 生命周期 ----

    override val id: String get() = providerId
    override val name: String get() = displayName

    override fun onLoad(context: PluginContext) {
        // 宿主在装载任何插件之前注册（见 PluginRuntime.createHost）；拿不到说明宿主异常
        bridge = context.service(YunHostBridge.SERVICE_ID, YunHostBridge::class.java)
    }

    // ---- CloudProvider 契约 ----

    override fun capabilities(): Set<CloudCapability> = setOf(
        CloudCapability.SHARE_PARSE,
        CloudCapability.SHARE_BROWSE,
        CloudCapability.DIRECT_LINK,
    )

    /**
     * 认领判定复用宿主的 [ShareLinkParser]：与内置路由对同一个链接的识别**必然一致**，
     * 不会出现"插件认领了、内置却解析不了"或反过来的错位。
     */
    override fun handles(rawInput: String): Boolean =
        runCatching { ShareLinkParser.parse(rawInput)?.platform?.name == platformId }
            .getOrDefault(false)

    /**
     * 健康检查刻意宽松：只确认桥在。不查登录态 —— 夸克/蓝奏等支持游客路径，
     * 登录与否让解析调用自己回答（失败会回退内置，内置给出准确的"请先登录"提示）。
     */
    override fun health(): String? =
        if (bridge == null) "宿主桥不可用（重启应用后恢复）" else null

    // ---- 委托执行 ----

    /**
     * 从宿主桥拿本平台的登录凭证（cookie / token，按字段顺序探测）。
     * 没登录返回 null —— 各仓库对空凭证有自己的失败方式（与内置路径一致）。
     */
    protected suspend fun credential(): String? {
        val b = bridge ?: return null
        return runCatching {
            val arr = JSONObject(b.loggedInAccountsJson()).optJSONArray("accounts") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("platform") != platformId) continue
                for (key in listOf("cookie", "accessToken", "appToken", "token")) {
                    val v = o.optString(key)
                    if (v.isNotBlank()) return v
                }
            }
            null
        }.getOrNull()
    }

    /** 由子类提供本网盘的宿主仓库（无状态构造，缓存一份复用）。 */
    protected abstract fun buildRepo(bridge: YunHostBridge): ShareResolveRepository

    private suspend fun repo(): ShareResolveRepository {
        cachedRepo?.let { return it }
        val b = bridge ?: throw IllegalStateException("宿主桥不可用")
        return synchronized(this) {
            cachedRepo ?: buildRepo(b).also { cachedRepo = it }
        }
    }

    override suspend fun resolveShare(request: CloudShareRequest): CloudResult<CloudShare> =
        runRepo {
            val session = it.createSession(request.rawInput, request.password, credential().orEmpty())
                .getOrThrow()
            CloudShare(shareId = session.shareId, title = session.title)
        }

    override suspend fun listShareFiles(
        shareId: String,
        path: String,
        password: String?,
        page: String?,
    ): CloudResult<CloudFilePage> = runRepo {
        val files = it.listFiles(
            ShareSession(shareId = shareId, stoken = "", title = displayName),
            dirFid = path,
            cookie = credential().orEmpty(),
        ).getOrThrow()
        CloudFilePage(
            files = files.map { f ->
                CloudFile(
                    id = f.fid,
                    name = f.fname,
                    isDirectory = f.isdir,
                    sizeBytes = f.fsize,
                )
            },
            nextPage = null,
        )
    }

    override suspend fun resolveDownload(
        shareId: String,
        file: CloudFile,
        password: String?,
    ): CloudResult<CloudDownloadInfo> = runRepo {
        val link = it.getShareDownloadLinkWithoutSave(
            ShareSession(shareId = shareId, stoken = "", title = displayName),
            com.yunget.app.data.network.model.ShareFile(
                fid = file.id,
                fname = file.name,
                fsize = file.sizeBytes,
                isdir = false,
                pdirFid = "",
                fidToken = "",
            ),
            cookie = credential().orEmpty(),
        ).getOrThrow()
        CloudDownloadInfo(
            url = link.downloadUrl,
            fileName = link.filename,
            sizeBytes = link.size,
        )
    }

    private suspend inline fun <T> runRepo(block: (ShareResolveRepository) -> T): CloudResult<T> = try {
        CloudResult.Ok(block(repo()))
    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
        throw c
    } catch (e: Exception) {
        CloudResult.Err(CloudError.UNEXPECTED(e.message ?: e.javaClass.simpleName))
    }
}
