package com.yunget.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunget.app.data.prefs.SettingsRepository

/**
 * 主题控制器（单例）：
 * - 内存中持有主题设置（mutableStateOf），修改后 Compose 自动重组、主题即时生效；
 * - 读写同时同步 SharedPreferences 持久化；
 * - 由 ComposeEmptyActivityTheme 初始化（幂等），确保任何入口（主界面/崩溃页）都能读到。
 */
object ThemeController {

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode by mutableStateOf(0)
        private set

    /** 主题色模式：0=动态色彩，1=默认蓝色，2=自定义种子色 */
    var colorMode by mutableStateOf(0)
        private set

    /** 自定义主题种子色（ARGB） */
    var seedColor by mutableStateOf(SettingsRepository.DEFAULT_SEED_COLOR)
        private set

    private var initialized = false

    /** 从持久化存储加载（幂等；首次调用有效） */
    fun init(context: Context) {
        if (initialized) return
        val s = SettingsRepository(context)
        darkMode = s.darkMode
        colorMode = s.themeColorMode
        seedColor = s.themeSeedColor
        initialized = true
    }

    /** 设置深色模式并持久化 */
    fun setDarkMode(context: Context, value: Int) {
        darkMode = value.coerceIn(0, 2)
        SettingsRepository(context).darkMode = darkMode
    }

    /** 设置主题色模式并持久化（0=动态 / 1=默认 / 2=自定义） */
    fun setColorMode(context: Context, value: Int) {
        colorMode = value.coerceIn(0, 2)
        SettingsRepository(context).themeColorMode = colorMode
    }

    /** 设置自定义种子色（自动切到自定义模式）并持久化 */
    fun setSeedColor(context: Context, argb: Long) {
        seedColor = argb
        colorMode = 2
        SettingsRepository(context).apply {
            themeSeedColor = argb
            themeColorMode = 2
        }
    }
}
