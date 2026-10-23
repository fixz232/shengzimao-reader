package com.shengzimao.reader.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 底部导航的三个主入口。 */
enum class MainTab(val label: String, val icon: ImageVector) {
    LIBRARY("书库", Icons.AutoMirrored.Filled.MenuBook),
    ONLINE("在线搜书", Icons.Filled.Public),
    SETTINGS("设置", Icons.Filled.Settings),
}

/**
 * 底部导航栏。阅读页不显示它——阅读时需要整屏沉浸，退出阅读再回到原 tab。
 */
@Composable
fun MainBottomBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}
