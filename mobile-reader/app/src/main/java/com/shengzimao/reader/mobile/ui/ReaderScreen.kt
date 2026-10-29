package com.shengzimao.reader.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.shengzimao.reader.mobile.AppContainer
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.Chapter
import com.shengzimao.reader.mobile.data.Highlight
import com.shengzimao.reader.mobile.data.PageIndicator
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.TurnMode
import com.shengzimao.reader.mobile.reader.FontProvider
import com.shengzimao.reader.mobile.reader.PageFrame
import com.shengzimao.reader.mobile.reader.PageMap
import com.shengzimao.reader.mobile.reader.Paginator
import com.shengzimao.reader.mobile.reader.Paragraphs
import com.shengzimao.reader.mobile.reader.ReaderSurface
import com.shengzimao.reader.mobile.reader.ReaderTts
import com.shengzimao.reader.mobile.reader.SearchHit
import com.shengzimao.reader.mobile.reader.TextSearch
import com.shengzimao.reader.mobile.reader.TextSelection
import com.shengzimao.reader.mobile.reader.rememberPageStyle
import com.shengzimao.reader.mobile.reader.readerFooterHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
    val preferenceJson = libraryState.books.firstOrNull { it.id == bookId }?.readerPreferences.orEmpty()
    val settings = remember(libraryState.settings, preferenceJson) {
        com.shengzimao.reader.mobile.data.ReadingPreferences.novelForBook(preferenceJson, libraryState.settings)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var book by remember(bookId) { mutableStateOf<Book?>(null) }
    val latestBook = libraryState.books.firstOrNull { it.id == bookId } ?: book
    var text by remember(bookId) { mutableStateOf("") }
    var pageMap by remember(bookId) { mutableStateOf<PageMap?>(null) }
    var frames by remember(bookId) { mutableStateOf<List<PageFrame>>(emptyList()) }
    var pageIndex by remember(bookId) { mutableIntStateOf(0) }
    var returnPage by remember(bookId) { mutableStateOf<Int?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var bookmarkVisible by remember { mutableStateOf(false) }
    var excerptVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var readSettingsVisible by remember { mutableStateOf(false) }
    var listeningVisible by remember { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<Highlight?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var paginating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var pageHint by remember { mutableStateOf<String?>(null) }
    var brightnessOverride by remember { mutableStateOf<Float?>(null) }
    val measurer = rememberTextMeasurer(cacheSize = 8)

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fontFamily = remember(settings.font, settings.customFontId, settings.customFontName) {
        FontProvider.family(context, settings)
    }
    val paddingPx = with(density) { settings.paddingDp.dp.toPx() }
    val footerHeight = readerFooterHeight(settings.showFooter)
    // 可用视口由正文容器实测（BoxWithConstraints 回填），不再用「整屏高度 - 22dp」估算。
    // 估算值在关掉「隐藏状态栏」、三键导航、分屏与折叠屏内屏展开时都会与实际可视区不一致，
    // 表现为末行被裁或页脚被系统栏压住。未实测前先用整屏尺寸兜底，避免首帧排版为空。
    var viewport by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    val widthPx = (viewport?.width ?: (with(density) { configuration.screenWidthDp.dp.toPx() } - paddingPx * 2).toInt()).coerceAtLeast(1)
    val heightPx = (viewport?.height ?: (with(density) { configuration.screenHeightDp.dp.toPx() } - paddingPx * 2 - with(density) { footerHeight.toPx() }).toInt()).coerceAtLeast(1)

    val style = rememberPageStyle(settings, fontFamily)
    val layoutKey = listOf(
        settings.fontSize,
        settings.lineSpacingPercent,
        settings.font.name,
        settings.customFontId,
        settings.fontBold,
        settings.paddingDp,
        settings.indentParagraphs,
        widthPx,
        heightPx,
        text.length,
        density.fontScale,
        density.density,
    )
    val signature = remember(layoutKey) {
        Paginator.signatureFor(
            width = widthPx,
            height = heightPx,
            fontSize = settings.fontSize,
            lineSpacingPercent = settings.lineSpacingPercent,
            fontKey = "${settings.font.name}:${settings.customFontId}:${settings.paddingDp}:${settings.indentParagraphs}:${density.fontScale}:${density.density}",
            bold = settings.fontBold,
            indent = settings.indentParagraphs,
            textLength = text.length,
        )
    }

    // 打开书籍：读取正文与章节，失败给出可解释的提示。
    LaunchedEffect(bookId) {
        try {
            val loaded = viewModel.openBook(bookId)
            if (loaded == null) {
                error = "正文缺失或无法读取，请回到书库重新导入这本书。"
            } else {
                book = loaded.first
                text = loaded.second
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "打开书籍失败：${failure.message ?: "无法读取正文"}"
        }
    }

    // 分页：样式或尺寸变化时重排，并以“上次保存的阅读位置”为锚点落回原处。
    // 锚点来源优先级：已排好的当前页 > 书籍里保存的位置（首次打开时用后者）。
    LaunchedEffect(signature, book) {
        if (text.isEmpty()) {
            pageMap = null
            frames = emptyList()
            return@LaunchedEffect
        }
        paginating = true
        progress = 0f
        val anchor = when {
            pageMap != null -> pageMap!!.pageStart(pageIndex)
            else -> book?.position ?: 0
        }
        val chapters = book?.chapters.orEmpty()
        try {
            val cache = com.shengzimao.reader.mobile.reader.PageMapCache(java.io.File(context.cacheDir, "pagination"))
            val cacheKey = withContext(Dispatchers.Default) { cache.key(text, signature, chapters.joinToString { "${it.position}:${it.title}" }) }
            val cached = withContext(Dispatchers.IO) { cache.read(cacheKey, text.length, chapters.size) }
            val measured = cached ?: withContext(Dispatchers.Default) {
                val slices = ArrayList<IntArray>()
                for (range in Paginator.sliceBoundaries(text)) {
                    currentCoroutineContext().ensureActive()
                    slices.add(Paginator.paginate(measurer, density, text, style, widthPx, heightPx, range.first, range.last + 1))
                    withContext(Dispatchers.Main) { progress = (range.last + 1).toFloat() / text.length }
                }
                Paginator.assemble(text, chapters, slices)
            }
            if (cached == null) withContext(Dispatchers.IO) { cache.write(cacheKey, measured) }
            pageMap = measured
            frames = Paginator.frames(measured, text)
            pageIndex = measured.pageOf(anchor)
            progress = 1f
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "正文排版失败：${failure.message ?: "请尝试调整字号或字体后重试"}"
        } finally {
            paginating = false
        }
    }

    // 阅读时长：仅在页面 RESUMED 且“最近一分钟内有阅读动作”时累加，
    // 避免开着阅读页发呆也把时长记进统计。
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastActivity by remember(bookId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(bookId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(20_000)
                if (System.currentTimeMillis() - lastActivity <= 90_000) {
                    viewModel.recordReading(bookId, 20)
                }
            }
        }
    }

    // 朗读（TTS）：按句朗读当前页，读完自动翻页续读（可在设置里关闭）。
    // 用进程级单例而不是 remember{ReaderTts(...)}：后者会在离开阅读页时销毁引擎，
    // 导致锁屏或切后台立刻中断朗读。
    val tts = remember { ReaderTts.get(context) }
    val ttsState by tts.state.collectAsState()
    var ttsActive by remember(bookId) { mutableStateOf(false) }
    val ttsPaused = ttsState.paused
    var ttsRange by remember(bookId) { mutableStateOf<IntRange?>(null) }
    var ttsSegments by remember(bookId) { mutableStateOf<List<IntRange>>(emptyList()) }
    // 朗读段序号映射：引擎里的段序号是“本页从 startAt 开始”的相对值。
    var ttsOffset by remember(bookId) { mutableIntStateOf(0) }
    var spokenPage by remember(bookId) { mutableIntStateOf(-1) }

    DisposableEffect(tts) {
        // 只解绑回调，不销毁引擎：离开阅读页后仍要继续朗读（听书的核心场景）。
        onDispose { tts.detach() }
    }

    // 一次恢复完整配置，避免切声线覆盖调音、或同一句被重复提交。
    LaunchedEffect(settings.ttsVoice, settings.ttsRate, settings.ttsPitch) {
        tts.configureVoice(settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
    }

    val currentMap = pageMap
    val pageCount = currentMap?.pageCount ?: 1
    val pageStart = currentMap?.pageStart(pageIndex) ?: 0
    val pageEnd = currentMap?.pageEnd(pageIndex) ?: text.length
    val chapterIndex = currentMap?.pageChapter?.getOrNull(pageIndex) ?: -1
    val chapters = book?.chapters.orEmpty()
    val chapterTitle = if (chapterIndex in chapters.indices) chapters[chapterIndex].title else book?.title.orEmpty()

    fun pageFooter(): String {
        val map = pageMap ?: return ""
        return when (settings.pageIndicator) {
            PageIndicator.BOOK -> "${pageIndex + 1} / ${map.pageCount}"
            PageIndicator.CHAPTER -> {
                val (current, total) = map.chapterPage(pageIndex)
                if (total <= 0) "${pageIndex + 1} / ${map.pageCount}" else "本章 $current / $total"
            }
            PageIndicator.PERCENT -> "${(map.progressOf(pageIndex) * 100).toInt()}%"
        }
    }

    fun showHint() {
        if (!settings.showPageHint) return
        val map = pageMap ?: return
        val percent = (map.progressOf(pageIndex) * 100).toInt()
        pageHint = if (chapterIndex >= 0) "第 ${chapterIndex + 1} 章 · $percent%" else "$percent%"
    }

    /** 收集当前页的段落区间，供 TTS 逐段朗读与高亮。 */
    fun segmentsOfPage(): List<IntRange> {
        val map = pageMap ?: return emptyList()
        val start = map.pageStart(pageIndex)
        val end = map.pageEnd(pageIndex).coerceAtMost(text.length)
        return Paragraphs.of(text, start, end)
            .map { it.start until it.end }
            .filter { range -> text.substring(range.first, range.last + 1).any { !it.isWhitespace() } }
    }

    fun speakPage(fromParagraph: Int = 0) {
        val ranges = segmentsOfPage()
        ttsSegments = ranges
        if (ranges.isEmpty()) {
            viewModel.message("这一页没有可朗读的文字")
            return
        }
        val startAt = fromParagraph.coerceIn(0, ranges.lastIndex)
        val visible = ranges.drop(startAt)
        ttsRange = ranges.getOrNull(startAt)
        spokenPage = pageIndex
        // 朗读偏移：引擎里的段序号从 0 开始，需要映射回页内绝对段。
        ttsOffset = startAt
        // 拉起前台服务，保证锁屏 / 切后台继续朗读；通知栏可暂停、继续、停止。
        ttsActive = tts.speakSegments(visible.map { text.substring(it.first, it.last + 1).replace('\n', ' ').replace("　", "") })
        if (ttsActive && !com.shengzimao.reader.mobile.TtsPlaybackService.start(context)) {
            viewModel.message("无法启动后台听书服务，请保持阅读页在前台", true)
        }
    }

    fun stopSpeaking() {
        ttsActive = false
        ttsRange = null
        ttsSegments = emptyList()
        tts.stop()
    }

    LaunchedEffect(settings.ttsSleepMinutes) { tts.setSleepMinutes(settings.ttsSleepMinutes) }
    LaunchedEffect(ttsState.activeSession) {
        if (!ttsState.activeSession) { ttsActive = false; ttsRange = null }
    }

    fun toggleListening() {
        when {
            ttsState.paused -> { ttsActive = tts.resume() }
            ttsActive && (ttsState.speaking || ttsState.initializing) -> tts.pause()
            else -> speakPage()
        }
    }
    val latestSpeechFinished by rememberUpdatedState<() -> Unit> {
        if (ttsActive && settings.autoTtsTurnPage && pageIndex < pageCount - 1) {
            pageIndex++; speakPage()
        } else {
            stopSpeaking()
            if (pageIndex >= pageCount - 1) viewModel.message("全书已朗读完毕")
        }
    }
    DisposableEffect(tts) {
        tts.onQueueFinished = { latestSpeechFinished() }
        onDispose {
            tts.detach()
        }
    }
    // 高亮跟随「当前正在读的第几段」。
    // 引擎按句推进，但高亮粒度是段落，所以这里用 segment 而不是 sentenceIndex：
    // 一句话读完时 segment 仍指向它所在的段落，段落切换时才跳，视觉上才平滑。
    LaunchedEffect(ttsState.segment, ttsOffset) {
        if (ttsActive && ttsState.segment >= 0) ttsRange = ttsSegments.getOrNull(ttsOffset + ttsState.segment)
    }
    LaunchedEffect(ttsState.error) {
        ttsState.error?.let { ttsActive = false; ttsRange = null; viewModel.message(it, true) }
    }
    LaunchedEffect(pageIndex) { if (ttsActive && spokenPage != pageIndex) stopSpeaking() }
    LaunchedEffect(signature) { if (ttsActive) stopSpeaking() }

    fun closeReader() {
        stopSpeaking()
        currentMap?.let { viewModel.savePosition(bookId, it.pageStart(pageIndex)) }
        navigator.returnFromReader()
    }

    BackHandler {
        when {
            selection != null -> selection = null
            searchHits.isNotEmpty() -> searchHits = emptyList()
            noteTarget != null -> noteTarget = null
            searchVisible -> searchVisible = false
            excerptVisible -> excerptVisible = false
            bookmarkVisible -> bookmarkVisible = false
            catalogVisible -> catalogVisible = false
            readSettingsVisible -> readSettingsVisible = false
            listeningVisible -> listeningVisible = false
            menuVisible -> menuVisible = false
            else -> closeReader()
        }
    }

    // 切到后台/退出阅读页时保存进度：不能只依赖返回键。
    val latestPageMap by rememberUpdatedState(pageMap)
    val latestPageIndex by rememberUpdatedState(pageIndex)
    DisposableEffect(bookId) {
        onDispose {
            latestPageMap?.let { viewModel.savePosition(bookId, it.pageStart(latestPageIndex)) }
        }
    }

    // 进程可能在后台被系统回收：仅在 onDispose 保存会丢掉整次阅读进度，
    // 这里补一条 ON_STOP 保存，与 20 秒阅读时长心跳保持同一可靠性标准。
    DisposableEffect(bookId, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                latestPageMap?.let { viewModel.savePosition(bookId, it.pageStart(latestPageIndex)) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 音量键翻页：开启后音量下 = 下一页、音量上 = 上一页；未开启时保留系统行为。
    DisposableEffect(settings.volumeKeysTurnPages, pageCount) {
        val activity = (context as? android.app.Activity)
        if (!settings.volumeKeysTurnPages || activity == null) {
            onDispose { }
        } else {
            val listener = android.view.View.OnKeyListener { _, keyCode, event ->
                if (event == null || event.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener false
                if (pageMap == null) return@OnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (selection != null) selection = null
                        if (pageIndex < pageCount - 1) {
                            pageIndex++
                            lastActivity = System.currentTimeMillis()
                            showHint()
                        } else {
                            viewModel.message("已读到全书结尾")
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (selection != null) selection = null
                        if (pageIndex > 0) {
                            pageIndex--
                            lastActivity = System.currentTimeMillis()
                            showHint()
                        } else {
                            viewModel.message("已经是全书开头")
                        }
                        true
                    }
                    else -> false
                }
            }
            activity.window.decorView.setOnKeyListener(listener)
            onDispose { activity.window.decorView.setOnKeyListener(null) }
        }
    }

    // 应用内亮度：覆盖系统亮度，离开阅读页恢复。
    DisposableEffect(settings.screenBrightness, brightnessOverride) {
        val activity = (context as? android.app.Activity)
        val value = brightnessOverride ?: settings.screenBrightness
        if (activity == null) return@DisposableEffect onDispose { }
        val window = activity.window
        val params = window.attributes
        params.screenBrightness = if (value < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.coerceIn(0.02f, 1f)
        runCatching { window.attributes = params }
        onDispose {
            val restore = window.attributes
            restore.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            runCatching { window.attributes = restore }
        }
    }

    // 翻页浮层 1.2 秒后自动消失。
    LaunchedEffect(pageHint) {
        if (pageHint != null) {
            delay(1200)
            pageHint = null
        }
    }

    // 选区随翻页/重排失效。
    LaunchedEffect(pageIndex, pageMap) { selection = null }

    // 当前页的划线（只算与当前页相交的，绘制时再裁剪）。
    val pageHighlights = remember(latestBook?.highlights, pageMap, pageIndex) {
        latestBook?.highlights.orEmpty().filter { it.end > pageStart && it.start < pageEnd }
    }
    val pageSearchHits = remember(searchHits, pageMap, pageIndex) {
        searchHits.map { Offset(it.position.toFloat(), (it.position + it.length).toFloat()) }
            .filter { it.y > pageStart && it.x < pageEnd }
    }
    // 选中态换算成页内相对坐标供绘制。
    val relativeSelection = selection?.let { sel ->
        val ordered = sel.ordered()
        TextSelection(
            ordered.start.coerceIn(pageStart, pageEnd) - pageStart,
            ordered.end.coerceIn(pageStart, pageEnd) - pageStart,
        )
    }
    val relativeTts = ttsRange?.let { range ->
        val from = range.first.coerceIn(pageStart, pageEnd) - pageStart
        val to = (range.last + 1).coerceIn(pageStart, pageEnd) - pageStart
        if (to <= from) null else from until to
    }

    val theme = settings
    val background = Color(theme.pageBackground)
    val onBackground = Color(theme.pageForeground)
    val visibleMessage = libraryState.message

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    viewModel.message("正在校验并导入字体…")
                    val (id, name) = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { FontProvider.readFontBytes(it) }
                            ?: error("读取字体文件失败")
                        require(FontProvider.isUsable(bytes)) { "这个文件不是可用的字体" }
                        currentCoroutineContext().ensureActive()
                        val id = "custom-" + java.util.UUID.randomUUID().toString()
                        val name = runCatching {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.substringBeforeLast('.') else null
                            }
                        }.getOrNull().orEmpty()
                        FontProvider.import(context, id, bytes)
                        id to name
                    }
                    viewModel.updateSettings { s ->
                        s.copy(font = com.shengzimao.reader.mobile.data.FontChoice.CUSTOM, customFontId = id, customFontName = name)
                    }
                    viewModel.message("已应用导入字体")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    viewModel.message(failure.message ?: "字体导入失败，请重试", error = true)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        if (error != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error ?: "", color = onBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { navigator.returnFromReader() }) { Text("返回书库") }
            }
            return@Box
        }
        if (pageMap == null || frames.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (paginating) "正在排版 ${(progress * 100).toInt()}%" else "正在打开…",
                        color = onBackground.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(book?.title.orEmpty(), color = onBackground.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        } else {
            // 实测正文可用区，回填给分页使用；同时按设置决定是否避让系统栏。
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (settings.fullScreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
            ) {
                val measured = androidx.compose.ui.unit.IntSize(
                    with(density) { (maxWidth - settings.paddingDp.dp * 2).roundToPx() }.coerceAtLeast(1),
                    with(density) { (maxHeight - settings.paddingDp.dp * 2 - footerHeight).roundToPx() }.coerceAtLeast(1),
                )
                LaunchedEffect(measured) {
                    if (viewport != measured) viewport = measured
                }
                ReaderSurface(
                modifier = Modifier.fillMaxSize(),
                text = text,
                frames = frames,
                pageIndex = pageIndex,
                settings = settings,
                progressText = pageFooter(),
                chapterText = chapterTitle,
                theHighlights = pageHighlights,
                searchHits = pageSearchHits,
                selection = relativeSelection,
                ttsRange = relativeTts,
                fontFamily = fontFamily,
                onPrev = {
                    if (selection != null) {
                        selection = null
                    } else if (pageIndex > 0) {
                        pageIndex--
                        lastActivity = System.currentTimeMillis()
                        showHint()
                    } else {
                        viewModel.message("已经是全书开头")
                    }
                },
                onNext = {
                    if (selection != null) {
                        selection = null
                    } else if (pageIndex < pageCount - 1) {
                        pageIndex++
                        lastActivity = System.currentTimeMillis()
                        showHint()
                    } else {
                        viewModel.message("已读到全书结尾")
                    }
                },
                onTapCenter = {
                    if (selection != null) {
                        selection = null
                    } else {
                        menuVisible = !menuVisible
                    }
                    lastActivity = System.currentTimeMillis()
                },
                onSelectStart = { offset ->
                    val absolute = offset + pageStart
                    selection = TextSelection(absolute, absolute + 1)
                    menuVisible = false
                    lastActivity = System.currentTimeMillis()
                },
                onSelectionExtend = { sel ->
                    selection = TextSelection(sel.start + pageStart, sel.end + pageStart)
                },
                onSeekPage = { target ->
                    if (target != pageIndex) {
                        pageIndex = target
                        lastActivity = System.currentTimeMillis()
                        showHint()
                    }
                },
                onEdgeSwipe = { delta ->
                    // 左边缘左右拖动调亮度：取当前生效值再累加。
                    val base = brightnessOverride ?: settings.screenBrightness.let { if (it < 0f) 0.5f else it }
                    val next = (base + delta).coerceIn(0.02f, 1f)
                    brightnessOverride = next
                    viewModel.updateSettings { s -> s.copy(screenBrightness = next) }
                },
                pageHint = pageHint,
                contentPadding = PaddingValues(settings.paddingDp.dp),
            )

            // 朗读悬浮条
            if (ttsActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    TtsBar(
                        settings = settings,
                        speaking = ttsState.speaking,
                        paused = ttsPaused,
                        segment = ttsSegments.indexOfFirst { it == ttsRange },
                        total = ttsSegments.size,
                        error = ttsState.error,
                        onTogglePause = {
                            toggleListening()
                        },
                        onStop = { stopSpeaking() },
                        onOpenSettings = { listeningVisible = true },
                    )
                }
            }
            // 结束 BoxWithConstraints（只包住正文与朗读悬浮条，菜单栏留在外层不受页边距影响）。
            }

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
                        settings = settings,
                        onBack = { closeReader() },
                        onSearch = {
                            menuVisible = false
                            searchVisible = true
                        },
                        onCatalog = {
                            catalogVisible = true
                            menuVisible = false
                        },
                        onBookmarks = {
                            bookmarkVisible = true
                            menuVisible = false
                        },
                        onExcerpts = {
                            excerptVisible = true
                            menuVisible = false
                        },
                        onSettings = {
                            readSettingsVisible = true
                            menuVisible = false
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selection != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val selected = selection!!
                        SelectionBar(
                            theme = settings,
                            length = selected.length,
                            onCopy = {
                                val value = text.substring(
                                    selected.start.coerceIn(0, text.length),
                                    selected.end.coerceIn(0, text.length),
                                )
                                copyToClipboard(context, value)
                                viewModel.message("已复制 ${value.length} 字")
                                selection = null
                            },
                            onHighlight = {
                                viewModel.addHighlight(bookId, selected, text)
                                selection = null
                            },
                            onNote = {
                                noteTarget = Highlight(start = selected.start, end = selected.end)
                                selection = null
                            },
                            onClear = { selection = null },
                        )
                    }
                } else {
                    AnimatedVisibility(
                        visible = menuVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        ReaderBottomBar(
                            pageOffset = { page -> pageMap?.pageStart(page) ?: 0 },
                            settings = settings,
                            book = book,
                            pageIndex = pageIndex,
                            pageCount = pageCount,
                            chapterTitle = chapterTitle,
                            percentText = pageFooter(),
                            speaking = ttsActive,
                            paused = ttsPaused,
                            ttsError = ttsState.error,
                            onPrevChapter = {
                                val target = chapters.getOrNull(chapterIndex - 1)?.position
                                if (target == null) {
                                    viewModel.message("已经是第一章")
                                } else {
                                    pageIndex = pageMap!!.pageOf(target)
                                    showHint()
                                }
                            },
                            onNextChapter = {
                                val target = chapters.getOrNull(chapterIndex + 1)?.position
                                if (target == null) {
                                    viewModel.message("已经是最后一章")
                                } else {
                                    pageIndex = pageMap!!.pageOf(target)
                                    showHint()
                                }
                            },
                            onSeek = { fraction ->
                                stopSpeaking()
                                returnPage = pageIndex
                                pageIndex = (fraction * (pageCount - 1)).toInt().coerceIn(0, pageCount - 1)
                                lastActivity = System.currentTimeMillis()
                                showHint()
                            },
                            onToggleSpeak = {
                                menuVisible = false; listeningVisible = true
                            },
                            onCatalog = { menuVisible = false; catalogVisible = true },
                            onStopSpeak = { stopSpeaking() },
                            onSettings = {
                                menuVisible = false
                                readSettingsVisible = true
                            },
                        )
                    }
                }
            }
            if (catalogVisible) {
                CatalogSheet(
                    chapters = chapters,
                    currentPosition = pageStart,
                    theme = settings,
                    onDismiss = { catalogVisible = false },
                    onSelect = { chapter: Chapter ->
                        catalogVisible = false
                        pageIndex = pageMap!!.pageOf(chapter.position)
                        showHint()
                    },
                )
            }
            if (bookmarkVisible) {
                BookmarkSheet(
                    book = latestBook,
                    text = text,
                    onDismiss = { bookmarkVisible = false },
                    onSelect = { mark ->
                        bookmarkVisible = false
                        pageIndex = pageMap!!.pageOf(mark.position)
                    },
                    onDelete = { mark -> viewModel.removeBookmark(bookId, mark.id) },
                    onAdd = {
                        viewModel.addBookmark(bookId, pageStart, chapterTitle)
                        viewModel.message("已在本页添加书签")
                    },
                )
            }
            if (excerptVisible) {
                ExcerptSheet(
                    book = latestBook ?: Book(title = bookId),
                    text = text,
                    onDismiss = { excerptVisible = false },
                    onSelect = { mark ->
                        excerptVisible = false
                        pageIndex = pageMap!!.pageOf(mark.start)
                        showHint()
                    },
                    onEditNote = { mark -> noteTarget = mark },
                    onDelete = { mark -> viewModel.removeHighlight(bookId, mark.id) },
                    onShareAll = {
                        val body = buildString {
                            append("《").append(book?.title.orEmpty()).append("》摘录\n\n")
                            latestBook?.highlights.orEmpty().sortedBy { it.start }.forEach { mark ->
                                append("· ").append(mark.snippet.ifBlank { text.substring(mark.start, mark.end) })
                                if (mark.isNote) append("\n  笔记：").append(mark.note)
                                if (mark.tags.isNotEmpty()) append("\n  标签：").append(mark.tags.joinToString(" "))
                                append("\n\n")
                            }
                        }
                        shareText(context, "《${book?.title.orEmpty()}》摘录", body)
                    },
                )
            }
            if (searchVisible) {
                ReaderSearchSheet(
                    initialQuery = "",
                    onSearch = { keyword ->
                        withContext(Dispatchers.Default) {
                            TextSearch.search(text, chapters, book?.title.orEmpty(), keyword)
                        }
                    },
                    onDismiss = { searchVisible = false },
                    onSelect = { hit ->
                        searchVisible = false
                        searchHits = TextSearch.search(text, chapters, book?.title.orEmpty(), text.substring(hit.position, hit.position + hit.length)).take(200)
                        pageIndex = pageMap!!.pageOf(hit.position)
                        showHint()
                    },
                )
            }
            if (listeningVisible) {
                LaunchedEffect(tts) { tts.ensureReady() }
                val voiceList = remember(ttsState.ready) { tts.voices() }
                NovelListeningSheet(
                    settings, ttsState, ttsActive,
                    onToggle = { toggleListening() },
                    onStop = { stopSpeaking() },
                    onUpdate = { transform ->
                        val next = transform(settings)
                        tts.configureVoice(next.ttsVoice, next.ttsRate, next.ttsPitch)
                        val applied = tts.state.value
                        viewModel.saveBookPreferences(bookId,
                            com.shengzimao.reader.mobile.data.ReadingPreferences.encode(next.copy(
                                ttsVoice = applied.voiceName, ttsRate = applied.rate, ttsPitch = applied.pitch)))
                    },
                    onSelectVoice = { name ->
                        tts.selectVoice(name)
                        // 音色也要存进偏好设置，下次打开这本书继续用。
                        viewModel.saveBookPreferences(bookId,
                            com.shengzimao.reader.mobile.data.ReadingPreferences.encode(
                                settings.copy(ttsVoice = tts.state.value.voiceName)))
                    },
                    voices = voiceList,
                    sleepRemainingMs = ttsState.sleepRemainingMs,
                    onPrevious = { tts.moveSentence(-1) },
                    onNext = { tts.moveSentence(1) },
                    onPreview = { tts.preview() },
                    onStopPreview = { tts.stopPreview() },
                    onReconnect = { tts.reconnect() },
                    bookTitle = book?.title.orEmpty(), chapterTitle = chapterTitle,
                    onDismiss = { listeningVisible = false })
            }
            if (readSettingsVisible) {
                ReaderSettingsSheet(
                    settings = settings,
                    onUpdate = { transform -> viewModel.saveBookPreferences(bookId,
                        com.shengzimao.reader.mobile.data.ReadingPreferences.encode(transform(settings))) },
                    onDismiss = { readSettingsVisible = false },
                    onReset = { viewModel.saveBookPreferences(bookId, "") },
                    onPickFont = { fontLauncher.launch(arrayOf("font/*", "application/octet-stream", "*/*")) },
                    onOpenAllSettings = {
                        readSettingsVisible = false
                        navigator.openSettings()
                    },
                )
            }
            noteTarget?.let { target ->
                NoteDialog(
                    initial = target.note,
                    quote = runCatching { text.substring(target.start.coerceIn(0, text.length), target.end.coerceIn(0, text.length)) }.getOrDefault(""),
                    onDismiss = { noteTarget = null },
                    initialTags = target.tags,
                    onSave = { note, tags ->
                        viewModel.addHighlight(bookId, TextSelection(target.start, target.end), text, note = note, tags = tags)
                        noteTarget = null
                    },
                )
            }
        }
        if (returnPage != null && pageMap != null) {
            androidx.compose.material3.FilledTonalButton(onClick = {
                pageIndex = returnPage!!.coerceIn(0, pageMap!!.pageCount - 1); returnPage = null; showHint()
            }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (menuVisible) 150.dp else 48.dp)) { Text("返回跳转前的位置") }
        }
        if (visibleMessage != null) {
            MessageToast(visibleMessage.text, visibleMessage.error) { viewModel.consumeMessage() }
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText("摘录", value)) }
}

internal fun shareText(context: Context, title: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
