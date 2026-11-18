package com.yunget.app.ui.navigation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 底部导航栏（竖屏）：4 个主 Tab（解析 / 网盘 / 下载 / 设置）。
 */
@Composable
internal fun SharedBottomBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    classic: Boolean = false,
    selectionAnimation: FiniteAnimationSpec<Float> = spring(),
) {
    Column(Modifier.fillMaxWidth()) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            MainTab.values().forEach { tab ->
                val iconScale by animateFloatAsState(
                    targetValue = if (currentTab == tab) 1.06f else 1f,
                    animationSpec = selectionAnimation, label = "bottomNavigationSelection",
                )
                NavigationBarItem(
                    selected = currentTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                        )
                    },
                    label = { Text(tab.title) },
                    colors = if (classic) NavigationBarItemDefaults.colors()
                    else NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * 侧边导航栏（横屏）：同 4 个主 Tab，未选中项只显示图标，节省横向空间。
 */
@Composable
internal fun SharedNavigationRail(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    classic: Boolean = false,
    selectionAnimation: FiniteAnimationSpec<Float> = spring(),
) {
    Row(modifier = Modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(),
        ) {
            MainTab.values().forEach { tab ->
                val iconScale by animateFloatAsState(
                    targetValue = if (currentTab == tab) 1.06f else 1f,
                    animationSpec = selectionAnimation, label = "railNavigationSelection",
                )
                NavigationRailItem(
                    selected = currentTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                        )
                    },
                    label = { Text(tab.title) },
                    alwaysShowLabel = true,
                    colors = if (classic) NavigationRailItemDefaults.colors()
                    else NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        if (!classic) {
            VerticalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            )
        }
    }
}
