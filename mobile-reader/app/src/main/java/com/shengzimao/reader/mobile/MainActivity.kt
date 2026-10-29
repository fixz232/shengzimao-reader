package com.shengzimao.reader.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shengzimao.reader.mobile.ui.AboutScreen
import com.shengzimao.reader.mobile.ui.MainScaffold
import com.shengzimao.reader.mobile.ui.ReaderScreen
import com.shengzimao.reader.mobile.ui.MangaReaderScreen
import com.shengzimao.reader.mobile.ui.Screen
import com.shengzimao.reader.mobile.ui.rememberNavigator
import com.shengzimao.reader.mobile.ui.rememberReaderViewModel
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as ReaderApp
            val mainState = rememberSaveableStateHolder()
            val navigator = rememberNavigator()
            val viewModel = rememberReaderViewModel()
            val state by viewModel.ui.collectAsState()
            val mangaSettings by viewModel.mangaSettings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val view = LocalView.current

            val inNovelReader = navigator.screen is Screen.Reading && navigator.toolPage == null && !navigator.showAbout
            val inComicReader = navigator.screen is Screen.ComicReading && navigator.toolPage == null && !navigator.showAbout
            val inReader = inNovelReader || inComicReader
            // 外观深浅：AUTO 跟随系统，LIGHT 强制白色，DARK 强制深色。
            // 阅读页仍按正文底色决定系统栏图标，避免白底书页配白图标。
            val appearanceDark = when (state.settings.appearance) {
                "LIGHT" -> false
                "DARK" -> true
                else -> systemDark
            }
            val dark = when {
                state.settings.nightMode -> true
                inComicReader -> mangaSettings.isDark
                inNovelReader -> state.settings.isDarkPage
                else -> appearanceDark
            }

            // 阅读页沉浸式（可按设置保留状态栏）；其余页面跟随深浅色。
            // 漫画页的沉浸/亮度由 MangaReaderScreen 内部的 ReaderWindowEffects 接管。
            DisposableEffect(inNovelReader, state.settings.fullScreen, dark) {
                val window = this@MainActivity.window
                val controller = WindowCompat.getInsetsController(window, view)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                if (inNovelReader && state.settings.fullScreen) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else if (!inComicReader) {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
                onDispose { }
            }

            // 小说阅读时保持亮屏（漫画在阅读页内按漫画设置处理）。
            DisposableEffect(inNovelReader, state.settings.keepScreenOn) {
                val window = this@MainActivity.window
                if (inNovelReader && state.settings.keepScreenOn) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else if (!inComicReader) {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            BackHandler(enabled = !inReader) {
                if (!navigator.onBack()) finish()
            }

            val appStyle = remember(state.settings.appThemeStyle) {
                com.shengzimao.reader.mobile.ui.theme.appThemeStyleOf(state.settings.appThemeStyle)
            }
            val glassMode = remember(state.settings.glassMode) {
                runCatching { com.shengzimao.reader.mobile.ui.theme.GlassMode.valueOf(state.settings.glassMode) }
                    .getOrDefault(com.shengzimao.reader.mobile.ui.theme.GlassMode.LIQUID)
            }
            val paletteId = remember(state.settings.glassPalette) {
                runCatching { com.shengzimao.reader.mobile.ui.theme.GlassPaletteId.valueOf(state.settings.glassPalette) }
                    .getOrDefault(com.shengzimao.reader.mobile.ui.theme.GlassPaletteId.AURORA)
            }
            val atmosphere = remember(state.settings.atmosphere) {
                com.shengzimao.reader.mobile.ui.theme.Atmosphere.of(state.settings.atmosphere)
            }

            ShengZiMaoTheme(
                darkTheme = dark,
                style = appStyle,
                glassMode = glassMode,
                paletteId = paletteId,
                atmosphere = atmosphere,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        navigator.toolPage != null && !navigator.showAbout -> mainState.SaveableStateProvider("tool-${navigator.toolPage!!.name}") {
                            com.shengzimao.reader.mobile.ui.ToolPageScreen(viewModel, navigator, navigator.toolPage!!)
                        }
                        navigator.showAbout -> AboutScreen(viewModel, navigator)
                        navigator.screen is Screen.Reading -> {
                            val screen = navigator.screen as Screen.Reading
                            if (state.books.any { it.id == screen.bookId && it.onlineOnly }) {
                                com.shengzimao.reader.mobile.ui.OnlineReaderScreen(viewModel, navigator, screen.bookId)
                            } else ReaderScreen(
                                container = app.container,
                                viewModel = viewModel,
                                bookId = screen.bookId,
                                navigator = navigator,
                            )
                        }
                        navigator.screen is Screen.ComicReading -> {
                            val screen = navigator.screen as Screen.ComicReading
                            MangaReaderScreen(viewModel, navigator, screen.bookId, screen.chapter)
                        }
                        else -> mainState.SaveableStateProvider("main") {
                            MainScaffold(viewModel = viewModel, navigator = navigator)
                        }
                    }
                }
            }
        }
    }
}
