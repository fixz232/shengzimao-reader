package com.shengzimao.reader.mobile.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * 氛围（Atmosphere）。
 *
 * 比「配色方案」高一层：同时决定 **配色 + 背景动态 + 情绪节奏**。
 *
 * 每个氛围都由三层构成，缺一层就会显得单薄：
 * 1. **远景**：大而柔、极慢的色晕（负责场景气氛，例如夏日的阳光、秋日的夕阳）；
 * 2. **主粒子**：该天气最标志性的元素（雨丝、雪花、花瓣、落叶……）；
 * 3. **副元素**：让场景「活」起来的第二样东西（雨积水珠、露珠、飞鸟、霜晶……）。
 *
 * 星空（[drawStars]）在深色下独立叠加，不受氛围限制。
 */
enum class Atmosphere(
    val label: String,
    val hint: String,
    val speed: Float,
    val density: Float,
) {
    NONE("无", "跟随下方配色方案", 1f, 1f),

    // ---------- 情绪 ----------
    SUNNY("晴朗", "暖金日光，光斑上浮、空气微颤", 0.55f, 0.90f),
    MELANCHOLY("伤感", "冷灰蓝，浓雾慢移、细雨飘零", 0.22f, 1.00f),
    FURIOUS("热烈", "赤红余烬，火星翻涌、热浪扭曲", 1.45f, 1.15f),

    // ---------- 天气 ----------
    RAINY("雨境", "深蓝雨夜，雨丝斜落、玻璃挂水珠", 1.00f, 1.35f),
    MISTY("雾晨", "淡紫灰，晨雾流动、露珠凝结", 0.30f, 1.00f),
    SNOWY("雪落", "冷白，雪花飘落、地面积霜", 0.50f, 1.00f),

    // ---------- 四季 ----------
    SPRING("春境", "樱粉新绿，花瓣飘落、嫩芽抽枝", 0.62f, 1.00f),
    SUMMER("夏境", "碧海青空，热浪蒸腾、萤火点点", 0.85f, 1.10f),
    AUTUMN("秋境", "橙金暮色，落叶翻飞、飞鸟南迁", 0.58f, 1.00f),
    WINTER("冬境", "靛蓝夜空，雪花斜飞、寒霜结晶", 0.42f, 1.00f);

    /** 取该氛围在当前深浅下的色板。 */
    fun palette(dark: Boolean): AuroraPalette? = when (this) {
        NONE -> null

        SUNNY -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF1A1207), Color(0xFF241A0C), Color(0xFF2C2011), Color(0xFF1C1409)),
            accent = Color(0xFFFFC46B), accentAlt = Color(0xFFFFE0A0),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFFFFBF2), Color(0xFFFFF2DC), Color(0xFFFFF9EC), Color(0xFFFDF3E6)),
            accent = Color(0xFFD97A1E), accentAlt = Color(0xFFE9A62F),
        )

        MELANCHOLY -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF0A0E15), Color(0xFF0F141D), Color(0xFF121A24), Color(0xFF0C1118)),
            accent = Color(0xFF9FB0C8), accentAlt = Color(0xFFBCC9DD),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFEEF1F6), Color(0xFFE6EAF2), Color(0xFFEFF2F7), Color(0xFFE8ECF3)),
            accent = Color(0xFF64718A), accentAlt = Color(0xFF8693A8),
        )

        FURIOUS -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF180606), Color(0xFF220A08), Color(0xFF2C0E0A), Color(0xFF190706)),
            accent = Color(0xFFFF6B4A), accentAlt = Color(0xFFFFA96B),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFFDEFED), Color(0xFFFBE4DF), Color(0xFFFDF1EA), Color(0xFFFAE6E2)),
            accent = Color(0xFFC0301C), accentAlt = Color(0xFFE25B27),
        )

        RAINY -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF0A1826), Color(0xFF10263A), Color(0xFF163A52), Color(0xFF0B1A2A)),
            accent = Color(0xFF8FD3FF), accentAlt = Color(0xFFC2E8FF),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFE8EEF4), Color(0xFFDCE6EF), Color(0xFFEAF1F7), Color(0xFFE2EAF2)),
            accent = Color(0xFF2E6699), accentAlt = Color(0xFF4E88C4),
        )

        MISTY -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF0D0B13), Color(0xFF13101B), Color(0xFF171422), Color(0xFF0F0C16)),
            accent = Color(0xFFC0B4E0), accentAlt = Color(0xFFDCD2F2),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFF4F3F8), Color(0xFFEDEBF3), Color(0xFFF6F4F9), Color(0xFFF0EEF5)),
            accent = Color(0xFF736798), accentAlt = Color(0xFF9488B6),
        )

        SNOWY -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF090D13), Color(0xFF0E131B), Color(0xFF121A24), Color(0xFF0A0F16)),
            accent = Color(0xFFCFE0F2), accentAlt = Color(0xFFEAF3FC),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFF7FAFE), Color(0xFFF1F6FC), Color(0xFFF9FCFF), Color(0xFFF4F8FD)),
            accent = Color(0xFF527698), accentAlt = Color(0xFF7DA0C4),
        )

        // 春：樱粉为主，带一点新绿，避免整屏都是粉。
        SPRING -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF1A0D14), Color(0xFF24121B), Color(0xFF2A1720), Color(0xFF1C0F16)),
            accent = Color(0xFFFFB3D2), accentAlt = Color(0xFFB6EFA0),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFFDF5F8), Color(0xFFFBEAF1), Color(0xFFF6FAF2), Color(0xFFFDF3F7)),
            accent = Color(0xFFC74E83), accentAlt = Color(0xFF5FA33F),
        )

        // 夏：海天蓝绿，点缀阳光金。
        SUMMER -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF04141A), Color(0xFF08222B), Color(0xFF0B2E38), Color(0xFF051820)),
            accent = Color(0xFF6FE6D8), accentAlt = Color(0xFFFFD97A),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFEDF9F8), Color(0xFFE2F3F3), Color(0xFFF1FBF5), Color(0xFFE8F6F6)),
            accent = Color(0xFF0E8F86), accentAlt = Color(0xFFD9931A),
        )

        // 秋：橙金暮色，压一点砖红。
        AUTUMN -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF1A0F05), Color(0xFF241608), Color(0xFF2E1C0B), Color(0xFF1C1106)),
            accent = Color(0xFFFFAE5C), accentAlt = Color(0xFFE8734A),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFFDF6EC), Color(0xFFFBEDDC), Color(0xFFFDF8F0), Color(0xFFFAF0E4)),
            accent = Color(0xFFB85E17), accentAlt = Color(0xFFC9452A),
        )

        // 冬：靛蓝夜空，比雨境更冷更暗，强调寒霜的银白。
        WINTER -> if (dark) paletteOf(
            dark = true,
            gradient = listOf(Color(0xFF060C18), Color(0xFF0A1424), Color(0xFF0D1B2E), Color(0xFF070F1C)),
            accent = Color(0xFFB9D4F5), accentAlt = Color(0xFFE6F2FF),
        ) else paletteOf(
            dark = false,
            gradient = listOf(Color(0xFFF3F7FC), Color(0xFFEBF1F9), Color(0xFFF6F9FD), Color(0xFFEEF3FA)),
            accent = Color(0xFF3C6288), accentAlt = Color(0xFF6E92B8),
        )
    }

    companion object {
        fun of(name: String): Atmosphere = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** 玻璃层统一用这套：氛围只换背景与强调色，玻璃本身仍然保持「近乎无色」。 */
private fun paletteOf(
    dark: Boolean,
    gradient: List<Color>,
    accent: Color,
    accentAlt: Color,
): AuroraPalette = AuroraPalette(
    base = gradient.first(),
    blooms = gradient,
    veil = Color(0x00000000),
    glass = if (dark) Color(0x1FFFFFFF) else Color(0x14FFFFFF),
    glassBorder = if (dark) Color(0x2EFFFFFF) else Color(0x52FFFFFF),
    glassHighlight = if (dark) Color(0x33FFFFFF) else Color(0x8CFFFFFF),
    glassShade = if (dark) Color(0x1A000000) else Color(0x0A0F1428),
    glassShadow = if (dark) Color(0x66000000) else Color(0x14202B45),
    accent = accent,
    accentAlt = accentAlt,
    isDark = dark,
)

/** 当前氛围，由主题提供。 */
val LocalAtmosphere = staticCompositionLocalOf { Atmosphere.NONE }

/* ------------------------------------------------------------------ *
 * 确定性伪随机
 * ------------------------------------------------------------------ */

/** 同一个 index 每帧都得到同一个值，粒子才不会抖。 */
internal fun rnd(index: Int, salt: Int): Float {
    var x = (index + 1) * 374761393 + salt * 668265263
    x = (x xor (x shr 13)) * 1274126177
    x = x xor (x shr 16)
    return (x and 0x7FFFFFFF) / 0x7FFFFFFF.toFloat()
}

/** 0..1 的慢速正弦，用来做「呼吸」。 */
private fun breath(time: Float, freq: Float, phase: Float): Float =
    (kotlin.math.sin(time * freq + phase * 6.283f) + 1f) / 2f

/* ------------------------------------------------------------------ *
 * 入口
 * ------------------------------------------------------------------ */

/** 绘制该氛围的全部动态。顺序：远景 → 主粒子 → 副元素。 */
fun DrawScope.drawAtmosphere(atmosphere: Atmosphere, palette: AuroraPalette, time: Float) {
    if (atmosphere == Atmosphere.NONE) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val dark = palette.isDark
    val t = time * atmosphere.speed
    val count = BASE_COUNT * atmosphere.density

    atmosphereBackdrop(atmosphere, palette, t, w, h)
    when (atmosphere) {
        Atmosphere.NONE -> Unit
        Atmosphere.SUNNY -> {
            sunBokeh(palette, t, w, h, count, dark)
            heatShimmer(palette, t, w, h, warm = true)
        }
        Atmosphere.MELANCHOLY -> {
            mistBands(palette, t, w, h, dark, heavy = true)
            rainLines(palette, t, w, h, count * 0.55f, dark, slow = true)
        }
        Atmosphere.FURIOUS -> {
            embers(palette, t, w, h, count, dark)
            heatShimmer(palette, t, w, h, strong = true)
        }
        Atmosphere.RAINY -> {
            rainLines(palette, t, w, h, count, dark)
            ripples(palette, t, w, h, dark)
        }
        Atmosphere.MISTY -> {
            mistBands(palette, t, w, h, dark, heavy = false)
            dewDrops(palette, t, w, h, dark)
        }
        Atmosphere.SNOWY -> {
            snowFlakes(palette, t, w, h, count, dark)
            frostGlints(palette, t, w, h, dark)
        }
        Atmosphere.SPRING -> {
            petals(palette, t, w, h, count, dark)
            sprouts(palette, t, w, h, dark)
        }
        Atmosphere.SUMMER -> {
            fireflies(palette, t, w, h, count, dark)
            heatShimmer(palette, t, w, h, warm = true)
        }
        Atmosphere.AUTUMN -> {
            leaves(palette, t, w, h, count, dark)
            birds(palette, t, w, h, dark)
        }
        Atmosphere.WINTER -> {
            snowFlakes(palette, t, w, h, count * 1.15f, dark, wind = true)
            frostCrystals(palette, t, w, h, dark)
        }
    }
}

/** 各氛围的远景：极慢、极大、极淡的一到两块色晕，负责「场景」。 */
private fun DrawScope.atmosphereBackdrop(
    atmosphere: Atmosphere,
    palette: AuroraPalette,
    t: Float,
    w: Float,
    h: Float,
) {
    val dark = palette.isDark
    fun glow(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
        val radius = maxOf(w * rx, h * ry)
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                center = Offset(w * cx, h * cy),
                radius = radius,
            ),
            topLeft = Offset(w * cx - w * rx, h * cy - h * ry),
            size = Size(w * rx * 2f, h * ry * 2f),
        )
    }

    when (atmosphere) {
        Atmosphere.SUNNY -> {
            val p = breath(t, 0.12f, 0.1f)
            glow(Color(0xFFFFD98F), 0.78f, 0.12f, 0.75f, 0.38f, (if (dark) 0.12f else 0.26f) * (0.7f + p * 0.3f))
        }
        Atmosphere.SPRING -> {
            glow(palette.accent, 0.22f, 0.10f, 0.72f, 0.36f, if (dark) 0.10f else 0.20f)
            glow(palette.accentAlt, 0.85f, 0.82f, 0.68f, 0.34f, if (dark) 0.07f else 0.15f)
        }
        Atmosphere.SUMMER -> {
            glow(palette.accent, 0.80f, 0.16f, 0.78f, 0.40f, if (dark) 0.12f else 0.22f)
            glow(Color(0xFFFFE08A), 0.20f, 0.86f, 0.62f, 0.30f, if (dark) 0.05f else 0.14f)
        }
        Atmosphere.AUTUMN -> {
            glow(palette.accent, 0.12f, 0.22f, 0.80f, 0.42f, if (dark) 0.13f else 0.24f)
            glow(palette.accentAlt, 0.88f, 0.74f, 0.62f, 0.32f, if (dark) 0.07f else 0.14f)
        }
        Atmosphere.WINTER -> {
            glow(palette.accent, 0.30f, 0.08f, 0.72f, 0.34f, if (dark) 0.12f else 0.18f)
        }
        Atmosphere.RAINY -> {
            // 远处一点城市灯的暖调，让蓝色雨夜不死沉。
            glow(Color(0xFF3E6C8A), 0.88f, 0.30f, 0.60f, 0.32f, 0.10f)
        }
        Atmosphere.MELANCHOLY, Atmosphere.MISTY -> {
            glow(Color.White, 0.30f, 0.20f, 0.85f, 0.40f, if (dark) 0.04f else 0.16f)
        }
        Atmosphere.SNOWY -> {
            glow(Color(0xFFBFD4EA), 0.24f, 0.12f, 0.72f, 0.34f, if (dark) 0.09f else 0.16f)
        }
        Atmosphere.FURIOUS, Atmosphere.NONE -> Unit
    }
}

/* ------------------------------------------------------------------ *
 * 主粒子
 * ------------------------------------------------------------------ */

/** 雨：远近两层细线。 */
private fun DrawScope.rainLines(
    palette: AuroraPalette,
    t: Float,
    w: Float,
    h: Float,
    count: Float,
    dark: Boolean,
    slow: Boolean = false,
) {
    val n = count.toInt().coerceAtLeast(4)
    val farColor = palette.accent.copy(alpha = if (dark) 0.18f else 0.15f)
    val nearColor = palette.accentAlt.copy(alpha = if (dark) 0.40f else 0.28f)

    repeat(n) { i ->
        val x0 = rnd(i, 1) * (w + 200f) - 100f
        val speed = (0.42f + rnd(i, 2) * 0.30f) * (if (slow) 0.7f else 1f)
        val progress = (t * speed + rnd(i, 3)) % 1.25f
        val y = progress * (h + 240f) - 120f
        val drift = progress * 54f
        val len = 22f + rnd(i, 4) * 30f
        drawLine(
            color = farColor,
            start = Offset(x0 + drift, y),
            end = Offset(x0 + drift - len * 0.19f, y + len),
            strokeWidth = 1f + rnd(i, 5) * 0.7f,
        )
    }

    val nearN = (n * 0.42f).toInt().coerceAtLeast(4)
    repeat(nearN) { i ->
        val idx = i + 1000
        val x0 = rnd(idx, 1) * (w + 240f) - 120f
        val speed = (0.85f + rnd(idx, 2) * 0.55f) * (if (slow) 0.6f else 1f)
        val progress = (t * speed + rnd(idx, 3)) % 1.15f
        val y = progress * (h + 300f) - 150f
        val drift = progress * 74f
        val len = 40f + rnd(idx, 4) * 54f
        drawLine(
            color = nearColor,
            start = Offset(x0 + drift, y),
            end = Offset(x0 + drift - len * 0.20f, y + len),
            strokeWidth = 1.5f + rnd(idx, 5) * 1.1f,
            cap = StrokeCap.Round,
        )
    }
}

/** 雨天地面积水：横向扁椭圆涟漪，扩散并淡出。 */
private fun DrawScope.ripples(palette: AuroraPalette, t: Float, w: Float, h: Float, dark: Boolean) {
    val n = 9
    repeat(n) { i ->
        val cycle = 4.5f + rnd(i, 31) * 3.5f
        val progress = ((t * 0.55f + rnd(i, 32) * cycle) % cycle) / cycle
        val cx = rnd(i, 33) * w
        val cy = h * (0.62f + rnd(i, 34) * 0.36f)
        val rx = 8f + progress * 46f
        val ry = rx * 0.26f
        val alpha = (if (dark) 0.20f else 0.14f) * (1f - progress)
        drawOval(
            color = palette.accentAlt.copy(alpha = alpha),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = 1.2f),
        )
    }
}

/** 雪：柔软圆点，左右摇摆；[wind] 为真时整体斜飞。 */
private fun DrawScope.snowFlakes(
    palette: AuroraPalette,
    t: Float,
    w: Float,
    h: Float,
    count: Float,
    dark: Boolean,
    wind: Boolean = false,
) {
    val n = count.toInt().coerceAtLeast(4)
    val color = if (dark) Color.White.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.88f)
    repeat(n) { i ->
        val baseX = rnd(i, 1)
        val speed = 0.10f + rnd(i, 2) * 0.14f
        val progress = (t * speed + rnd(i, 3)) % 1.1f
        val y = progress * (h + 80f) - 40f
        val sway = kotlin.math.sin(t * (0.6f + rnd(i, 4)) + rnd(i, 3) * 6.28f) * (14f + rnd(i, 5) * 22f)
        val skew = if (wind) progress * 90f else 0f
        drawCircle(
            color = color,
            radius = 1.6f + rnd(i, 6) * 2.8f,
            center = Offset(baseX * w + sway + skew, y),
        )
    }
}

/** 花瓣：椭圆旋转飘落。 */
private fun DrawScope.petals(palette: AuroraPalette, t: Float, w: Float, h: Float, count: Float, dark: Boolean) {
    val n = count.toInt().coerceAtLeast(4)
    val c1 = palette.accent.copy(alpha = if (dark) 0.46f else 0.38f)
    val c2 = palette.accentAlt.copy(alpha = if (dark) 0.30f else 0.26f)
    val white = Color.White.copy(alpha = if (dark) 0.26f else 0.62f)
    repeat(n) { i ->
        val baseX = rnd(i, 1)
        val speed = 0.09f + rnd(i, 2) * 0.13f
        val progress = (t * speed + rnd(i, 3)) % 1.1f
        val y = progress * (h + 80f) - 40f
        val sway = kotlin.math.sin(t * (0.8f + rnd(i, 4)) + rnd(i, 3) * 6.28f) * (20f + rnd(i, 5) * 26f)
        val s = 3.5f + rnd(i, 6) * 4.5f
        val angle = t * (40f + rnd(i, 7) * 90f) + rnd(i, 3) * 360f
        val tint = when {
            i % 5 == 0 -> white
            i % 2 == 0 -> c1
            else -> c2
        }
        rotate(degrees = angle, pivot = Offset(baseX * w + sway, y)) {
            drawOval(
                color = tint,
                topLeft = Offset(baseX * w + sway - s, y - s * 0.62f),
                size = Size(s * 2f, s * 1.24f),
            )
        }
    }
}

/** 落叶：比花瓣更大、带叶脉，边落边翻。 */
private fun DrawScope.leaves(palette: AuroraPalette, t: Float, w: Float, h: Float, count: Float, dark: Boolean) {
    val n = (count * 0.8f).toInt().coerceAtLeast(4)
    val warm = listOf(
        palette.accent.copy(alpha = if (dark) 0.48f else 0.40f),
        palette.accentAlt.copy(alpha = if (dark) 0.42f else 0.34f),
        Color(0xFFC9452A).copy(alpha = if (dark) 0.34f else 0.26f),
    )
    repeat(n) { i ->
        val baseX = rnd(i, 1)
        val speed = 0.07f + rnd(i, 2) * 0.11f
        val progress = (t * speed + rnd(i, 3)) % 1.12f
        val y = progress * (h + 90f) - 45f
        val sway = kotlin.math.sin(t * (0.55f + rnd(i, 4)) + rnd(i, 3) * 6.28f) * (34f + rnd(i, 5) * 40f)
        val s = 4.5f + rnd(i, 6) * 5.5f
        val angle = t * (26f + rnd(i, 7) * 50f) + rnd(i, 3) * 360f
        val tint = warm[i % warm.size]
        rotate(degrees = angle, pivot = Offset(baseX * w + sway, y)) {
            drawOval(color = tint, topLeft = Offset(baseX * w + sway - s, y - s * 0.55f), size = Size(s * 2f, s * 1.1f))
            drawLine(
                color = tint.copy(alpha = tint.alpha * 0.85f),
                start = Offset(baseX * w + sway - s * 0.85f, y),
                end = Offset(baseX * w + sway + s * 0.85f, y),
                strokeWidth = 0.8f,
            )
        }
    }
}

/** 萤火：缓慢游动 + 呼吸。 */
private fun DrawScope.fireflies(palette: AuroraPalette, t: Float, w: Float, h: Float, count: Float, dark: Boolean) {
    val n = (count * 0.62f).toInt().coerceAtLeast(5)
    repeat(n) { i ->
        val baseX = rnd(i, 1)
        val baseY = rnd(i, 2)
        val x = baseX * w + kotlin.math.sin(t * (0.30f + rnd(i, 3) * 0.4f) + rnd(i, 4) * 6.28f) * (40f + rnd(i, 5) * 60f)
        val y = baseY * h + kotlin.math.cos(t * (0.24f + rnd(i, 6) * 0.35f) + rnd(i, 7) * 6.28f) * (34f + rnd(i, 8) * 54f)
        val pulse = 0.35f + 0.65f * breath(t, 1.2f + rnd(i, 9) * 1.4f, rnd(i, 10))
        val radius = 2.2f + rnd(i, 11) * 2.4f
        val tint = if (i % 2 == 0) palette.accentAlt else Color(0xFFB7EA8A)
        drawCircle(tint.copy(alpha = (if (dark) 0.16f else 0.12f) * pulse), radius * 3.4f, Offset(x, y))
        drawCircle(tint.copy(alpha = (if (dark) 0.88f else 0.62f) * pulse), radius, Offset(x, y))
    }
}

/** 余烬：由下往上飘并逐渐熄灭。 */
private fun DrawScope.embers(palette: AuroraPalette, t: Float, w: Float, h: Float, count: Float, dark: Boolean) {
    val n = count.toInt().coerceAtLeast(6)
    repeat(n) { i ->
        val baseX = rnd(i, 1)
        val speed = 0.18f + rnd(i, 2) * 0.28f
        val progress = (t * speed + rnd(i, 3)) % 1.15f
        val y = h + 40f - progress * (h + 120f)
        val sway = kotlin.math.sin(t * (1.1f + rnd(i, 4)) + rnd(i, 3) * 6.28f) * (16f + rnd(i, 5) * 20f)
        val radius = 1.8f + rnd(i, 6) * 3.2f
        val fade = (1f - progress).coerceIn(0f, 1f)
        val tint = if (i % 3 == 0) palette.accentAlt else palette.accent
        drawCircle(tint.copy(alpha = (if (dark) 0.52f else 0.40f) * fade), radius, Offset(baseX * w + sway, y))
    }
}

/** 光斑：大而柔、向上浮。 */
private fun DrawScope.sunBokeh(palette: AuroraPalette, t: Float, w: Float, h: Float, count: Float, dark: Boolean) {
    val n = (count * 0.45f).toInt().coerceAtLeast(5)
    repeat(n) { i ->
        val baseX = rnd(i, 1)
        val speed = 0.05f + rnd(i, 2) * 0.09f
        val progress = (t * speed + rnd(i, 3)) % 1.2f
        val y = h + 140f - progress * (h + 280f)
        val sway = kotlin.math.sin(t * (0.35f + rnd(i, 4)) + rnd(i, 3) * 6.28f) * (30f + rnd(i, 5) * 40f)
        val radius = 40f + rnd(i, 6) * 90f
        val alpha = (if (dark) 0.13f else 0.20f) * (1f - progress * 0.5f)
        val tint = if (i % 2 == 0) palette.accent else palette.accentAlt
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = alpha), Color.Transparent),
                center = Offset(baseX * w + sway, y),
                radius = radius,
            ),
            radius = radius,
            center = Offset(baseX * w + sway, y),
        )
    }
}

/** 热浪：极淡的横向亮带，让画面「晃」。 */
private fun DrawScope.heatShimmer(
    palette: AuroraPalette,
    t: Float,
    w: Float,
    h: Float,
    strong: Boolean = false,
    warm: Boolean = false,
) {
    val bands = if (strong) 7 else 4
    val tint = if (warm) Color(0xFFFFE08A) else palette.accentAlt
    repeat(bands) { i ->
        val y = h * (0.12f + rnd(i, 51) * 0.8f)
        val amp = (if (strong) 10f else 5f) + rnd(i, 52) * 8f
        val speed = 0.25f + rnd(i, 53) * 0.4f
        val cx = ((t * speed + rnd(i, 54)) % 1.4f - 0.2f) * w
        val bandH = 16f + rnd(i, 55) * 26f
        val alpha = (if (palette.isDark) 0.05f else 0.10f) * (if (strong) 1.4f else 1f)
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = alpha), Color.Transparent),
                center = Offset(cx, y),
                radius = w * 0.5f,
            ),
            topLeft = Offset(cx - amp * 4f, y - bandH / 2f),
            size = Size(amp * 8f, bandH),
        )
    }
}

/** 雾：几条极宽横向柔光带。 */
private fun DrawScope.mistBands(
    palette: AuroraPalette,
    t: Float,
    w: Float,
    h: Float,
    dark: Boolean,
    heavy: Boolean,
) {
    val colour = if (dark) Color.White else palette.accent
    val bands = if (heavy) 7 else 5
    repeat(bands) { i ->
        val bandH = h * (0.16f + rnd(i, 1) * 0.14f)
        val y = h * (0.08f + rnd(i, 2) * 0.85f)
        val speed = 0.05f + rnd(i, 3) * 0.07f
        val x = ((t * speed + rnd(i, 4)) % 1.6f - 0.3f) * w
        val alpha = (if (dark) (if (heavy) 0.09f else 0.06f) else (if (heavy) 0.34f else 0.22f)) * (0.6f + rnd(i, 5) * 0.4f)
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(colour.copy(alpha = alpha), Color.Transparent),
                center = Offset(x + w * 0.3f, y),
                radius = w * 0.7f,
            ),
            topLeft = Offset(x, y - bandH / 2f),
            size = Size(w * 0.9f, bandH),
        )
    }
    drawRect(color = Color.White.copy(alpha = if (dark) 0.015f else (if (heavy) 0.10f else 0.06f)))
}

/* ------------------------------------------------------------------ *
 * 副元素
 * ------------------------------------------------------------------ */

/** 露珠：极小的高光点，像草叶上的水。 */
private fun DrawScope.dewDrops(palette: AuroraPalette, t: Float, w: Float, h: Float, dark: Boolean) {
    repeat(14) { i ->
        val x = rnd(i, 61) * w
        val y = rnd(i, 62) * h
        val rr = 1.2f + rnd(i, 63) * 2.2f
        val p = 0.5f + 0.5f * breath(t, 0.4f + rnd(i, 64) * 0.5f, rnd(i, 65))
        drawCircle(Color.White.copy(alpha = (if (dark) 0.30f else 0.70f) * p), rr, Offset(x, y))
    }
}

/** 春芽：底部向上抽出的嫩绿短茎 + 两片子叶。 */
private fun DrawScope.sprouts(palette: AuroraPalette, t: Float, w: Float, h: Float, dark: Boolean) {
    val stem = palette.accentAlt.copy(alpha = if (dark) 0.34f else 0.26f)
    repeat(7) { i ->
        val x = (rnd(i, 71) * 0.9f + 0.05f) * w
        val baseY = h * (0.90f + rnd(i, 72) * 0.10f)
        val phase = rnd(i, 73)
        val sway = kotlin.math.sin(t * (0.5f + rnd(i, 74) * 0.4f) + phase * 6.28f) * 4f
        val height = (18f + rnd(i, 75) * 26f) * (0.85f + 0.15f * breath(t, 0.3f, phase))
        drawLine(
            color = stem,
            start = Offset(x, baseY),
            end = Offset(x + sway, baseY - height),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round,
        )
        val leafR = 3.4f + rnd(i, 76) * 2.4f
        drawOval(
            color = stem,
            topLeft = Offset(x + sway - leafR * 1.5f, baseY - height - leafR * 0.5f),
            size = Size(leafR * 1.6f, leafR),
        )
        drawOval(
            color = stem,
            topLeft = Offset(x + sway - leafR * 0.1f, baseY - height - leafR * 1.4f),
            size = Size(leafR * 1.6f, leafR),
        )
    }
}

/** 飞鸟：高空几笔「人」字形，缓慢横移。 */
private fun DrawScope.birds(palette: AuroraPalette, t: Float, w: Float, h: Float, dark: Boolean) {
    val ink = (if (dark) Color.White else palette.accent).copy(alpha = if (dark) 0.30f else 0.22f)
    repeat(4) { i ->
        val speed = 0.012f + rnd(i, 81) * 0.018f
        val progress = (t * speed + rnd(i, 82)) % 1.3f
        val x = progress * (w + 120f) - 60f
        val y = h * (0.10f + rnd(i, 83) * 0.22f)
        val s = 5f + rnd(i, 84) * 5f
        val flap = kotlin.math.sin(t * (2.2f + rnd(i, 85)) + rnd(i, 86) * 6.28f) * (s * 0.35f)
        drawLine(ink, Offset(x - s, y + flap), Offset(x, y), strokeWidth = 1.4f, cap = StrokeCap.Round)
        drawLine(ink, Offset(x, y), Offset(x + s, y + flap), strokeWidth = 1.4f, cap = StrokeCap.Round)
    }
}

/** 霜晶：小而锐利的六角星点，极慢闪烁。 */
private fun DrawScope.frostCrystals(palette: AuroraPalette, t: Float, w: Float, h: Float, dark: Boolean) {
    repeat(16) { i ->
        val x = rnd(i, 91) * w
        val y = rnd(i, 92) * h
        val p = 0.35f + 0.65f * breath(t, 0.25f + rnd(i, 93) * 0.4f, rnd(i, 94))
        val rr = 1.4f + rnd(i, 95) * 1.8f
        val color = palette.accentAlt.copy(alpha = (if (dark) 0.42f else 0.34f) * p)
        repeat(3) { k ->
            val a = (k * 60f) * (kotlin.math.PI / 180f).toFloat()
            drawLine(
                color = color,
                start = Offset(x - kotlin.math.cos(a) * rr, y - kotlin.math.sin(a) * rr),
                end = Offset(x + kotlin.math.cos(a) * rr, y + kotlin.math.sin(a) * rr),
                strokeWidth = 0.8f,
            )
        }
    }
}

/** 雪面反光：低位几点白光，像积雪在闪。 */
private fun DrawScope.frostGlints(palette: AuroraPalette, t: Float, w: Float, h: Float, dark: Boolean) {
    repeat(12) { i ->
        val x = rnd(i, 96) * w
        val y = h * (0.80f + rnd(i, 97) * 0.20f)
        val p = 0.4f + 0.6f * breath(t, 0.3f + rnd(i, 98) * 0.4f, rnd(i, 99))
        drawCircle(
            color = Color.White.copy(alpha = (if (dark) 0.22f else 0.55f) * p),
            radius = 0.9f + rnd(i, 100) * 1.6f,
            center = Offset(x, y),
        )
    }
}

/* ------------------------------------------------------------------ *
 * 星空
 * ------------------------------------------------------------------ */

/**
 * 夜空星点：只在深色下出现（浅色天空看不见星）。
 *
 * 主体一颗小圆 + 四个方向的细十字光芒。
 * 十字用两条短线而非星形路径，几十颗星每帧走路径会明显掉帧。
 */
fun DrawScope.drawStars(palette: AuroraPalette, time: Float, density: Float) {
    if (!palette.isDark) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val count = (58 * density).toInt().coerceIn(0, 96)
    if (count == 0) return

    repeat(count) { i ->
        val x = rnd(i, 101) * w
        val y = rnd(i, 102) * h * 0.82f
        val baseR = 0.7f + rnd(i, 103) * 1.5f
        val twinkle = 0.35f + 0.65f * breath(time, 0.5f + rnd(i, 104) * 1.6f, rnd(i, 105))
        val alpha = ((0.25f + rnd(i, 106) * 0.55f) * twinkle).coerceIn(0f, 1f)
        val tint = if (rnd(i, 107) > 0.82f) palette.accentAlt else Color.White
        val color = tint.copy(alpha = alpha)

        drawCircle(color = color, radius = baseR, center = Offset(x, y))

        if (alpha > 0.5f) {
            val len = baseR * (2.4f + rnd(i, 108) * 2.2f)
            val glow = color.copy(alpha = alpha * 0.55f)
            drawLine(glow, Offset(x - len, y), Offset(x + len, y), strokeWidth = 0.8f)
            drawLine(glow, Offset(x, y - len), Offset(x, y + len), strokeWidth = 0.8f)
        }
    }
}

/** 每个氛围的星空密度倍率。 */
internal fun Atmosphere.starDensity(): Float = when (this) {
    Atmosphere.RAINY -> 1.15f
    Atmosphere.WINTER -> 1.30f
    Atmosphere.SNOWY -> 0.95f
    Atmosphere.MELANCHOLY -> 0.70f
    Atmosphere.AUTUMN -> 0.85f
    Atmosphere.MISTY -> 0.55f
    Atmosphere.SPRING -> 0.50f
    Atmosphere.SUMMER -> 0.90f
    Atmosphere.FURIOUS -> 0.50f
    Atmosphere.SUNNY -> 0.35f
    Atmosphere.NONE -> 1f
}

/** 基础粒子数量，实际会乘以氛围的 density。 */
private const val BASE_COUNT = 56f
