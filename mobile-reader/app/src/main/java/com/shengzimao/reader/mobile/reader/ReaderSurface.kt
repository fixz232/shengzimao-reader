package com.shengzimao.reader.mobile.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.EdgeGesture
import com.shengzimao.reader.mobile.data.Highlight
import com.shengzimao.reader.mobile.data.HighlightColors
import com.shengzimao.reader.mobile.data.PageAnimation
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.TapTarget
import com.shengzimao.reader.mobile.data.TurnMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs

/** 一页的绘制参数：全文区间、首行缩进标记与行高。 */
class PageFrame(val start: Int, val end: Int, val indent: Float, val lineHeight: Float)

/** 分页和绘制共同避让页脚，跟随系统字体比例。 */
@Composable
fun readerFooterHeight(showFooter: Boolean): Dp = with(LocalDensity.current) {
    if (showFooter) 16.sp.toDp() + 8.dp else 0.dp
}

/** 正文选中态：全文字符区间。 */
data class TextSelection(val start: Int, val end: Int) {
    val length: Int get() = end - start
    val isActive: Boolean get() = length > 0
    fun ordered(): TextSelection = if (start <= end) this else TextSelection(end, start)
}

@Composable
fun rememberPageStyle(
    settings: ReaderSettings,
    family: androidx.compose.ui.text.font.FontFamily,
): TextStyle = remember(
    family,
    settings.fontSize,
    settings.lineSpacingPercent,
    settings.fontBold,
    settings.indentParagraphs,
) {
    TextStyle(
        fontFamily = family,
        fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineSpacingPercent / 100f).sp,
        fontWeight = if (settings.fontBold) FontWeight.Medium else FontWeight.Normal,
        textIndent = if (settings.indentParagraphs) TextIndent(firstLine = (settings.fontSize * 2).sp) else TextIndent.None,
    )
}

/**
 * 阅读区域：正文按页区间绘制。
 *
 * 交互（按 `settings.turnMode`）：
 * - TAP：点按分热区翻页/呼出菜单，左右（或上下）滑动同样翻页；长按选字。
 * - SCROLL：竖直拖动带动正文，松手吸附到最近一页；点按中间呼出菜单。
 * - NONE：只保留点按呼出菜单，翻页交给音量键。
 */
@Composable
fun ReaderSurface(
    modifier: Modifier,
    text: String,
    frames: List<PageFrame>,
    pageIndex: Int,
    settings: ReaderSettings,
    progressText: String,
    chapterText: String,
    theHighlights: List<Highlight>,
    searchHits: List<Offset>,
    selection: TextSelection?,
    ttsRange: IntRange?,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTapCenter: () -> Unit,
    onSelectStart: (offset: Int) -> Unit,
    onSelectionExtend: (TextSelection) -> Unit,
    onSeekPage: (Int) -> Unit,
    onEdgeSwipe: (delta: Float) -> Unit,
    pageHint: String?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val style = rememberPageStyle(settings, fontFamily)
    val textColor = Color(settings.pageForeground)
    val footerColor = textColor.copy(alpha = 0.60f)
    val background = Color(settings.pageBackground)
    val footerHeight = readerFooterHeight(settings.showFooter)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val textOrigin = with(density) { Offset(contentPadding.calculateLeftPadding(direction).toPx(), contentPadding.calculateTopPadding().toPx()) }
    val latestTextOrigin by rememberUpdatedState(textOrigin)
    val paginated = frames.isNotEmpty()
    val safePage = if (paginated) pageIndex.coerceIn(0, frames.lastIndex) else 0
    val frame = if (paginated) frames[safePage] else PageFrame(0, text.length, 0f, 0f)

    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    var turnAnimation by remember { mutableStateOf<Job?>(null) }
    val scrollOffset = remember { Animatable(0f) }
    val pageCount = frames.size

    // 手势检测器只建一次，回调通过 rememberUpdatedState 取最新值，避免闭包捕获旧状态。
    val latestPrev by rememberUpdatedState(onPrev)
    val latestNext by rememberUpdatedState(onNext)
    val latestCenter by rememberUpdatedState(onTapCenter)
    val latestLongPressAt by rememberUpdatedState(onSelectStart)
    val latestSelectionChange by rememberUpdatedState(onSelectionExtend)
    val latestAnimation by rememberUpdatedState(settings.pageAnimation)
    val latestVertical by rememberUpdatedState(settings.turnMode != TurnMode.HORIZONTAL && settings.tapZone.isVertical())
    val latestTurnMode by rememberUpdatedState(settings.turnMode)
    val latestEdge by rememberUpdatedState(settings.edgeGesture)
    val latestOnSeek by rememberUpdatedState(onSeekPage)
    val latestOnEdgeSwipe by rememberUpdatedState(onEdgeSwipe)
    val latestPageCount by rememberUpdatedState(pageCount)
    val latestPage by rememberUpdatedState(safePage)

    // 拖动选字时用的当前页布局（绘制与命中共用同一份布局结果）。
    var layout by remember(frame, style, textColor) { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableStateOf(Size.Zero) }

    // 滚动模式下进入新页时，把偏移对齐到该页，避免手势与程序翻页打架。
    LaunchedEffect(safePage, settings.turnMode) {
        if (settings.turnMode == TurnMode.SCROLL && viewport.height > 0f) {
            val target = -safePage * viewport.height
            if (abs(scrollOffset.value - target) > 1f) {
                scrollOffset.snapTo(target)
            }
        }
    }

    fun animateTurn(direction: Int, commit: () -> Unit) {
        // 先提交阅读位置，动画只负责呈现；连续手势取消旧动画时不能取消这次翻页。
        turnAnimation?.cancel()
        commit()
        if (latestAnimation == PageAnimation.NONE) {
            turnAnimation = scope.launch { slide.snapTo(0f) }
            return
        }
        val duration = when (latestAnimation) {
            PageAnimation.FADE -> 190
            PageAnimation.SIMULATE -> 300
            else -> 250
        }
        val from = if (latestAnimation == PageAnimation.COVER) -direction.toFloat() else direction.toFloat()
        turnAnimation = scope.launch {
            slide.snapTo(from)
            slide.animateTo(0f, tween(duration, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .semantics {
                contentDescription = "第 ${safePage + 1} 页，共 $pageCount 页。" + text.substring(frame.start.coerceIn(0, text.length), frame.end.coerceIn(frame.start.coerceIn(0, text.length), text.length))
                customActions = listOf(
                    CustomAccessibilityAction("上一页") { latestPrev(); true },
                    CustomAccessibilityAction("下一页") { latestNext(); true },
                    CustomAccessibilityAction("阅读菜单") { latestCenter(); true },
                )
            }
            .pointerInput(Unit) {
                var downTime = 0L
                // 用系统长按时长，而不是写死 420ms：不同设备/无障碍设置下用户的预期不同。
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                var longPressFired = false
                var startSelection = 0
                var dragging = false
                var edgeDragging = false
                var consumed = false

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    viewport = Size(width, height)
                    downTime = System.currentTimeMillis()
                    longPressFired = false
                    dragging = false
                    consumed = false
                    // 边缘手势：只取最外侧一条窄带。原先左右各 16%（竖屏约 58dp）会与
                    // Android 系统返回手势热区（单侧约 20dp）重叠，而且会把正常翻页滑动吞掉。
                    edgeDragging = latestTurnMode != TurnMode.HORIZONTAL && latestEdge != EdgeGesture.NONE &&
                        (down.position.x < width * EDGE_ZONE_FRACTION || down.position.x > width * (1f - EDGE_ZONE_FRACTION))

                    // 自己计时长按：避免 detectTapGestures 的拖动监听与滑动翻页抢手势。
                    var change: PointerInputChange = down
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val move = event.changes.firstOrNull { it.id == down.id } ?: break
                        change = move
                        if (!move.pressed) {
                            pressed = false
                            break
                        }
                        val total = move.position - down.position
                        if (!longPressFired && !edgeDragging &&
                            System.currentTimeMillis() - downTime > longPressTimeout &&
                            total.getDistance() < viewConfiguration.touchSlop
                        ) {
                            longPressFired = true
                            val current = layout
                            if (current != null && current.layoutInput.text.isNotEmpty()) {
                                startSelection = runCatching {
                                    current.getOffsetForPosition(move.position - latestTextOrigin)
                                }.getOrDefault(0)
                                latestLongPressAt(startSelection)
                            }
                        }
                        if (!dragging && total.getDistance() > viewConfiguration.touchSlop * 1.2f) {
                            dragging = true
                        }
                        if (dragging && !longPressFired && (latestTurnMode == TurnMode.HORIZONTAL || latestTurnMode == TurnMode.TAP) &&
                            abs(total.x) > abs(total.y) * 1.15f) move.consume()
                        // 长按后拖动：扩展选区（只在本页内，跨页由翻页完成后再选）。
                        if (longPressFired && dragging) {
                            val current = layout
                            if (current != null) {
                                val end = runCatching { current.getOffsetForPosition(move.position - latestTextOrigin) }.getOrDefault(startSelection)
                                latestSelectionChange(TextSelection(startSelection, end))
                                consumed = true
                            }
                        }
                    }

                    if (consumed) return@awaitEachGesture
                    val totalDx = change.position.x - down.position.x
                    val totalDy = change.position.y - down.position.y
                    val elapsed = System.currentTimeMillis() - downTime
                    val moved = kotlin.math.hypot(totalDx, totalDy)

                    // 边缘拖动：只处理横向位移，用于调节亮度。
                    if (edgeDragging && abs(totalDx) > 8f && abs(totalDx) > abs(totalDy)) {
                        latestOnEdgeSwipe(totalDx / width)
                        return@awaitEachGesture
                    }

                    if (longPressFired) return@awaitEachGesture

                    val mode = latestTurnMode
                    val allowH = mode == TurnMode.TAP || mode == TurnMode.HORIZONTAL
                    val allowV = mode == TurnMode.SCROLL
                    val swipe = SwipeClassifier.decide(
                        totalDx, totalDy, width, height, allowH, allowV,
                        touchSlopPx = viewConfiguration.touchSlop,
                    )
                    when (swipe) {
                        SwipeClassifier.Result.NEXT -> {
                            animateTurn(1) { latestNext() }
                            return@awaitEachGesture
                        }
                        SwipeClassifier.Result.PREV -> {
                            animateTurn(-1) { latestPrev() }
                            return@awaitEachGesture
                        }
                        SwipeClassifier.Result.SCROLL -> {
                            if (latestPageCount > 1) {
                                val base = -latestPage * height + totalDy
                                val fraction = ScrollMath.fractionOf(-base, height, latestPageCount)
                                val target = ScrollMath.snapPage(fraction, latestPageCount)
                                latestOnSeek(target)
                                scope.launch { scrollOffset.snapTo(-target * height) }
                            }
                            return@awaitEachGesture
                        }
                        SwipeClassifier.Result.NONE -> Unit
                    }

                    // 判定为点按：位移与时长都在容差内才触发。
                    if (moved < viewConfiguration.touchSlop * 1.5f && elapsed < 600) {
                        val target = TapClassifier.classify(
                            point = down.position,
                            width = width,
                            height = height,
                            vertical = latestVertical,
                            edge = 0.32f,
                        )
                        when (target) {
                            TapTarget.PREV -> if (allowH) animateTurn(-1) { latestPrev() } else latestCenter()
                            TapTarget.NEXT -> if (allowH) animateTurn(1) { latestNext() } else latestCenter()
                            TapTarget.MENU -> latestCenter()
                        }
                    }
                }
            },
    ) {
        val offset = slide.value
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .padding(bottom = footerHeight)
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (settings.pageAnimation == PageAnimation.SIMULATE || settings.pageAnimation == PageAnimation.COVER || settings.pageAnimation == PageAnimation.SLIDE) {
                        offset * size.width
                    } else {
                        0f
                    }
                    translationY = if (settings.turnMode == TurnMode.SCROLL) scrollOffset.value else 0f
                    rotationY = if (settings.pageAnimation == PageAnimation.SIMULATE) offset * 6f else 0f
                    alpha = when (settings.pageAnimation) {
                        PageAnimation.FADE -> 1f - abs(offset) * 0.85f
                        else -> 1f - abs(offset) * 0.22f
                    }.coerceIn(0.08f, 1f)
                }
                .drawBehind {
                    val pageStyle = style.copy(color = textColor)
                    val result = measurePage(measurer, text, frame, pageStyle, size.width.toInt())
                    layout = result
                    if (result == null) return@drawBehind
                    drawHighlights(text, frame, result, theHighlights, searchHits, settings.pageForeground)
                    selection?.let { drawSelection(result, it) }
                    ttsRange?.let { if (settings.ttsHighlight) drawTtsRange(result, it) }
                    clipRect { drawText(result) }
                },
        )
        // 仿真翻页的折角阴影：跟随位移方向，盖在正文之上。
        if (settings.pageAnimation == PageAnimation.SIMULATE && offset != 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = if (offset > 0f) size.width * 0.92f else -size.width * 0.02f }
                    .drawBehind {
                        val strength = 0.30f * abs(offset)
                        val brush = if (offset > 0f) {
                            Brush.horizontalGradient(0f to Color.Black.copy(alpha = strength), 1f to Color.Transparent)
                        } else {
                            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = strength))
                        }
                        drawRect(brush, size = Size(size.width * 0.08f, size.height))
                    },
            )
        }
        if (settings.showFooter) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(contentPadding)
                    .height(footerHeight)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = chapterText,
                    color = footerColor,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = progressText, color = footerColor, fontSize = 11.sp, maxLines = 1)
            }
        }
        // 翻页位置浮层：顶部短暂显示“第 3 章 · 42%”。
        if (pageHint != null) {
            Text(
                text = pageHint,
                color = textColor.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            )
        }
    }
}

/**
 * 绘制一页。缩进统一交给样式的 TextIndent：分页与绘制使用同一份样式，
 * 因此不会再出现「分页按 2 个字收窄、绘制按 2 sp 缩进」的口径差。
 */
private fun measurePage(
    measurer: TextMeasurer,
    text: String,
    frame: PageFrame,
    style: TextStyle,
    maxWidth: Int,
): TextLayoutResult? {
    if (text.isEmpty() || maxWidth <= 0) return null
    val start = frame.start.coerceIn(0, text.length)
    val end = frame.end.coerceIn(start, text.length)
    if (start == end) return null
    return runCatching {
        measurer.measure(
            text = Paginator.pageText(text, start, end),
            style = style,
            constraints = Constraints(maxWidth = maxWidth),
        )
    }.getOrNull()
}

/** 把划线与搜索命中画成底色块。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHighlights(
    text: String,
    frame: PageFrame,
    result: TextLayoutResult,
    marks: List<Highlight>,
    hits: List<Offset>,
    foreground: Long,
) {
    val pageStart = frame.start
    val pageEnd = frame.end
    marks.forEach { mark ->
        val s = mark.start.coerceIn(0, text.length)
        val e = mark.end.coerceIn(0, text.length)
        val from = maxOf(s, pageStart) - pageStart
        val to = minOf(e, pageEnd) - pageStart
        if (to <= from) return@forEach
        val color = Color(HighlightColors.argb(mark.colorIndex, if (mark.isNote) 0x55 else 0x4D))
        drawRange(result, from, to, color)
    }
    if (hits.isNotEmpty()) {
        hits.forEach { hit ->
            val startOffset = maxOf(hit.x.toInt(), pageStart) - pageStart
            val endOffset = minOf(hit.y.toInt(), pageEnd) - pageStart
            if (endOffset <= startOffset) return@forEach
            drawRange(result, startOffset, endOffset, Color(foreground).copy(alpha = 0.18f))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRange(
    result: TextLayoutResult,
    from: Int,
    to: Int,
    color: Color,
) {
    val textLength = result.layoutInput.text.length
    val start = from.coerceIn(0, textLength)
    val end = to.coerceIn(0, textLength)
    if (end <= start) return
    val path = runCatching { result.getPathForRange(start, end) }.getOrNull()
    if (path != null) drawPath(path, color) else Unit
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelection(result: TextLayoutResult, selection: TextSelection) {
    val ordered = selection.ordered()
    val length = result.layoutInput.text.length
    if (length == 0) return
    val from = ordered.start.coerceIn(0, length)
    val to = ordered.end.coerceIn(0, length)
    if (to <= from) return
    drawRange(result, from, to, Color(0xFF3B82F6).copy(alpha = 0.32f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTtsRange(result: TextLayoutResult, range: IntRange) {
    val length = result.layoutInput.text.length
    if (length == 0) return
    val from = range.first.coerceIn(0, length)
    val to = range.last.coerceIn(0, length)
    if (to <= from) return
    drawRange(result, from, to, Color(0xFF3B82F6).copy(alpha = 0.14f))
}

/** 把本地（页内）偏移换算为当前页内可绘制的相对区间。 */
fun PageFrame.clampToPage(start: Int, end: Int): Pair<Int, Int> =
    (maxOf(start, this.start) - this.start).coerceIn(0, this.end - this.start) to
        (minOf(end, this.end) - this.start).coerceIn(0, this.end - this.start)

/** 取出选中的原文。 */
fun selectedText(text: String, selection: TextSelection): String {
    val ordered = selection.ordered()
    val start = ordered.start.coerceIn(0, text.length)
    val end = ordered.end.coerceIn(0, text.length)
    return if (end <= start) "" else text.substring(start, end)
}

/**
 * 左右边缘手势带宽度（占屏宽比例）。
 *
 * 0.05f 在 360dp 竖屏上约 18dp，略小于 Android 系统返回手势热区（单侧约 20dp），
 * 这样边缘拖动调亮度不会被系统全部吃掉，同时也不会像旧值 16%（约 58dp）那样
 * 把正常的左右滑动翻页整段吞掉。只在「边缘拖动」判定里使用。
 */
private const val EDGE_ZONE_FRACTION = 0.05f
