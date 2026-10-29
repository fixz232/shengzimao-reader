package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.reader.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 原创正文、真实分页和系统 TTS；不读取正式版数据或连接小说源。 */
fun novelPagingListeningSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val body = (1..8).joinToString("\n") { "这是第${it}段原创排版验证文字，长段落跨页后应从左侧接续，新的自然段保持缩进，正文末行不能盖住页脚。".repeat(15) }
    val ready = CountDownLatch(1)
    val layoutError = AtomicReference<Throwable?>()
    var checkedPages = 0
    test.runOnMainSync { activity.setContent {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val settings = ReaderSettings(fontSize = 36, lineSpacingPercent = 260)
        val style = rememberPageStyle(settings, FontFamily.Serif)
        SideEffect {
            runCatching {
                val width = with(density) { 300.dp.roundToPx() }
                val height = with(density) { 500.dp.roundToPx() }
                val starts = Paginator.paginate(measurer, density, body, style, width, height)
                val map = PageMap(starts, IntArray(starts.size), body.length)
                val frames = Paginator.frames(map, body)
                check(frames.joinToString("") { body.substring(it.start, it.end) } == body)
                frames.forEach { frame ->
                    val value = Paginator.pageText(body, frame.start, frame.end)
                    val layout = measurer.measure(value, style, constraints = Constraints(maxWidth = width))
                    val last = value.text.indexOfLast { !it.isWhitespace() }
                    if (last >= 0) check(layout.getLineBottom(layout.getLineForOffset(last)) <= height + 2f) {
                        "page overflow start=${frame.start} bottom=${layout.getLineBottom(layout.getLineForOffset(last))} height=$height"
                    }
                    if (!Paginator.isLineStart(body, frame.start)) check(kotlin.math.abs(layout.getHorizontalPosition(0, true)) <= 2f)
                    checkedPages++
                }
            }.onFailure { layoutError.set(it) }
            ready.countDown()
        }
        Text("原创分页排版核对")
    } }
    check(ready.await(15, TimeUnit.SECONDS))
    layoutError.get()?.let { throw it }
    record("NOVEL_PAGE_REFLOW_OK pages=$checkedPages largeFont=36 spacing=260 continuation aligned; all original characters and final visible lines fit")

    val root = File(test.targetContext.cacheDir, "novel-listening-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    val container = AppContainer(context)
    val preferences = ReadingPreferences.encode(ReaderSettings(fontSize = 32, lineSpacingPercent = 200, paddingDp = 40,
        turnMode = TurnMode.HORIZONTAL, fullScreen = false, pageAnimation = PageAnimation.NONE, showPageHint = false, ttsRate = 2f, ttsSleepMinutes = 15))
    val offline = Book(id = "qa-novel-paging", title = "原创翻页测试", file = container.store.writeText("qa-novel-paging", body),
        length = body.length, chapters = ChapterParser.find(body), readerPreferences = preferences)
    val chapters = listOf(OnlineChapter("第一章", "https://novel-qa.invalid/one"), OnlineChapter("第二章", "https://novel-qa.invalid/two"))
    val online = Book(id = "qa-online-paging", title = "原创在线翻页", onlineOnly = true, onlineUrl = "https://novel-qa.invalid/book",
        onlineManifest = OnlineChapterCodec.encode(chapters), length = 2, readerPreferences = preferences)
    val short = online.copy(id = "qa-online-listening", title = "原创连续听书", onlineUrl = "https://novel-qa.invalid/short")
    val cache = OnlineReadingCache(File(root, "reading-cache/novels"))
    chapters.forEach { cache.write(online.onlineUrl, it.url, body) }
    cache.write(short.onlineUrl, chapters[0].url, "第一章。你好。")
    cache.write(short.onlineUrl, chapters[1].url, "第二章。再见。")
    container.store.save(Library(books = listOf(offline, online, short), consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = ViewModelStore()
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    test.runOnMainSync { model = ReaderViewModel(container); owner.put("novel", model) }
    fun node(label: String) = pagingNode(qaRoot(test)) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    fun click(label: String) {
        pagingWait("enabled listening control: $label") {
            if (android.os.Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
            var target = node(label) ?: return@pagingWait false
            while (!target.isClickable) target = target.parent ?: return@pagingWait false
            target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    fun surface() = pagingNode(qaRoot(test)) { it.contentDescription?.toString()?.contains("页，共") == true }
    fun page(): Int? {
        if (android.os.Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
        return Regex("第 (\\d+) 页").find(surface()?.contentDescription?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
    }
    fun swipe(x: Float, y: Float, dx: Float, dy: Float = 0f) {
        check(qaRoot(test)?.packageName?.toString() == test.targetContext.packageName)
        val started = SystemClock.uptimeMillis()
        fun inject(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x + dx * fraction, y + dy * fraction, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, 0f)
        repeat(8) { Thread.sleep(18); inject(MotionEvent.ACTION_MOVE, (it + 1) / 8f) }
        inject(MotionEvent.ACTION_UP, 1f)
    }
    try {
        pagingWait("isolated novel library") { model.ui.value.loaded }
        for (isOnline in listOf(false, true)) {
            test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) {
                if (isOnline) OnlineReaderScreen(model, navigator, online.id) else ReaderScreen(container, model, offline.id, navigator)
            } } }
            pagingWait("novel page ready") { page() == 1 }
            verifyHorizontalNovel(test, if (isOnline) "ONLINE" else "OFFLINE", record)
            test.waitForIdleSync(); Thread.sleep(350)
            val bounds = Rect().also { surface()!!.getBoundsInScreen(it) }
            val margin = 16f * activity.resources.displayMetrics.density
            swipe(bounds.left + bounds.width() * .85f, bounds.top + margin, -bounds.width() * .25f)
            pagingWait("top-right margin swipe") { page() == 2 }
            swipe(bounds.left + bounds.width() * .15f, bounds.bottom - margin, bounds.width() * .25f)
            pagingWait("bottom-left margin swipe") { page() == 1 }
            swipe(bounds.centerX().toFloat(), bounds.centerY().toFloat(), -bounds.width() * .08f, bounds.width() * .025f)
            pagingWait("short diagonal swipe") { page() == 2 }
            record("NOVEL_CORNER_SWIPE_OK online=$isOnline top-right margin, bottom-left margin, short diagonal drag")
            pagingTouch(test, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            pagingWait("listening entry") { node("听书设置") != null || node("听书") != null }
            click(if (isOnline) "听书" else "听书设置")
            pagingWait("listening panel") { node("开始听书") != null }
            click("开始听书")
            pagingWait("system TTS started") { node("暂停听书") != null }
            val engine = ReaderTts.get(context)
            pagingWait("engine timer started") { engine.state.value.activeSession && engine.state.value.sleepRemainingMs > 0L }
            val services = context.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            pagingWait("listening foreground playback service") { services.getRunningServices(30).any {
                it.service.className == TtsPlaybackService::class.java.name && it.foreground
            } }
            click("暂停听书")
            pagingWait("TTS paused") { node("继续听书") != null }
            val remaining = engine.state.value.sleepRemainingMs
            Thread.sleep(1200)
            check(engine.state.value.sleepRemainingMs == remaining) { "paused listening timer kept running" }
            click("继续听书")
            pagingWait("TTS resumed") { node("暂停听书") != null }
            pagingWait("resumed timer advances") { engine.state.value.sleepRemainingMs < remaining }
            click("停止听书")
            pagingWait("TTS stopped") { node("开始听书") != null }
            check(!engine.state.value.activeSession && engine.state.value.sleepRemainingMs == 0L && !engine.hasPending())
            record("NOVEL_LISTENING_TIMER_SERVICE_OK online=$isOnline media playback foreground service; pause freezes timer; resume advances; stop resets session")
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(test.targetContext.getExternalFilesDir(null), "qa-listening-${if(isOnline) "online" else "offline"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            record("NOVEL_LISTENING_UI_OK online=$isOnline actual system TTS start, pause, resume, stop")
            test.runOnMainSync { activity.setContent { Text("切换原创测试书") } }
            pagingWait("reader disposed") { node("切换原创测试书") != null }
        }
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { OnlineReaderScreen(model, navigator, short.id) } } }
        pagingWait("short chapter ready") { page() == 1 }
        val bounds = Rect().also { surface()!!.getBoundsInScreen(it) }
        pagingTouch(test, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        pagingWait("short listening entry") { node("听书") != null }; click("听书")
        pagingWait("short listening panel") { node("开始听书") != null }; click("开始听书")
        pagingWait("automatic next chapter persisted") { model.ui.value.books.first { it.id == short.id }.position == 1 }
        record("NOVEL_LISTENING_NEXT_CHAPTER_OK cached second chapter automatically opened and position saved")
        if (node("暂停听书") != null || node("继续听书") != null) click("停止听书")
    } finally {
        test.runOnMainSync { activity.setContent { Text("小说翻页与听书验证完成") }; owner.clear() }
        root.deleteRecursively()
    }
}
