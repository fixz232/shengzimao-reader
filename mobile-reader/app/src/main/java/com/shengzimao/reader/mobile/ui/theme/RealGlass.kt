package com.shengzimao.reader.mobile.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 「真」玻璃实现。
 *
 * 之前 LiquidGlass.kt 里的玻璃只是「半透明底色 + 一层矩形径向高光」，
 * 那是「看起来像玻璃」，并不是玻璃：
 * - 没有任何背景模糊，背后的东西是被简单透出来的；
 * - 描边是一整圈均匀亮线，而真实玻璃的边缘只会沿光源方向亮；
 * - 拟态只有内部高光，缺少让它「凸起来」的两个方向外投影。
 *
 * 这个文件用真实光学特征重做两种质感：
 *
 * **拟态玻璃（Neumorphic）** —— 靠光影做厚度，不靠边框：
 * 1. 外投影 A：光从左上来 → 左上方向投**亮**影；
 * 2. 外投影 B：右下方向投**暗**影；
 *    两者都是真实高斯柔化（BlurMaskFilter），所以边界是软的；
 * 3. 体内左上内高光 + 右下内阴影，让表面有微凸的弧面感；
 * 4. 完全不用 1px 描边（拟态的标志就是没有描边）。
 *
 * **液态玻璃（Liquid Glass）** —— 靠折射与镜面高光做通透：
 * 1. 背景折射：按玻璃在屏幕上的真实位置，把背后的极光重新取样、
 *    模糊、再画进玻璃里 —— 这才是「透过玻璃看到的东西」，
 *    而不是把一层半透明白铺上去；
 * 2. 镜面高光：沿圆角边缘按光照方向画一圈不均匀亮边（左上有、右下几乎无）；
 * 3. 内壁折射带：紧贴内边缘一圈更亮的细线，模拟厚玻璃的边缘折光；
 * 4. 斜向光泽：一道很淡的对角高光，模拟玻璃表面的反光带。
 */

/** 玻璃几何参数：圆角与描边都按 px 算，避免在绘制阶段反复转换。 */
@Immutable
private data class GlassGeometry(
    val cornerPx: Float,
    val width: Float,
    val height: Float,
)

/** 用真实高斯模糊画一个圆角矩形（可偏移），用于外投影与内阴影。 */
private fun DrawScope.blurredRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    color: Color,
    blurPx: Float,
    dilationPx: Float = 0f,
) {
    if (color.alpha < 0.01f) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        if (blurPx > 0.1f) maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        style = android.graphics.Paint.Style.FILL
    }
    val native = drawContext.canvas.nativeCanvas
    native.drawRoundRect(
        left - dilationPx,
        top - dilationPx,
        right + dilationPx,
        bottom + dilationPx,
        radius + dilationPx,
        radius + dilationPx,
        paint,
    )
}

/**
 * 背景渐变绘制：抽出来复用，保证「页面背景」和「玻璃里折射出来的背景」是同一套公式，
 * 否则玻璃里的颜色会对不上背后的颜色，一眼假。
 *
 * [originX] / [originY] 是玻璃左上角在屏幕上的位置；减掉它就把屏幕坐标换算成本地坐标，
 * 于是玻璃里画出来的，正好是「本该在它背后」的那一段渐变。
 */
internal fun DrawScope.drawAuroraBlooms(
    palette: AuroraPalette,
    mode: GlassMode,
    fullWidth: Float,
    fullHeight: Float,
    originX: Float = 0f,
    originY: Float = 0f,
) {
    if (fullWidth <= 0f || fullHeight <= 0f) return
    val g = palette.blooms
    if (g.isEmpty()) return

    // 与 LiquidGlassBackground 完全同构的一条斜向渐变，只是原点做了平移。
    drawRect(
        brush = Brush.linearGradient(
            colors = g,
            start = Offset(-originX, -originY),
            end = Offset(fullWidth * 0.75f - originX, fullHeight - originY),
        ),
        topLeft = Offset(-originX, -originY),
        size = Size(fullWidth, fullHeight),
    )
}

/**
 * 拟态玻璃：真正的双向柔化投影 + 内壁起伏。
 *
 * 注意 style 里**不能**再加 border —— 拟态的全部层次都由光影表达，
 * 一加描边就退回成「卡片 + 边框」，那是扁平设计而不是拟态。
 */
@Composable
fun Modifier.neumorphicSurface(
    cornerRadius: Dp = 22.dp,
    tint: Color,
    light: Color,
    dark: Color,
    pressed: Boolean = false,
): Modifier {
    val density = LocalDensity.current
    val r = with(density) { cornerRadius.toPx() }
    // 凸起用 6dp 的柔和投影；按下时反过来（内凹），这正是拟态「按下去」的手感来源。
    val depth = with(density) { (if (pressed) 1.5f else 5f).dp.toPx() }
    val blur = with(density) { (if (pressed) 5f else 9f).dp.toPx() }
    val innerBlur = with(density) { 4.dp.toPx() }

    return this.drawBehind {
        val w = size.width
        val h = size.height

        if (!pressed) {
            // ---- 外部双向投影：光来自左上，所以左上亮、右下暗 ----
            blurredRoundRect(
                left = depth, top = depth, right = w - depth, bottom = h - depth,
                radius = r, color = light, blurPx = blur, dilationPx = 0.5f,
            )
            blurredRoundRect(
                left = -depth * 0.55f, top = -depth * 0.55f,
                right = w + depth * 0.55f, bottom = h + depth * 0.55f,
                radius = r, color = dark, blurPx = blur * 1.05f,
            )
        }

        // ---- 玻璃本体：如实填充；拟态不透明，颜色跟随所在底色 ----
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = tint.toArgb()
            style = android.graphics.Paint.Style.FILL
        }
        drawContext.canvas.nativeCanvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

        // ---- 内壁光影 ----
        // 内高光：一条贴着左上边缘的模糊亮弧
        // 内阴影：一条贴着右下边缘的模糊暗弧
        // 做法是把模糊矩形裁进圆角内部再画，超出部分被裁掉，正好留下一条内壁光。
        val innerInset = with(density) { 1.5f.dp.toPx() }
        val highlightOffset = with(density) { 2.5f.dp.toPx() }
        val shadeOffset = with(density) { 2.5f.dp.toPx() }

        drawContext.canvas.nativeCanvas.apply {
            save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
            }
            clipPath(clipPath)

            if (pressed) {
                // 按下态：光从里面的右上角打进来，左右反一下。
                blurredRoundRect(
                    left = -innerInset, top = -innerInset,
                    right = w + innerInset, bottom = h + innerInset,
                    radius = r,
                    color = dark,
                    blurPx = innerBlur,
                    dilationPx = 0f,
                )
            } else {
                // 左上内高光
                blurredRoundRect(
                    left = -w + highlightOffset, top = -h + highlightOffset,
                    right = highlightOffset, bottom = highlightOffset,
                    radius = r,
                    color = light,
                    blurPx = innerBlur * 1.4f,
                )
                // 右下内阴影
                blurredRoundRect(
                    left = w - shadeOffset, top = h - shadeOffset,
                    right = w * 2f - shadeOffset, bottom = h * 2f - shadeOffset,
                    radius = r,
                    color = dark,
                    blurPx = innerBlur * 1.4f,
                )
            }
            restore()
        }
    }
}

/**
 * 液态玻璃：真实背景折射 + 边缘镜面高光。
 *
 * 关键点是 [drawRefractedBackdrop]：它按玻璃在窗口里的真实位置，
 * 把背后那层极光重新算一遍并模糊，于是玻璃里显示的就是「真正在它背后的颜色」，
 * 而不是一层与背景无关的半透明白。这是液体玻璃和「半透明卡片」的根本区别。
 */
@Composable
fun Modifier.liquidGlassSurface(
    cornerRadius: Dp = 22.dp,
    palette: AuroraPalette,
    mode: GlassMode,
    tint: Color,
    border: Color,
    shadow: Color,
    emphasis: Boolean = false,
): Modifier {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidth = with(density) { windowInfo.containerSize.width.toFloat() }
    val screenHeight = with(density) { windowInfo.containerSize.height.toFloat() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // 雨境联动：氛围是「雨境」时，玻璃表面会挂上水珠，
    // 而且水珠随时间缓慢汇聚、滑落 —— 让卡片和下雨这件事产生关系，
    // 而不是「背景下雨、卡片干着」。
    val atmosphere = LocalAtmosphere.current
    val rainy = atmosphere == Atmosphere.RAINY
    val clock = rememberInfiniteTransition(label = "glassRainClock")
    val dripTime by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glassRainTime",
    )

    val r = with(density) { cornerRadius.toPx() }
    val refractionBlur = with(density) { 14.dp.toPx() }

    return this
        .onGloballyPositioned { coords ->
            val next = coords.positionInWindow()
            if (next != origin) origin = next
        }
        .drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind

            val native = drawContext.canvas.nativeCanvas
            val clip = android.graphics.Path().apply {
                addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
            }

            // ---------- 1) 背景折射 ----------
            native.save()
            native.clipPath(clip)
            // 用 saveLayer + BlurMaskFilter 把整层折射内容软化成「磨砂」，
            // 这是真正的模糊，而不是把 alpha 调低假装通透。
            val layerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(refractionBlur, BlurMaskFilter.Blur.NORMAL)
            }
            native.saveLayer(0f, 0f, w, h, layerPaint)
            // 把真实的背景极光按玻璃位置重绘（origin 为负偏移，换算成本地坐标）。
            drawAuroraBlooms(
                palette = palette,
                mode = mode,
                fullWidth = screenWidth,
                fullHeight = screenHeight,
                originX = origin.x,
                originY = origin.y,
            )
            native.restore()

            // 玻璃自身的染色（很薄，只为压一点对比，不遮背景）。
            val tintPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                style = android.graphics.Paint.Style.FILL
            }
            native.drawRoundRect(0f, 0f, w, h, r, r, tintPaint)

            // 玻璃底部的暗部：真实玻璃越靠下越厚、越暗，这一步能拉出体积。
            native.drawRect(
                0f, h * 0.55f, w, h,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(
                        0f, h * 0.55f, 0f, h,
                        android.graphics.Color.TRANSPARENT,
                        (if (palette.isDark) Color(0x33000000) else Color(0x0F1B2340)).toArgb(),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                },
            )
            native.restore()

            // ---------- 2) 外投影：让玻璃「浮」在背景上 ----------
            // 参考图里玻璃有很明显的柔和落影，这是它看起来像一块实体玻璃的关键。
            // 用 clipPath(差集) 把内部挖掉，只让投影落在外面。
            val dropOffset = with(density) { 3.dp.toPx() }
            val dropBlur = with(density) { 14.dp.toPx() }
            native.save()
            val outside = android.graphics.Path().apply {
                addRect(-w, -h, w * 2f, h * 2f, android.graphics.Path.Direction.CW)
                addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
                fillType = android.graphics.Path.FillType.EVEN_ODD
            }
            native.clipPath(outside)
            native.drawRoundRect(
                -dropOffset * 0.4f, dropOffset, w + dropOffset * 0.4f, h + dropOffset,
                r, r,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = (if (palette.isDark) Color(0xFF000000) else Color(0xFF1B2340)).toArgb()
                    alpha = if (palette.isDark) 120 else 30
                    maskFilter = BlurMaskFilter(dropBlur, BlurMaskFilter.Blur.NORMAL)
                },
            )
            native.restore()

            native.save()
            native.clipPath(clip)

            // ---------- 3) 底部内阴影 ----------
            // 玻璃是有厚度的：下缘会积一点暗，上缘则被光打亮。
            // 没有这一层，玻璃就是一张均匀的贴纸。
            native.drawRect(
                0f, h * 0.62f, w, h,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(
                        0f, h * 0.62f, 0f, h,
                        android.graphics.Color.TRANSPARENT,
                        (if (palette.isDark) Color(0xFF000000) else Color(0xFF243052)).toArgb(),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                    alpha = if (palette.isDark) 70 else 16
                },
            )
            native.restore()

            // ---------- 4) 边缘轮廓 ----------
            // 参考图的卡片**几乎没有白描边**：只有一圈极淡的、从左上传到右下渐隐的轮廓线。
            // 之前画了一条接近纯白的顶边，整张卡看起来像「描边盒子」而不是玻璃。
            // 这里把亮度压到很低，并让轮廓在下半部直接消失。
            val rimWidth = with(density) { 1.dp.toPx() }
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = if (palette.isDark) 0.26f else 0.46f),
                        0.35f to Color.White.copy(alpha = if (palette.isDark) 0.13f else 0.24f),
                        0.72f to Color.White.copy(alpha = if (palette.isDark) 0.04f else 0.08f),
                        1.00f to Color.Transparent,
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.55f, h),
                ),
                topLeft = Offset(rimWidth / 2f, rimWidth / 2f),
                size = Size(w - rimWidth, h - rimWidth),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = rimWidth),
            )

            // 顶缘一道极细的提亮：只覆盖左上角一小段弧（约 40% 宽、20% 高），
            // 这是「光正好擦过边」的感觉，而不是给整条顶边刷白。
            native.save()
            native.clipRect(0f, 0f, w * 0.42f, h * 0.22f)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0.00f to Color.White.copy(alpha = if (palette.isDark) 0.34f else 0.60f),
                    0.55f to Color.White.copy(alpha = if (palette.isDark) 0.14f else 0.26f),
                    1.00f to Color.Transparent,
                    startX = 0f,
                    endX = w * 0.42f,
                ),
                topLeft = Offset(rimWidth / 2f, rimWidth / 2f),
                size = Size(w - rimWidth, h - rimWidth),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = rimWidth * 1.1f),
            )
            native.restore()

            // ---------- 3b) 边缘色散（chromatic dispersion）----------
            // 参考图玻璃左下角那道彩虹边，物理上是「不同波长折射率不同」造成的色散。
            // 做法：沿边缘画两条错开的细弧，一条偏青蓝、一条偏品红，
            // 只有左上一小段有，多了就变成 RGB 灯效。
            native.save()
            native.clipPath(clip)
            val dispWidth = with(density) { 1.3f.dp.toPx() }
            // 青蓝分量：略微向内偏
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF7FD8FF).copy(alpha = if (palette.isDark) 0.32f else 0.26f),
                        0.22f to Color(0xFF9BB8FF).copy(alpha = if (palette.isDark) 0.14f else 0.11f),
                        0.42f to Color.Transparent,
                        1.00f to Color.Transparent,
                    ),
                    start = Offset(0f, h),
                    end = Offset(w * 0.62f, 0f),
                ),
                topLeft = Offset(dispWidth * 1.6f, dispWidth * 1.6f),
                size = Size(w - dispWidth * 3.2f, h - dispWidth * 3.2f),
                cornerRadius = CornerRadius(maxOf(0f, r - dispWidth), maxOf(0f, r - dispWidth)),
                style = Stroke(width = dispWidth),
            )
            // 品红分量：略微向外偏，与上一条错开就是彩虹边
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFFFF9AD5).copy(alpha = if (palette.isDark) 0.24f else 0.18f),
                        0.20f to Color(0xFFFFC2A0).copy(alpha = if (palette.isDark) 0.12f else 0.08f),
                        0.40f to Color.Transparent,
                        1.00f to Color.Transparent,
                    ),
                    start = Offset(0f, h),
                    end = Offset(w * 0.60f, 0f),
                ),
                topLeft = Offset(dispWidth * 0.2f, dispWidth * 0.2f),
                size = Size(w - dispWidth * 0.4f, h - dispWidth * 0.4f),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = dispWidth),
            )
            native.restore()

            // ---------- 5) 内壁折射带 ----------
            // 厚玻璃会把光折射进内壁，形成第二道更细的亮边，通常在上半部最明显。
            val innerInset = with(density) { 2.6f.dp.toPx() }
            native.save()
            native.clipRect(0f, 0f, w, h * 0.42f)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0.00f to Color.White.copy(alpha = if (palette.isDark) 0.06f else 0.18f),
                    0.30f to Color.White.copy(alpha = if (palette.isDark) 0.12f else 0.26f),
                    1.00f to Color.Transparent,
                    startX = 0f,
                    endX = w,
                ),
                topLeft = Offset(innerInset, innerInset),
                size = Size(w - innerInset * 2, h - innerInset * 2),
                cornerRadius = CornerRadius(maxOf(0f, r - innerInset), maxOf(0f, r - innerInset)),
                style = Stroke(width = with(density) { 0.9f.dp.toPx() }),
            )
            native.restore()

            // ---------- 6) 雨境联动：玻璃表面的水珠 ----------
            // 只在这个氛围下画。水珠分「静止挂着」和「正在滑落」两种，
            // 滑落的那颗会随时间往下走，滑到底部淡出后重新在顶部生成。
            if (rainy) {
                native.save()
                native.clipPath(clip)

                // 固定水珠：位置由 index 决定，不随帧变化，只有极缓慢的呼吸。
                val staticCount = ((w / 46f).toInt()).coerceIn(3, 11)
                repeat(staticCount) { i ->
                    val bx = (rnd(i, 11) * (w - 16f) + 8f)
                    val by = (rnd(i, 12) * (h - 16f) + 8f)
                    val base = 1.6f + rnd(i, 13) * 3.0f
                    val rh = base * (1.1f + rnd(i, 14) * 0.5f)
                    val breathe = 1f + kotlin.math.sin(dripTime * (0.3f + rnd(i, 15) * 0.25f)) * 0.05f
                    val rw = base * breathe
                    // 水滴本体
                    native.drawOval(
                        bx - rw, by - rh, bx + rw, by + rh,
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            alpha = 46
                        },
                    )
                    // 高光点
                    native.drawCircle(
                        bx - rw * 0.32f, by - rh * 0.34f, rw * 0.36f,
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            alpha = 150
                        },
                    )
                }

                // 滑落的水珠：每颗有自己的周期与起点。
                val dropCount = (w / 130f).toInt().coerceIn(1, 4)
                repeat(dropCount) { i ->
                    val idx = i + 700
                    val sx = rnd(idx, 21) * (w - 24f) + 12f
                    val cycle = 7f + rnd(idx, 22) * 9f
                    val phase = rnd(idx, 23) * cycle
                    val local = ((dripTime * 1.0f + phase) % cycle) / cycle
                    // 用平方让它在顶部慢慢「酝酿」，然后加速下滑。
                    val travel = local * local
                    val sy = travel * (h + 18f) - 9f
                    if (sy < -8f || sy > h + 8f) return@repeat
                    val base = 1.8f + rnd(idx, 24) * 2.4f
                    // 拖尾：越滑越快，尾巴越长。
                    val tail = base * (1.2f + travel * 3.4f)
                    native.drawOval(
                        sx - base, sy - tail, sx + base, sy + base * 0.9f,
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            alpha = (36 * (1f - travel * 0.55f)).toInt().coerceIn(0, 255)
                        },
                    )
                    native.drawCircle(
                        sx - base * 0.3f, sy - tail * 0.5f, base * 0.34f,
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            alpha = (140 * (1f - travel * 0.5f)).toInt().coerceIn(0, 255)
                        },
                    )
                }
                native.restore()
            }
        }
}
