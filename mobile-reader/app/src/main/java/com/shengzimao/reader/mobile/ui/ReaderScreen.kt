package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.AppContainer
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.Chapter
import com.shengzimao.reader.mobile.reader.PageMap
import com.shengzimao.reader.mobile.reader.PageFrame
import com.shengzimao.reader.mobile.reader.Paginator
import com.shengzimao.reader.mobile.reader.ReaderSurface
import com.shengzimao.reader.mobile.reader.rememberPageStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState

/**
 * 阅读页。没有隐蔽模式：版面、配色与交互都按“正式阅读”设计。
 */
@Composable
fun ReaderScreen(
    container: AppContainer,
    viewModel: ReaderViewModel,
    bookId: String,
    navigator: Navigator,
) {
    val libraryState by viewModel.ui.collectAsStateWithLifecycle()
    val settings = libraryState.settings
    var book by remember(bookId) { mutableStateOf<Book?>(null) }
    var text by remember(bookId) { mutableStateOf("") }
    var pageMap by remember(bookId) { mutableStateOf<PageMap?>(null) }
    var frames by remember(bookId) { mutableStateOf<List<PageFrame>>(emptyList()) }
    var pageIndex by remember(bookId) { mutableIntStateOf(0) }
    var menuVisible by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var bookmarkVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paginating by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer(cacheSize = 8)

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val paddingPx = with(density) { settings.paddingDp.dp.toPx() }
    val widthPx = (with(density) { configuration.screenWidthDp.dp.toPx() } - paddingPx * 2).toInt().coerceAtLeast(1)
    val heightPx = (with(density) { configuration.screenHeightDp.dp.toPx() } - paddingPx * 2 - with(density) { 22.dp.toPx() }).toInt().coerceAtLeast(1)

    val style = rememberPageStyle(settings)
    // 只有影响排版的字段变化才需要重排；改动画/朗读参数不该触发整本重排。
    val layoutKey = listOf(
        settings.fontSize,
        settings.lineSpacingPercent,
        settings.font.name,
        settings.fontBold,
        settings.paddingDp,
        settings.indentParagraphs,
        widthPx,
        heightPx,
        text.length,
    )
    val signature = remember(layoutKey) {
        Paginator.signatureFor(
            width = widthPx,
            height = heightPx,
            fontSize = settings.fontSize,
            lineSpacingPercent = settings.lineSpacingPercent,
            fontKey = "${settings.font.name}:${settings.paddingDp}:${settings.indentParagraphs}",
            bold = settings.fontBold,
            indent = settings.indentParagraphs,
            textLength = text.length,
        )
    }

    // 打开书籍：读取正文与章节，失败给出可解释的提示。
    LaunchedEffect(bookId) {
        val loaded = viewModel.openBook(bookId)
        if (loaded == null) {
            error = "正文缺失或无法读取，请回到书库重新导入这本书。"
        } else {
            book = loaded.first
            text = loaded.second
        }
    }

    // 分页：在 Default 线程按切片测量，样式或尺寸变化时重排并落回原位置。
    LaunchedEffect(signature) {
        if (text.isEmpty()) {
            pageMap = null
            frames = emptyList()
            return@LaunchedEffect
        }
        paginating = true
        val anchor = pageMap?.pageStart(pageIndex) ?: book?.position ?: 0
        val chapters = book?.chapters.orEmpty()
        // TextMeasurer 持有 UI 线程绑定的字体解析器，不能在 Default 线程使用；
        // 改为在当前线程分片测量并让出，避免长书阻塞首屏。
        val slices = ArrayList<IntArray>()
        var sliceIndex = 0
        for (range in Paginator.sliceBoundaries(text)) {
            slices.add(Paginator.paginate(measurer, density, text, style, widthPx, heightPx, range.first, range.last + 1))
            if (++sliceIndex % 4 == 0) kotlinx.coroutines.yield()
        }
        val measured = Paginator.assemble(text, chapters, slices)
        pageMap = measured
        frames = Paginator.frames(measured, text)
        pageIndex = measured.pageOf(anchor)
        paginating = false
    }

    // 阅读时长：仅在页面处于 RESUMED 时累加，切后台不计时。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(bookId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(20_000)
                viewModel.recordReading(bookId, 20)
            }
        }
    }

    // 朗读（TTS）：只读当前页；读完自动翻页续读（可在设置里关闭）。
    val context = androidx.compose.ui.platform.LocalContext.current
    val tts = remember(bookId) { com.shengzimao.reader.mobile.reader.ReaderTts(context) }
    val ttsState by tts.state.collectAsState()
    var ttsActive by remember(bookId) { mutableStateOf(false) }

    DisposableEffect(tts) {
        onDispose { tts.release() }
    }

    LaunchedEffect(settings.ttsRate) { tts.setRate(settings.ttsRate) }
    LaunchedEffect(settings.ttsPitch) { tts.setPitch(settings.ttsPitch) }

    val currentMap = pageMap
    val currentChapterTitle: String = run {
        val index = currentMap?.pageChapter?.getOrNull(pageIndex) ?: -1
        val chapters = book?.chapters.orEmpty()
        if (index in chapters.indices) chapters[index].title else book?.title.orEmpty()
    }

    fun speakCurrentPage() {
        val target = currentMap ?: return
        val start = target.pageStart(pageIndex)
        val end = target.pageEnd(pageIndex).coerceAtMost(text.length)
        val page = text.substring(start.coerceIn(0, text.length), end.coerceIn(start, text.length))
            .replace('\n', ' ')
            .replace("　", "")
        if (page.isBlank()) {
            viewModel.message("这一页没有可朗读的文字")
            return
        }
        ttsActive = true
        if (!tts.state.value.ready) tts.ensureReady()
        tts.speak(page)
    }

    fun stopSpeaking() {
        ttsActive = false
        tts.stop()
    }

    // 朗读到页尾：自动翻页并继续朗读。
    DisposableEffect(tts, currentMap) {
        tts.onPageFinished = {
            if (!ttsActive) {
                tts.stop()
            } else if (settings.autoTtsTurnPage) {
                val target = currentMap
                if (target != null && pageIndex < target.pageCount - 1) {
                    pageIndex++
                    speakCurrentPage()
                } else {
                    stopSpeaking()
                }
            } else {
                stopSpeaking()
            }
        }
        onDispose { tts.onPageFinished = null }
    }

    fun toggleSpeaking() {
        if (ttsState.speaking) stopSpeaking() else speakCurrentPage()
    }

    fun closeReader() {
        stopSpeaking()
        pageMap?.let { viewModel.savePosition(bookId, it.pageStart(pageIndex)) }
        navigator.openLibrary()
    }

    BackHandler {
        if (menuVisible) {
            menuVisible = false
        } else {
            closeReader()
        }
    }

    // 音量键翻页：开启后音量下 = 下一页、音量上 = 上一页；未开启时保留系统行为。
    androidx.compose.runtime.DisposableEffect(settings.volumeKeysTurnPages) {
        val activity = (context as? android.app.Activity)
        if (!settings.volumeKeysTurnPages || activity == null) {
            onDispose { }
        } else {
            val listener = object : android.view.View.OnKeyListener {
                override fun onKey(view: android.view.View?, keyCode: Int, event: android.view.KeyEvent?): Boolean {
                    if (event == null || event.action != android.view.KeyEvent.ACTION_DOWN) return false
                    val target = pageMap ?: return false
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            if (pageIndex < target.pageCount - 1) pageIndex++ else viewModel.message("已读到全书结尾")
                            return true
                        }
                        android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                            if (pageIndex > 0) pageIndex-- else viewModel.message("已经是全书开头")
                            return true
                        }
                    }
                    return false
                }
            }
            activity.window.decorView.setOnKeyListener(listener)
            onDispose { activity.window.decorView.setOnKeyListener(null) }
        }
    }

    val theme = settings.theme
    val background = Color(theme.background)
    val onBackground = Color(theme.onBackground)
    val map = pageMap
    val visibleMessage = libraryState.message

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        if (error != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error ?: "", color = onBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { navigator.openLibrary() }) { Text("返回书库") }
            }
            return@Box
        }
        if (map == null || frames.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (paginating) "正在排版…" else "正在打开…", color = onBackground.copy(alpha = 0.75f))
                    Spacer(Modifier.height(8.dp))
                    Text(book?.title.orEmpty(), color = onBackground.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        } else {
            val chapterIndex = map.pageChapter.getOrElse(pageIndex) { -1 }
            val chapterCount = book?.chapters?.size ?: 0
            val chapterTitle = if (chapterIndex >= 0 && chapterIndex < chapterCount) {
                book!!.chapters[chapterIndex].title
            } else {
                book?.title.orEmpty()
            }
            ReaderSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = settings.paddingDp.dp, vertical = settings.paddingDp.dp),
                text = text,
                frames = frames,
                pageIndex = pageIndex,
                settings = settings,
                progressText = "${pageIndex + 1} / ${map.pageCount}",
                chapterText = chapterTitle,
                onNext = {
                    stopSpeaking()
                    if (pageIndex < map.pageCount - 1) {
                        pageIndex++
                    } else {
                        viewModel.message("已读到全书结尾")
                    }
                },
                onPrev = {
                    stopSpeaking()
                    if (pageIndex > 0) pageIndex-- else viewModel.message("已经是全书开头")
                },
                onTapCenter = { menuVisible = !menuVisible },
                onLongPress = {
                    viewModel.addBookmark(bookId, map.pageStart(pageIndex), chapterTitle)
                },
            )

            AnimatedVisibility(visible = menuVisible, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.05f)))
            }
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = menuVisible,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    ReaderTopBar(
                        title = book?.title.orEmpty(),
                        chapter = chapterTitle,
                        theme = theme,
                        onBack = { closeReader() },
                        onCatalog = {
                            catalogVisible = true
                            menuVisible = false
                        },
                        onBookmarks = {
                            bookmarkVisible = true
                            menuVisible = false
                        },
                        onSettings = { menuVisible = true },
                    )
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = menuVisible,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    ReaderBottomBar(
                        settings = settings,
                        pageIndex = pageIndex,
                        pageCount = map.pageCount,
                        theme = theme,
                        onFontSize = { value -> viewModel.updateSettings { s -> s.copy(fontSize = value) } },
                        onTheme = { value -> viewModel.updateSettings { s -> s.copy(theme = value) } },
                        onSettings = {
                            menuVisible = false
                            navigator.openSettings()
                        },
                        onSeek = { fraction ->
                            stopSpeaking()
                            pageIndex = (fraction * (map.pageCount - 1)).toInt().coerceIn(0, map.pageCount - 1)
                        },
                        speaking = ttsState.speaking,
                        ttsError = ttsState.error,
                        onToggleSpeak = { toggleSpeaking() },
                        onPrevChapter = {
                            val chapters = book?.chapters.orEmpty()
                            val current = chapters.lastOrNull { it.position <= map.pageStart(pageIndex) }
                            val target = current?.let { chapter ->
                                val index = chapters.indexOf(chapter)
                                chapters.getOrNull(index - 1)?.position
                            }
                            if (target == null) viewModel.message("已经是第一章") else pageIndex = map.pageOf(target)
                        },
                        onNextChapter = {
                            val chapters = book?.chapters.orEmpty()
                            val target = chapters.firstOrNull { it.position > map.pageStart(pageIndex) }?.position
                            if (target == null) viewModel.message("已经是最后一章") else pageIndex = map.pageOf(target)
                        },
                    )
                }
            }
            if (catalogVisible) {
                CatalogSheet(
                    chapters = book?.chapters.orEmpty(),
                    currentPosition = map.pageStart(pageIndex),
                    theme = theme,
                    onDismiss = { catalogVisible = false },
                    onSelect = { chapter: Chapter ->
                        catalogVisible = false
                        pageIndex = map.pageOf(chapter.position)
                    },
                )
            }
            if (bookmarkVisible) {
                BookmarkSheet(
                    bookmarks = viewModel.currentBook(bookId)?.bookmarks.orEmpty(),
                    theme = theme,
                    onDismiss = { bookmarkVisible = false },
                    onSelect = { mark ->
                        bookmarkVisible = false
                        pageIndex = map.pageOf(mark.position)
                    },
                    onDelete = { mark -> viewModel.removeBookmark(bookId, mark.id) },
                    onAdd = { viewModel.addBookmark(bookId, map.pageStart(pageIndex), chapterTitle) },
                )
            }
        }
        if (visibleMessage != null) {
            MessageToast(visibleMessage.text, visibleMessage.error) { viewModel.consumeMessage() }
        }
    }
}
