package com.shengzimao.reader.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 圣子猫阅读 品牌设计系统。
 *
 * 主色沿用桌面版图标的「深蓝底 + 亮蓝描边」，辅色是暖琥珀，用于收藏、进度等
 * 需要温度的元素。整体走低饱和、克制的墨水风：界面退后，内容（书封、正文）靠前。
 */
private val Indigo = Color(0xFF3556C9)
private val IndigoDeep = Color(0xFF22409A)
private val IndigoContainer = Color(0xFFDEE6FB)
private val Amber = Color(0xFF875B11)
private val AmberDark = Color(0xFFD9B166)

// 中性色阶：浅色模式偏冷的纸灰，深色模式带蓝的石墨，避免纯灰的呆板。
private val InkLight = Color(0xFF1A1D26)
private val InkDark = Color(0xFFE4E8F0)

internal val BrandLightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFF101C45),
    secondary = Color(0xFF4F5B75),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E9F4),
    onSecondaryContainer = Color(0xFF1C2740),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E7C8),
    onTertiaryContainer = Color(0xFF3F2E04),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF5F6FA),
    onBackground = InkLight,
    surface = Color.White,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF5A6478),
    surfaceContainer = Color(0xFFF9FAFD),
    surfaceContainerLow = Color(0xFFF5F6FA),
    surfaceContainerHigh = Color(0xFFEFF2F8),
    outline = Color(0xFFC9D0DE),
    outlineVariant = Color(0xFFE2E7F0),
    scrim = Color(0xFF0B0E17),
)

internal val BrandDarkScheme = darkColorScheme(
    primary = Color(0xFFAEC2FF),
    onPrimary = Color(0xFF0E1B45),
    primaryContainer = Color(0xFF2C4288),
    onPrimaryContainer = Color(0xFFDCE4FD),
    secondary = Color(0xFFB9C4DB),
    onSecondary = Color(0xFF212B42),
    secondaryContainer = Color(0xFF33405C),
    onSecondaryContainer = Color(0xFFD8E0F2),
    tertiary = AmberDark,
    onTertiary = Color(0xFF3A2C00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFF8E2AE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101319),
    onBackground = InkDark,
    surface = Color(0xFF171B23),
    onSurface = InkDark,
    surfaceVariant = Color(0xFF222835),
    onSurfaceVariant = Color(0xFFAEB8CB),
    surfaceContainer = Color(0xFF1C212B),
    surfaceContainerLow = Color(0xFF141821),
    surfaceContainerHigh = Color(0xFF242A36),
    outline = Color(0xFF465063),
    outlineVariant = Color(0xFF2C3442),
    scrim = Color(0xFF000000),
)

/** 极简黑白风格的中性色（浅/深），去色只保留灰阶与一点点墨色。 */
internal val MonoLightScheme = lightColorScheme(
    primary = Color(0xFF2F2F33),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8EA),
    onPrimaryContainer = Color(0xFF1C1C1F),
    secondary = Color(0xFF5A5A60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEFF1),
    onSecondaryContainer = Color(0xFF232326),
    tertiary = Color(0xFF4A4A50),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E4E7),
    onTertiaryContainer = Color(0xFF232326),
    error = Color(0xFF8C1D18),
    onError = Color.White,
    errorContainer = Color(0xFFF2DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1C),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1C),
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF5C5C62),
    surfaceContainer = Color(0xFFF7F7F8),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainerHigh = Color(0xFFF0F0F2),
    outline = Color(0xFFC4C4C8),
    outlineVariant = Color(0xFFE0E0E3),
    scrim = Color(0xFF000000),
)

internal val MonoDarkScheme = darkColorScheme(
    primary = Color(0xFFD8D8DC),
    onPrimary = Color(0xFF232326),
    primaryContainer = Color(0xFF3A3A3F),
    onPrimaryContainer = Color(0xFFEAEAEC),
    secondary = Color(0xFFB9B9BE),
    onSecondary = Color(0xFF26262A),
    secondaryContainer = Color(0xFF34343A),
    onSecondaryContainer = Color(0xFFDEDEE1),
    tertiary = Color(0xFFB9B9BE),
    onTertiary = Color(0xFF26262A),
    tertiaryContainer = Color(0xFF34343A),
    onTertiaryContainer = Color(0xFFDEDEE1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101012),
    onBackground = Color(0xFFE6E6E8),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFE6E6E8),
    surfaceVariant = Color(0xFF232327),
    onSurfaceVariant = Color(0xFFB0B0B6),
    surfaceContainer = Color(0xFF1C1C20),
    surfaceContainerLow = Color(0xFF141417),
    surfaceContainerHigh = Color(0xFF242429),
    outline = Color(0xFF4A4A50),
    outlineVariant = Color(0xFF2E2E33),
    scrim = Color(0xFF000000),
)

/**
 * 液态玻璃风格色板。
 *
 * 与旧的两套不一样：这里 surface 全部带透明度，让底层的极光色团透上来；
 * 文字色则取高对比的冷白 / 深墨，保证玻璃上的可读性。
 */
internal val GlassLightScheme = lightColorScheme(
    primary = Color(0xFF4C63E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF0B1642),
    secondary = Color(0xFFE0609F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBDDEB),
    onSecondaryContainer = Color(0xFF44102C),
    tertiary = Color(0xFF16A39A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFF3EF),
    onTertiaryContainer = Color(0xFF06322F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF3F4FD),
    onBackground = Color(0xFF141A2E),
    // 注意：surface 必须实色。页面透明是各页面 Scaffold 自己的事，
    // 半透明的 surface 会让 ModalBottomSheet / AlertDialog 透出下面的正文。
    surface = Color(0xFFFDFDFF),
    onSurface = Color(0xFF141A2E),
    surfaceVariant = Color(0xFFEFF2F9),
    onSurfaceVariant = Color(0xFF5A6478),
    surfaceContainer = Color(0xFFF6F8FD),
    surfaceContainerLow = Color(0xFFFAFBFF),
    surfaceContainerHigh = Color(0xFFEDF0F8),
    surfaceContainerHighest = Color(0xFFE6EAF4),
    inverseSurface = Color(0xFF1B2233),
    inverseOnSurface = Color(0xFFEDF0FA),
    outline = Color(0xFF9AA3C4),
    outlineVariant = Color(0x33202A4A),
    scrim = Color(0xFF0B0E17),
)

internal val GlassDarkScheme = darkColorScheme(
    primary = Color(0xFF9FB4FF),
    onPrimary = Color(0xFF0B1642),
    primaryContainer = Color(0xFF35479C),
    onPrimaryContainer = Color(0xFFE2E8FF),
    secondary = Color(0xFFFF9BC8),
    onSecondary = Color(0xFF44102C),
    secondaryContainer = Color(0xFF6E2450),
    onSecondaryContainer = Color(0xFFFFD9EC),
    tertiary = Color(0xFF63E6DA),
    onTertiary = Color(0xFF06322F),
    tertiaryContainer = Color(0xFF0E5751),
    onTertiaryContainer = Color(0xFFCFF3EF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0D1A),
    onBackground = Color(0xFFF0F3FF),
    surface = Color(0xFF141A26),
    onSurface = Color(0xFFF0F3FF),
    surfaceVariant = Color(0xFF232C3D),
    onSurfaceVariant = Color(0xFFAEB8D6),
    surfaceContainer = Color(0xFF19212F),
    surfaceContainerLow = Color(0xFF161D2A),
    surfaceContainerHigh = Color(0xFF1E2736),
    surfaceContainerHighest = Color(0xFF232D3E),
    inverseSurface = Color(0xFFE9EDFA),
    inverseOnSurface = Color(0xFF1B2233),
    outline = Color(0xFF6C779C),
    outlineVariant = Color(0x33FFFFFF),
    scrim = Color(0xFF000000),
)

/** 全局圆角与间距常量：保留给尚未迁移到 token 的旧组件，新代码请用 LocalAppTokens。 */
object AppDimens {
    /** 页面左右留白。 */
    val PagePadding = 16.dp

    /** 卡片内边距。 */
    val CardPadding = 14.dp

    /** 卡片圆角。 */
    val CardRadius = 12.dp

    /** 封面圆角。 */
    val CoverRadius = 8.dp

    /** 列表项间距。 */
    val ItemSpacing = 8.dp

    /** 网格间距。 */
    val GridSpacing = 12.dp
}

@Composable
fun ShengZiMaoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: AppThemeStyle = AppThemeStyle.LIQUID_GLASS,
    glassMode: GlassMode = GlassMode.LIQUID,
    paletteId: GlassPaletteId = GlassPaletteId.AURORA,
    atmosphere: Atmosphere = Atmosphere.NONE,
    content: @Composable () -> Unit,
) {
    val tokens = tokensFor(style)
    // 氛围优先：选了氛围就由它接管整个色板（背景色 + 强调色 + 粒子），
    // 没选才回落到「配色方案」。这样两个设置不会互相打架。
    val aurora = remember(paletteId, darkTheme, atmosphere) {
        atmosphere.palette(darkTheme) ?: auroraPalette(paletteId, darkTheme)
    }
    val baseColors = when (style) {
        AppThemeStyle.MONO -> if (darkTheme) MonoDarkScheme else MonoLightScheme
        // 玻璃风格的强调色跟随所选配色，保证「选蜜桃就是蜜桃」而不是永远蓝紫。
        AppThemeStyle.LIQUID_GLASS -> glassScheme(aurora, darkTheme)
        // 墨水纸感与表达风格共用品牌色板；表达风格更依赖容器色与高度表达层次。
        else -> if (darkTheme) BrandDarkScheme else BrandLightScheme
    }
    val colors = remember(baseColors) { readableSurfaceScheme(baseColors) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // statusBarColor / navigationBarColor 在 targetSdk >= 35 后已被系统忽略，
            // 真正生效的只有图标明暗；颜色由背景铺到系统栏后面来体现。
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(
        LocalAppTokens provides tokens,
        LocalAurora provides aurora,
        LocalGlassDark provides darkTheme,
        LocalGlassMode provides glassMode,
        LocalAtmosphere provides atmosphere,
    ) {
        MaterialTheme(colorScheme = colors, typography = tokens.typography, shapes = appShapesFor(tokens), content = content)
    }
}

/**
 * 把当前配色的强调色注入 Material 色板，让 Switch / Slider / Chip 等控件同步换色。
 *
 * 关键点：`surface` 必须是**不透明**的。
 * 页面背景的透明是由各页面自己的 Scaffold(containerColor = Color.Transparent) 实现的，
 * 不需要、也不能靠篡改 Material 的 surface —— 因为 ModalBottomSheet / AlertDialog /
 * DropdownMenu 全都用 surface 当自己的底色。一旦 surface 半透明，
 * 目录、详情、设置面板就会透出底下的正文，两层字叠在一起。
 */
internal fun glassScheme(palette: AuroraPalette, dark: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (dark) GlassDarkScheme else GlassLightScheme
    val primary = readableAccent(palette.accent, base.surfaceContainerHighest)
    val secondary = readableAccent(palette.accentAlt, base.surfaceContainerHighest)
    val primaryContainer = primary.copy(alpha = if (dark) .30f else .12f).compositeOver(base.surface)
    val secondaryContainer = secondary.copy(alpha = if (dark) .30f else .12f).compositeOver(base.surface)
    return base.copy(
        primary = primary, onPrimary = onAccent(primary),
        secondary = secondary, onSecondary = onAccent(secondary),
        tertiary = secondary, onTertiary = onAccent(secondary),
        primaryContainer = primaryContainer, onPrimaryContainer = readableAccent(base.onSurface, primaryContainer),
        secondaryContainer = secondaryContainer, onSecondaryContainer = readableAccent(base.onSurface, secondaryContainer),
        tertiaryContainer = secondaryContainer, onTertiaryContainer = readableAccent(base.onSurface, secondaryContainer),
    )
}

val BrandIndigoDeep = IndigoDeep
