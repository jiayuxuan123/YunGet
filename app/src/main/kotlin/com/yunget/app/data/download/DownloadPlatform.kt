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

package com.yunget.app.data.download

/**
 * 下载来源平台标识（用于按平台独立设置下载线程数 / 标记任务来源）。
 * 字符串常量而非枚举：便于直接持久化到 Room 字段，也与各 ViewModel 解耦。
 *
 * ★ 本文件为移植 Gopeed 引擎时补入（上游 YunX 的原样副本）：本项目原无此文件，
 *   而上游引擎侧代码（DownloadPlatform.GITHUB / MAGNET 等）依赖这些常量。
 *   目前实际被用到的只有 [GITHUB]（内核包 GitHub 通道的分片数口径）。
 */
object DownloadPlatform {
    const val QUARK = "quark"
    const val UC = "uc"
    const val XUNLEI = "xunlei"
    const val BAIDU = "baidu"
    const val C139 = "c139"
    const val PAN123 = "pan123"
    const val PAN115 = "pan115"
    /** 光鸭云盘 */
    const val GUANGYA = "guangya"
    /** 蓝奏云优享版 */
    const val ILANZOU = "ilanzou"
    /** 蓝奏云 */
    const val LANZOU = "lanzou"
    /** 磁力链接（BT）：只能交给 Gopeed 引擎，内置分片下载器不支持 */
    const val MAGNET = "magnet"
    /** GitHub 仓库/Release 下载 */
    const val GITHUB = "github"
    /** 通用/未知来源（手动添加、应用更新下载等） */
    const val GENERIC = "generic"
}
