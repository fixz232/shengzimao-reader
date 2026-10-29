package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 在线按章取文，按真实视口排版；翻页仍复用离线小说的手势与绘制。 */
@Composable
internal fun OnlinePagedReader(
    text: String, title: String, settings: ReaderSettings, family: FontFamily,
    initialOffset: Int, seek: Pair<Int, Int>?, turnRequest: Int, query: String,
    onPosition: (Int) -> Unit, onBoundary: (Int) -> Unit, onMenu: () -> Unit,
    onNote: (Int, String) -> Unit,
    onUserTurn: () -> Unit = {},
    speechRange: IntRange? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val style = rememberPageStyle(settings, family)
    var map by remember(text) { mutableStateOf<PageMap?>(null) }
    var frames by remember(text) { mutableStateOf<List<PageFrame>>(emptyList()) }
    var page by remember(text) { mutableIntStateOf(0) }
    var failure by remember(text) { mutableStateOf("") }
    var measuring by remember(text) { mutableStateOf(true) }
    var consumedTurn by remember(text) { mutableIntStateOf(turnRequest) }
    var consumedSeek by remember(text) { mutableStateOf<Int?>(null) }
    var pendingTurn by remember(text) { mutableIntStateOf(0) }
    val latestPosition by rememberUpdatedState(onPosition)
    val latestBoundary by rememberUpdatedState(onBoundary)
    val hits = remember(text, query) {
        if (query.isBlank()) emptyList() else buildList {
            var cursor = text.indexOf(query, ignoreCase = true)
            while (cursor >= 0 && size < 2000) {
                add(Offset(cursor.toFloat(), (cursor + query.length).toFloat()))
                cursor = text.indexOf(query, cursor + query.length, ignoreCase = true)
            }
        }
    }
    val footerHeight = readerFooterHeight(settings.showFooter)
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val width = with(density) { (maxWidth - settings.paddingDp.dp * 2).roundToPx() }.coerceAtLeast(1)
        val height = with(density) { (maxHeight - 32.dp - 28.dp - footerHeight).roundToPx() }.coerceAtLeast(1)
        LaunchedEffect(text, style, width, height) {
            val anchor = map?.pageStart(page) ?: initialOffset
            measuring = true; failure = ""
            try {
                val measured = withContext(Dispatchers.Default) {
                    val starts = Paginator.sliceBoundaries(text).flatMap { range ->
                        currentCoroutineContext().ensureActive()
                        Paginator.paginate(measurer, density, text, style, width, height, range.first, range.last + 1).toList()
                    }.distinct().toIntArray()
                    PageMap(starts, IntArray(starts.size), text.length)
                }
                map = measured; frames = Paginator.frames(measured, text)
                page = measured.pageOf(anchor)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { failure = "本章排版失败：${error.message.orEmpty()}" }
            finally { measuring = false }
        }
        LaunchedEffect(seek, map) {
            if (seek != null && seek.second != consumedSeek) map?.let {
                page = it.pageOf(seek.first); consumedSeek = seek.second
            }
        }
        fun turn(delta: Int) {
            val last = frames.lastIndex
            if (last < 0) return
            if (measuring) { pendingTurn = (pendingTurn + delta).coerceIn(-8, 8); return }
            onUserTurn()
            if (page + delta in 0..last) page += delta else latestBoundary(delta)
        }
        LaunchedEffect(measuring) {
            if (!measuring && pendingTurn != 0) {
                val delta = pendingTurn; pendingTurn = 0
                turn(delta)
            }
        }
        LaunchedEffect(turnRequest, map) {
            val delta = turnRequest - consumedTurn; consumedTurn = turnRequest
            if (delta != 0) turn(delta.coerceIn(-1, 1))
        }
        LaunchedEffect(map, page, measuring) { if (!measuring) map?.let { latestPosition(it.pageStart(page)) } }
        when {
            failure.isNotBlank() -> Text(failure, color = Color(settings.pageForeground), modifier = Modifier.align(Alignment.Center))
            frames.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> ReaderSurface(
                modifier = Modifier.fillMaxSize(), text = text, frames = frames, pageIndex = page,
                settings = settings, progressText = "本章 ${page + 1} / ${frames.size} 页", chapterText = title,
                theHighlights = emptyList(), searchHits = hits, selection = null,
                ttsRange = speechRange?.let { range ->
                    val frame = frames[page.coerceIn(0, frames.lastIndex)]
                    val start = maxOf(range.first, frame.start) - frame.start
                    val end = minOf(range.last + 1, frame.end) - frame.start
                    if (end > start) start until end else null
                }, fontFamily = family,
                onPrev = { turn(-1) }, onNext = { turn(1) }, onTapCenter = onMenu,
                onSelectStart = { offset ->
                    val start = ((map?.pageStart(page) ?: 0) + offset).coerceIn(0, text.length)
                    onNote(start, text.substring(start, minOf(text.length, start + 600)))
                }, onSelectionExtend = {}, onSeekPage = { page = it.coerceIn(0, frames.lastIndex) }, onEdgeSwipe = {}, pageHint = null,
                contentPadding = PaddingValues(start = settings.paddingDp.dp, end = settings.paddingDp.dp, top = 32.dp, bottom = 28.dp),
            )
        }
        if (measuring && frames.isNotEmpty()) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp))
    }
}
