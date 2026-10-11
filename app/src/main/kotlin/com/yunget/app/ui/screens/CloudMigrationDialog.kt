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

package com.yunget.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunget.app.data.backup.AuthBackupManager
import com.yunget.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 网盘插件化的**迁移告知**（P8 的迁移体验，先于第一个 Provider 插件落地）。
 *
 * ## 为什么在还没有网盘插件时就弹
 *
 * 插件化改的是"解析由谁来做"，用户能感知到的风险只有两个：「我的账号还在吗」和
 * 「插件坏了是不是网盘就用不了了」。这两个问题在版本更新那一刻就该有答案，
 * 而不是等第一个插件出问题再解释。所以这是一次性的告知 + 确认：
 *
 *  1. **账号保留询问**：列出当前已登录的平台与昵称，明确告诉用户迁移不动它们 ——
 *     内置实现保留作回退（P0 的既定原则），账号数据存在应用库里，插件接管不删除。
 *  2. **回退保证**：装了并启用了某个网盘的插件后，那个网盘优先走插件；
 *     插件认领失败、健康检查不过或抛异常，自动回退到内置实现并写日志
 *     （见 [com.yunget.app.data.cloud.CloudProviderResolver]，回退是运行时行为，不由这个弹窗控制）。
 *
 * ## 展示时机
 *
 * 升级到 2.7.6 或更高版本后的**首次启动**、且用户至少登录过一个网盘时弹一次；
 * 一个版本号只弹一次（确认状态记在 SharedPreferences）。没有账号的用户不打扰 ——
 * 没有账号就没有"迁移会动我什么"的疑虑。
 */
object CloudMigrationNotice {

    private const val PREFS = "yunget_settings"
    private const val KEY_ACK_VERSION = "cloud_migration_ack_version"

    /** 还没针对这个版本确认过、且用户有已登录的网盘账号时返回 true。 */
    suspend fun shouldShow(context: Context, versionName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACK_VERSION, null) == versionName) return false
        return runCatching { hasLoggedInAccount(context) }.getOrDefault(false)
    }

    /** 用户确认过（或没有账号）之后调用：本版本内不再弹。 */
    fun acknowledge(context: Context, versionName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACK_VERSION, versionName).apply()
    }

    /** 是否有"凭证非空"的已登录账号（复用导出逻辑的同一套判定，两边口径不会漂移）。 */
    private suspend fun hasLoggedInAccount(context: Context): Boolean =
        accountSummaries(context).isNotEmpty()

    /**
     * 已登录账号摘要：(平台展示名, 昵称或平台名)。
     *
     * 走 [AuthBackupManager.exportJson] 而不是逐个 DAO 数数：那里已经定义了
     * "什么算已登录"（cookie/token 非空），复用它让弹窗和备份的口径永远一致。
     */
    suspend fun accountSummaries(context: Context): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.get(context.applicationContext)
                val manager = AuthBackupManager(
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
                )
                val json = manager.exportJson(onlyLoggedIn = true)
                val arr = JSONObject(json).optJSONArray("accounts") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val platform = o.optString("platform")
                        if (platform.isEmpty()) continue
                        add(platformDisplayName(platform) to o.optString("nickname").ifBlank { platformDisplayName(platform) })
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** 平台 id → 展示名（与解析页、登录页的叫法一致）。 */
    fun platformDisplayName(id: String): String = when (id) {
        "quark" -> "夸克"
        "uc" -> "UC"
        "xunlei" -> "迅雷"
        "baidu" -> "百度"
        "c139" -> "139"
        "pan123" -> "123"
        "pan115" -> "115"
        "guangya" -> "光鸭"
        "ilanzou" -> "蓝奏云优享"
        "lanzou" -> "蓝奏云"
        else -> id
    }
}

/** 迁移告知弹窗。内容由调用方传入（账号摘要、已装网盘插件数），本组件只负责呈现。 */
@Composable
fun CloudMigrationDialog(
    accounts: List<Pair<String, String>>,
    providerCount: Int,
    providerNames: List<String>,
    onAcknowledge: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text("网盘功能正在插件化") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "从 2.7.6 起，网盘的分享解析会逐步交给插件处理：" +
                        "装了某个网盘的插件并启用后，那个网盘会优先由插件解析。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "插件出问题时会自动回退到应用内置的实现并记录日志，" +
                        "网盘功能不会因此中断；内置实现会一直保留。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (accounts.isEmpty()) {
                        "当前没有已登录的网盘账号。"
                    } else {
                        "以下账号都会原样保留，迁移不会删除或改动它们："
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (accounts.isNotEmpty()) {
                    Text(
                        text = accounts.joinToString("\n") { (platform, nickname) ->
                            "· $platform" + (if (nickname != platform) "（$nickname）" else "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (providerCount > 0) {
                        "已安装 ${providerCount} 个网盘插件：" +
                            providerNames.joinToString("、") + "。"
                    } else {
                        "目前还没有可用的网盘插件。装好之后，上面这些账号对应的网盘会优先走插件通道。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAcknowledge()
            }) { Text("知道了") }
        },
    )
}
