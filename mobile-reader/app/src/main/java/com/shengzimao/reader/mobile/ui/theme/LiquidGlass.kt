package com.shengzimao.reader.mobile.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃视觉系统。
 *
 * 三件事分开，互相组合，避免「改一个样式要动一堆组件」：
 * 1. [GlassMode]：玻璃**质感**——液态 / 拟态 / 磨砂；
 * 2. [GlassPaletteId]：配色**方案**——极光 / 蜜桃 / 薄荷 / 暮霞 / 星夜；
 * 3. 深浅：浅色（白底）或深色，由设置里的 appearance 决定。
 *
 * 组件只读 [GlassDefaults]，不写死 alpha。想调整观感只改本文件的常量。
 */

/* ------------------------------------------------------------------ *
 * 质感
 * ------------------------------------------------------------------ */

/**
 * 玻璃质感。
 *
 * 三者的差别不只是透明度，而是「光怎么走」：
 * - [LIQUID] 液态玻璃：通透、彩色、左上柔光强、亮描边明显，像覆盖在彩色水面上的玻璃；
 * - [NEUMORPHIC] 拟态玻璃：同色系凹凸，靠「左上亮 + 右下暗」的双向高光塑造厚度，
 *   几乎不用描边，整体柔和、低对比，像压出来的塑料面板；
 * - [FROSTED] 磨砂玻璃：白度高、对比低，高光很弱、描边几乎不可见，
 *   像一块真正的磨砂玻璃片，最适合长时间阅读的界面。
 *
 * 三种质感都可以搭配任意一种配色（含「透明」配色），互相独立。
 */
enum class GlassMode(val label: String, val description: String) {
    LIQUID("液态玻璃", "通透彩色、亮边高光，最有玻璃感"),
    NEUMORPHIC("拟态玻璃", "同色凹凸、柔和厚度，安静不抢眼"),
    FROSTED("磨砂玻璃", "低对比半透明白，久看不累"),
}

/* ------------------------------------------------------------------ *
 * 配色
 * ------------------------------------------------------------------ */

/** 配色方案标识。深色版本沿用同一套色相，只降低明度、提高暗底权重。 */
enum class GlassPaletteId(val label: String, val hint: String) {
    AURORA("极光", "蓝紫 + 樱粉"),
    PEACH("蜜桃", "珊瑚 + 蜜金"),
    MINT("薄荷", "青绿 + 湖蓝"),
    DUSK("暮霞", "紫罗兰 + 晚橙"),
    MIDNIGHT("星夜", "靛蓝 + 霓虹青"),
    /** 透明配色：几乎不着色，玻璃只剩一层薄边，背景保持干净的纸白 / 墨黑。 */
    CLEAR("透明", "无色玻璃，最干净"),
}

/** 一套极光/晕染色板。 */
@Immutable
data class AuroraPalette(
    /** 页面底色（色团画在它上面）。 */
    val base: Color,
    /** 色团颜色，按顺序在画布上以径向渐变铺开。 */
    val blooms: List<Color>,
    /** 覆盖在色团上的中性磨砂层，用来压住饱和度、保证文字可读。 */
    val veil: Color,
    /** 玻璃卡片底色。 */
    val glass: Color,
    /** 玻璃描边（亮边）。 */
    val glassBorder: Color,
    /** 玻璃左上高光。 */
    val glassHighlight: Color,
    /** 拟态风格的右下暗部。 */
    val glassShade: Color,
    /** 玻璃投影。 */
    val glassShadow: Color,
    /** 强调色，用于选中态与关键数字。 */
    val accent: Color,
    val accentAlt: Color,
    val isDark: Boolean,
)

/**
 * 生成一套浅色板。
 *
 * 这里是「像不像参考图」的关键。参考图里的背景是一层**平滑、低饱和、几乎看不出边界**的
 * 渐层，玻璃本身**几乎没有颜色**——玻璃的价值在于「透出背后的东西 + 一条细亮边」，
 * 而不是自己带一身颜色。
 *
 * 所以：色团改成跨整屏的渐变停靠点（blooms 现在表示渐变节点，而不是几个实心圆），
 * 颜色全部压到最高 8% 左右的对比度；玻璃底色降到 8% 白，只留边缘高光。
 */
private fun lightPalette(id: GlassPaletteId): AuroraPalette {
    val gradient: List<Color>
    val accent: Color
    val accentAlt: Color
    when (id) {
        // 极光：冷蓝灰过渡到极淡的紫，参考图 1 那种「灰白工作室」感。
        GlassPaletteId.AURORA -> {
            gradient = listOf(Color(0xFFEDF1F9), Color(0xFFE6EBF6), Color(0xFFF0EDF8), Color(0xFFEAF2F7))
            accent = Color(0xFF2F6BFF); accentAlt = Color(0xFF12B5D4)
        }
        // 蜜桃：暖白到杏色，像傍晚日光灯下的纸。
        GlassPaletteId.PEACH -> {
            gradient = listOf(Color(0xFFFCF0E6), Color(0xFFF9E8E6), Color(0xFFFDF1E2), Color(0xFFF8EEF0))
            accent = Color(0xFFE2603C); accentAlt = Color(0xFFE8A03A)
        }
        // 薄荷：清透的冷绿灰，最接近「临床」的干净感。
        GlassPaletteId.MINT -> {
            gradient = listOf(Color(0xFFE8F4EF), Color(0xFFE7F0F5), Color(0xFFE3F3E9), Color(0xFFECF4F2))
            accent = Color(0xFF0E9F8A); accentAlt = Color(0xFF2E86C9)
        }
        // 暮霞：淡紫与藕粉，饱和度仍然很低。
        GlassPaletteId.DUSK -> {
            gradient = listOf(Color(0xFFF0EAF8), Color(0xFFF8EAF0), Color(0xFFFBEFE6), Color(0xFFF2EDF8))
            accent = Color(0xFF7B5BD6); accentAlt = Color(0xFFD96A3C)
        }
        // 星夜（浅色版）：中性冷灰蓝，几乎无色。
        GlassPaletteId.MIDNIGHT -> {
            gradient = listOf(Color(0xFFE9EEF8), Color(0xFFE4EEF6), Color(0xFFEBEAF8), Color(0xFFE7F0F6))
            accent = Color(0xFF3358C9); accentAlt = Color(0xFF0E9BB5)
        }
        // 透明：真正的无色。参考图 1 就是这种——背景就是浅灰，玻璃只是个形状。
        GlassPaletteId.CLEAR -> {
            gradient = listOf(Color(0xFFF4F6FA), Color(0xFFEFF2F7), Color(0xFFF6F7FB), Color(0xFFF1F4F8))
            accent = Color(0xFF4A5670); accentAlt = Color(0xFF78849C)
        }
    }
    return AuroraPalette(
        base = gradient.first(),
        blooms = gradient,
        // veil 几乎不加：加了就会变成「蒙一层白纱」，参考图没有那层纱。
        veil = Color(0x0AFFFFFF),
        glass = Color(0x14FFFFFF),
        glassBorder = Color(0x52FFFFFF),
        glassHighlight = Color(0x8CFFFFFF),
        glassShade = Color(0x0A0F1428),
        glassShadow = Color(0x14202B45),
        accent = accent,
        accentAlt = accentAlt,
        isDark = false,
    )
}

/**
 * 生成一套深色板。
 *
 * 对齐参考图 2：深青绿的**平滑流动**背景 + 深色半透明玻璃卡。
 * 关键是背景要有一个主色调的明暗起伏（不是霓虹杂色），玻璃卡比背景略亮一点点、
 * 边缘有一道冷色高光，正文纯白 —— 这样才有「高级感」而不是「RGB 灯效」。
 */
private fun darkPalette(id: GlassPaletteId): AuroraPalette {
    val gradient: List<Color>
    val accent: Color
    val accentAlt: Color
    when (id) {
        // 极光：深海青灰，参考图 2 的主色。
        GlassPaletteId.AURORA -> {
            gradient = listOf(Color(0xFF0B1520), Color(0xFF11202C), Color(0xFF16303A), Color(0xFF0D1A26))
            accent = Color(0xFF6FD3E8); accentAlt = Color(0xFF5B8CFF)
        }
        GlassPaletteId.PEACH -> {
            gradient = listOf(Color(0xFF1A0F0C), Color(0xFF241512), Color(0xFF2E1B14), Color(0xFF1C100D))
            accent = Color(0xFFFFA184); accentAlt = Color(0xFFFFC978)
        }
        GlassPaletteId.MINT -> {
            gradient = listOf(Color(0xFF071614), Color(0xFF0C2220), Color(0xFF10302C), Color(0xFF081A18))
            accent = Color(0xFF5FE3C0); accentAlt = Color(0xFF6BC8FF)
        }
        GlassPaletteId.DUSK -> {
            gradient = listOf(Color(0xFF150F22), Color(0xFF1E1430), Color(0xFF2A1B3A), Color(0xFF171024))
            accent = Color(0xFFC0A6FF); accentAlt = Color(0xFFFFA07A)
        }
        GlassPaletteId.MIDNIGHT -> {
            gradient = listOf(Color(0xFF0A101E), Color(0xFF0E1829), Color(0xFF122438), Color(0xFF0B1424))
            accent = Color(0xFF8FB0FF); accentAlt = Color(0xFF6FE0E8)
        }
        // 透明（深色）：中性石墨，不带彩。
        GlassPaletteId.CLEAR -> {
            gradient = listOf(Color(0xFF0E1116), Color(0xFF14181F), Color(0xFF1A1F27), Color(0xFF101419))
            accent = Color(0xFFD5DAE4); accentAlt = Color(0xFF9AA4B4)
        }
    }
    return AuroraPalette(
        base = gradient.first(),
        blooms = gradient,
        veil = Color(0x00000000),
        glass = Color(0x1FFFFFFF),
        glassBorder = Color(0x2EFFFFFF),
        glassHighlight = Color(0x33FFFFFF),
        glassShade = Color(0x1A000000),
        glassShadow = Color(0x66000000),
        accent = accent,
        accentAlt = accentAlt,
        isDark = true,
    )
}

fun auroraPalette(id: GlassPaletteId, dark: Boolean): AuroraPalette =
    if (dark) darkPalette(id) else lightPalette(id)

/** 兼容旧引用：默认极光浅色 / 深色。 */
val AuroraLight: AuroraPalette = lightPalette(GlassPaletteId.AURORA)
val AuroraDark: AuroraPalette = darkPalette(GlassPaletteId.AURORA)

/* ------------------------------------------------------------------ *
 * 一层玻璃的参数
 * ------------------------------------------------------------------ */

/** 一层玻璃的绘制参数。所有 UI 都从这里取，避免各处硬编码 alpha。 */
@Immutable
data class GlassSpec(
    /** 玻璃主体色。 */
    val tint: Color,
    /** 描边色，1dp 的亮边是「玻璃」最重要的识别特征。 */
    val border: Color,
    /** 左上角柔光，模拟光源打在弧面上的高光。 */
    val highlight: Color,
    /** 右下角暗部，拟态风格靠它做出「压下去」的厚度。 */
    val shade: Color,
    /** 底部阴影色。 */
    val shadow: Color,
    /** 高光扩散范围（占控件尺寸的比例）。 */
    val highlightExtent: Float = 0.85f,
    /** 描边宽度。 */
    val borderWidth: Dp = 1.dp,
    /** 投影高度（仅用于 Surface 的 shadowElevation 场景）。 */
    val elevation: Dp = 12.dp,
)

object GlassDefaults {
    @Composable
    fun palette(): AuroraPalette = LocalAurora.current

    @Composable
    fun mode(): GlassMode = LocalGlassMode.current

    @Composable
    fun isDark(): Boolean = LocalGlassDark.current

    /** 页面主玻璃：卡片、面板。质感由 [mode] 决定。 */
    @Composable
    fun card(): GlassSpec = build(bar = false)

    /** 悬浮条玻璃：底部导航、工具栏。比卡片更厚，保证图标可读。 */
    @Composable
    fun bar(): GlassSpec = build(bar = true)

    /** 内嵌小玻璃：chip、输入框、进度条底槽。几乎无阴影。 */
    @Composable
    fun inset(): GlassSpec {
        val p = palette()
        val mode = mode()
        return when (mode) {
            GlassMode.LIQUID -> GlassSpec(
                tint = if (p.isDark) Color(0x14FFFFFF) else Color(0x1FFFFFFF),
                border = if (p.isDark) Color(0x2EFFFFFF) else Color(0x66FFFFFF),
                highlight = Color.Transparent,
                shade = Color.Transparent,
                shadow = Color.Transparent,
                elevation = 0.dp,
            )
            GlassMode.NEUMORPHIC -> GlassSpec(
                // 拟态小控件：几乎和卡片同色，只靠双向高光区分。
                tint = if (p.isDark) Color(0xFF1E2739) else Color(0xFFEFF2FA),
                border = Color.Transparent,
                highlight = if (p.isDark) Color(0x33FFFFFF) else Color(0xFFFFFFFF),
                shade = if (p.isDark) Color(0xFF05070D) else Color(0xFFCCD4E8),
                shadow = Color.Transparent,
                highlightExtent = 1.1f,
                elevation = 0.dp,
            )
            GlassMode.FROSTED -> GlassSpec(
                tint = if (p.isDark) Color(0x1FFFFFFF) else Color(0x73FFFFFF),
                border = if (p.isDark) Color(0x1AFFFFFF) else Color(0x40FFFFFF),
                highlight = Color.Transparent,
                shade = Color.Transparent,
                shadow = Color.Transparent,
                elevation = 0.dp,
            )
        }
    }

    /** 强调玻璃：选中态、主按钮，用强调色做底。 */
    @Composable
    fun accent(alpha: Float = 0.92f): GlassSpec {
        val p = palette()
        return GlassSpec(
            tint = p.accent.copy(alpha = alpha),
            border = Color.White.copy(alpha = if (p.isDark) 0.24f else 0.36f),
            highlight = Color.White.copy(alpha = 0.32f),
            shade = Color.Transparent,
            shadow = p.accent.copy(alpha = 0.35f),
            elevation = 10.dp,
        )
    }

    /** 玻璃上的次级文案色。 */
    @Composable
    fun secondaryText(): Color = if (isDark()) Color(0xFFAEB8D6) else Color(0xFF5A6478)

    /** 玻璃上的正文色。 */
    @Composable
    fun primaryText(): Color = if (isDark()) Color(0xFFF0F3FF) else Color(0xFF141A2E)

    /** 分隔线。 */
    @Composable
    fun divider(): Color = if (isDark()) Color(0x1FFFFFFF) else Color(0x14202A4A)

    @Composable
    private fun build(bar: Boolean): GlassSpec {
        val p = palette()
        val mode = mode()
        return when (mode) {
            // 液态：真正的主角是「透过来的背景」和「一道细亮边」。
            // 染色压到极低（浅色 6%、深色 8%），否则背景一被盖住就没有玻璃感了。
            // 悬浮条略厚一点，否则图标压在花背景上会糊。
            GlassMode.LIQUID -> GlassSpec(
                tint = when {
                    bar && p.isDark -> Color(0x3D0E141C)
                    bar -> Color(0x33FFFFFF)
                    else -> p.glass
                },
                border = when {
                    bar && p.isDark -> Color(0x4DFFFFFF)
                    bar -> Color(0x99FFFFFF)
                    else -> p.glassBorder
                },
                highlight = p.glassHighlight,
                shade = Color.Transparent,
                shadow = if (bar) {
                    if (p.isDark) Color(0x8C000000) else Color(0x1F1B2340)
                } else {
                    p.glassShadow
                },
                highlightExtent = 0.85f,
                elevation = if (bar) 22.dp else if (p.isDark) 10.dp else 14.dp,
            )
            // 拟态：同色系、无描边，靠「左上亮投影 + 右下暗投影 + 内壁起伏」做厚度。
            // 底色必须接近不透明——拟态是实心面板，透过去就变成了玻璃而不是拟态。
            GlassMode.NEUMORPHIC -> GlassSpec(
                tint = if (p.isDark) Color(0xFF1A2234) else Color(0xFFF4F6FC),
                border = Color.Transparent,
                highlight = if (p.isDark) Color(0x40FFFFFF) else Color(0xFFFFFFFF),
                shade = if (p.isDark) Color(0xFF06080F) else Color(0xFFC9D2E6),
                shadow = if (p.isDark) Color(0x66000000) else Color(0x1A37426B),
                highlightExtent = 1.15f,
                elevation = if (bar) 14.dp else 8.dp,
            )
            // 磨砂：高白度、低对比、弱描边，可读性优先。
            GlassMode.FROSTED -> GlassSpec(
                tint = when {
                    bar && p.isDark -> Color(0xD9141A2B)
                    bar -> Color(0xF2FFFFFF)
                    p.isDark -> Color(0x80161C2E)
                    else -> Color(0xD9FFFFFF)
                },
                border = if (p.isDark) Color(0x1AFFFFFF) else Color(0x3DFFFFFF),
                highlight = if (p.isDark) Color(0x14FFFFFF) else Color(0x59FFFFFF),
                shade = Color.Transparent,
                shadow = if (p.isDark) Color(0x4D000000) else Color(0x142A3566),
                highlightExtent = 0.6f,
                elevation = if (bar) 16.dp else 6.dp,
            )
        }
    }
}

/** 当前是否深色玻璃。 */
val LocalGlassDark = staticCompositionLocalOf { false }

/** 当前玻璃质感。 */
val LocalGlassMode = staticCompositionLocalOf { GlassMode.LIQUID }

/** 当前配色板。 */
val LocalAurora = staticCompositionLocalOf { AuroraLight }

/**
 * 玻璃容器。
 *
 * 绘制顺序：底色 → 左上柔光（液态/拟态）→ 右下暗部（拟态）→ 描边。
 * 磨砂模式把高光压到很低，所以看起来是「白片」而不是「彩色玻璃」。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    spec: GlassSpec = GlassDefaults.card(),
    onClick: (() -> Unit)? = null,
    contentColor: Color = GlassDefaults.primaryText(),
    /** 原生绘制用的圆角半径。胶囊形状传 999.dp，普通卡片用默认值。 */
    cornerRadius: Dp = GlassCornerRadius,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalAppTokens.current.style != AppThemeStyle.LIQUID_GLASS) {
        val target = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        Box(target) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() } }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val mode = GlassDefaults.mode()
    val palette = GlassDefaults.palette()
    val corner = cornerRadius

    // 按质感走不同的真实实现：
    // - 拟态：双向柔化投影 + 内壁起伏，不用描边；
    // - 液态 / 磨砂：背景折射 + 边缘镜面高光。
    // 磨砂把折射的模糊度调大、染色加厚，观感更「雾」。
    val fx = when (mode) {
        GlassMode.NEUMORPHIC -> Modifier.neumorphicSurface(
            cornerRadius = corner,
            tint = spec.tint,
            light = spec.highlight,
            dark = spec.shade,
        )
        GlassMode.LIQUID, GlassMode.FROSTED -> Modifier.liquidGlassSurface(
            cornerRadius = corner,
            palette = palette,
            mode = mode,
            tint = spec.tint,
            border = spec.border,
            shadow = spec.shadow,
            emphasis = spec.elevation >= 18.dp,
        )
    }

    val base = modifier
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = ripple(color = MaterialTheme.colorScheme.primary),
                    onClick = onClick,
                )
            } else Modifier,
        )
        .clip(shape)
        .then(fx)
        .then(
            // 拟态不用描边；其余质感由 liquidGlassSurface 自己画不均匀亮边，
            // 这里只在「磨砂」时补一条极淡的整圈边，帮助形状在浅背景上立住。
            if (mode == GlassMode.FROSTED && spec.border.alpha > 0.01f) {
                Modifier.border(spec.borderWidth, spec.border, shape)
            } else Modifier,
        )

    Box(modifier = base) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** 玻璃卡片的默认圆角。原生绘制需要具体数值，而 Shape 里的 CornerSize 读不回来，
 *  所以 GlassSurface 统一用这个半径开圆角；需要别的圆角时显式传 cornerRadius。 */
internal val GlassCornerRadius: Dp = 16.dp

/** 极光背景：底层色团 + 磨砂veil + 可选氛围粒子。整个 App 只铺一层，后面的页面透明叠上去。 */
@Composable
fun LiquidGlassBackground(
    modifier: Modifier = Modifier,
    palette: AuroraPalette = GlassDefaults.palette(),
    mode: GlassMode = GlassDefaults.mode(),
    atmosphere: Atmosphere = LocalAtmosphere.current,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // 氛围粒子需要一个持续的时间轴。用无限循环动画推进，只读一个 Float，
    // 不触发重组（读在 drawBehind 里），因此不会带来布局/测量开销。
    val motion = atmosphere != Atmosphere.NONE && android.animation.ValueAnimator.areAnimatorsEnabled()
    val time = if (motion) {
        val clock = rememberInfiniteTransition(label = "atmosphereClock")
        val value by clock.animateFloat(initialValue = 0f, targetValue = 1000f,
            animationSpec = infiniteRepeatable(animation = tween(durationMillis = 600_000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
            label = "atmosphereTime")
        value
    } else 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.base)
            .drawBehind {
                val w = size.width
                val h = size.height
                val g = palette.blooms
                if (g.isEmpty()) return@drawBehind

                // ---------- 平滑底：一条斜向的整屏渐变 ----------
                // 参考图的背景没有任何「能看出边界」的形状，就是一层缓慢的斜向渐层。
                // 之前那种几个实心色团一定叠出斑驳感，观感立刻变廉价。
                drawRect(
                    Brush.linearGradient(
                        colors = g,
                        start = Offset(0f, 0f),
                        end = Offset(w * 0.75f, h),
                    ),
                )

                // ---------- 两条极淡的柔光，只负责制造明暗起伏 ----------
                // 强度很低（8%~14%），深色下更弱。作用不是「上色」，
                // 而是让整块背景像有一束光斜擦过去，玻璃压上去才有东西可透。
                val soft = if (palette.isDark) 0.10f else 0.16f
                fun sheen(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                            center = Offset(w * cx, h * cy),
                            radius = maxOf(w * rx, h * ry),
                        ),
                        topLeft = Offset(w * cx - w * rx, h * cy - h * ry),
                        size = androidx.compose.ui.geometry.Size(w * rx * 2, h * ry * 2),
                    )
                }
                if (palette.isDark) {
                    // 深色：一道偏亮的青灰斜光，模拟参考图 2 那种流动的金属感。
                    sheen(Color(0xFF2E5D6B), 0.75f, 0.22f, 0.9f, 0.45f, soft)
                    sheen(Color(0xFF0A0F16), 0.20f, 0.80f, 0.9f, 0.45f, 0.30f)
                } else {
                    sheen(Color(0xFFFFFFFF), 0.72f, 0.16f, 0.85f, 0.40f, soft)
                    sheen(Color(0xFFFFFFFF), 0.18f, 0.86f, 0.85f, 0.40f, soft * 0.7f)
                }

                if (palette.veil.alpha > 0.01f) drawRect(palette.veil)

                // ---------- 星空 ----------
                // 星星在最远处，画在氛围粒子之下：雨丝/萤火要盖在星光前面才有纵深。
                drawStars(palette, time, atmosphere.starDensity())

                // ---------- 氛围粒子 ----------
                // 画在渐变之上、内容之下：它属于「背景」，不该盖住任何文字。
                drawAtmosphere(atmosphere, palette, time)
            },
    ) { content() }
}

/** 玻璃描边（不填充），用于给已有 Surface 加一层亮边。 */
fun Modifier.glassBorder(shape: Shape, spec: GlassSpec): Modifier =
    if (spec.border.alpha > 0.01f) border(spec.borderWidth, spec.border, shape) else this

fun glassShape(radius: Dp = 20.dp): Shape = RoundedCornerShape(radius)

internal fun Color.prefersDarkContent(): Boolean = luminance() > 0.55f
