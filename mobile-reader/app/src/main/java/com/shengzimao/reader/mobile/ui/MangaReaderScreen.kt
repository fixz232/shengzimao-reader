package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.MangaBook
import com.shengzimao.reader.mobile.data.MangaReadingMode
import com.shengzimao.reader.mobile.data.MangaScaleMode
import com.shengzimao.reader.mobile.data.MangaSettings
import com.shengzimao.reader.mobile.data.ComicPageAnalysis
import com.shengzimao.reader.mobile.data.ReadingNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlin.math.roundToInt

/** 条漫模式下一个可滚动单元：某张图的某一块。 */
private data class ComicTile(val page: Int, val tile: Int = 0, val info: MangaImageInfo? = null)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MangaReaderScreen(viewModel: ReaderViewModel, navigator: Navigator, bookId: String, initialChapter: Int = -1) {
    val controller = viewModel.manga
    val state by controller.state.collectAsStateWithLifecycle()
    val globalSettings by viewModel.mangaSettings.collectAsStateWithLifecycle()
    val book = state.books.firstOrNull { it.id == bookId }
    val settings = remember(globalSettings, book?.readerPreferences) {
        com.shengzimao.reader.mobile.data.ReadingPreferences.manga(book?.readerPreferences.orEmpty(), globalSettings)
    }
    var chosenChapter by remember(bookId, initialChapter) { mutableIntStateOf(initialChapter) }
    var chosenToc by remember(bookId, initialChapter) { mutableStateOf(book?.chapters.orEmpty()) }
    fun chooseChapter(index: Int) { chosenToc = book?.chapters.orEmpty(); chosenChapter = index }
    var openLastPage by remember(bookId) { mutableStateOf(false) }
    val chapterIndex = (if (chosenChapter >= 0 && chosenToc.isNotEmpty()) com.shengzimao.reader.mobile.data.ChapterAnchor.match(
        chosenToc.map { com.shengzimao.reader.mobile.data.OnlineChapter(it.title, it.url) },
        book?.chapters.orEmpty().map { com.shengzimao.reader.mobile.data.OnlineChapter(it.title, it.url) }, chosenChapter).index
        else if (chosenChapter >= 0) chosenChapter else book?.chapterIndex ?: 0)
        .coerceIn(0, maxOf(0, (book?.chapters?.size ?: 1) - 1))

    var pages by remember(bookId, chapterIndex) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(bookId, chapterIndex) { mutableStateOf("") }
    var reload by remember(bookId, book?.url, chapterIndex) { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf(false) }
    var sourceSwitch by remember { mutableStateOf(false) }
    var chapterLoading by remember { mutableStateOf(false) }
    var imageRetrySignal by remember(bookId, chapterIndex) { mutableIntStateOf(0) }
    val failedPages = remember(bookId, chapterIndex) { mutableStateMapOf<Int, String>() }
    var pageIntervalMs by remember(bookId) { mutableLongStateOf(10_000L) }
    var pageEnteredAt by remember(bookId) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var settingsSheet by remember { mutableStateOf(false) }
    var autoSheet by remember(bookId) { mutableStateOf(false) }
    var autoPlaying by remember(bookId) { mutableStateOf(false) }
    /** 工具栏是否可见；沉浸模式下默认隐藏，单击中央呼出。 */
    var barsVisible by remember { mutableStateOf(!settings.immersive) }
    var currentPage by remember(bookId, chapterIndex) { mutableIntStateOf(0) }
    var currentFraction by remember(bookId, chapterIndex) { mutableFloatStateOf(0f) }
    var seekRequest by remember(bookId, chapterIndex) { mutableStateOf<Pair<Int, Float>?>(null) }
    var horizontalSeek by remember(bookId, chapterIndex) { mutableStateOf<Pair<Int, Float>?>(null) }
    var horizontalSeekRevision by remember(bookId, chapterIndex) { mutableIntStateOf(0) }
    var thumbnails by remember { mutableStateOf(false) }
    var pageAdjust by remember { mutableStateOf(false) }
    var positionReady by remember(bookId, chapterIndex, pages) { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var zoomed by remember(bookId) { mutableStateOf(false) }
    // 递增即通知内部缩放容器复位，用于「放大状态下翻页」前先解锁。
    var zoomResetSignal by remember(bookId) { mutableIntStateOf(0) }
    val infos = remember(bookId, chapterIndex, pages) { mutableStateMapOf<Int, MangaImageInfo>() }
    val decodedTiles = remember(bookId, chapterIndex, pages, imageRetrySignal) { mutableStateMapOf<Pair<Int, Int>, Boolean>() }
    val entries = pages.indices.flatMap { page ->
        infos[page]?.let { info -> (0 until info.tileCount).map { tile -> ComicTile(page, tile, info) } } ?: listOf(ComicTile(page))
    }
    val latestEntries by rememberUpdatedState(entries)
    val list = remember(bookId, chapterIndex) { LazyListState() }
    val userDragging by list.interactionSource.collectIsDraggedAsState()
    val resume = if (book?.chapterIndex == chapterIndex) book.pageIndex.coerceAtLeast(0) else 0
    val spread = if (settings.doublePage && settings.mode != MangaReadingMode.VERTICAL) 2 else 1
    val widePages = infos.filterValues { it.wideSpread }.keys.toSet()
    val overrides = remember(book?.pageOverrides, chapterIndex, pages.size) {
        val chapter = book?.chapters?.getOrNull(chapterIndex)
        if (chapter == null) emptyMap() else pages.indices.associateWith { com.shengzimao.reader.mobile.data.MangaPageOverrides.get(book.pageOverrides, chapter, it) }
    }
    fun pageSettings(page: Int) = if (overrides[page]?.original == true) settings.copy(autoCrop = false, cropPercent = 0) else settings
    val pageGroups = remember(pages.size, spread, settings.smartSpreads, widePages, overrides) {
        com.shengzimao.reader.mobile.data.MangaPageOverrides.groups(pages.size, spread == 2, settings.smartSpreads, widePages, overrides)
    }
    val pager = rememberPagerState(initialPage = 0, pageCount = { pageGroups.size })
    var pendingBookmark by remember(bookId) { mutableStateOf<Triple<Int, Int, Float>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    fun savePosition() {
        if (positionReady && pages.isNotEmpty()) controller.savePosition(bookId, chapterIndex, currentPage, currentFraction, pages.getOrNull(currentPage).orEmpty())
    }
    // A local function reference can compare equal after pages replaces the readiness state.
    // Capture the current closure so lifecycle/disposal never keeps the initial not-ready guard.
    val latestSave by rememberUpdatedState<() -> Unit>({ savePosition() })
    val leaveReader = {
        savePosition()
        controller.flushReadingProgress()
        navigator.returnFromReader()
    }
    BackHandler {
        when {
            sourceSwitch -> { sourceSwitch = false; controller.closeAlternatives() }
            catalog -> catalog = false
            downloads -> downloads = false
            settingsSheet -> settingsSheet = false
            barsVisible -> barsVisible = false
            else -> leaveReader()
        }
    }

    // 阅读页的亮度、亮屏由漫画设置接管（小说阅读走另一套设置）。
    ReaderWindowEffects(
        enabled = true,
        keepScreenOn = settings.keepScreenOn,
        brightness = settings.brightness,
        fullScreen = settings.immersive,
        dark = settings.isDark,
    )

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, bookId) {
        controller.engine.reading(true)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) controller.engine.reading(true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.engine.reading(false)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) autoPlaying = false
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { latestSave(); controller.flushReadingProgress() }
        }
        lifecycle.addObserver(observer)
        onDispose { controller.engine.reading(false); lifecycle.removeObserver(observer); latestSave(); controller.flushReadingProgress() }
    }
    LaunchedEffect(bookId, book?.url, chapterIndex, reload) {
        if (book == null) return@LaunchedEffect
        chapterLoading = true; error = ""; positionReady = false
        try { pages = controller.ensurePages(bookId, chapterIndex, refreshPages = reload > 0); infos.clear(); failedPages.clear() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取漫画章节失败" }
        finally { chapterLoading = false }
    }

    // 跨章预取：临近章末时静默拉取下一章的图片地址与前几张图，翻章不再干等。
    LaunchedEffect(pages.size, currentPage, chapterIndex, book?.id, settings.prefetchPages, settings.prefetchWifiOnly) {
        val target = book ?: return@LaunchedEffect
        val now = android.os.SystemClock.elapsedRealtime(); val elapsed = now - pageEnteredAt
        if (elapsed in 800..600_000) pageIntervalMs = (pageIntervalMs + elapsed) / 2
        pageEnteredAt = now
        val count = ReadingNetwork.prefetchCount(context, pageIntervalMs, controller.engine.imageLoadMillis(target.sourceId), settings.prefetchPages, settings.prefetchWifiOnly)
        if (count <= 0 || pages.isEmpty()) return@LaunchedEffect
        try {
            pages.drop(currentPage + 1).take(count).forEach { reference ->
                try { controller.readerImage(bookId, chapterIndex, pages.indexOf(reference), priority = com.shengzimao.reader.mobile.data.MangaImagePriority.PREFETCH) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
            val next = chapterIndex + 1
            if (next <= target.chapters.lastIndex) {
                val nextPages = controller.ensurePages(bookId, next)
                nextPages.take(if (currentPage >= pages.lastIndex - count) count else 1).forEach { reference ->
                    try { controller.readerImage(bookId, next, nextPages.indexOf(reference), priority = com.shengzimao.reader.mobile.data.MangaImagePriority.PREFETCH) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }

    fun turnPage(delta: Int) {
        if (pages.isEmpty()) return
        // 放大状态下也要能翻页：先复位缩放，而不是静默吞掉这次翻页请求。
        // 旧实现 `if (... || zoomed) return` 会让音量键、滑动、侧点全部无声失效。
        if (zoomed) {
            zoomResetSignal++
            zoomed = false
        }
        val target = pageGroups.indexOfFirst { currentPage in it }.coerceAtLeast(0) + delta
        when {
            target in pageGroups.indices -> seekRequest = pageGroups[target].first() to 0f
            delta < 0 && chapterIndex > 0 -> { savePosition(); openLastPage = true; chooseChapter(chapterIndex - 1) }
            delta > 0 && chapterIndex < (book?.chapters?.lastIndex ?: 0) -> { savePosition(); openLastPage = false; chooseChapter(chapterIndex + 1) }
        }
    }
    // 在线与本地共用同一套翻页、跨章与位置保存逻辑。
    val volumeKeys = Modifier.volumeKeyPager(
        enabled = settings.volumeKeysTurnPages && pages.isNotEmpty(),
        onPrev = { autoPlaying = false; turnPage(-1) }, onNext = { autoPlaying = false; turnPage(1) },
    )

    // Automatic reading never advances past an unloaded image or a failed chapter.
    // Menus, user input and backgrounding stop the session rather than silently resuming it.
    LaunchedEffect(catalog, downloads, settingsSheet, sourceSwitch, thumbnails, autoSheet, zoomed, error, failedPages.size) {
        if (catalog || downloads || settingsSheet || sourceSwitch || thumbnails || autoSheet || zoomed || error.isNotBlank() || failedPages.isNotEmpty()) autoPlaying = false
    }
    val canAutoRead by rememberUpdatedState(positionReady && !chapterLoading && pages.isNotEmpty() &&
        (if (settings.mode == MangaReadingMode.VERTICAL) {
            val visible = list.layoutInfo.visibleItemsInfo
            visible.isNotEmpty() && visible.all { item -> latestEntries.getOrNull(item.index)?.let { decodedTiles[it.page to it.tile] == true } == true }
        } else pageGroups.getOrNull(pager.currentPage)?.all { page ->
            val tile = if (page == currentPage) ((infos[page]?.height ?: 0) * currentFraction / MANGA_TILE_HEIGHT).toInt() else 0
            decodedTiles[page to tile] == true
        } == true) &&
        failedPages.isEmpty() && error.isBlank() && !zoomed)
    val automaticTurn by rememberUpdatedState<() -> Unit>({
        if (pager.currentPage < pageGroups.lastIndex) turnPage(1)
        else if (settings.autoNextChapter && chapterIndex < (book?.chapters?.lastIndex ?: 0)) {
            savePosition(); chooseChapter(chapterIndex + 1)
        } else autoPlaying = false
    })
    LaunchedEffect(autoPlaying, settings.mode, settings.autoReadIntervalSeconds, settings.autoScrollDpPerSecond, bookId, chapterIndex) {
        if (!autoPlaying) return@LaunchedEffect
        if (settings.mode == MangaReadingMode.VERTICAL) {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                previous = now
                if (!canAutoRead) { delay(100); continue }
                val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: continue
                if (latestEntries.getOrNull(lastVisible)?.info == null) continue
                if (list.canScrollForward) list.scrollBy(settings.autoScrollDpPerSecond * density * seconds)
                else if (infos.containsKey(pages.lastIndex)) {
                    if (settings.autoNextChapter && chapterIndex < (book?.chapters?.lastIndex ?: 0)) {
                        savePosition(); chooseChapter(chapterIndex + 1)
                    } else autoPlaying = false
                    break
                }
            }
        } else {
            while (true) {
                // Count only time for which the current page is actually readable.
                var elapsed = 0L
                while (elapsed < settings.autoReadIntervalSeconds * 1000L) {
                    delay(100)
                    if (canAutoRead) elapsed += 100 else elapsed = 0
                }
                automaticTurn()
                delay(100)
            }
        }
    }

    LaunchedEffect(pages, chapterIndex, settings.mode, pageGroups, settings.autoCrop, settings.cropPercent, configuration.screenWidthDp) {
        if (pages.isEmpty()) return@LaunchedEffect
        val pending = pendingBookmark
        if (pending != null && pending.first == chapterIndex) {
            seekRequest = pending.second to pending.third; pendingBookmark = null; return@LaunchedEffect
        }
        val byReference = if (book?.chapterIndex == chapterIndex) pages.indexOf(book.pageReference) else -1
        val page = (if (openLastPage) pages.lastIndex else if (positionReady) currentPage else if (byReference >= 0) byReference else resume).coerceIn(0, pages.lastIndex)
        openLastPage = false
        seekRequest = page to if (positionReady) currentFraction else if (book?.chapterIndex == chapterIndex) book.pageFraction else 0f
    }

    LaunchedEffect(seekRequest, settings.mode, pages, pageGroups, settings.autoCrop, settings.cropPercent) {
        val (target, fraction) = seekRequest ?: return@LaunchedEffect
        if (pages.isEmpty()) return@LaunchedEffect
        positionReady = false
        val page = target.coerceIn(0, pages.lastIndex)
        if (settings.mode == MangaReadingMode.VERTICAL) {
            // Resolve the destination before scrolling: placeholder expansion otherwise changes
            // its list index while a distant seek is in flight.
            if (infos[page] == null && book != null) try {
                infos[page] = kotlinx.coroutines.withTimeout(20_000) { loadMangaImageInfo(controller.engine, pages[page], book.sourceId, fileLoader = { stage -> controller.readerImage(bookId, chapterIndex, page, onStage = stage) }) }
                withFrameNanos { }
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                failedPages[page] = "目标图片加载超时，可重试"
            } catch (_: OutOfMemoryError) { failedPages[page] = "内存不足，可重试或减少预取" }
            catch (failure: Exception) { failedPages[page] = failure.message ?: "目标图片读取失败" }
            list.scrollToItem(latestEntries.indexOfFirst { it.page == page }.coerceAtLeast(0))
            // 未加载的尾图只有一个短占位，第一次滚动会被列表末端截断。
            // 即使目标比例为 0，也要等目标图展开为 tiles 后再次定位。
            val info = kotlinx.coroutines.withTimeoutOrNull(8000) {
                snapshotFlow { latestEntries.firstOrNull { it.page == page }?.info }.first { it != null }
            }
            if (info != null) {
                    withFrameNanos { }
                    val sourceY = (fraction * info.height).coerceIn(0f, (info.height - 1).toFloat())
                    val tile = (sourceY / MANGA_TILE_HEIGHT).toInt()
                    val entry = latestEntries.indexOfFirst { it.page == page && it.tile == tile }.coerceAtLeast(0)
                    val width = ((configuration.screenWidthDp - settings.paddingDp * 2) * density).coerceAtLeast(1f)
                    list.scrollToItem(entry, ((sourceY - info.tileTop(tile)) * width / (info.width * (1f - info.crop(pageSettings(page)) / 50f))).toInt())
            }
        } else {
            horizontalSeek = page to fraction
            horizontalSeekRevision++
            pager.scrollToPage(pageGroups.indexOfFirst { page in it }.coerceAtLeast(0))
        }
        currentPage = page; currentFraction = fraction
        positionReady = true
        seekRequest = null
    }

    LaunchedEffect(pages, chapterIndex, settings.mode, positionReady, pageGroups) {
        if (!positionReady || pages.isEmpty()) return@LaunchedEffect
        if (settings.mode == MangaReadingMode.VERTICAL) {
            snapshotFlow {
                val item = latestEntries.getOrNull(list.firstVisibleItemIndex)
                val size = list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
                val info = item?.info
                val withinTile = list.firstVisibleItemScrollOffset.toFloat() / size
                val fraction = if (item != null && info != null) (info.tileTop(item.tile) +
                    withinTile * (info.tileBottom(item.tile) - info.tileTop(item.tile))) / info.height else 0f
                (item?.page ?: 0) to fraction.coerceIn(0f, 1f)
            }.distinctUntilChanged().onEach { (page, fraction) -> currentPage = page; currentFraction = fraction }.debounce(700)
                .collect { (page, fraction) -> controller.savePosition(bookId, chapterIndex, page, fraction, pages[page]) }
        } else {
            snapshotFlow { Triple(pager.currentPage, currentPage, currentFraction) }.distinctUntilChanged()
                .onEach { (group, page, _) ->
                    if (page !in pageGroups.getOrElse(group) { emptyList() }) { currentPage = pageGroups.getOrNull(group)?.firstOrNull() ?: 0; currentFraction = 0f }
                }.debounce(700).collect { (group, page, fraction) ->
                    if (page in pageGroups.getOrElse(group) { emptyList() }) controller.savePosition(bookId, chapterIndex, page, fraction, pages[page])
                }
        }
    }

    // 翻页位置提示浮层。
    LaunchedEffect(currentPage, chapterIndex) {
        if (!settings.showPageHint || pages.isEmpty()) return@LaunchedEffect
        hint = "${currentPage + 1} / ${pages.size}"
        delay(1200)
        hint = ""
    }

    // 只有用户实际滑动到最后一张图片底部后才翻章；加载/恢复末页不应跳过正文。
    LaunchedEffect(bookId, settings.mode, settings.autoNextChapter, pages, chapterIndex) {
        if (settings.mode != MangaReadingMode.VERTICAL || !settings.autoNextChapter) return@LaunchedEffect
        if (pages.isEmpty()) return@LaunchedEffect
        val target = book ?: return@LaunchedEffect
        if (chapterIndex >= target.chapters.lastIndex) return@LaunchedEffect
        var scrolled = false
        snapshotFlow {
            Triple(userDragging, list.isScrollInProgress,
                !list.canScrollForward && infos[pages.lastIndex] != null &&
                    list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == latestEntries.lastIndex)
        }.collect { (dragged, scrolling, atEnd) ->
            if (dragged) scrolled = true
            if (scrolled && !dragged && !scrolling && atEnd && !zoomed) chooseChapter(chapterIndex + 1)
        }
    }

    val total = maxOf(1, pages.size)
    val progress = ((currentPage + 1).toFloat() / total).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().then(volumeKeys).background(Color(settings.backgroundColor)).pointerInput(autoPlaying) {
        if (autoPlaying) awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            autoPlaying = false
        }
    }) {
        CompositionLocalProvider(LocalContentColor provides if (settings.isDark) Color.White else Color(0xFF222222)) { key(imageRetrySignal) { when {
            state.loaded && book == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这部漫画已从漫库移除", color = Color.White)
            }
            error.isNotBlank() && pages.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error, color = Color(0xFFFFB4AB))
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { reload++ }) { Text("重新读取") }
                if (book?.local == false) TextButton(onClick = {
                    sourceSwitch = true; controller.searchAlternatives(bookId)
                }) { Text("查找可读漫画源") }
                TextButton(onClick = { navigator.openMangaOnline() }) { Text("查看漫画源") }
            }
            pages.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
                Text("正在读取章节图片…", Modifier.padding(top = 12.dp), color = Color.White)
            }
            settings.mode == MangaReadingMode.VERTICAL -> VerticalReader(
                pages = pages,
                entries = entries,
                infos = infos,
                list = list,
                book = book!!.copy(chapterIndex = chapterIndex),
                controller = controller,
                settings = settings,
                overrides = overrides,
                onRefreshChapter = { reload++ },
                onSwitchSource = { savePosition(); sourceSwitch = true; controller.searchAlternatives(bookId) },
                onLoadInfo = { page, info -> infos[page] = info; failedPages.remove(page) },
                onImageFailure = { page, failure -> failedPages[page] = failure },
                onTileReady = { page, tile -> decodedTiles[page to tile] = true; if (decodedTiles.none { it.key.first == page && !it.value }) failedPages.remove(page) },
                onTileFailure = { page, tile, failure -> decodedTiles[page to tile] = false; failedPages[page] = failure },
                onZoomChanged = { zoomed = it },
                zoomResetSignal = zoomResetSignal,
                toggleBars = { barsVisible = !barsVisible },
            )
            else -> HorizontalReader(
                pages = pages,
                infos = infos,
                pager = pager,
                book = book!!.copy(chapterIndex = chapterIndex),
                controller = controller,
                settings = settings,
                overrides = overrides,
                onRefreshChapter = { reload++ },
                onSwitchSource = { savePosition(); sourceSwitch = true; controller.searchAlternatives(bookId) },
                pageGroups = pageGroups,
                onLoadInfo = { page, info -> infos[page] = info; failedPages.remove(page) },
                onImageFailure = { page, failure -> failedPages[page] = failure },
                onTileReady = { page, tile -> decodedTiles[page to tile] = true; if (decodedTiles.none { it.key.first == page && !it.value }) failedPages.remove(page) },
                onTileFailure = { page, tile, failure -> decodedTiles[page to tile] = false; failedPages[page] = failure },
                onZoomChanged = { zoomed = it },
                zoomResetSignal = zoomResetSignal,
                toggleBars = { barsVisible = !barsVisible },
                seek = horizontalSeek,
                seekRevision = horizontalSeekRevision,
                onPosition = { page, fraction -> if (positionReady) { currentPage = page; currentFraction = fraction } },
                onTurn = ::turnPage,
            )
        } } }

        // 沉浸模式下横屏/条漫滑动时自动隐藏工具栏。
        LaunchedEffect(barsVisible, settings.immersive) {
            if (barsVisible && settings.immersive && !zoomed) {
                delay(4000)
                barsVisible = false
            }
        }

        AnimatedVisibility(
            visible = barsVisible || error.isNotBlank(),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            MangaReaderTopBar(
                book = book,
                chapterIndex = chapterIndex,
                onBack = leaveReader,
                onCatalog = { catalog = true },
                onPages = { thumbnails = true },
                onSettings = { settingsSheet = true },
                onDownloads = { downloads = true },
                onUpdate = { savePosition(); controller.checkUpdates(bookId) },
                onSwitchSource = { savePosition(); sourceSwitch = true; controller.searchAlternatives(bookId) },
                onReloadImages = { reload++ },
                onAutoRead = { if (autoPlaying) autoPlaying = false else autoSheet = true },
                autoPlaying = autoPlaying,
                updating = bookId in state.updatingIds,
            )
        }

        if (settings.showStatusBar && !barsVisible && pages.isNotEmpty()) MangaReaderStatus(
            book?.chapters?.getOrNull(chapterIndex)?.title.orEmpty(), currentPage, pages.size, autoPlaying,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        AnimatedVisibility(
            visible = barsVisible || error.isNotBlank(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MangaReaderBottomBar(
                book = book,
                chapterIndex = chapterIndex,
                mode = settings.mode,
                currentPage = currentPage,
                pageCount = pages.size,
                emptyPageStatus = if (error.isNotBlank()) "读取失败" else if (chapterLoading) "正在读取" else "本章暂无图片",
                progress = progress,
                onPrevChapter = { savePosition(); chooseChapter(chapterIndex - 1) },
                onNextChapter = { savePosition(); chooseChapter(chapterIndex + 1) },
                onModeChange = { controller.saveReaderPreferences(bookId, com.shengzimao.reader.mobile.data.ReadingPreferences.encode(settings.copy(mode = it))) },
                onSettings = { settingsSheet = true },
            onCatalog = { catalog = true },
                onSeek = { fraction ->
                    val target = (fraction * (pages.size - 1)).roundToInt().coerceIn(0, pages.lastIndex.coerceAtLeast(0))
                    seekRequest = target to 0f
                },
                onCacheChapter = {
                    viewModel.downloadComic(bookId, chapterIndex)
                    downloads = true
                },
            )
        }

        // 翻页位置提示。
        if (barsVisible && (failedPages.isNotEmpty() || chapterLoading || error.isNotBlank())) Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp),
            color = Color(0xE6101114), contentColor = Color.White, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text(if (chapterLoading) "正在读取章节图片地址，已有画面保留…" else "已显示 ${infos.size}/${pages.size} 张 · ${failedPages.size} 张加载失败", style = MaterialTheme.typography.labelSmall)
                if (failedPages.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow {
                        TextButton(onClick = { imageRetrySignal++; failedPages.clear() }) { Text("重试失败图片") }
                        if (book?.local == false) {
                            TextButton(onClick = { reload++ }) { Text("刷新章节链接") }
                            TextButton(onClick = { savePosition(); sourceSwitch = true; controller.searchAlternatives(bookId) }) {
                                Text("查找可读漫画源")
                            }
                        }
                    }
                }
                if (error.isNotBlank()) TextButton(onClick = { reload++ }) { Text("$error · 重试章节") }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = hint.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xCC000000)) {
                Text(hint, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White)
            }
        }
    }

    if (catalog && book != null) {
        MangaCatalogSheet(
            book = book,
            chapterIndex = chapterIndex,
            onDismiss = { catalog = false },
            onSelect = { savePosition(); chooseChapter(it); catalog = false },
            onUpdate = { savePosition(); controller.checkUpdates(bookId) },
            updating = bookId in state.updatingIds,
            onTracking = { controller.toggleTracking(bookId) },
        )
    }
    if (sourceSwitch && book != null) MangaSourceSwitchSheet(controller, book, pages.size,
        onDismiss = { sourceSwitch = false; controller.closeAlternatives() }, onChanged = { chooseChapter(-1); sourceSwitch = false })
    val feedback = state.sourceError.ifBlank { state.message }
    if (feedback.isNotBlank()) MessageToast(feedback, state.sourceError.isNotBlank()) { controller.dismissMessage() }
    if (settingsSheet) {
        MangaSettingsSheet(
            settings = settings,
            onChange = { transform -> controller.saveReaderPreferences(bookId, com.shengzimao.reader.mobile.data.ReadingPreferences.encode(transform(settings))) },
            onDismiss = { settingsSheet = false },
            onReset = { controller.saveReaderPreferences(bookId, "") },
        )
    }
    if (autoSheet) MangaAutoReadSheet(settings,
        onSave = { controller.saveReaderPreferences(bookId, com.shengzimao.reader.mobile.data.ReadingPreferences.encode(it)) },
        onStart = { autoSheet = false; barsVisible = false; autoPlaying = true }, onDismiss = { autoSheet = false })
    if (downloads) DownloadManagerSheet(viewModel, navigator) { downloads = false }
    if (pageAdjust && book != null && pages.isNotEmpty()) MangaPageAdjustSheet(controller, book, chapterIndex, currentPage, settings) { pageAdjust = false }
    if (thumbnails && book != null) MangaPageSheet(book.copy(chapterIndex = chapterIndex), pages, currentPage, currentFraction, controller,
        onAdjust = { thumbnails = false; pageAdjust = true },
        onDismiss = { thumbnails = false }, onSelect = { page, fraction -> seekRequest = page to fraction; thumbnails = false },
        onChapter = { index, page, fraction ->
            if (index == chapterIndex) seekRequest = page to fraction
            else { pendingBookmark = Triple(index, page, fraction); savePosition(); chooseChapter(index) }
            thumbnails = false
        })
}

/** 条漫：整页共享缩放，滑动不再被缩放状态打断。 */
@OptIn(FlowPreview::class)
@Composable
private fun VerticalReader(
    pages: List<String>,
    entries: List<ComicTile>,
    infos: MutableMap<Int, MangaImageInfo>,
    list: LazyListState,
    book: MangaBook,
    controller: MangaController,
    settings: MangaSettings,
    overrides: Map<Int, com.shengzimao.reader.mobile.data.MangaPageOverride>,
    onRefreshChapter: () -> Unit, onSwitchSource: () -> Unit,
    onLoadInfo: (Int, MangaImageInfo) -> Unit,
    onImageFailure: (Int, String) -> Unit,
    onTileReady: (Int, Int) -> Unit,
    onTileFailure: (Int, Int, String) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    zoomResetSignal: Int = 0,
    toggleBars: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        MangaZoomBox(
            lockPan = settings.lockPan,
            doubleTapScale = settings.doubleTapScale,
            onZoomChanged = onZoomChanged,
            resetSignal = zoomResetSignal,
        ) { zoom ->
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = settings.paddingDp.dp),
                state = list,
                verticalArrangement = if (settings.gapDp > 0) Arrangement.spacedBy(settings.gapDp.dp) else Arrangement.Top,
                userScrollEnabled = !zoom.isZoomed,
            ) {
                items(entries.size, key = { index -> val item = entries[index]; "${item.page}:${item.tile}" }) { index ->
                    val item = entries[index]
                    if (item.info == null) {
                        MangaImageLoader(controller.engine, pages[item.page], book.sourceId,
                            requestKey = "${book.id}|${book.chapters.getOrNull(book.chapterIndex)?.url}|${item.page}|${list.layoutInfo.visibleItemsInfo.any { it.index == index }}",
                            fileLoader = { force, stage -> controller.readerImage(book.id, book.chapterIndex, item.page, force, priority = if (list.layoutInfo.visibleItemsInfo.any { it.index == index }) com.shengzimao.reader.mobile.data.MangaImagePriority.VISIBLE else com.shengzimao.reader.mobile.data.MangaImagePriority.NEARBY, onStage = stage) },
                            onRefreshChapter = onRefreshChapter, onSwitchSource = onSwitchSource,
                            onFailure = { onImageFailure(item.page, it) }) { onLoadInfo(item.page, it) }
                    } else {
                        MangaImageTile(
                            info = item.info,
                            tile = item.tile,
                            page = item.page,
                            zoom = zoom,
                            contentScale = settings.scaleMode.toContentScale(),
                            doubleTapEnabled = settings.doubleTapZoom,
                            onTap = toggleBars,
                            onReady = { onTileReady(item.page, item.tile) },
                            onFailure = { onTileFailure(item.page, item.tile, it) },
                            cropPercent = if (overrides[item.page]?.original == true) 0 else item.info.crop(settings),
                            onRetryImage = { scope.launch { runCatching { controller.readerImage(book.id, book.chapterIndex, item.page, force = true) }; MangaBitmapCache.clear(); infos.remove(item.page) } },
                        )
                    }
                }
            }
        }
    }
}

/** 横向 / 日漫：pager 翻页，每页内部同样整页缩放。 */
@Composable
private fun HorizontalReader(
    pages: List<String>,
    infos: MutableMap<Int, MangaImageInfo>,
    pager: androidx.compose.foundation.pager.PagerState,
    book: MangaBook,
    controller: MangaController,
    settings: MangaSettings,
    overrides: Map<Int, com.shengzimao.reader.mobile.data.MangaPageOverride>,
    onRefreshChapter: () -> Unit, onSwitchSource: () -> Unit,
    pageGroups: List<List<Int>>,
    onLoadInfo: (Int, MangaImageInfo) -> Unit,
    onImageFailure: (Int, String) -> Unit,
    onTileReady: (Int, Int) -> Unit,
    onTileFailure: (Int, Int, String) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    zoomResetSignal: Int = 0,
    toggleBars: () -> Unit,
    seek: Pair<Int, Float>?,
    seekRevision: Int,
    onPosition: (Int, Float) -> Unit,
    onTurn: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), reverseLayout = settings.mode.reversed,
        pageSpacing = settings.gapDp.dp, userScrollEnabled = !zoomed) { spreadIndex ->
        val indices = pageGroups.getOrNull(spreadIndex).orEmpty()
        Row(Modifier.fillMaxSize()) {
            (if (settings.mode.reversed) indices.reversed() else indices).forEach { page ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val info = infos[page]
                    if (info == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MangaImageLoader(controller.engine, pages[page], book.sourceId,
                            requestKey = "${book.id}|${book.chapters.getOrNull(book.chapterIndex)?.url}|$page|${pager.currentPage == spreadIndex}",
                            fileLoader = { force, stage -> controller.readerImage(book.id, book.chapterIndex, page, force, priority = if (pager.currentPage == spreadIndex) com.shengzimao.reader.mobile.data.MangaImagePriority.VISIBLE else com.shengzimao.reader.mobile.data.MangaImagePriority.NEARBY, onStage = stage) },
                            onRefreshChapter = onRefreshChapter, onSwitchSource = onSwitchSource,
                            onFailure = { onImageFailure(page, it) }) { onLoadInfo(page, it) }
                    }
                    else key(pages[page]) {
                        HorizontalComicPage(info, page, indices.size, if (overrides[page]?.original == true) settings.copy(autoCrop = false, cropPercent = 0) else settings, seek, seekRevision,
                            active = pager.currentPage == spreadIndex && page == (seek?.first?.takeIf { it in indices } ?: indices.first()),
                            onPosition = { fraction -> onPosition(page, fraction) },
                            onTileReady = { tile -> onTileReady(page, tile) },
                            onTileFailure = { tile, failure -> onTileFailure(page, tile, failure) },
                            onZoomChanged = { zoomed = it; onZoomChanged(it) }, toggleBars = toggleBars,
                            onTurn = onTurn,
                            zoomResetSignal = zoomResetSignal,
                            onRetry = { scope.launch { runCatching { controller.readerImage(book.id, book.chapterIndex, page, force = true) }; MangaBitmapCache.clear(); infos.remove(page) } })
                    }
                }
            }
        }
    }
}

/** 保存横向长图内部的原图比例，宽度/裁边变化后重新定位到同一内容。 */
@Composable
private fun HorizontalComicPage(
    info: MangaImageInfo, page: Int, spread: Int, settings: MangaSettings,
    seek: Pair<Int, Float>?, seekRevision: Int, active: Boolean, onPosition: (Float) -> Unit,
    onZoomChanged: (Boolean) -> Unit, toggleBars: () -> Unit, onRetry: () -> Unit,
    onTurn: (Int) -> Unit, zoomResetSignal: Int = 0,
    onTileReady: (Int) -> Unit,
    onTileFailure: (Int, String) -> Unit,
) {
    val list = rememberLazyListState()
    var restoring by remember(info.file) { mutableStateOf(true) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val latestPosition by rememberUpdatedState(onPosition)
    LaunchedEffect(info, seekRevision, settings.cropPercent, configuration.screenWidthDp, spread) {
        restoring = true
        val fraction = seek?.takeIf { it.first == page }?.second ?: 0f
        val sourceY = (fraction * info.height).coerceIn(0f, (info.height - 1).toFloat())
        val tile = (sourceY / MANGA_TILE_HEIGHT).toInt().coerceIn(0, info.tileCount - 1)
        val width = ((configuration.screenWidthDp / spread - settings.paddingDp * 2) * density).coerceAtLeast(1f)
        list.scrollToItem(tile, ((sourceY - info.tileTop(tile)) * width / (info.width * (1f - info.crop(settings) / 50f))).toInt())
        withFrameNanos { }
        restoring = false
    }
    LaunchedEffect(info, active, seekRevision) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            if (restoring) null else {
                val tile = list.firstVisibleItemIndex.coerceIn(0, info.tileCount - 1)
                val size = list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
                val withinTile = list.firstVisibleItemScrollOffset.toFloat() / size
                ((info.tileTop(tile) + withinTile * (info.tileBottom(tile) - info.tileTop(tile))) / info.height).coerceIn(0f, 1f)
            }
        }.distinctUntilChanged().collect { fraction -> if (fraction != null) latestPosition(fraction) }
    }
    MangaZoomBox(settings.lockPan, settings.doubleTapScale, onZoomChanged, resetSignal = zoomResetSignal) { zoom ->
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = settings.paddingDp.dp), state = list, userScrollEnabled = !zoom.isZoomed) {
            items(info.tileCount) { tile -> MangaImageTile(info, tile, page, zoom,
                settings.scaleMode.toContentScale(), settings.doubleTapZoom, toggleBars,
                cropPercent = info.crop(settings), onRetryImage = onRetry,
                onReady = { onTileReady(tile) },
                onFailure = { onTileFailure(tile, it) },
                onPageTap = { x -> when {
                    x < .32f -> onTurn(if (settings.mode.reversed) 1 else -1)
                    x > .68f -> onTurn(if (settings.mode.reversed) -1 else 1)
                    else -> toggleBars()
                } }) }
        }
    }
}

private fun MangaScaleMode.toContentScale(): ContentScale = when (this) {
    MangaScaleMode.WIDTH -> ContentScale.FillWidth
    MangaScaleMode.HEIGHT -> ContentScale.FillHeight
    MangaScaleMode.FIT -> ContentScale.Fit
    MangaScaleMode.ORIGINAL -> ContentScale.None
}

@Composable
private fun MangaReaderTopBar(
    book: MangaBook?,
    chapterIndex: Int,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onPages: () -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    onUpdate: () -> Unit,
    onSwitchSource: () -> Unit,
    onReloadImages: () -> Unit,
    onAutoRead: () -> Unit,
    autoPlaying: Boolean,
    updating: Boolean,
) {
    Surface(color = Color(0xE6101114), contentColor = Color.White) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回漫库") }
            Column(Modifier.weight(1f)) {
                Text(book?.title ?: "漫画阅读", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    book?.chapters?.getOrNull(chapterIndex)?.title.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB3BDCC),
                )
            }
            var more by remember { mutableStateOf(false) }
            IconButton(onClick = onAutoRead) { Icon(if (autoPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (autoPlaying) "暂停自动阅读" else "自动阅读") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "更多阅读工具") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("目录") }, onClick = { more = false; onCatalog() })
                    DropdownMenuItem(text = { Text("缩略图与页面书签") }, onClick = { more = false; onPages() })
                    DropdownMenuItem(text = { Text("下载管理") }, onClick = { more = false; onDownloads() })
                    DropdownMenuItem(text = { Text("阅读设置 · 当前漫画") }, onClick = { more = false; onSettings() })
                    if (book?.local == false) {
                        DropdownMenuItem(text = { Text(if (updating) "正在更新目录…" else "更新目录") }, onClick = { more = false; onUpdate() }, enabled = !updating)
                        DropdownMenuItem(text = { Text("切换漫画源") }, onClick = { more = false; onSwitchSource() })
                        DropdownMenuItem(text = { Text("刷新本章图片地址") }, onClick = { more = false; onReloadImages() })
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaReaderBottomBar(
    book: MangaBook?,
    chapterIndex: Int,
    mode: MangaReadingMode,
    currentPage: Int,
    pageCount: Int,
    emptyPageStatus: String,
    progress: Float,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onModeChange: (MangaReadingMode) -> Unit,
    onSeek: (Float) -> Unit,
    onCacheChapter: () -> Unit,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember(currentPage, pageCount) { mutableFloatStateOf(if (pageCount <= 1) 0f else currentPage.toFloat() / (pageCount - 1)) }
    var modes by remember { mutableStateOf(false) }
    Surface(color = Color(0xE6101114), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pageCount == 0) emptyPageStatus else "${currentPage + 1} / $pageCount 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB3BDCC),
                )
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = value,
                    onValueChange = { dragging = true; value = it },
                    onValueChangeFinished = { dragging = false; onSeek(value) },
                    enabled = pageCount > 1,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFAAC1FF),
                        activeTrackColor = Color(0xFFAAC1FF),
                        inactiveTrackColor = Color(0xFF3A3F47),
                    ),
                )
            }
            Box {
                ReaderActionBar(Color.White, onPrevChapter, onCatalog, { modes = true }, Icons.Default.SwapHoriz,
                    "模式", onSettings, onNextChapter, previousEnabled = chapterIndex > 0,
                    nextEnabled = book != null && chapterIndex < book.chapters.lastIndex)
                DropdownMenu(modes, { modes = false }) { MangaReadingMode.entries.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.label) }, onClick = { modes = false; onModeChange(entry) })
                } }
            }
        }
    }
}

