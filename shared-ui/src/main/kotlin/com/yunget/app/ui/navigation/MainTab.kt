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

package com.yunget.app.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.yunget.app.ui.components.SharedIcons

/**
 * 主页底部导航的 4 个 Tab。
 */
enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Resolve("解析", SharedIcons.link, SharedIcons.link),
    Drive("网盘", SharedIcons.cloud, SharedIcons.cloudOutline),
    Download("下载", SharedIcons.download, SharedIcons.downloadOutline),
    Settings("设置", SharedIcons.settings, SharedIcons.settingsOutline)
}
