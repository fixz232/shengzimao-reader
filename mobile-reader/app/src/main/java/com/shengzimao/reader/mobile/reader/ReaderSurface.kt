package com.shengzimao.reader.mobile.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.FontChoice
import com.shengzimao.reader.mobile.data.PageAnimation
import com.shengzimao.reader.mobile.data.ReaderSettings
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** 一页的绘制参数：全文区间、首行缩进（用于约束宽度）与行高。 */
class PageFrame(val start: Int, val end: Int, val indent: Float, val lineHeight: Float)

@Composable
fun rememberPageStyle(settings: ReaderSettings): TextStyle {
    val family = when (settings.font) {
        FontChoice.SYSTEM_SANS -> FontFamily.SansSerif
        FontChoice.SYSTEM_SERIF -> FontFamily.Serif
        FontChoice.SYSTEM_MONO -> FontFamily.Monospace
    }
    return remember(settings.font, settings.fontSize, settings.lineSpacingPercent, settings.fontBold, settings.indentParagraphs) {
        TextStyle(
            fontFamily = family,
            fontSize = settings.fontSize.sp,
            lineHeight = (settings.fontSize * settings.lineSpacingPercent / 100f).sp,
            fontWeight = if (settings.fontBold) FontWeight.Medium else FontWeight.Normal,
            textIndent = if (settings.indentParagraphs) TextIndent(firstLine = 2.sp) else TextIndent.None,
        )
    }
}

/**
 * 阅读区域：正文按页区间绘制；左右三分之一点按翻页，中间打开菜单，长按放书签。
 * 正文用 Canvas 文本布局绘制，避免每页重组大量 Text 节点带来的测序开销。
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
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onTapCenter: () -> Unit,
    onLongPress: () -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val style = rememberPageStyle(settings)
    val textColor = Color(settings.theme.onBackground)
    val footerColor = textColor.copy(alpha = 0.62f)
    val background = Color(settings.theme.background)
    val paginated = frames.isNotEmpty()
    val safePage = if (paginated) pageIndex.coerceIn(0, frames.lastIndex) else 0
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    val indentPx = with(density) { (settings.fontSize * 2).toFloat() }
    val frame = if (paginated) frames[safePage] else PageFrame(0, text.length, 0f, lineHeightPx)

    val widthState = remember { mutableIntStateOf(1) }
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 手势检测器只建一次，回调通过 rememberUpdatedState 取最新值，避免闭包捕获旧状态。
    val latestNext by rememberUpdatedState(onNext)
    val latestPrev by rememberUpdatedState(onPrev)
    val latestCenter by rememberUpdatedState(onTapCenter)
    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestFrames by rememberUpdatedState(frames)
    val latestPage by rememberUpdatedState(pageIndex)
    val latestAnimation by rememberUpdatedState(settings.pageAnimation)

    LaunchedEffect(paginated) {
        if (!paginated) slide.snapTo(0f)
    }

    fun turn(direction: Int) {
        val canTurn = paginated && (if (direction > 0) safePage < frames.lastIndex else safePage > 0)
        if (!canTurn) {
            if (direction > 0) onNext() else onPrev()
            return
        }
        if (settings.pageAnimation == PageAnimation.NONE) {
            if (direction > 0) onNext() else onPrev()
            return
        }
        val duration = when (settings.pageAnimation) {
            PageAnimation.FADE -> 200
            else -> 260
        }
        val from = if (settings.pageAnimation == PageAnimation.COVER) -direction.toFloat() else direction.toFloat()
        scope.launch {
            slide.snapTo(from)
            slide.animateTo(0f, tween(duration, easing = FastOutSlowInEasing))
        }
        if (direction > 0) onNext() else onPrev()
    }

    val offset = slide.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .onSizeChanged { widthState.intValue = max(1, it.width) }
            .semantics { contentDescription = "阅读区域" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { point ->
                        val width = size.width.toFloat()
                        val count = latestFrames.size
                        val page = latestPage
                        val canPrev = count > 0 && page > 0
                        val canNext = count > 0 && page < count - 1
                        when {
                            point.x < width * 0.32f -> if (canPrev) latestPrev() else latestPrev()
                            point.x > width * 0.68f -> if (canNext) latestNext() else latestNext()
                            else -> latestCenter()
                        }
                    },
                    onLongPress = { latestLongPress() },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset * size.width
                    rotationY = if (settings.pageAnimation == PageAnimation.SIMULATE) offset * 8f else 0f
                    alpha = if (offset == 0f) 1f else (1f - abs(offset) * 0.28f).coerceAtLeast(0.25f)
                }
                .drawBehind {
                    val layout = measurePage(measurer, text, frame, style.copy(color = textColor), size.width.toInt())
                    layout?.let { drawText(it) }
                },
        )
        if (settings.pageAnimation == PageAnimation.SIMULATE && offset != 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = if (offset > 0f) size.width * 0.9f else 0f }
                    .drawBehind {
                        val brush = Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.18f * abs(offset)),
                            1f to Color.Transparent,
                        )
                        drawRect(brush, size = Size(size.width * 0.1f, size.height))
                    },
            )
        }
        // 页脚固定在底部：左侧章节名、右侧页码，不参与正文排版区域计算（留白由页面高度决定）。
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = chapterText,
                color = footerColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = progressText, color = footerColor, fontSize = 11.sp, maxLines = 1)
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
): androidx.compose.ui.text.TextLayoutResult? {
    if (text.isEmpty() || maxWidth <= 0) return null
    val start = frame.start.coerceIn(0, text.length)
    val end = frame.end.coerceIn(start, text.length)
    if (start == end) return null
    return runCatching {
        measurer.measure(
            text = text.substring(start, end),
            style = style,
            constraints = Constraints(maxWidth = maxWidth),
        )
    }.getOrNull()
}
