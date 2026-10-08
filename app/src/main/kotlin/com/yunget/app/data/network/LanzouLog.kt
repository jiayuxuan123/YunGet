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

package com.yunget.app.data.network

import android.util.Log

/**
 * 蓝奏取链链路的诊断日志（走 logcat）。
 *
 * 为什么单独拎出来：这条链路本质上是"猜页面"—— 分享页、iframe 页、校验页、下载节点，
 * 每一步都可能被换成另一个形状。出问题时唯一能回答"它到底请求了哪一页、拿到什么"的就是这条日志。
 * 应用内「导出日志」会把 logcat 一起带上，所以这些行能随后台日志交到手里 ——
 * 之前两个蓝奏故障都是卡在"看不到它请求了什么"，只能来回猜。
 *
 * **只记 host 与 path，不记 query**：蓝奏的直链 query 里带签名，等于一把临时钥匙，
 * 没有理由为了一句诊断把它写进日志（日志是要发给别人看的）。
 */
internal object LanzouLog {

    private const val TAG = "LanzouParse"

    /** 下载节点的一次探测（HEAD）。`first` 标出首跳，方便看出后面是被重定向到哪一步的。 */
    fun hop(host: String, path: String, code: Int, first: Boolean) {
        Log.d(TAG, "hop${if (first) "(首跳)" else ""} $code $host$path")
    }

    /** 其它需要留痕的节点：命中校验页、提交验证、参数取自哪一份 HTML 等。 */
    fun note(message: String) {
        Log.d(TAG, message)
    }
}
