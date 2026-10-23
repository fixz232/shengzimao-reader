package com.shengzimao.reader.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shengzimao.reader.mobile.ui.AboutScreen
import com.shengzimao.reader.mobile.ui.MainScaffold
import com.shengzimao.reader.mobile.ui.MainTab
import com.shengzimao.reader.mobile.ui.ReaderScreen
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
            val navigator = rememberNavigator()
            val viewModel = rememberReaderViewModel()
            val state by viewModel.ui.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val view = LocalView.current

            val inReader = navigator.screen is Screen.Reading
            val dark = if (inReader) state.settings.theme.isDark else systemDark

            // 阅读页沉浸式（可按设置保留状态栏）；其余页面跟随系统深浅色。
            DisposableEffect(inReader, state.settings.fullScreen, dark) {
                val window = this@MainActivity.window
                val controller = WindowCompat.getInsetsController(window, view)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                if (inReader && state.settings.fullScreen) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
                onDispose { }
            }

            // 阅读时按设置保持亮屏。
            DisposableEffect(inReader, state.settings.keepScreenOn) {
                val window = this@MainActivity.window
                if (inReader && state.settings.keepScreenOn) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            BackHandler(enabled = !inReader) {
                if (!navigator.onBack()) finish()
            }

            ShengZiMaoTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        inReader -> {
                            val screen = navigator.screen as Screen.Reading
                            ReaderScreen(
                                container = app.container,
                                viewModel = viewModel,
                                bookId = screen.bookId,
                                navigator = navigator,
                            )
                        }
                        navigator.showAbout -> AboutScreen(viewModel, navigator)
                        else -> MainScaffold(
                            viewModel = viewModel,
                            navigator = navigator,
                            initialTab = when {
                                navigator.showSettings -> MainTab.SETTINGS
                                navigator.screen is Screen.Online -> MainTab.ONLINE
                                else -> MainTab.LIBRARY
                            },
                        )
                    }
                }
            }
        }
    }
}
