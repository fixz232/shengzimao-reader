package com.shengzimao.reader.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 品牌色取自桌面版图标：深蓝底 + 亮蓝描边；避免营销风大色块。 */
private val Indigo = Color(0xFF2F5BD3)
private val IndigoDeep = Color(0xFF22409A)
private val Amber = Color(0xFFB8862B)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFC),
    onPrimaryContainer = Color(0xFF14224F),
    secondary = Color(0xFF566172),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EBF0),
    onSecondaryContainer = Color(0xFF2A313B),
    tertiary = Amber,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF23262E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF23262E),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF566172),
    outline = Color(0xFFD3D9E2),
    outlineVariant = Color(0xFFE4E8EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA9F0),
    onPrimary = Color(0xFF111B36),
    primaryContainer = Color(0xFF293D77),
    onPrimaryContainer = Color(0xFFDDE5FB),
    secondary = Color(0xFFB3BDCC),
    onSecondary = Color(0xFF1D222A),
    secondaryContainer = Color(0xFF2C333D),
    onSecondaryContainer = Color(0xFFDDE2EA),
    tertiary = Color(0xFFD9B166),
    background = Color(0xFF14171C),
    onBackground = Color(0xFFE2E6ED),
    surface = Color(0xFF1B1F26),
    onSurface = Color(0xFFE2E6ED),
    surfaceVariant = Color(0xFF262C35),
    onSurfaceVariant = Color(0xFFB3BDCC),
    outline = Color(0xFF414A57),
    outlineVariant = Color(0xFF2F363F),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShengZiMaoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

val BrandIndigoDeep = IndigoDeep
