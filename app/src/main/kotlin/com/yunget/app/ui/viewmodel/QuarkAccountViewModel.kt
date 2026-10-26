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

package com.yunget.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunget.app.data.db.QuarkAccountEntity
import com.yunget.app.data.repository.QuarkAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 夸克账号 ViewModel：暴露登录态，供主页与登录页共享。
 */
class QuarkAccountViewModel(
    private val repository: QuarkAccountRepository
) : ViewModel() {

    val quarkAccount: StateFlow<QuarkAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /**
     * 本次登录是"覆盖当前账号"还是"新增一个账号"。
     *
     * 默认覆盖：日常重登（Cookie 过期、换密码）远比"再加一个号"常见，
     * 默认新增会让用户每次重登都多出一行垃圾账号。
     *
     * 登录页在保存前显示这个选择器；登录成功后重置为 false，
     * 避免用户上一次选了"新增"、这次重登又莫名其妙多一个号。
     */
    var addAsNewAccount by mutableStateOf(false)
        private set

    /** 由登录页的"新增账号"开关调用（不能叫 setAddAsNewAccount：会与属性 setter 撞签名）。 */
    fun changeLoginMode(addNew: Boolean) {
        addAsNewAccount = addNew
    }

    /** 保存夸克 Cookie；返回是否保存成功 */
    suspend fun saveQuarkAccount(cookie: String): Boolean {
        val ok = repository.saveQuarkAccount(cookie, addNew = addAsNewAccount)
        // 保存成功后复位：下一次登录默认回到"覆盖"
        if (ok) addAsNewAccount = false
        return ok
    }

    /** 退出登录：清除本地 Cookie */
    fun logout() {
        viewModelScope.launch { repository.logoutQuark() }
    }


    /** 修改本平台账号的备注（空串 = 清除）。 */
    fun updateRemark(id: String, remark: String) {
        viewModelScope.launch { repository.updateRemark(id, remark) }
    }

    class Factory(
        private val repository: QuarkAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(QuarkAccountViewModel::class.java))
            return QuarkAccountViewModel(repository) as T
        }
    }

    /** 全部账号（多账号列表）。主账号排最前。 */
    val allAccounts: StateFlow<List<QuarkAccountEntity>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * 删除指定账号。
     *
     * 与 [logout] 的区别：那个是"退出当前登录"（会清 Cookie、重置刷新计时），
     * 这个是"从多账号列表里移除一个"。删除主账号后必须把剩下的某个升为主账号，
     * 否则所有"需要单一身份"的操作都会拿不到账号。
     */
    fun removeAccount(id: String) {
        viewModelScope.launch {
            repository.removeAccount(id)
        }
    }

    /** 切换主账号。 */
    fun setPrimary(id: String) {
        viewModelScope.launch { repository.setPrimary(id) }
    }

}