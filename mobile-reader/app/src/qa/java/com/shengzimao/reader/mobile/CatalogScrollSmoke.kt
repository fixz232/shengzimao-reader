package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.json.JSONObject

/** Reproduce catalog-end sheet movement using the real details page and 119 original chapters. */
fun catalogScrollSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val root = File(test.targetContext.cacheDir, "catalog-scroll-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val workers = Executors.newFixedThreadPool(2)
    val accept = Thread {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            workers.submit { runCatching { socket.use { connection ->
                val input = connection.getInputStream().bufferedReader()
                input.readLine()
                while (!input.readLine().isNullOrEmpty()) { }
                val body = ("<h1>原创目录滚动测试</h1>" + (1..119).joinToString("") { n ->
                    "<a class='chapter' href='/chapter/$n'>第${n}话${if (n == 119) " 最终话" else ""}</a>"
                }).toByteArray()
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } }
        }
    }.apply { isDaemon = true; start() }
    val source = MangaSource.parse(JSONObject("""{"id":"qa-catalog-scroll","name":"原创目录源","baseUrl":"$base","search":{"url":"/search","items":"a","title":"text","link":"href"},"detail":{"chapters":".chapter","title":"text","link":"href"},"reader":{"pages":"img@src"}}"""))
    MangaStore(File(root, "comics")).saveSources(listOf(source))
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val book = MangaBook("qa-catalog-book", "原创目录滚动测试", source.id, source.name, "$base/book", author = "原创作者",
        intro = "目录末尾滚动时标题和搜索栏应保持稳定。".repeat(18))
    val owner = ViewModelStore()
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    val screen = Rect()
    test.runOnMainSync { activity.window.decorView.getWindowVisibleDisplayFrame(screen) }
    val edgeMargin = (72 * activity.resources.displayMetrics.density).toInt()
    fun node(label: String) = pagingNode(qaRoot(test)) { it.text?.toString() == label }
    fun bounds(label: String): Rect {
        val target = node(label) ?: error("missing catalog element: $label")
        return Rect().also { target.getBoundsInScreen(it) }
    }
    fun swipe(x: Float, start: Float, end: Float) {
        check(qaRoot(test)?.packageName?.toString() == test.targetContext.packageName) { "test app is not foreground; refusing touch injection" }
        val safeStart = start.coerceIn(screen.top + edgeMargin.toFloat(), screen.bottom - edgeMargin.toFloat())
        val safeEnd = end.coerceIn(screen.top + edgeMargin.toFloat(), screen.bottom - edgeMargin.toFloat())
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, safeStart)
        for (i in 1..10) { Thread.sleep(12); inject(MotionEvent.ACTION_MOVE, safeStart + (safeEnd - safeStart) * i / 10) }
        inject(MotionEvent.ACTION_UP, safeEnd)
    }
    try {
        test.runOnMainSync { model = ReaderViewModel(container); owner.put("catalog", model) }
        pagingWait("catalog model ready") { model.ui.value.loaded && model.manga.state.value.loaded }
        test.runOnMainSync {
            activity.setContent { ShengZiMaoTheme(true) { MangaOnlineScreen(model, navigator) } }
            model.manga.openDetails(book)
        }
        pagingWait("119 chapter details loaded") { model.manga.state.value.detail?.chapters?.size == 119 && !model.manga.state.value.detailLoading && node("在线看漫") != null }
        Thread.sleep(700) // Wait for the modal show animation before using screen coordinates.
        val list = pagingNode(qaRoot(test)) { it.isScrollable && it.collectionInfo?.rowCount == 119 }
            ?: error("119 chapter catalog list missing")
        val listBounds = Rect().also { list.getBoundsInScreen(it) }
        record("CATALOG_GESTURE_BOUNDS screen=$screen list=$listBounds edgeMargin=$edgeMargin")
        repeat(24) {
            if (node("第119话 最终话") == null) {
                val currentList = pagingNode(qaRoot(test)) { it.isScrollable && it.collectionInfo?.rowCount == 119 }
                    ?: error("catalog disappeared during swipe")
                currentList.getBoundsInScreen(listBounds)
                swipe(screen.centerX().toFloat(), listBounds.bottom - edgeMargin.toFloat(), listBounds.top + 40f)
                Thread.sleep(180)
            }
        }
        pagingWait("last chapter visible") { node("第119话 最终话") != null }
        val search = bounds("搜索章节名称")
        val last = bounds("第119话 最终话")
        repeat(3) { swipe(last.centerX().toFloat(), last.bottom.toFloat() - 8, search.bottom.toFloat() + 30) }
        Thread.sleep(1200)
        val headerTops = mutableListOf<Int>(); val searchTops = mutableListOf<Int>(); val lastTops = mutableListOf<Int>()
        repeat(36) {
            headerTops += bounds(book.title).top
            searchTops += bounds("搜索章节名称").top
            lastTops += bounds("第119话 最终话").top
            Thread.sleep(80)
        }
        fun span(values: List<Int>) = values.max() - values.min()
        record("CATALOG_BOTTOM_MEASURE headerPx=${span(headerTops)} searchPx=${span(searchTops)} lastChapterPx=${span(lastTops)} samples=${headerTops.size}")
        check(span(headerTops) <= 2 && span(searchTops) <= 2 && span(lastTops) <= 2) { "catalog keeps bouncing after reaching the last chapter" }
        val dragTop = bounds(book.title).top
        val dragStart = bounds("第119话 最终话").centerY().toFloat().coerceAtMost(screen.bottom - edgeMargin.toFloat())
        val dragX = last.centerX().toFloat()
        val down = SystemClock.uptimeMillis()
        fun drag(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, dragX, y, 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        val titleToSearch = mutableListOf<Int>(); val titleToLast = mutableListOf<Int>(); val titlePositions = mutableListOf<Int>()
        drag(MotionEvent.ACTION_DOWN, dragStart)
        repeat(20) { i ->
            drag(MotionEvent.ACTION_MOVE, dragStart - (i + 1) * 7f)
            Thread.sleep(30)
            if (android.os.Build.VERSION.SDK_INT >= 34) test.uiAutomation.clearCache()
            val top = bounds(book.title).top
            titlePositions += top
            titleToSearch += bounds("搜索章节名称").top - top
            titleToLast += bounds("第119话 最终话").top - top
        }
        drag(MotionEvent.ACTION_UP, dragStart - 140f)
        record("CATALOG_DRAG_MEASURE titleMovementPx=${span(titlePositions)} headerSearchGapPx=${span(titleToSearch)} headerLastGapPx=${span(titleToLast)} initialTop=$dragTop")
        check(span(titlePositions) <= 2 && span(titleToSearch) <= 2 && span(titleToLast) <= 2) { "catalog height changes as sheet handles end drag" }
        Thread.sleep(600)
        test.uiAutomation.takeScreenshot()?.let { shot ->
            val top = (bounds(book.title).top - 20).coerceIn(0, shot.height - 1)
            val bottom = (bounds("第119话 最终话").bottom + 80).coerceIn(top + 1, shot.height)
            val cropped = Bitmap.createBitmap(shot, 0, top, shot.width, bottom - top)
            File(test.targetContext.getExternalFilesDir(null), "qa-catalog-bottom.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (cropped !== shot) cropped.recycle()
            shot.recycle()
        }
        record("MANGA_CATALOG_BOTTOM_STABLE_OK 119 chapters; repeated end flings; title, search and final chapter stay still after release")
        fun filter(value: String) {
            val field = pagingNode(qaRoot(test)) { it.isEditable } ?: error("catalog search missing")
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        }
        filter("第119话")
        pagingWait("catalog filtered last chapter") {
            pagingNode(qaRoot(test)) { it.collectionInfo?.rowCount == 1 } != null && node("第119话 最终话") != null
        }
        filter("")
        pagingWait("catalog filter cleared") { pagingNode(qaRoot(test)) { it.collectionInfo?.rowCount == 119 } != null }
        test.runOnMainSync { model.manga.closeDetails() }
        pagingWait("catalog closed") { node("在线看漫") == null }
        test.runOnMainSync { model.manga.openDetails(book) }
        pagingWait("catalog reopened") { node("在线看漫") != null && node("第1话") != null }
        check(model.manga.state.value.detail?.chapters?.size == 119)
        record("MANGA_CATALOG_FILTER_REOPEN_OK last-chapter search, clear filter and reopen preserve full directory")
    } finally {
        test.runOnMainSync { activity.setContent { Text("目录滚动回归结束") } }
        pagingWait("catalog disposed") { node("目录滚动回归结束") != null }
        test.runOnMainSync { owner.clear() }
        server.close(); workers.shutdownNow(); accept.join(1000); root.deleteRecursively()
    }
}
