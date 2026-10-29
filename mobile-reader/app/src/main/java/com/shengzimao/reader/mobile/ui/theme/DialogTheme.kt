package com.shengzimao.reader.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 弹层（底部抽屉 / 对话框 / 目录 / 详情）的独立配色。
 *
 * 为什么需要它：
 * 玻璃风格把 Material 的 `surface` 设成了半透明（0xCCFFFFFF / 0x801B2236），
 * 这样页面能透出背景的极光。但 Material 的 ModalBottomSheet / AlertDialog 用的是
 * **同一套 surface 与 scrim**，于是弹层也变成半透明 ——
 * 结果就是「目录浮在正文上、两层字叠在一起」。
 *
 * 修法不是把页面改回不透明（那会破坏玻璃），而是给弹层单独一套色板：
 * - surface 用**接近不透明**的底色，保证弹层内容永远清晰；
 * - scrim 加深，让「弹层是浮在上面的一层」这件事有明确的视觉分离。
 *
 * 用法见 [DialogTheme]。
 */
@Composable
fun dialogColorScheme(): ColorScheme = dialogColorSchemeFor(MaterialTheme.colorScheme,
    LocalAppTokens.current.style, LocalGlassDark.current, LocalAurora.current)

internal fun dialogColorSchemeFor(base: ColorScheme, style: AppThemeStyle, dark: Boolean, palette: AuroraPalette): ColorScheme {
    if (style != AppThemeStyle.LIQUID_GLASS) return readableSurfaceScheme(base)
    val surface = if (dark) Color(0xFF141A26) else Color(0xFFFDFDFF)
    val primary = readableAccent(palette.accent, surface)
    val secondary = readableAccent(palette.accentAlt, surface)
    val scheme = if (dark) {
        base.copy(
            // 深色：比背景略亮的实底，保留一点点通透但不透字。
            surface = Color(0xFF141A26),
            surfaceContainerLowest = Color(0xFF0F141F),
            surfaceContainerLow = Color(0xFF161D2A),
            surfaceContainer = Color(0xFF19212F),
            surfaceContainerHigh = Color(0xFF1E2736),
            surfaceContainerHighest = Color(0xFF232D3E),
            onSurface = Color(0xFFF0F3FF),
            onSurfaceVariant = Color(0xFFAEB8D6),
            // 弹层上的强调色跟随氛围。
            primary = primary, onPrimary = onAccent(primary),
            secondary = secondary, onSecondary = onAccent(secondary),
            outline = Color(0xFF5A6785),
            outlineVariant = Color(0x33FFFFFF),
            scrim = Color(0xCC000000),
        )
    } else {
        base.copy(
            surface = Color(0xFFFDFDFF),
            surfaceContainerLowest = Color(0xFFF7F8FC),
            surfaceContainerLow = Color(0xFFFAFBFF),
            surfaceContainer = Color(0xFFF2F4FA),
            surfaceContainerHigh = Color(0xFFEDF0F8),
            surfaceContainerHighest = Color(0xFFE6EAF4),
            onSurface = Color(0xFF141A2E),
            onSurfaceVariant = Color(0xFF5A6478),
            primary = primary, onPrimary = onAccent(primary),
            secondary = secondary, onSecondary = onAccent(secondary),
            outline = Color(0xFF9AA3C4),
            outlineVariant = Color(0xFFDFE4EF),
            scrim = Color(0x99101728),
        )
    }
    return readableSurfaceScheme(scheme)
}

/**
 * 给弹层包一层「不透明」主题。
 *
 * 只替换 colorScheme，不动 typography / shapes，所以弹层的外观与页面保持一致，
 * 区分只在「有没有实底」这一点上。
 */
@Composable
fun DialogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dialogColorScheme(), content = content)
}

/** 弹层容器色，方便直接给自定义弹层背景取色。 */
val LocalDialogSurface = staticCompositionLocalOf { Color(0xFFFDFDFF) }
