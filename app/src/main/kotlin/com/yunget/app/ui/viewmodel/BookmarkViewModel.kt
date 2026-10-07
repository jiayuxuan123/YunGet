/*
 * YunGet (云取) - A network drive share-link parser and high-speed downloader for Android.
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

package com.yunget.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunget.app.data.db.BookmarkDao
import com.yunget.app.data.db.BookmarkEntity
import com.yunget.app.ui.SnackbarController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 网盘链接收藏 ViewModel：收藏列表（Room Flow → StateFlow）+ 分类合并 + CRUD。
 */
class BookmarkViewModel(private val dao: BookmarkDao) : ViewModel() {

    val bookmarks: StateFlow<List<BookmarkEntity>> = dao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 分类列表：预置分类 + 数据库中已出现的自定义分类（去重，保持预置在前） */
    val categories: StateFlow<List<String>> = dao.observeCategories()
        .map { db -> (BookmarkEntity.PRESET_CATEGORIES + db).distinct() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BookmarkEntity.PRESET_CATEGORIES
        )

    fun addBookmark(
        link: String,
        title: String,
        platform: String,
        pwd: String,
        category: String
    ) {
        val trimmed = link.trim()
        if (trimmed.isBlank()) {
            SnackbarController.show("请输入网盘链接")
            return
        }
        viewModelScope.launch {
            dao.insert(
                BookmarkEntity(
                    link = trimmed,
                    title = title.trim(),
                    platform = platform,
                    pwd = pwd.trim(),
                    category = category.ifBlank { BookmarkEntity.DEFAULT_CATEGORY }
                )
            )
            SnackbarController.show("已收藏")
        }
    }

    fun updateCategory(id: Long, category: String) {
        val cat = category.ifBlank { BookmarkEntity.DEFAULT_CATEGORY }
        viewModelScope.launch {
            dao.updateCategory(id, cat)
            SnackbarController.show("已移动到「$cat」")
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            dao.delete(id)
            SnackbarController.show("已删除")
        }
    }

    /** 主页快捷方式（主页解析页下方的网格）已固定的收藏 */
    val homePinned: StateFlow<List<BookmarkEntity>> = dao.observeHomePinned()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 添加 / 移除主页快捷方式。
     *
     * 为什么不做上限：主页网格可横向滚动，且用户自己会控制数量；
     * 硬编码上限（如 8 个）反而会在"我有 9 个常用链接"时逼用户二选一。
     */
    fun setHomePinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            dao.updateHomePinned(id, pinned)
            SnackbarController.show(if (pinned) "已添加到主页" else "已从主页移除")
        }
    }

    /** 主页快捷方式色块的自定义文字（空串 = 自动取标题前几个字） */
    fun setHomeLabel(id: Long, label: String) {
        viewModelScope.launch {
            dao.updateHomeLabel(id, label.trim())
            SnackbarController.show(if (label.isBlank()) "已恢复自动文字" else "已更新显示文字")
        }
    }

    class Factory(private val dao: BookmarkDao) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BookmarkViewModel::class.java))
            return BookmarkViewModel(dao) as T
        }
    }
}