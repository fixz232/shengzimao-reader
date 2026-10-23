package com.shengzimao.reader.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 单 Activity 的极简页面栈：书库 -> 阅读；设置、关于为覆盖层。 */
sealed interface Screen {
    data object Library : Screen
    data class Reading(val bookId: String) : Screen
    data object Online : Screen
}

@Composable
fun rememberNavigator(): Navigator {
    val navigator = remember { Navigator() }
    return navigator
}

class Navigator {
    var screen by mutableStateOf<Screen>(Screen.Library)
        internal set
    var showSettings by mutableStateOf(false)
        internal set
    var showAbout by mutableStateOf(false)
        internal set

    fun openReader(bookId: String) {
        screen = Screen.Reading(bookId)
    }

    fun openLibrary() {
        screen = Screen.Library
    }

    fun openOnline() {
        screen = Screen.Online
    }

    fun openSettings() {
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
    }

    fun openAbout() {
        showAbout = true
    }

    fun closeAbout() {
        showAbout = false
    }

    /** 返回键：返回 true 表示已消费。 */
    fun onBack(): Boolean {
        if (showSettings) {
            showSettings = false
            return true
        }
        if (showAbout) {
            showAbout = false
            return true
        }
        if (screen is Screen.Reading) {
            screen = Screen.Library
            return true
        }
        if (screen is Screen.Online) {
            screen = Screen.Library
            return true
        }
        return false
    }
}
