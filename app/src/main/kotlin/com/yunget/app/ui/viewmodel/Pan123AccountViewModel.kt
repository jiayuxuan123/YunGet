package com.yunget.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunget.app.data.db.Pan123AccountEntity
import com.yunget.app.data.repository.Pan123AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 123 云盘账号 ViewModel：账号+密码登录 → JWT 落库，暴露登录态供主页/登录页/解析页共享。
 */
class Pan123AccountViewModel(
    private val repository: Pan123AccountRepository
) : ViewModel() {

    val pan123Account: StateFlow<Pan123AccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 登录错误信息（登录页 Snackbar 提示） */
    var loginError by mutableStateOf<String?>(null)
        private set

    /** 登录中（按钮 loading） */
    var isLoggingIn by mutableStateOf(false)
        private set

    fun consumeLoginError() {
        loginError = null
    }

    /** 账号密码登录；成功返回 true */
    fun login(account: String, password: String) {
        viewModelScope.launch {
            loginError = null
            isLoggingIn = true
            try {
                val ok = repository.login(account, password)
                if (!ok) loginError = "登录失败，请检查账号密码"
            } catch (e: Exception) {
                loginError = e.message ?: "登录失败，请检查账号密码"
            } finally {
                isLoggingIn = false
            }
        }
    }

    /**
     * 保存网页登录得到的 token（从 localStorage 的 `authorToken` 提取）。
     *
     * 与账号密码登录并列的**主路径**：用户不再向 App 提供密码，
     * 从而避免官方把「频繁调用 sign_in 接口」判定为异常客户端而风控。
     *
     * @return 校验并落库成功返回 true
     */
    suspend fun saveToken(token: String): Boolean = repository.saveToken(token)

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: Pan123AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(Pan123AccountViewModel::class.java))
            return Pan123AccountViewModel(repository) as T
        }
    }
}