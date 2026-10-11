/*
 * YunGet L2 网盘 Provider 插件 —— 首批四个网盘。
 *
 * 每个类 = 一个市场插件：认领本平台的分享链接，解析执行委托宿主既有仓库。
 * 链接识别复用宿主的 ShareLinkParser，所以"插件认领"与"内置能解析"永远一致。
 *
 * 凭证来源：宿主桥的已登录账号摘要（与「导出网盘认证」同一套判定口径）——
 * 用户在哪个网盘登录过，对应插件就能用哪个网盘；没登录时交给内置路径给出
 * 准确的"请先登录"提示（自动回退，功能不中断）。
 */

package com.yunget.plugin.provider

import com.yunget.app.data.network.LanzouApi
import com.yunget.app.data.network.Pan123Api
import com.yunget.app.data.network.QuarkApi
import com.yunget.app.data.network.UCApi
import com.yunget.app.data.plugin.YunHostBridge
import com.yunget.app.data.repository.LanzouResolveRepository
import com.yunget.app.data.repository.Pan123ResolveRepository
import com.yunget.app.data.repository.QuarkResolveRepository
import com.yunget.app.data.repository.UCResolveRepository

/** 夸克：纯 HTTP 分享解析，登录态走 Cookie（web 端口径）。 */
class QuarkCloudProvider : HostDelegatingProvider(
    providerId = "provider.quark",
    displayName = "夸克网盘",
    platformId = "QUARK",
) {
    override fun buildRepo(bridge: YunHostBridge) = QuarkResolveRepository(QuarkApi())
}

/** UC：与夸克同族（账号系统、接口形态一致），Cookie 登录态。 */
class UcCloudProvider : HostDelegatingProvider(
    providerId = "provider.uc",
    displayName = "UC 网盘",
    platformId = "UC",
) {
    override fun buildRepo(bridge: YunHostBridge) = UCResolveRepository(UCApi())
}

/** 蓝奏云：分享解析与取链完全匿名，Cookie 仅用于个人能力（游客也可用本插件）。 */
class LanzouCloudProvider : HostDelegatingProvider(
    providerId = "provider.lanzou",
    displayName = "蓝奏云",
    platformId = "LANZOU",
) {
    override fun buildRepo(bridge: YunHostBridge) = LanzouResolveRepository(LanzouApi())
}

/** 123 云盘：JWT 登录态，由仓库的 tokenProvider 每次现取（过期刷新与内置路径一致）。 */
class Pan123CloudProvider : HostDelegatingProvider(
    providerId = "provider.pan123",
    displayName = "123云盘",
    platformId = "PAN123",
) {
    override fun buildRepo(bridge: YunHostBridge) = Pan123ResolveRepository(
        Pan123Api(),
        tokenProvider = { credential() },
    )
}
