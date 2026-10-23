package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 主框架：底部导航 + 三个主页面（书库 / 在线搜书 / 设置）。
 *
 * 各页面自己不带 Scaffold 顶栏以外的底部留白，这里统一把内容放进带 bottomBar 的 Scaffold，
 * 避免底部导航遮挡列表最后一项。
 */
@Composable
fun MainScaffold(
    viewModel: ReaderViewModel,
    navigator: Navigator,
    initialTab: MainTab = MainTab.LIBRARY,
) {
    var tab by remember { mutableStateOf(initialTab) }

    Scaffold(
        bottomBar = { MainBottomBar(current = tab) { next -> tab = next } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                MainTab.LIBRARY -> LibraryScreen(
                    viewModel = viewModel,
                    navigator = navigator,
                    embedded = true,
                    onOpenSettings = { tab = MainTab.SETTINGS },
                )
                MainTab.ONLINE -> OnlineScreen(
                    viewModel = viewModel,
                    navigator = navigator,
                    embedded = true,
                )
                MainTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onBack = null,
                    embedded = true,
                )
            }
        }
    }
}
