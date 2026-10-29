package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Original fixtures, production search/reader, real HTTP headers and a new controller after reopening. */
fun mangaReaderSessionSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val root = File(test.targetContext.cacheDir, "manga-session-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val workers = Executors.newFixedThreadPool(4)
    val chapterHits = AtomicInteger(); val imageHits = AtomicInteger(); val rejected = AtomicInteger()
    val slowStarted = CountDownLatch(1); val slowRelease = CountDownLatch(1)
    val imageFiles = (1..6).map { number -> File(root,"comics/local/qa-session/original-$number.png").apply {
        parentFile?.mkdirs()
        Bitmap.createBitmap(300, 1500, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(42 + number * 10, 65, 90))
            val canvas = Canvas(bitmap); val paint = Paint().apply { color = Color.WHITE; textSize = 24f }
            for (line in 0..14) canvas.drawText("ORIGINAL $number / $line", 25f, 65f + line * 95, paint)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    } }
    val accept = Thread {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            workers.submit { runCatching { socket.use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "/" }.substringBefore('?')
                val headers = mutableMapOf<String,String>()
                while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                var status = 200
                val bytes = when {
                    path == "/slow" -> { slowStarted.countDown(); slowRelease.await(12, TimeUnit.SECONDS)
                        "<div class='book'><a href='/late'>原创漫画晚到</a></div>".toByteArray() }
                    path == "/search" -> "<div class='book'><a href='/comic'>原创漫画</a></div>".toByteArray()
                    path == "/empty" -> { chapterHits.incrementAndGet(); "<h1>目录不可用</h1>".toByteArray() }
                    path.startsWith("/chapter/") -> { chapterHits.incrementAndGet(); (1..6).joinToString("") { "<img src='/image/$it.png'>" }.toByteArray() }
                    path.startsWith("/image/") -> {
                        if (headers["referer"] != "$base/chapter/1" || headers["region"] != "0") { status = 403; rejected.incrementAndGet(); "context missing".toByteArray() }
                        else { imageHits.incrementAndGet(); imageFiles[path.substringAfterLast('/').substringBefore('.').toInt()-1].readBytes() }
                    }
                    else -> "<a class='chapter' href='/chapter/1'>第一话 原创</a>".toByteArray()
                }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $status Fixture\r\nContent-Type: ${if (path.endsWith(".png") && status == 200) "image/png" else "text/html; charset=utf-8"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            } } }
        }
    }.apply { isDaemon = true; start() }
    fun source(id: String, search: String) = MangaSource.parse(JSONObject("""{"id":"$id","name":"$id","baseUrl":"$base",
        "search":{"url":"$search?q={query}","items":".book","title":"a","link":"a@href"},
        "detail":{"chapters":".chapter","title":"text","link":"href"},"reader":{"pages":"img@src"}}"""))
    val fastRaw = source("qa-session-fast", "/search")
    val fast = fastRaw.copy(definition = fastRaw.rules.put("headers",JSONObject().put("region","0")).toString())
    val slow = source("qa-session-slow", "/slow")
    val headerSource = MangaSource.parse(fast.rules.put("id","qa-session-headers").put("name","原创请求头源")
        .put("legacy", JSONObject("""{"ruleBookContent":"img@src@Header:{Referer:thisPage,region:'0'}"}""")))
    val store = MangaStore(File(root,"comics")); store.saveSources(listOf(fast,slow,headerSource))
    val preferences = ReadingPreferences.encode(MangaSettings(mode = MangaReadingMode.HORIZONTAL, autoReadIntervalSeconds = 2,
        autoScrollDpPerSecond = 120, immersive = false, autoNextChapter = false, prefetchPages = 0))
    val online = MangaBook("qa-session-online","原创在线漫画",headerSource.id,headerSource.name,"$base/comic",onlineOnly = true,
        chapters = listOf(MangaChapter("第一话 原创","$base/chapter/1")),readerPreferences = preferences)
    val local = MangaBook("qa-session-local","原创本地漫画",chapters = listOf(MangaChapter("本地第一话","local:session",imageFiles.map { it.toURI().toString() })),readerPreferences = preferences)
    store.upsert(online); store.upsert(local)
    val container = AppContainer(context); container.store.save(Library(consentAccepted = true,consentVersion = Consent.VERSION))
    val owner = ViewModelStore(); lateinit var model: ReaderViewModel
    fun node(value: String) = pagingNode(qaRoot(test)) { it.text?.toString() == value || it.contentDescription?.toString() == value }
    fun shown(value: String) = pagingNode(qaRoot(test)) { it.text?.toString()?.contains(value) == true } != null
    fun click(value: String) { pagingWait("session click $value") {
        var n = node(value) ?: return@pagingWait false
        while (!n.isClickable) n = n.parent ?: return@pagingWait false
        n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } }
    fun newModel() { test.runOnMainSync { model = ReaderViewModel(container); owner.put("session",model) }
        pagingWait("session model loaded") { model.ui.value.loaded && model.manga.state.value.loaded } }
    fun leave(label: String) { test.runOnMainSync { activity.setContent { Text(label) } }; pagingWait(label) { shown(label) }; test.waitForIdleSync() }
    fun show(id: String) { test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaReaderScreen(model,Navigator(),id) } } }
        pagingWait("session image displayed") { node("第 1 页") != null } }
    fun screenshot(name: String) { test.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(test.targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() } }
    try {
        newModel()
        val nativePages = runBlocking { model.manga.engine.pages(fast,online.chapters[0]) }
        val nativeImage = runBlocking { model.manga.engine.imageFile(nativePages[0],fast.id) }
        check(nativeImage.length() > 1000 && rejected.get() == 0)
        record("MANGA_DEFAULT_CHAPTER_REFERER_OK declarative image request uses chapter URL and preserves configured headers")
        val cachedHits = imageHits.get()
        nativeImage.writeText("<html>old invalid cache</html>")
        val stages = mutableListOf<String>()
        val repaired = runBlocking { model.manga.engine.imageFile(nativePages[0],fast.id,onStage = { stages += it }) }
        check(repaired.length() > 1000 && imageHits.get() == cachedHits + 1 && stages.any { it.contains("下载图片") })
        record("MANGA_INVALID_CACHE_REPAIR_OK invalid old file refetched and validated; download stage visible")
        test.runOnMainSync { model.manga.selectSources(setOf(fast.id,slow.id)); model.manga.search("原创漫画") }
        check(slowStarted.await(5, TimeUnit.SECONDS))
        pagingWait("fast result before slow source") { model.manga.state.value.results.any { it.sourceId == fast.id } && model.manga.state.value.searching }
        test.runOnMainSync { model.manga.cancelSearch() }; slowRelease.countDown(); Thread.sleep(500)
        check(model.manga.state.value.results.none { it.url.endsWith("late") })
        record("MANGA_SEARCH_STREAM_CANCEL_OK first source visible before completion; cancelled late response cannot overwrite results")
        val first = runBlocking { model.manga.ensurePages(online.id,0) }
        check(first.size == 6 && store.books().first { it.id == online.id }.chapters[0].imageHeaders.isNotEmpty())
        val before = chapterHits.get()
        test.runOnMainSync { owner.clear() }; newModel()
        val reopened = runBlocking { model.manga.ensurePages(online.id,0) }
        val image = runBlocking { model.manga.engine.imageFile(reopened[0],headerSource.id) }
        check(image.length() > 1000 && rejected.get() == 0 && chapterHits.get() == before)
        show(online.id); screenshot("qa-ycy-online-context.png")
        record("MANGA_IMAGE_CONTEXT_REOPEN_OK evaluated Referer and region persist; fresh controller downloads and production reader decodes image without re-fetching chapter")
        leave("请求头验证完成")
        val empty = online.copy(id="qa-session-empty",sourceId=fast.id,sourceName=fast.name,url="$base/empty",chapters=emptyList())
        runBlocking { model.manga.keep(empty,openFailureDetails=true)?.join() }
        check(model.manga.state.value.detail?.id == empty.id && model.manga.state.value.detailError.isNotBlank())
        check(store.books().none { it.id == empty.id }); test.runOnMainSync { model.manga.closeDetails() }
        record("MANGA_DIRECT_READ_FAILURE_RECOVERY_OK failed direct read opens retry/alternative details; no broken shelf entry")
        show(local.id)
        click("自动阅读"); click("开始自动阅读")
        val bounds = Rect()
        pagingWait("automatic horizontal next decoded image") {
            val image = node("第 2 页") ?: return@pagingWait false
            image.getBoundsInScreen(bounds)
            shown("2/6 · 自动") && bounds.width() > 100 && bounds.height() > 100
        }
        pagingTouch(test,bounds.centerX().toFloat(),bounds.centerY().toFloat())
        pagingWait("touch pauses automatic session") { node("自动阅读") != null }
        screenshot("qa-ycy-auto-horizontal.png")
        Thread.sleep(2400); check(node("第 2 页") != null)
        record("MANGA_AUTO_HORIZONTAL_TOUCH_OK interval advances image; physical touch pauses and leaves normal gestures available")
        click("自动阅读"); click("开始自动阅读")
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        pagingWait("reader actually backgrounded") { !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        Thread.sleep(2500)
        test.runOnMainSync { activity.startActivity(android.content.Intent(activity,MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        pagingWait("reader resumed") { activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        pagingWait("background pauses automatic session") { !shown("· 自动") }
        leave("自动翻页验证完成")
        runBlocking { model.manga.saveReaderPreferences(local.id,ReadingPreferences.encode(MangaSettings(mode=MangaReadingMode.VERTICAL,
            autoScrollDpPerSecond=120,immersive=false,autoNextChapter=false,prefetchPages=0))).join() }
        test.runOnMainSync { model.manga.savePosition(local.id,0,0,0f,local.chapters[0].pages[0]) }
        runBlocking { model.manga.flushReadingProgress().join() }
        check(ReadingPreferences.manga(model.manga.state.value.books.first { it.id == local.id }.readerPreferences).mode == MangaReadingMode.VERTICAL)
        show(local.id); Thread.sleep(400)
        val initialVertical = Rect(); node("第 1 页")!!.getBoundsInScreen(initialVertical)
        click("自动阅读"); click("开始自动阅读")
        pagingWait("vertical automatic session running") { shown("· 自动") }
        Thread.sleep(1800)
        val movedVertical = Rect(); node("第 1 页")!!.getBoundsInScreen(movedVertical)
        screenshot("qa-ycy-auto-vertical.png")
        record("MANGA_AUTO_VERTICAL_DIAGNOSTIC before=$initialVertical after=$movedVertical running=${shown("· 自动")} mode=${ReadingPreferences.manga(model.manga.state.value.books.first { it.id == local.id }.readerPreferences).mode}")
        // Stop through a physical touch, then disposal flushes the actual visible source fraction.
        val vertical = Rect(); node("第 1 页")!!.getBoundsInScreen(vertical)
        pagingTouch(test,vertical.centerX().toFloat(),vertical.centerY().toFloat())
        leave("条漫滚动验证完成"); runBlocking { model.manga.flushReadingProgress().join() }
        val progress = store.books().first { it.id == local.id }
        record("MANGA_AUTO_VERTICAL_PROGRESS page=${progress.pageIndex} fraction=${progress.pageFraction}")
        check(progress.pageFraction > .01f || progress.pageIndex > 0) { "automatic vertical scroll position was not persisted" }
        show(local.id); Thread.sleep(2300); check(!shown("· 自动"))
        record("MANGA_AUTO_VERTICAL_REOPEN_OK frame-driven scroll moves content; exit saves fraction; reopening remains paused")
        leave("目录测试")
        val catalog = local.copy(chapters=(1..119).map { MangaChapter("第${it}话 原创","local:chapter$it") })
        val selection = AtomicInteger(-1)
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaCatalogSheet(catalog,52,{}, {selection.set(it)},{},false,{}) } } }
        pagingWait("catalog current row") { node("第53话 原创") != null }
        click("正序"); click("定位当前章")
        pagingWait("reversed current row") { node("第53话 原创") != null }
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo?) { if(n==null)return; if(n.isEditable) fields+=n; for(i in 0 until n.childCount) collect(n.getChild(i)) }
        collect(qaRoot(test)); check(fields.size==2)
        fields[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"2") })
        click("跳转"); check(selection.get()==1)
        screenshot("qa-ycy-catalog.png")
        record("MANGA_CATALOG_REVERSE_LOCATE_JUMP_OK presentation reversal preserves original index; chapter 2 selects index 1")
    } catch (failure: Throwable) {
        record("MANGA_SESSION_FAILURE ${android.util.Log.getStackTraceString(failure)}")
        screenshot("qa-ycy-session-failure.png")
        throw failure
    } finally {
        slowRelease.countDown(); runCatching { leave("漫画阅读回归完成") }; test.runOnMainSync { owner.clear() }
        server.close(); workers.shutdownNow(); accept.join(1000); root.deleteRecursively()
    }
}
