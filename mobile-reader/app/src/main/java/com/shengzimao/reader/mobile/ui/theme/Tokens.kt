package com.shengzimao.reader.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 界面外观风格。
 *
 * 与 [com.shengzimao.reader.mobile.data.ReaderTheme] 是两回事：
 * - 这里管的是「App 界面」的视觉语言（圆角、字阶、层级、动效）；
 * - ReaderTheme 管的是「阅读正文」的底色与字色。
 * 两者互相独立，可以任意组合（例如纯黑阅读底色 + 墨水纸感界面）。
 */
enum class AppThemeStyle(val label: String, val description: String) {
    LIQUID_GLASS("液态玻璃", "彩色极光、通透玻璃、悬浮导航"),
    INK_PAPER("墨水纸感", "安静、克制，界面退后，内容靠前"),
    EXPRESSIVE("Material 表达", "大圆角、分层容器、明显的动效"),
    MONO("极简黑白", "去色、去装饰，只在阅读上花心思"),
}

/** 旧版保存的风格名 → 新枚举，避免升级后用户设置失效。 */
fun appThemeStyleOf(name: String): AppThemeStyle = when (name) {
    AppThemeStyle.LIQUID_GLASS.name -> AppThemeStyle.LIQUID_GLASS
    AppThemeStyle.INK_PAPER.name -> AppThemeStyle.INK_PAPER
    AppThemeStyle.EXPRESSIVE.name -> AppThemeStyle.EXPRESSIVE
    AppThemeStyle.MONO.name -> AppThemeStyle.MONO
    // 无记录（全新安装）默认走新视觉。
    else -> AppThemeStyle.LIQUID_GLASS
}

/** 圆角体系。 */
@Immutable
data class AppRadii(
    val card: Dp,
    val cover: Dp,
    val chip: Dp,
    val button: Dp,
    val sheet: Dp,
)

/** 间距体系：全部组件只从这里取间距，保证节奏一致。 */
@Immutable
data class AppSpacing(
    /** 页面左右留白。 */
    val page: Dp,
    /** 卡片内边距。 */
    val card: Dp,
    /** 元素之间。 */
    val item: Dp,
    /** 网格间距。 */
    val grid: Dp,
    /** 区块之间。 */
    val section: Dp,
)

/** 层级体系：用高度还是用描边来表达层次，是各风格最大的差别之一。 */
@Immutable
data class AppElevation(
    val card: Dp,
    val bar: Dp,
    val sheet: Dp,
    /** 0 表示只用描边，不投影。 */
    val borderWidth: Dp,
)

/**
 * 设计 token 集合。
 *
 * 三套风格 = 三份这个数据类的常量；所有组件只读这里的值，不写死圆角/间距/字号。
 * 这样新增风格不需要动任何组件代码。
 */
@Immutable
data class AppTokens(
    val style: AppThemeStyle,
    val radii: AppRadii,
    val spacing: AppSpacing,
    val elevation: AppElevation,
    val typography: Typography,
    /** 图标线宽（极简风格用细线）。 */
    val iconStroke: Dp,
    /** 是否启用 Material You 动态取色（仅 API 31+，且只有表达风格建议开启）。 */
    val useDynamicColor: Boolean,
    /** 界面强调色，用于极简风格下关闭彩色时的克制着色。 */
    val accent: Color,
)

// ---------- 字阶 ----------

/** 墨水纸感：整体偏小偏紧，正文 16sp，标签 12sp。 */
private val InkPaperType = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, lineHeight = 15.sp),
)

/** Material 表达：字阶更大，标题更重，行高更松。 */
private val ExpressiveType = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
)

/** 液态玻璃：标题更重更清晰，标签留一点字距，配合玻璃上的亮字。 */
private val GlassType = Typography(
    headlineSmall = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, lineHeight = 14.sp),
)

/** 极简黑白：字阶收敛，标签更小更中性。 */
private val MonoType = Typography(
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp),
)

// ---------- 风格常量 ----------

val GlassTokens = AppTokens(
    style = AppThemeStyle.LIQUID_GLASS,
    radii = AppRadii(card = 16.dp, cover = 10.dp, chip = 999.dp, button = 12.dp, sheet = 24.dp),
    spacing = AppSpacing(page = 16.dp, card = 16.dp, item = 10.dp, grid = 14.dp, section = 22.dp),
    elevation = AppElevation(card = 12.dp, bar = 20.dp, sheet = 28.dp, borderWidth = 1.dp),
    typography = GlassType,
    iconStroke = 1.8.dp,
    useDynamicColor = false,
    accent = Color(0xFF4C63E6),
)

val InkPaperTokens = AppTokens(
    style = AppThemeStyle.INK_PAPER,
    radii = AppRadii(card = 12.dp, cover = 8.dp, chip = 999.dp, button = 10.dp, sheet = 20.dp),
    spacing = AppSpacing(page = 16.dp, card = 14.dp, item = 8.dp, grid = 12.dp, section = 20.dp),
    elevation = AppElevation(card = 0.dp, bar = 1.dp, sheet = 8.dp, borderWidth = 1.dp),
    typography = InkPaperType,
    iconStroke = 1.5.dp,
    useDynamicColor = false,
    accent = Color(0xFF3556C9),
)

val ExpressiveTokens = AppTokens(
    style = AppThemeStyle.EXPRESSIVE,
    radii = AppRadii(card = 16.dp, cover = 12.dp, chip = 999.dp, button = 999.dp, sheet = 28.dp),
    spacing = AppSpacing(page = 16.dp, card = 16.dp, item = 10.dp, grid = 14.dp, section = 24.dp),
    elevation = AppElevation(card = 1.dp, bar = 3.dp, sheet = 12.dp, borderWidth = 0.dp),
    typography = ExpressiveType,
    iconStroke = 2.dp,
    useDynamicColor = true,
    accent = Color(0xFF3556C9),
)

val MonoTokens = AppTokens(
    style = AppThemeStyle.MONO,
    radii = AppRadii(card = 2.dp, cover = 2.dp, chip = 4.dp, button = 2.dp, sheet = 8.dp),
    spacing = AppSpacing(page = 16.dp, card = 12.dp, item = 8.dp, grid = 10.dp, section = 20.dp),
    elevation = AppElevation(card = 0.dp, bar = 0.dp, sheet = 4.dp, borderWidth = 1.dp),
    typography = MonoType,
    iconStroke = 1.dp,
    useDynamicColor = false,
    accent = Color(0xFF3A3A3A),
)

fun tokensFor(style: AppThemeStyle): AppTokens = when (style) {
    AppThemeStyle.LIQUID_GLASS -> GlassTokens
    AppThemeStyle.INK_PAPER -> InkPaperTokens
    AppThemeStyle.EXPRESSIVE -> ExpressiveTokens
    AppThemeStyle.MONO -> MonoTokens
}

/** 当前风格的设计 token，由 [com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme] 提供。 */
val LocalAppTokens = staticCompositionLocalOf { GlassTokens }

/** Material controls and custom surfaces follow the same saved theme. */
internal fun appShapesFor(tokens: AppTokens) = Shapes(
    extraSmall = RoundedCornerShape(tokens.radii.cover),
    small = RoundedCornerShape(tokens.radii.button.coerceAtMost(16.dp)),
    medium = RoundedCornerShape(tokens.radii.card),
    large = RoundedCornerShape(tokens.radii.card),
    extraLarge = RoundedCornerShape(tokens.radii.sheet),
)
