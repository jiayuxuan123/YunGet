package com.yunget.app.ui.screens

import android.webkit.CookieManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yunget.app.data.community.CommunityApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun rememberCommunityUnread(active: Boolean): Int {
    if (LocalInspectionMode.current) return 0
    var unread by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, active) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                unread = CommunityApi.unread(CookieManager.getInstance().getCookie(CommunityApi.BASE_URL))
                delay(60_000)
            }
        }
    }
    return unread
}
