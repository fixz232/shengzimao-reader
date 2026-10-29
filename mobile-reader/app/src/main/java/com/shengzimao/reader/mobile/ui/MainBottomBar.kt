package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.ui.theme.*

enum class MainTab(val label: String, val icon: ImageVector) {
    LIBRARY("书架", Icons.AutoMirrored.Filled.MenuBook),
    DISCOVER("发现", Icons.Default.Explore),
    MINE("我的", Icons.Default.Tune);
    @Deprecated("使用 DISCOVER", ReplaceWith("DISCOVER"))
    val isDiscover: Boolean get() = this == DISCOVER
}

enum class LibrarySection(val label: String) { NOVEL("小说"), COMIC("漫画") }

@Composable
fun MainBottomBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    val glass = LocalAppTokens.current.style == AppThemeStyle.LIQUID_GLASS
    val items: @Composable () -> Unit = {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)) {
            MainTab.entries.forEach { tab ->
                NavigationBarItem(selected = current == tab, onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, null) }, label = { Text(tab.label) })
            }
        }
    }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = if (glass) 12.dp else 0.dp, vertical = if (glass) 4.dp else 0.dp)) {
        if (glass) GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            cornerRadius = 16.dp, spec = GlassDefaults.bar()) { items() }
        else Surface(color = MaterialTheme.colorScheme.surfaceContainer) { items() }
    }
}
