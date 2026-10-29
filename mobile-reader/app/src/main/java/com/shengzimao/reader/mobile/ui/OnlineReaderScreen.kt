package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.reader.FontProvider
import com.shengzimao.reader.mobile.reader.ReaderTts
import com.shengzimao.reader.mobile.reader.rememberPageStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private data class OnlineParagraph(val start: Int, val text: String)

/** 阅读缓存保留原文，与用户主动下载的离线作品分开管理。 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun OnlineReaderScreen(viewModel: ReaderViewModel, navigator: Navigator, bookId: String) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val book = state.books.firstOrNull { it.id == bookId }
    if (book == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            Text("书籍已从书架移除"); TextButton(onClick = navigator::openLibrary) { Text("返回书架") }
        }; return
    }
    val settings = remember(state.settings, book.readerPreferences) { ReadingPreferences.novelForBook(book.readerPreferences, state.settings) }
    val readingKey = NovelSourceGroups.identity(book.onlineUrl, book.onlineSourceGroup, book.onlineSourceKey)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toc = remember(book.onlineManifest) { book.onlineChapters }
    var selectedChapter by remember(bookId, readingKey) { mutableIntStateOf(book.position.coerceIn(0, maxOf(0, toc.lastIndex))) }
    var selectedToc by remember(bookId, readingKey) { mutableStateOf(toc) }
    val chapter = remember(selectedToc, toc, selectedChapter) { ChapterAnchor.match(selectedToc, toc, selectedChapter).index }
    val chapterUrl = toc.getOrNull(chapter)?.url.orEmpty()
    var retry by remember(bookId, readingKey, chapter) { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var highlightedQuery by remember(bookId) { mutableStateOf("") }
    var bars by remember { mutableStateOf(false) }
    var body by remember(bookId, readingKey, chapter) { mutableStateOf<List<OnlineParagraph>>(emptyList()) }
    var chapterText by remember(bookId, readingKey, chapter) { mutableStateOf("") }
    var rawChapterText by remember(bookId, readingKey, chapter, chapterUrl) { mutableStateOf("") }
    var cleanedText by remember(bookId, readingKey, chapter) { mutableStateOf(ContentCleanup.apply("", emptyList())) }
    var loadedRetry by remember(bookId, readingKey, chapter) { mutableIntStateOf(-1) }
    var loadStage by remember(bookId) { mutableStateOf("正在获取正文…") }
    var loadMs by remember(bookId) { mutableLongStateOf(1500L) }
    var readingInterval by remember(bookId) { mutableLongStateOf(60_000L) }
    var enteredChapterAt by remember(bookId) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var pageOffset by remember(bookId, readingKey, chapter) { mutableIntStateOf(-1) }
    var pageSeek by remember(bookId, readingKey, chapter) { mutableStateOf<Pair<Int, Int>?>(null) }
    var modeAnchor by remember(bookId, readingKey, chapter) { mutableStateOf<Int?>(null) }
    var pageTurn by remember(bookId, chapter) { mutableIntStateOf(0) }
    var chapterEnd by remember(bookId) { mutableStateOf(false) }
    var error by remember(bookId, readingKey, chapter) { mutableStateOf("") }
    var loading by remember(bookId, readingKey, chapter) { mutableStateOf(true) }
    var ready by remember(bookId, readingKey, chapter) { mutableStateOf(false) }
    var loadedChapter by remember(bookId) { mutableIntStateOf(-1) }
    var pendingMark by remember(bookId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var selection by remember { mutableStateOf<OnlineParagraph?>(null) }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var speaking by remember { mutableStateOf(false) }
    var speechStart by remember(bookId, chapter) { mutableIntStateOf(0) }
    var continueListening by remember(bookId) { mutableStateOf(false) }
    var editMark by remember { mutableIntStateOf(-1) }
    var tomatoSources by remember { mutableStateOf(false) }
    var switchCandidate by remember { mutableStateOf<OnlineResult?>(null) }
    var switching by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val prefetched = remember(bookId, readingKey) { linkedMapOf<String, Deferred<String>>() }
    // 进程级单例：与离线阅读共用同一个引擎，锁屏 / 切后台可继续朗读。
    val tts = remember { ReaderTts.get(context) }
    val speech by tts.state.collectAsStateWithLifecycle()
    val family = remember(settings.font, settings.customFontId) { FontProvider.family(context, settings) }
    val paragraphStyle = rememberPageStyle(settings, family)
    val annotations = remember(book.onlineAnnotations) { runCatching { JSONArray(book.onlineAnnotations) }.getOrDefault(JSONArray()) }
    val horizontal = settings.turnMode == TurnMode.HORIZONTAL
    // 与离线阅读一致：进入时同步这本书保存的语速、音高与音色。
    LaunchedEffect(settings.ttsVoice, settings.ttsRate, settings.ttsPitch) {
        tts.configureVoice(settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
    }
    fun paragraphIndex(): Int = if (horizontal) body.indexOfLast { it.start <= pageOffset }.coerceAtLeast(0)
        else list.firstVisibleItemIndex.coerceIn(0, maxOf(0, body.lastIndex))
    fun visibleOffset(): Int {
        if (horizontal) return pageOffset.coerceAtLeast(0)
        val item = body.getOrNull(paragraphIndex()) ?: return 0
        val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == paragraphIndex() }?.size?.coerceAtLeast(1) ?: 1
        return item.start + (item.text.length * (list.firstVisibleItemScrollOffset.toFloat() / height).coerceIn(0f, 1f)).toInt()
    }
    fun save() {
        if (!ready || body.isEmpty()) return
        if (horizontal && pageOffset < 0) return
        val index = paragraphIndex()
        val item = body[index]
        val height = list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
        val fraction = if (horizontal) ((pageOffset - item.start).toFloat() / item.text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
            else (list.firstVisibleItemScrollOffset.toFloat() / height).coerceIn(0f, 1f)
        viewModel.saveOnlinePosition(bookId, chapter, index, cleanedText.rawOffset(if (horizontal) pageOffset else item.start + (item.text.length * fraction).toInt()), fraction, book.onlineUrl, book.onlineSourceGroup, book.onlineSourceKey)
    }
    val latestSave by rememberUpdatedState(::save)
    LaunchedEffect(horizontal, ready) {
        val anchor = modeAnchor ?: return@LaunchedEffect
        if (!ready || body.isEmpty()) return@LaunchedEffect
        if (horizontal) pageSeek = anchor to ((pageSeek?.second ?: 0) + 1)
        else {
            val index = body.indexOfLast { it.start <= anchor }.coerceAtLeast(0)
            list.scrollToItem(index)
            withFrameNanos { }
            val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            val ratio = ((anchor - body[index].start).toFloat() / body[index].text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
            if (height > 0) list.scrollToItem(index, (height * ratio).toInt())
        }
        modeAnchor = null
    }
    BackHandler { when { panel.isNotEmpty() -> panel = ""; bars -> bars = false; else -> { save(); navigator.returnFromReader() } } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, bookId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) latestSave()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); latestSave(); tts.detach() }
    }
    DisposableEffect(prefetched) { onDispose { prefetched.values.forEach { it.cancel() } } }
    ReaderWindowEffects(true, settings.keepScreenOn, settings.screenBrightness, settings.fullScreen, settings.isDarkPage)
    LaunchedEffect(settings.ttsSleepMinutes) { tts.setSleepMinutes(settings.ttsSleepMinutes) }
    LaunchedEffect(speech.activeSession) {
        if (!speech.activeSession) { speaking = false; continueListening = false }
    }
    LaunchedEffect(speech.error) {
        speech.error?.let { speaking = false; continueListening = false; viewModel.message(it, true) }
    }
    LaunchedEffect(bookId, readingKey, chapter, chapterUrl, retry, settings.cleanupEnabled, settings.cleanupRules) {
        loading = true; ready = false; error = ""; speaking = false; tts.stop(resetSession = !continueListening)
        try {
            val target = toc.getOrNull(chapter) ?: error("在线目录为空，请重新搜索并加入书架")
            loadStage = "正在获取正文 · ${target.title}"
            val started = android.os.SystemClock.elapsedRealtime()
            val raw = if (rawChapterText.isNotBlank() && loadedRetry == retry) rawChapterText else {
                withTimeout(25_000) {
                    val cached = if (retry == 0) viewModel.readCachedOnline(book, target).orEmpty() else ""
                    if (cached.isNotBlank()) { loadStage = "已找到本章缓存，正在恢复阅读…"; cached }
                    else {
                        loadStage = if (retry > 0) "正在重新连接源站读取正文…" else "本章尚未缓存，正在连接源站…"
                        val prefetchedText = if (retry == 0) prefetched.remove(target.url)?.await().orEmpty() else ""
                        prefetchedText.ifBlank { viewModel.readOnlineChapter(book, target, force = retry > 0) }
                    }
                }
            }
            require(raw.isNotBlank() && raw.length <= 2_000_000) { "源站正文为空或单章内容过大" }
            rawChapterText = raw; loadedRetry = retry
            loadMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(500)
            loadStage = "正文已获取，正在净化与排版…"
            val cleaned = withContext(Dispatchers.Default) { ContentCleanup.apply(raw,
                if (settings.cleanupEnabled) ContentCleanup.parse(settings.cleanupRules) else emptyList()) }
            val text = cleaned.text
            require(text.isNotBlank()) { "净化规则过滤了全部正文，请在正文净化中关闭或修改规则" }
            cleanedText = cleaned
            chapterText = text
            body = withContext(Dispatchers.Default) { Regex("[^\\r\\n]+").findAll(text).flatMap { match ->
                match.value.chunked(600).mapIndexed { part, value -> OnlineParagraph(match.range.first + part * 600, value) }
            }.filter { it.text.isNotBlank() }.toList() }
            loading = false
            val restore = if (book.position == chapter) {
                if (book.onlineCharacterOffset >= 0) body.indexOfLast { it.start <= cleaned.displayOffset(book.onlineCharacterOffset) }.coerceAtLeast(0)
                else book.onlineOffset.coerceIn(0, maxOf(0, body.lastIndex))
            } else 0
            if (!horizontal) list.scrollToItem(restore)
            if (!horizontal && book.position == chapter && book.onlineCharacterOffset >= 0) {
                withFrameNanos { }
                val paragraph = body.getOrNull(restore)
                val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == restore }?.size ?: 0
                val ratio = if (paragraph != null) ((cleaned.displayOffset(book.onlineCharacterOffset) - paragraph.start).toFloat() / paragraph.text.length.coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
                if (height > 0) list.scrollToItem(restore, (height * ratio).toInt())
            }; loadedChapter = chapter; ready = true
            pendingMark?.takeIf { it.first == chapter }?.let { mark ->
                pageSeek = cleaned.displayOffset(mark.second) to ((pageSeek?.second ?: 0) + 1)
                if (!horizontal) list.scrollToItem(body.indexOfLast { it.start <= cleaned.displayOffset(mark.second) }.coerceAtLeast(0))
                pendingMark = null
            }
            if (continueListening && tts.state.value.activeSession) {
                continueListening = false; speechStart = 0
                speaking = tts.speakSegments(body.map { it.text })
                if (speaking && !com.shengzimao.reader.mobile.TtsPlaybackService.start(context)) viewModel.message("无法启动后台听书服务，请保持阅读页在前台", true)
            }
            continueListening = false
        } catch (timeout: TimeoutCancellationException) { error = "正文读取超时，已读内容保留，可重试或换源"; loading = false; ready = body.isNotEmpty(); continueListening = false; tts.stop() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "联网读取失败：${failure.message ?: "网络不可用"}"; loading = false; ready = body.isNotEmpty(); continueListening = false; tts.stop() }
    }
    LaunchedEffect(bookId, chapter, ready, horizontal) {
        if (ready) snapshotFlow { if (horizontal) pageOffset to 0 else list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.distinctUntilChanged().debounce(700).collect { save() }
    }
    LaunchedEffect(chapter, ready, readingInterval, loadMs) {
        if (ready && chapter < toc.lastIndex) {
            val count = ReadingNetwork.prefetchCount(context, readingInterval, loadMs, 3, wifiOnly = false)
            val targets = (chapter + 1..minOf(toc.lastIndex, chapter + count)).map { toc[it] }
            val urls = targets.map { it.url }.toSet()
            prefetched.keys.filter { it !in urls }.toList().forEach { prefetched.remove(it)?.cancel() }
            for (next in targets) if (!prefetched.containsKey(next.url)) prefetched[next.url] = scope.async {
                try { viewModel.readOnlineChapter(book, next) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { "" }
            }
        }
    }
    fun changeChapter(target: Int, atEnd: Boolean = false, keepListening: Boolean = false) {
        if (target.coerceIn(0, maxOf(0, toc.lastIndex)) == chapter) return
        val now = android.os.SystemClock.elapsedRealtime(); val interval = now - enteredChapterAt
        if (interval in 1000..600_000) readingInterval = (readingInterval + interval) / 2
        enteredChapterAt = now
        if (!keepListening) continueListening = false
        save(); tts.stop(resetSession = !keepListening); speaking = false; ready = false; chapterEnd = atEnd; selectedToc = toc; selectedChapter = target.coerceIn(0, maxOf(0, toc.lastIndex)); retry = 0
    }
    fun stopListening() {
        tts.stop(); speaking = false; continueListening = false
    }
    fun toggleListening() {
        when {
            speech.paused -> { speaking = tts.resume() }
            speaking && (speech.speaking || speech.initializing) -> tts.pause()
            ready && body.isNotEmpty() -> {
                speechStart = paragraphIndex()
                val offset = (visibleOffset() - body[speechStart].start).coerceIn(0, body[speechStart].text.length)
                val segments = body.drop(speechStart).mapIndexed { index, item -> if (index == 0) item.text.drop(offset) else item.text }
                val skipped = segments.takeWhile { it.isBlank() }.size
                speechStart += skipped
                speaking = tts.speakSegments(segments.drop(skipped))
                if (speaking && !com.shengzimao.reader.mobile.TtsPlaybackService.start(context)) viewModel.message("无法启动后台听书服务，请保持阅读页在前台", true)
            }
            else -> viewModel.message("正文加载后即可开始听书", true)
        }
    }
    val latestSpeechFinished by rememberUpdatedState<() -> Unit> {
        if (speaking && settings.autoTtsTurnPage && chapter < toc.lastIndex) {
            continueListening = true; changeChapter(chapter + 1, keepListening = true)
        } else {
            stopListening()
            if (chapter == toc.lastIndex) viewModel.message("全书已朗读完毕")
        }
    }
    DisposableEffect(tts) {
        tts.onQueueFinished = { latestSpeechFinished() }
        onDispose { tts.onQueueFinished = null }
    }
    LaunchedEffect(speech.segment, chapter) {
        if (speaking && speech.segment >= 0) {
            val index = (speechStart + speech.segment).coerceIn(0, maxOf(0, body.lastIndex))
            if (horizontal) body.getOrNull(index)?.let { pageSeek = it.start to ((pageSeek?.second ?: 0) + 1) }
            else if (body.isNotEmpty()) list.scrollToItem(index)
        }
    }
    fun annotate(paragraph: OnlineParagraph?, text: String = "", labels: String = "") {
        if (annotations.length() >= 2000) { viewModel.message("书签笔记已达 2000 条", true); return }
        val items = JSONArray(annotations.toString())
        val p = paragraph ?: body.getOrNull(paragraphIndex()) ?: return
        items.put(JSONObject().put("chapterUrl", toc.getOrNull(chapter)?.url.orEmpty()).put("chapter", chapter)
            .put("position", cleanedText.rawOffset(p.start)).put("quote", p.text.take(600)).put("note", text.take(10000)).put("tags", labels.take(200)))
        viewModel.saveOnlineAnnotations(bookId, items.toString()); viewModel.message("已保存书签 / 笔记")
    }
    val spokenParagraph = if (speaking && speech.activeSession && speech.segment >= 0)
        body.getOrNull(speechStart + speech.segment) else null
    Box(Modifier.fillMaxSize().background(Color(settings.pageBackground)).volumeKeyPager(settings.volumeKeysTurnPages && panel.isBlank(),
        onPrev = { if (horizontal) pageTurn-- else scope.launch { list.animateScrollBy(-600f) } }, onNext = { if (horizontal) pageTurn++ else scope.launch { list.animateScrollBy(600f) } })) {
        if (horizontal && chapterText.isNotBlank()) OnlinePagedReader(
            text = chapterText, title = toc.getOrNull(chapter)?.title.orEmpty(), settings = settings, family = family,
            initialOffset = modeAnchor ?: pageOffset.takeIf { it >= 0 } ?: if (chapterEnd) Int.MAX_VALUE else if (book.position == chapter) cleanedText.displayOffset(book.onlineCharacterOffset.coerceAtLeast(0)) else 0,
            seek = pageSeek, turnRequest = pageTurn, query = highlightedQuery, onPosition = { pageOffset = it },
            onBoundary = { delta -> changeChapter(chapter + delta, atEnd = delta < 0) }, onMenu = { bars = !bars },
            onNote = { start, quote -> selection = OnlineParagraph(start, quote); editMark = -1; note = ""; tags = "" },
            onUserTurn = { if (speaking || continueListening) stopListening() },
            speechRange = spokenParagraph?.takeIf { settings.ttsHighlight }?.let { it.start until it.start + it.text.length },
        )
        else LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = settings.paddingDp.dp,
            end = settings.paddingDp.dp, top = 32.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(body) { _, paragraph ->
                val spoken = settings.ttsHighlight && paragraph.start == spokenParagraph?.start
                val highlighted = remember(paragraph.text, highlightedQuery, settings.isDarkPage, spoken) {
                    buildAnnotatedString {
                        append(paragraph.text)
                        if (spoken) addStyle(SpanStyle(background = if (settings.isDarkPage) Color(0xFF344D43) else Color(0xFFD2EADB)), 0, length)
                        if (highlightedQuery.isNotBlank()) {
                            var start = paragraph.text.indexOf(highlightedQuery, ignoreCase = true)
                            while (start >= 0) {
                                addStyle(SpanStyle(background = if (settings.isDarkPage) Color(0xFF735B16) else Color(0xFFFFE49B)), start, start + highlightedQuery.length)
                                start = paragraph.text.indexOf(highlightedQuery, start + highlightedQuery.length, ignoreCase = true)
                            }
                        }
                    }
                }
                Text(highlighted, style = paragraphStyle,
                    color = Color(settings.pageForeground), fontWeight = if (settings.fontBold) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { bars = !bars }, onLongClick = { selection = paragraph; editMark = -1; note = ""; tags = "" }))
            }
            if (body.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { changeChapter(chapter - 1) }, enabled = chapter > 0) { Text("上一章") }
                TextButton(onClick = { changeChapter(chapter + 1) }, enabled = chapter < toc.lastIndex) { Text("下一章") }
            } }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter))
        if (body.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (loading) loadStage else error, color = Color(settings.pageForeground))
            if (!loading) FlowRow {
                Button(onClick = { retry++ }) { Text("联网重试") }
                TextButton(onClick = { panel = "sources"; viewModel.searchOnline(book.title) }) { Text("换源") }
                TextButton(onClick = { panel = "cleanup" }) { Text("正文净化") }
                TextButton(onClick = { viewModel.updateTrackedBooks(listOf(bookId)) }) { Text("更新目录") }
            }
        }
        if (error.isNotBlank() && body.isNotEmpty()) Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp), color = MaterialTheme.colorScheme.errorContainer) {
            TextButton(onClick = { retry++ }) { Text("$error · 点击重试，已读正文保留") }
        }
        AnimatedVisibility(bars || error.isNotBlank(), modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(book.title, "在线 · ${toc.getOrNull(chapter)?.title.orEmpty()}", settings,
                onBack = { save(); navigator.returnFromReader() }, onSearch = { panel = "search"; query = ""; highlightedQuery = "" },
                onCatalog = { panel = "catalog"; query = "" }, onBookmarks = { panel = "marks" }, onExcerpts = { panel = "marks" }, onSettings = { panel = "settings" }, searchLabel = "搜索正文")
        }
        AnimatedVisibility(bars, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = Color(settings.pageBackground), contentColor = Color(settings.pageForeground)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp)) {
                    Text("缓存优先 · 第 ${chapter + 1}/${toc.size} 章 · 长按正文记笔记", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                    ReaderActionBar(Color(settings.pageForeground),
                        onPrevious = { changeChapter(chapter - 1) }, onCatalog = { panel = "catalog"; query = "" },
                        onMiddle = { bars = false; panel = "listening" },
                        middleIcon = Icons.Default.PlayCircle, middleLabel = "听书", onSettings = { panel = "settings" },
                        onNext = { changeChapter(chapter + 1) }, previousEnabled = chapter > 0 && !loading,
                        nextEnabled = chapter < toc.lastIndex && !loading)
                }
            }
        }
    }
    if (speaking && !bars && panel.isBlank()) Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
        TtsBar(settings, speech.speaking, speech.paused, speech.segment, speech.segmentCount, speech.error,
            onTogglePause = { toggleListening() }, onStop = { stopListening() }, onOpenSettings = { panel = "listening" })
    }
    if (panel == "listening") {
        LaunchedEffect(tts) { tts.ensureReady() }
        val voiceList = remember(speech.ready) { tts.voices() }
        NovelListeningSheet(settings, speech, speaking, onToggle = { toggleListening() },
            onStop = { stopListening() },
            onUpdate = { transform ->
                val next = transform(settings)
                tts.configureVoice(next.ttsVoice, next.ttsRate, next.ttsPitch)
                val applied = tts.state.value
                viewModel.saveBookPreferences(bookId, ReadingPreferences.encode(next.copy(
                    ttsVoice = applied.voiceName, ttsRate = applied.rate, ttsPitch = applied.pitch)))
            },
            onSelectVoice = { name ->
                tts.selectVoice(name)
                viewModel.saveBookPreferences(bookId, ReadingPreferences.encode(settings.copy(ttsVoice = tts.state.value.voiceName)))
            },
            voices = voiceList,
            sleepRemainingMs = speech.sleepRemainingMs,
            onPrevious = { tts.moveSentence(-1) },
            onNext = { tts.moveSentence(1) },
            onPreview = { tts.preview() },
            onStopPreview = { tts.stopPreview() },
            onReconnect = { tts.reconnect() },
            bookTitle = book.title, chapterTitle = toc.getOrNull(chapter)?.title.orEmpty(),
            onDismiss = { panel = "" })
    }
    if (panel == "settings") ReaderSettingsSheet(settings,
        onUpdate = { transform ->
            val next = transform(settings)
            if (horizontal != (next.turnMode == TurnMode.HORIZONTAL) && ready && body.isNotEmpty()) {
                val index = paragraphIndex()
                val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size?.coerceAtLeast(1) ?: 1
                modeAnchor = if (horizontal) pageOffset.coerceAtLeast(0) else body[index].start +
                    (body[index].text.length * (list.firstVisibleItemScrollOffset.toFloat() / height).coerceIn(0f, 1f)).toInt()
            }
            save(); viewModel.saveBookPreferences(bookId, ReadingPreferences.encode(next))
        }, onDismiss = { panel = "" },
        onPickFont = { panel = ""; navigator.openSettings() }, onOpenAllSettings = { panel = ""; navigator.openSettings() },
        onReset = { viewModel.saveBookPreferences(bookId, "") })
    if (panel == "cleanup") NovelCleanupSheet(settings, rawChapterText, { enabled, rules -> save(); viewModel.saveBookPreferences(bookId,
        ReadingPreferences.encode(settings.copy(cleanupEnabled = enabled, cleanupRules = rules))) }, { panel = "" })
    if (panel == "search") NovelTextSearchSheet(viewModel, book, chapter, rawChapterText, settings, { panel = "" }) { target, offset, keyword ->
        highlightedQuery = keyword
        if (target == chapter) { val display = cleanedText.displayOffset(offset); pageSeek = display to ((pageSeek?.second ?: 0) + 1)
            if (!horizontal) scope.launch { list.scrollToItem(body.indexOfLast { it.start <= display }.coerceAtLeast(0)) } }
        else { pendingMark = target to offset; changeChapter(target) }
    }
    if (panel in listOf("catalog", "marks", "sources")) AppModalSheet(onDismissRequest = { panel = "" },
        title = when (panel) { "catalog" -> "在线目录"; "sources" -> "搜索其他书源"; else -> "书签与笔记" }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).navigationBarsPadding().padding(horizontal = 16.dp)) {
            if (panel != "sources") OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), placeholder = { Text(if (panel == "catalog") "章节名或序号" else "当前章关键词") }, singleLine = true)
            if (panel == "catalog") androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { viewModel.updateTrackedBooks(listOf(bookId)) }) { Text("检查更新") }
                TextButton(onClick = { viewModel.searchOnline(book.title); tomatoSources = false; panel = "sources" }) { Text("换源") }
                var more by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { more = true }) { Text("目录工具") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("下载离线") }, onClick = { more = false; viewModel.downloadShelfBook(book) })
                        DropdownMenuItem(text = { Text("重新加载") }, onClick = { more = false; retry++; panel = "" })
                        DropdownMenuItem(text = { Text("加书签") }, onClick = { more = false; annotate(null) })
                        DropdownMenuItem(text = { Text("正文净化") }, onClick = { more = false; save(); panel = "cleanup" })
                        DropdownMenuItem(text = { Text(if (book.tracking) "取消追更" else "加入追更") }, onClick = { more = false; viewModel.toggleTracking(book) })
                    }
                }
                if (book.newChapterCount > 0) TextButton(onClick = { changeChapter((toc.size - book.newChapterCount).coerceAtLeast(0)); panel = "" }) { Text("阅读新增 ${book.newChapterCount} 章") }
            }
            if (panel == "catalog" && book.lastUpdateCheckAt > 0) Text("上次检查：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(book.lastUpdateCheckAt)), style = MaterialTheme.typography.bodySmall)
            if (panel == "marks") TextButton(onClick = { shareText(context, "${book.title} 在线笔记", (0 until annotations.length()).joinToString("\n\n") { i -> val mark = annotations.getJSONObject(i); mark.optString("quote") + "\n" + mark.optString("note") + "\n" + mark.optString("tags") }) }) { Text("导出笔记") }
            if (panel == "sources") {
                Row { TextButton(onClick = { tomatoSources = false; viewModel.searchOnline(book.title) }) { Text("聚合搜书") }
                    TextButton(onClick = { tomatoSources = true; viewModel.searchOnline(book.title, true) }) { Text("番茄小说") } }
                val sourceState = if (tomatoSources) state.tomato else state.online
                if (sourceState.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (sourceState.error.isNotBlank()) Text(sourceState.error, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn {
                when (panel) {
                    "sources" -> itemsIndexed((if (tomatoSources) state.tomato else state.online).results.filter { it.url != book.onlineUrl || it.sourceGroup != book.onlineSourceGroup || it.sourceKey != book.onlineSourceKey }) { _, result ->
                        TextButton(onClick = { switchCandidate = result }, enabled = !switching, modifier = Modifier.fillMaxWidth()) { Text("${result.bookName} · ${result.author} · ${result.sourceName}") }
                    }
                    "catalog" -> itemsIndexed(toc.withIndex().filter { query.isBlank() || it.value.title.contains(query, true) || (it.index + 1).toString() == query }) { _, item ->
                        TextButton(onClick = { changeChapter(item.index); panel = "" }, Modifier.fillMaxWidth()) { Text("${item.index + 1}. ${item.value.title}") }
                    }
                    "search" -> itemsIndexed(body.withIndex().filter { query.isNotBlank() && it.value.text.contains(query, true) }) { _, item ->
                        TextButton(onClick = { highlightedQuery = query; pageSeek = item.value.start to ((pageSeek?.second ?: 0) + 1); if (!horizontal) scope.launch { list.scrollToItem(item.index) }; panel = "" }, Modifier.fillMaxWidth()) { Text(item.value.text.take(160)) }
                    }
                    else -> {
                        item { TextButton(onClick = { annotate(null) }) { Text("添加当前阅读位置书签") } }
                        items(annotations.length()) { i -> val mark = annotations.getJSONObject(i)
                            if (query.isNotBlank() && !(mark.optString("quote") + mark.optString("note") + mark.optString("tags")).contains(query, true)) return@items
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                TextButton(onClick = {
                                    val byUrl = toc.indexOfFirst { it.url == mark.optString("chapterUrl") }
                                    val target = if (byUrl >= 0) byUrl else mark.optInt("chapter", -1).takeIf { it in toc.indices } ?: -1
                                    if (byUrl < 0 && target >= 0) viewModel.message("此笔记来自原书源，已按章序号定位，请核对正文")
                                    if (target >= 0) {
                                        if (target == chapter && loadedChapter == chapter) { val display = cleanedText.displayOffset(mark.optInt("position")); pageSeek = display to ((pageSeek?.second ?: 0) + 1); if (!horizontal) scope.launch { list.scrollToItem(body.indexOfLast { it.start <= display }.coerceAtLeast(0)) } }
                                        else { pendingMark = target to mark.optInt("position"); changeChapter(target) }
                                    }; panel = ""
                                }) { Text(mark.optString("quote").take(100)) }
                                Text(mark.optString("note") + " " + mark.optString("tags"), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { editMark = i; note = mark.optString("note"); tags = mark.optString("tags"); selection = OnlineParagraph(mark.optInt("position"), mark.optString("quote")) }) { Text("编辑") }
                                TextButton(onClick = { val next = JSONArray(); (0 until annotations.length()).filter { it != i }.forEach { next.put(annotations.get(it)) }; viewModel.saveOnlineAnnotations(bookId, next.toString()) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    switchCandidate?.let { candidate -> AlertDialog(onDismissRequest = { if (!switching) switchCandidate = null },
        title = { Text(if (switching) "正在验证新书源…" else "切换到 ${candidate.sourceName}？") },
        text = { Column { Text("验证目录和正文后切换，并匹配当前章节及附近文字。匹配不唯一时按进度比例定位；失败保留原书源。")
            if (switching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) } },
        confirmButton = { TextButton(enabled = !switching, onClick = {
            save(); switching = true
            val job = viewModel.switchOnlineSourceAt(bookId, candidate, rawChapterText, cleanedText.rawOffset(visibleOffset()), chapter) { panel = ""; switchCandidate = null }
            scope.launch { try { job.join() } finally { switching = false } }
        }) { Text("确认换源") } }, dismissButton = { TextButton(enabled = !switching, onClick = { switchCandidate = null }) { Text("取消") } }) }
    selection?.let { paragraph -> AlertDialog(onDismissRequest = { selection = null }, title = { Text("段落笔记") }, text = {
        Column { Text(paragraph.text.take(160)); OutlinedTextField(note, { note = it.take(10000) }, label = { Text("笔记") }); OutlinedTextField(tags, { tags = it.take(200) }, label = { Text("标签，用空格分隔") }) }
    }, confirmButton = { TextButton(onClick = { if (editMark >= 0 && editMark < annotations.length()) { val changed = JSONArray(annotations.toString()); changed.getJSONObject(editMark).put("note", note).put("tags", tags); viewModel.saveOnlineAnnotations(bookId, changed.toString()) } else annotate(paragraph, note, tags); editMark = -1; selection = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { selection = null }) { Text("取消") } }) }
    state.message?.let { MessageToast(it.text, it.error) { viewModel.consumeMessage() } }
}
