package com.yunget.app.ui.viewmodel

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

    /** 保存夸克 Cookie；返回是否保存成功 */
    suspend fun saveQuarkAccount(cookie: String): Boolean =
        repository.saveQuarkAccount(cookie)

    /** 退出登录：清除本地 Cookie */
    fun logout() {
        viewModelScope.launch { repository.logoutQuark() }
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
}