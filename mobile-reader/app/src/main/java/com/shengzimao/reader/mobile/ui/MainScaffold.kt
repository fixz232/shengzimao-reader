package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.ui.theme.AppThemeStyle
import com.shengzimao.reader.mobile.ui.theme.LocalAppTokens
import com.shengzimao.reader.mobile.ui.theme.LiquidGlassBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(viewModel: ReaderViewModel, navigator: Navigator) {
    val savedPages = rememberSaveableStateHolder()
    val tool = navigator.toolPage
    if (tool != null) {
        savedPages.SaveableStateProvider("tool-${tool.name}") {
            ToolPageScreen(viewModel, navigator, tool)
        }
        return
    }
    val tasks by viewModel.downloads.tasks.collectAsStateWithLifecycle()
    val manga by viewModel.manga.state.collectAsStateWithLifecycle()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val discoverScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    LaunchedEffect(navigator.discoverManga, navigator.discoverTomato) {
        discoverScroll.state.heightOffset = 0f
        discoverScroll.state.contentOffset = 0f
    }
    val content: @Composable BoxScope.() -> Unit = {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { if (!keyboardVisible) MainBottomBar(navigator.selectedTab, navigator::selectTab) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).imePadding()) {
                savedPages.SaveableStateProvider(navigator.selectedTab.name) {
                    when (navigator.selectedTab) {
                        MainTab.LIBRARY -> BookshelfScreen(viewModel, navigator, navigator.librarySection,
                            { navigator.librarySection = it }, navigator::openSettings)
                        MainTab.DISCOVER -> {
                            if (manga.browseSourceId.isNotBlank()) MangaSourceBrowserScreen(viewModel, navigator)
                            else Column(Modifier.fillMaxSize().nestedScroll(discoverScroll.nestedScrollConnection)) {
                                TopAppBar(title = { Text("发现", style = MaterialTheme.typography.titleLarge) },
                                    scrollBehavior = discoverScroll,
                                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                                        scrolledContainerColor = MaterialTheme.colorScheme.background),
                                    actions = {
                                    BadgedBox(badge = {
                                        val active = tasks.count { it.active }
                                        if (active > 0) Badge { Text(active.toString()) }
                                    }) {
                                        IconButton(onClick = { navigator.openTool(ToolPage.DOWNLOADS) }) { Icon(Icons.Default.Download, "下载管理") }
                                    }
                                })
                                DiscoveryTabs(when {
                                    navigator.discoverManga -> 2
                                    navigator.discoverTomato -> 1
                                    else -> 0
                                }) {
                                    when (it) {
                                        1 -> navigator.openTomato()
                                        2 -> navigator.openMangaOnline()
                                        else -> navigator.openDiscover()
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    savedPages.SaveableStateProvider(if (navigator.discoverManga) "manga-search" else "novel-search") {
                                        if (navigator.discoverManga) MangaOnlineScreen(viewModel, navigator)
                                        else OnlineScreen(viewModel, navigator, tomatoPage = navigator.discoverTomato)
                                    }
                                }
                            }
                        }
                        MainTab.MINE -> MineScreen(viewModel, navigator)
                    }
                }
            }
        }
    }
    if (LocalAppTokens.current.style == AppThemeStyle.LIQUID_GLASS) LiquidGlassBackground(content = content)
    else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = content)
}
