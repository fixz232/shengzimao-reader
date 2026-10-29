package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.ui.theme.Atmosphere
import com.shengzimao.reader.mobile.ui.theme.LocalAurora
import com.shengzimao.reader.mobile.ui.theme.LocalAtmosphere
import com.shengzimao.reader.mobile.ui.theme.rnd

/**
 * 封面装饰。
 *
 * 让书架的书与漫库的漫画封面**跟随当前氛围**变化：
 * - 雨境：封面挂水珠、底部积水涟漪；
 * - 雪落 / 冬境：角落积雪、霜花；
 * - 春境：樱花瓣落在封面角落；
 * - 夏境：右上阳光斑 + 萤点；
 * - 秋境：落叶卡在封面边缘；
 * - 雾晨 / 伤感：一层薄雾；
 * - 晴朗：一圈暖色光环；
 * - 热烈：底部余烬微光。
 *
 * 做法是在封面**之上**叠一层很薄的装饰，不改封面本身的配色，
 * 所以书封依然是可辨识的彩色渐变，只是多了季节/天气的「在场感」。
 */
@Composable
fun CoverDecoration(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    cornerRadius: Int = 16,
) {
    val atmosphere = LocalAtmosphere.current
    val palette = LocalAurora.current
    // NONE 时什么也不画，保持封面干净。
    if (atmosphere == Atmosphere.NONE) return

    val clock = rememberInfiniteTransition(label = "coverDeco")
    val time by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "coverDecoTime",
    )
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius.dp) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .drawWithContent {
                // 先画封面本身，再叠装饰，保证装饰永远在最上面。
                drawContent()
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@drawWithContent
                val t = time * atmosphere.speed * 0.55f
                val dark = palette.isDark

                when (atmosphere) {
                    Atmosphere.RAINY -> {
                        // 水珠：位置由 seed 决定，每本书不一样，不会整齐划一。
                        repeat(7) { i ->
                            val idx = seed * 31 + i
                            val bx = rnd(idx, 201) * w
                            val by = rnd(idx, 202) * h
                            val rr = (1.4f + rnd(idx, 203) * 2.4f) * (w / 110f).coerceIn(0.7f, 1.5f)
                            val breathe = 1f + kotlin.math.sin(t * (0.4f + rnd(idx, 204) * 0.3f)) * 0.06f
                            drawCircle(
                                color = Color.White.copy(alpha = 0.22f),
                                radius = rr * breathe * 1.5f,
                                center = Offset(bx, by),
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.55f),
                                radius = rr * 0.42f,
                                center = Offset(bx - rr * 0.3f, by - rr * 0.35f),
                            )
                        }
                        // 底部积水：一条很淡的亮边 + 涟漪
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.White.copy(alpha = if (dark) 0.10f else 0.16f)),
                                startY = h * 0.72f,
                                endY = h,
                            ),
                        )
                        repeat(2) { i ->
                            val cycle = 5f + rnd(seed, 210 + i) * 3f
                            val p = ((t * 0.5f + rnd(seed, 220 + i) * cycle) % cycle) / cycle
                            val cx = rnd(seed, 230 + i) * w
                            val rx = (6f + p * 22f) * (w / 110f)
                            drawOval(
                                color = Color.White.copy(alpha = 0.16f * (1f - p)),
                                topLeft = Offset(cx - rx, h * 0.93f - rx * 0.24f),
                                size = Size(rx * 2f, rx * 0.48f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                            )
                        }
                    }

                    Atmosphere.SNOWY, Atmosphere.WINTER -> {
                        // 角落积雪：左上与右下两团柔白。
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                                center = Offset(0f, 0f),
                                radius = w * 0.85f,
                            ),
                            radius = w * 0.85f,
                            center = Offset(0f, 0f),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                                center = Offset(w, h),
                                radius = w * 0.8f,
                            ),
                            radius = w * 0.8f,
                            center = Offset(w, h),
                        )
                        // 霜花：几根放射短线
                        repeat(5) { i ->
                            val idx = seed * 17 + i
                            val cx = rnd(idx, 240) * w
                            val cy = rnd(idx, 241) * h
                            val p = 0.4f + 0.6f * ((kotlin.math.sin(t * (0.3f + rnd(idx, 242) * 0.4f)) + 1f) / 2f)
                            val rr = (2.5f + rnd(idx, 243) * 3f) * (w / 110f)
                            val col = Color.White.copy(alpha = (if (dark) 0.34f else 0.55f) * p)
                            repeat(3) { k ->
                                val a = (k * 60f) * (kotlin.math.PI / 180f).toFloat()
                                drawLine(
                                    color = col,
                                    start = Offset(cx - kotlin.math.cos(a) * rr, cy - kotlin.math.sin(a) * rr),
                                    end = Offset(cx + kotlin.math.cos(a) * rr, cy + kotlin.math.sin(a) * rr),
                                    strokeWidth = 0.9f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }

                    Atmosphere.SPRING -> {
                        // 花瓣：几片固定在封面，缓慢摆动。
                        // 注意要用「浅粉 + 白」，不能用 palette.accent（那是深玫红，
                        // 压在青绿封面上会变成脏紫灰）。
                        repeat(5) { i ->
                            val idx = seed * 13 + i
                            val bx = rnd(idx, 250) * w
                            val by = rnd(idx, 251) * h
                            val s = (2.6f + rnd(idx, 252) * 2.6f) * (w / 110f)
                            val sway = kotlin.math.sin(t * (0.6f + rnd(idx, 253)) + rnd(idx, 254) * 6.28f) * 2.4f
                            val tint = when {
                                i % 3 == 0 -> Color(0xFFFFD9E8)
                                i % 3 == 1 -> Color(0xFFFFC2DC)
                                else -> Color(0xFFEFFAD9)
                            }
                            drawOval(
                                color = tint.copy(alpha = if (dark) 0.62f else 0.80f),
                                topLeft = Offset(bx - s, by + sway - s * 0.6f),
                                size = Size(s * 2f, s * 1.2f),
                            )
                        }
                        // 左下角一抹嫩绿，提示「新芽」。
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFFBFE8A0).copy(alpha = if (dark) 0.20f else 0.34f), Color.Transparent),
                                center = Offset(0f, h),
                                radius = w * 0.75f,
                            ),
                            radius = w * 0.75f,
                            center = Offset(0f, h),
                        )
                    }

                    Atmosphere.SUMMER -> {
                        // 右上阳光：一圈暖光 + 几点萤火。
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFFFFE08A).copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(w, 0f),
                                radius = w * 0.9f,
                            ),
                            radius = w * 0.9f,
                            center = Offset(w, 0f),
                        )
                        repeat(4) { i ->
                            val idx = seed * 19 + i
                            val bx = rnd(idx, 260) * w
                            val by = rnd(idx, 261) * h
                            val p = 0.4f + 0.6f * ((kotlin.math.sin(t * (1f + rnd(idx, 262)) + rnd(idx, 263) * 6.28f) + 1f) / 2f)
                            val rr = (1.2f + rnd(idx, 264) * 1.4f) * (w / 110f)
                            drawCircle(Color(0xFFB7EA8A).copy(alpha = 0.22f * p), rr * 3f, Offset(bx, by))
                            drawCircle(Color(0xFFE8FFC4).copy(alpha = 0.75f * p), rr, Offset(bx, by))
                        }
                    }

                    Atmosphere.AUTUMN -> {
                        // 落叶：三片卡在封面上，缓慢翻转。同样用固定暖色而不是主题强调色，
                        // 因为强调色在深色主题下会发暗。
                        repeat(3) { i ->
                            val idx = seed * 23 + i
                            val bx = rnd(idx, 270) * w
                            val by = rnd(idx, 271) * h
                            val s = (3f + rnd(idx, 272) * 3f) * (w / 110f)
                            val angle = t * (20f + rnd(idx, 273) * 30f) + rnd(idx, 274) * 360f
                            val tint = when (i % 3) {
                                0 -> Color(0xFFFFB870)
                                1 -> Color(0xFFE8734A)
                                else -> Color(0xFFFFD98F)
                            }
                            rotate(angle, Offset(bx, by)) {
                                drawOval(
                                    color = tint.copy(alpha = if (dark) 0.66f else 0.82f),
                                    topLeft = Offset(bx - s, by - s * 0.55f),
                                    size = Size(s * 2f, s * 1.1f),
                                )
                            }
                        }
                    }

                    Atmosphere.MISTY, Atmosphere.MELANCHOLY -> {
                        // 薄雾：一条横向柔白，缓慢左右漂。
                        val shift = (kotlin.math.sin(t * 0.25f) + 1f) / 2f
                        drawOval(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (dark) 0.08f else 0.22f),
                                    Color.Transparent,
                                ),
                            ),
                            topLeft = Offset(-w * 0.3f + shift * w * 0.4f, h * 0.42f),
                            size = Size(w * 1.2f, h * 0.34f),
                        )
                    }

                    Atmosphere.SUNNY -> {
                        // 暖色光环：从左上角扫过。
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFFFFE0A0).copy(alpha = 0.40f), Color.Transparent),
                                center = Offset(w * 0.15f, h * 0.12f),
                                radius = w * 0.95f,
                            ),
                            radius = w * 0.95f,
                            center = Offset(w * 0.15f, h * 0.12f),
                        )
                    }

                    Atmosphere.FURIOUS -> {
                        // 底部余烬微光。
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, palette.accent.copy(alpha = 0.28f)),
                                startY = h * 0.70f,
                                endY = h,
                            ),
                        )
                    }

                    Atmosphere.NONE -> Unit
                }

                // 所有氛围都加一层极淡压暗，避免封面被装饰冲淡后文字读不清。
                drawRect(color = Color.Black.copy(alpha = 0.04f))
            },
    )
}
