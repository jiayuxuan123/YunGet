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

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 应用对外的联系方式与仓库地址（引导页 / 设置页 / 关于页共用）。
 *
 * ★ 为什么要集中放：同一个仓库地址会在多个界面出现，就地写死的话改一处漏一处，
 *   所以对外入口一律从这里取常量。新增对外入口时，先往这里加常量，再到界面里引用。
 *
 * ★ 本二次开发版（YunGet）**没有自己的社区群**，因此不设群号入口 ——
 *   上游 YunX 的社区群与本版无关，把上游群号放进来会让用户以为那是本版的支持渠道。
 *   问题反馈走 [GITHUB_REPO] 的 issue 即可。
 */
object AppLinks {

    /** GitHub 仓库完整地址（点击用系统浏览器打开）：本二次开发版仓库 */
    const val GITHUB_REPO = "https://github.com/jiayuxuan123/YunGet"

    /** GitHub 仓库展示用短地址（去掉协议头，界面文字用；与 [GITHUB_REPO] 必须指向同一仓库） */
    const val GITHUB_REPO_DISPLAY = "github.com/jiayuxuan123/YunGet"

    /**
     * 云取官网（GitHub Pages）。
     *
     * 站点源码就在本仓库的 `website/` 下、产物在 `docs/`，所以这个地址与仓库同源：
     * 官网上的版本、截图、下载入口都是从仓库里生成出来的，不会与仓库各说各话。
     */
    const val WEBSITE = "https://jiayuxuan123.github.io/YunGet/"

    /** 官网展示用短地址。 */
    const val WEBSITE_DISPLAY = "jiayuxuan123.github.io/YunGet"

    /**
     * TurboDL（下载引擎）官网。
     *
     * 单列出来是因为它讲的是**另一件事**：引擎是可独立使用的 SDK，
     * 想看"分片调度与插件机制到底怎么做的"该去引擎那边，而不是在应用官网里翻。
     */
    const val ENGINE_WEBSITE = "https://jiayuxuan123.github.io/TurboDL/"

    /** 引擎官网展示用短地址。 */
    const val ENGINE_WEBSITE_DISPLAY = "jiayuxuan123.github.io/TurboDL"

    /** 引擎仓库（插件开发文档、Release、Issue 都在那边）。 */
    const val ENGINE_REPO = "https://github.com/jiayuxuan123/TurboDL"

    /** 插件源仓库（官方插件源：索引、示例插件、签名公钥）。 */
    const val PLUGINS_REPO = "https://github.com/jiayuxuan123/YunGet-Plugins"

    /** 用系统浏览器打开仓库页；返回 false 表示没有可用浏览器 */
    fun openRepo(context: Context): Boolean = open(context, GITHUB_REPO)

    /** 用系统浏览器打开云取官网。 */
    fun openWebsite(context: Context): Boolean = open(context, WEBSITE)

    /** 用系统浏览器打开 TurboDL 引擎官网。 */
    fun openEngineWebsite(context: Context): Boolean = open(context, ENGINE_WEBSITE)

    /** 用系统浏览器打开插件源仓库。 */
    fun openPluginsRepo(context: Context): Boolean = open(context, PLUGINS_REPO)

    /** 打开任意地址；返回 false 表示没有可用浏览器。 */
    private fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
