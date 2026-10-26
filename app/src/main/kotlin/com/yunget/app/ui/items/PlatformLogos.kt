/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
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

package com.yunget.app.ui.items

import androidx.annotation.DrawableRes
import com.yunget.app.R

/**
 * 网盘 / 视频平台 Logo 表：字符串标识 → drawable 资源。
 *
 * ## 为什么用字符串标识而不是枚举
 *
 * 网盘列表（`DriveScreen.DriveAccount`）与插件层都用字符串 id（`"quark"`、`"pan115"`…），
 * 因为插件是外部提供的，不该依赖应用内的枚举。映射放在 UI 层，数据层保持干净。
 *
 * ## 渲染约定
 *
 * **按 Logo 自身形状原样画**：不裁圆、不加底色。
 * 这批图标有两种来源——有的是自带底色的圆角方块，有的是透明背景的图形。
 * 统一裁成圆形会把前者的四角切掉，给后者糊一圈假底色。
 *
 * 未登录状态用整体降透明度表示，而不是换成"字母头像"——
 * 用户要的是"一眼认出这是哪个网盘"，字母（"夸"/"蓝"）做不到这一点。
 */
@DrawableRes
fun platformLogoRes(id: String): Int? = when (id.lowercase()) {
    // ---- 网盘 ----
    "quark" -> R.drawable.logo_quark
    "uc" -> R.drawable.logo_uc
    "xunlei" -> R.drawable.logo_xunlei
    "baidu" -> R.drawable.logo_baidu
    "c139" -> R.drawable.logo_c139
    "pan123" -> R.drawable.logo_123pan
    "pan115" -> R.drawable.logo_pan115
    "guangya" -> R.drawable.logo_guangya
    "ilanzou" -> R.drawable.logo_ilanzou
    "lanzou" -> R.drawable.logo_lanzou
    "github" -> R.drawable.logo_github

    // ---- 视频平台（本分支暂未接入解析，图标先备着）----
    "douyin" -> R.drawable.logo_douyin
    "xhs" -> R.drawable.logo_xhs
    "kuaishou" -> R.drawable.logo_kuaishou
    "weibo" -> R.drawable.logo_weibo
    "acfun" -> R.drawable.logo_acfun
    "bilibili" -> R.drawable.logo_bilibili
    "pixiv" -> R.drawable.logo_pixiv

    else -> null
}
