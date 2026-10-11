/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.plugin

import android.content.Context
import com.yunget.app.data.backup.AuthBackupManager
import com.yunget.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 宿主桥：L2 网盘 Provider 插件从宿主拿"它自己拿不到的东西"的唯一通道。
 *
 * 引擎的 [dev.turbodl.plugin.runtime.PluginContext] 是纯 JVM 面（没有 Android Context），
 * 而网盘插件必须知道两件事：**应用上下文**与**该网盘的登录态**。这两样都以**服务**
 * （[PluginHost.services]）的形式在装载任何插件**之前**注册进宿主：
 *
 * ```
 * host.services.register("yung.host.bridge", YunHostBridge(context), "host")
 * ```
 *
 * 插件侧用 `context.service("yung.host.bridge", YunHostBridge::class.java)` 取用。
 *
 * ## 信任边界（重要）
 *
 * 桥里**只有**"读上下文"与"读已登录账号摘要"两个能力，且 `loggedInAccountsJson` 的
 * 内容是**内存传递**——绝不允许写日志、写文件。L2 插件本来就能绕过桥直接访问宿主
 * （它跑在宿主进程里），桥的意义是给**行为边界**画一条清楚且可审计的线：
 * 插件用桥做什么，代码里一眼可见。
 */
class YunHostBridge(private val context: Context) {

    /** 应用上下文（供插件构造自己的 HTTP 客户端、读缓存目录等）。 */
    fun appContext(): Context = context.applicationContext

    /**
     * 当前已登录的网盘账号摘要（明文 JSON）：
     * `{ "accounts": [ { "platform": "quark", "cookie": "…", "nickname": "…" }, … ] }`
     *
     * 判定口径与「导出网盘认证」完全一致（同一份 [AuthBackupManager] 代码）：
     * 只有凭证非空的账号会出现。插件按自己的 platform 字段取对应凭证。
     * **绝不写日志**：这份 JSON 里是明文登录态。
     */
    suspend fun loggedInAccountsJson(): String = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context.applicationContext)
        AuthBackupManager(
            quarkDao = db.quarkAccountDao(),
            ucDao = db.ucAccountDao(),
            xunleiDao = db.xunleiAccountDao(),
            baiduDao = db.baiduAccountDao(),
            c139Dao = db.c139AccountDao(),
            pan123Dao = db.pan123AccountDao(),
            pan115Dao = db.pan115AccountDao(),
            guangyaDao = db.guangYaAccountDao(),
            ilanzouDao = db.iLanzouAccountDao(),
            lanzouDao = db.lanzouAccountDao(),
        ).exportJson(onlyLoggedIn = true)
    }

    companion object {
        /** 服务注册 id（插件按它取桥）。 */
        const val SERVICE_ID = "yung.host.bridge"
    }
}
