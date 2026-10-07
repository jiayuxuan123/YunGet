/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 * 本文件取自上游 YunX (https://github.com/CYQawa/YunX)
 * Copyright (C) 2026 CYQawa
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

package com.yunget.app.util

import android.util.Base64

/**
 * 「安全提示」弹窗的文本常量（异或 + Base64 存表，运行时解密）。
 *
 * ★ 为什么加密存：安全提示的作用是告诉用户「本应用免费、官方来源在哪」，
 *   二次打包者最先改的就是这几句（换成自己的收费渠道），明文常量改起来一行就够；
 *   存成密文至少要先把解密逻辑读一遍，抬高了「顺手改文案」的成本。
 * ★ 只保留 [SafetyNoticeDialog] 真正用到的那几条：上游 TextCipher 里还有一批
 *   与「防篡改探测 / 动态加载」相关的路径常量，那是上游别的机制在用的，
 *   本二次开发版没有对应实现，整表搬过来只会留一堆无人引用的死常量。
 *   若以后移植那些机制，把对应常量按同样方式补进来即可（K 表必须与上游一致）。
 * ★ 本版把 [dBody] / [dUrl] 里的仓库地址换成了**本二次开发版的仓库**
 *   （与「关于」页一致）：弹窗要让用户去核对的是自己这个版本从哪里来的。
 */
internal object TextCipher {

    private val K = intArrayOf(89, 79, 163, 17, 126, 216, 60, 155, 6, 242, 104, 29, 144, 90, 183, 76)

    internal fun dec(cipher: String): String {
        return try {
            val data = Base64.decode(cipher, Base64.NO_WRAP)
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor K[i % K.size]).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    /** 已确认过安全提示的标记（SharedPreferences key） */
    private const val P_NOTICE_FLAG = "IDz8fxGsVfhjrQl++w=="
    private const val D_TITLE = "vOEq9Ptw2hSWFcyn"
    private const val D_BODY = "v9MP9MRM2w+uF8aRdd8fqdzCS6XHN4AX407o+yrKUtDpqj6RkWSm83KGGG6qdZgrMDvLZBz2X/Rr3QJ08SPCNCwuzSBM6xPCc5wveOS5N86xxAb3/HDYIIgb9YN19C+qz/ZFqd4xvQjufN/4H8xS9e+nAbqWfr19t3CMpgiyA/W28y/50WzaA4gU6rV04jyk5PJHq/gwnjDhXcn7BONQ1t2oKpmYRJB0un6Asie9HMe8whD082DUJrsR6J8="
    private const val D_BTN = "v8cy9uF91RqVFtKb"
    private const val D_COUNTDOWN = "fCuD9tlK2QuIF+eydd8Epc7i"
    private const val D_OFFICIAL = "vOE79+hh2SeGFNKNd9M/"
    private const val D_URL = "MTvXYQ3iE7Rhmxx15TiZLzYijHsXuUXufocJc6FohGMAOs1WG6w="
    private const val D_COPIED = "vPgR9NpV2ROw"
    private const val D_COPY = "vOsu9PZu1Qi4FOa4"

    val pNoticeFlag get() = dec(P_NOTICE_FLAG)
    val dTitle get() = dec(D_TITLE)
    val dBody get() = dec(D_BODY)
    val dBtn get() = dec(D_BTN)
    val dCountdown get() = dec(D_COUNTDOWN)
    val dOfficial get() = dec(D_OFFICIAL)
    val dUrl get() = dec(D_URL)
    val dCopied get() = dec(D_COPIED)
    val dCopy get() = dec(D_COPY)
}
