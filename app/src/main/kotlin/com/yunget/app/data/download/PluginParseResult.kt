/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.yunget.app.data.download

/**
 * 插件解析一条链接的结果 —— 界面可以直接拿来展示与下载。
 *
 * ## 为什么不用引擎的 `DownloadRequest`
 *
 * 引擎的 `DownloadRequest` 里有 `destination: File`（落点）与 `stableKey`，
 * 那些是"下到哪、怎么去重"的**宿主决定**，界面上一个都用不到；而界面需要的
 * 平台名、作品标题、媒体类型（视频/图片）、清晰度标签，它一个都没有。
 * 直接把 `DownloadRequest` 摆到界面上，每个消费方都要自己再解析一遍 tags。
 *
 * 所以这里做一次显式映射：**这个类型只说界面需要的事**。
 *
 * @param platform 平台标识（`weibo` / `xhs` / `pixiv` / …）
 * @param title 作品标题（可能为空）
 * @param author 作者（可能为空）
 * @param cover 封面图地址（可能为空）
 * @param text 正文/描述（可能为空）
 * @param pageUrl 作品原始网页地址
 * @param medias 解析出的媒体
 */
data class PluginParseResult(
    val platform: String,
    val title: String = "",
    val author: String = "",
    val cover: String = "",
    val text: String = "",
    val pageUrl: String = "",
    val medias: List<PluginMedia> = emptyList(),
)

/**
 * 一条可下载的媒体。
 *
 * @param url 直链
 * @param fileName 建议文件名
 * @param headers 下载时必须带的请求头（各平台校验 Referer/UA，缺了 403）
 * @param quality 清晰度/规格标签（`720P` / `原图` / `P1/4`），仅展示
 * @param kind 媒体类型
 */
data class PluginMedia(
    val url: String,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "",
    val kind: PluginMediaKind = PluginMediaKind.IMAGE,
)

/** 媒体类型。判据来自插件的 `tags.kind`，缺省按扩展名推（见 turbo-plugin-parser）。 */
enum class PluginMediaKind { VIDEO, IMAGE, AUDIO }
