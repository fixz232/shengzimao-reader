package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Actual app controllers, durable cache, UI reopening, source recovery and reading-page catalog updates. */
fun readingEnhancementsSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val root = File(test.targetContext.cacheDir, "reading-enhancements-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val novelHits=AtomicInteger(); val mangaHits=AtomicInteger(); val imageHits=AtomicInteger(); val chapterCount=AtomicInteger(2)
    val workers=Executors.newFixedThreadPool(4)
    val raw=(1..30).joinToString("\n") { "原创阅读段落${it}，从缓存恢复后应继续展示正文，并准确保留当前阅读位置。" }
    val png=ByteArrayOutputStream().also { out -> Bitmap.createBitmap(100,500,Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.WHITE)
        for(x in 10..89) for(y in 0..499) setPixel(x,y,android.graphics.Color.BLUE)
        compress(Bitmap.CompressFormat.PNG,100,out); recycle()
    } }.toByteArray()
    val accept=Thread {
        while(!server.isClosed) {
            val socket=runCatching { server.accept() }.getOrNull() ?: break
            workers.submit { runCatching { socket.use {
                val input=it.getInputStream().bufferedReader()
                val path=input.readLine().orEmpty().split(' ').getOrElse(1) { "/" }.substringBefore('?')
                while(!input.readLine().isNullOrEmpty()) { }
                val body=when {
                    path.endsWith(".png") -> { imageHits.incrementAndGet(); png }
                    path.startsWith("/novel/") -> { novelHits.incrementAndGet(); "<h1>第一章 起点</h1><div id='content'><p>${raw.replace("\n","</p><p>")}</p></div>".toByteArray() }
                    path == "/book/2" -> "<h1>原创阅读书</h1><span class='author'>原创作者</span><a class='chapter' href='$base/new/1'>第一章 起点</a><a class='chapter' href='$base/new/2'>第二章 远方</a>".toByteArray()
                    path.startsWith("/new/") -> "<h1>第一章 起点</h1><div id='content'><p>新源前言</p><p>${raw.replace("\n","</p><p>")}</p></div>".toByteArray()
                    path == "/book/3" -> "<h1>损坏书源</h1>".toByteArray()
                    path == "/comic" -> (1..chapterCount.get()).joinToString("") { n -> "<a class='chapter' href='/comic/$n'>第${n}话</a>" }.toByteArray()
                    path.startsWith("/comic/") -> { mangaHits.incrementAndGet(); "<img src='/page1.png'><img src='/page2.png'>".toByteArray() }
                    else -> "<h1>原创阅读书</h1><span class='author'>原创作者</span><a class='chapter' href='$base/novel/1'>第一章 起点</a><a class='chapter' href='$base/novel/2'>第二章 远方</a>".toByteArray()
                }
                it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: ${if(path.endsWith(".png")) "image/png" else "text/html; charset=utf-8"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
            } } }
        }
    }.apply { isDaemon=true; start() }
    val rules=File(root,"qa-rules.json").apply { writeText("""[{
        "url":"$base/","name":"缓存测试源","needProxy":false,"language":"zh-CN",
        "search":{"url":"$base/search?q=%s","method":"get","result":".result","bookName":".name"},
        "book":{"url":"/book/(\\d+)","bookName":"h1","author":".author"},"toc":{"item":".chapter"},
        "chapter":{"title":"h1","content":"#content","paragraphTagClosed":true,"filterTxt":"(?!)","filterTag":"script"},
        "crawl":{"minInterval":0,"maxInterval":0,"maxAttempts":1,"retryMinInterval":0,"retryMaxInterval":0}}]""") }
    val engine=SonovelEngine(context,rules); check(engine.available) { engine.loadError.orEmpty() }
    val main=File(root,"sonovel/rules/main.json"); val size=main.length().toInt(); val bytes=rules.readBytes()
    check(bytes.size<=size); main.writeBytes(bytes+ByteArray(size-bytes.size){32})
    val toc=listOf(OnlineChapter("第一章 起点","$base/novel/1"), OnlineChapter("第二章 远方","$base/novel/2"))
    val book=Book(id="qa-reading-book",title="原创阅读书",onlineOnly=true,onlineUrl="$base/book/1",length=2,
        chapters=toc.mapIndexed { i,c -> Chapter(c.title,i) },onlineManifest=OnlineChapterCodec.encode(toc),newChapterCount=1,tracking=true)
    val container=AppContainer(context)
    container.store.save(Library(books=listOf(book),consentAccepted=true,consentVersion=Consent.VERSION))
    val source=MangaSource.parse(JSONObject("""{"id":"qa-reading-source","name":"阅读漫画源","baseUrl":"$base","search":{"url":"/search","items":".item","title":"a","link":"a@href"},"detail":{"chapters":".chapter","title":"text","link":"href"},"reader":{"pages":"img@src"}}"""))
    val comic=MangaBook("qa-reading-comic","原创漫画",source.id,source.name,"$base/comic",onlineOnly=true,
        chapters=(1..2).map { MangaChapter("第${it}话","$base/comic/$it") })
    MangaStore(File(root,"comics")).apply { saveSources(listOf(source)); upsert(comic) }
    val owner=ViewModelStore(); lateinit var model:ReaderViewModel
    val navigator=Navigator()
    fun shown(value:String)=pagingNode(qaRoot(test)) { it.text?.toString()?.contains(value)==true || it.contentDescription?.toString()?.contains(value)==true }!=null
    fun tap(value:String) {
        var node=pagingNode(qaRoot(test)) { it.text?.toString()==value } ?: error("missing button: $value")
        while(!node.isClickable) node=node.parent ?: error("unclickable: $value")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    fun newModel() { test.runOnMainSync { model=ReaderViewModel(container); owner.put("reader",model) }; pagingWait("reading model loaded") { model.ui.value.loaded && model.manga.state.value.loaded } }
    fun showNovel() { test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { OnlineReaderScreen(model,navigator,book.id) } } }; pagingWait("novel body from cache") { shown("原创阅读段落1") } }
    fun leaveNovel(label: String) {
        test.runOnMainSync { activity.setContent { Text(label) } }
        // setContent only schedules composition. Wait for disposal before replacing the model or its saved position.
        pagingWait("reader disposed: $label") { shown(label) }
        test.waitForIdleSync()
    }
    try {
        newModel()
        val text=runBlocking { model.readOnlineChapter(book,toc[0]) }
        val first=novelHits.get(); check(first==1)
        check(runBlocking { model.readOnlineChapter(book,toc[0]) }==text && novelHits.get()==first)
        showNovel()
        if (ReadingNetwork.prefetchCount(context,60_000,1500,3,wifiOnly=false)>0) {
            pagingWait("automatic next novel chapter prefetch") { runBlocking { model.readCachedOnline(book,toc[1]) }!=null }
            check(novelHits.get()==2)
            record("NOVEL_AUTOMATIC_PREFETCH_OK reading chapter one durably preloads chapter two without pressing download")
        }
        leaveNovel("保存第一章")
        runBlocking { model.readOnlineChapter(book,toc[1]); model.saveOnlinePosition(book.id,1,0,0).join() }
        check(container.store.load().books.first { it.id==book.id }.position==1) { "second chapter was not persisted before reopening" }
        test.runOnMainSync { owner.clear() }; newModel()
        val before=novelHits.get(); showNovel(); Thread.sleep(500)
        check(novelHits.get()==before) { "reopening cached chapter requested network" }
        check(model.ui.value.books.first { it.id==book.id }.position==1) { "reopened reader lost the saved second chapter" }
        leaveNovel("退出阅读")
        pagingWait("exit progress persisted") { container.store.load().books.first { it.id==book.id }.position==1 }
        record("NOVEL_DURABLE_CACHE_REOPEN_OK new ViewModel and UI display cached chapter with zero network requests; chapter and original text preserved")
        runBlocking { model.saveBookPreferences(book.id,ReadingPreferences.encode(ReaderSettings(cleanupEnabled=true,cleanupRules="-原创阅读段落2，"))).join() }
        showNovel(); check(!shown("原创阅读段落2，")); check(runBlocking { model.readCachedOnline(book,toc[1]) }!!.contains("原创阅读段落2，"))
        record("NOVEL_CLEANUP_UI_OK literal rule removes paragraph only from display; durable original remains unchanged")
        val hitChapter=AtomicInteger(-1); val hitOffset=AtomicInteger(-1)
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { NovelTextSearchSheet(model,book,1,text,ReaderSettings(),{}) { chapter,offset,_ -> hitChapter.set(chapter); hitOffset.set(offset) } } } }
        pagingWait("text search editable field") { pagingNode(qaRoot(test)) { it.isEditable }!=null }
        val input=pagingNode(qaRoot(test)) { it.isEditable }!!
        check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"原创阅读段落30") }))
        tap("阅读缓存"); val searchReads=novelHits.get(); tap("开始搜索")
        pagingWait("cached multi-chapter search results") { shown("2 处匹配") }
        check(novelHits.get()==searchReads)
        var hit=pagingNode(qaRoot(test)) { !it.isEditable && it.text?.toString()?.let { value -> value.contains("原创阅读段落30") && value!="原创阅读段落30" }==true } ?: error("result snippet missing")
        while(!hit.isClickable) hit=hit.parent ?: error("search result not clickable")
        check(hit.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        pagingWait("text result resolves original offset") { hitChapter.get()>=0 }
        val original=runBlocking { model.readCachedOnline(book,toc[hitChapter.get()]) }!!
        check(original.substring(hitOffset.get()).startsWith("原创阅读段落30"))
        record("NOVEL_MULTI_CHAPTER_SEARCH_UI_OK two durable cached chapters searched without network; result resolves to original chapter and text offset")
        leaveNovel("换源验证")
        runBlocking { model.switchOnlineSourceAt(book.id,OnlineResult(bookName=book.title,url="$base/book/2",sourceName="新源"),text,120,1){}.join() }
        val switched=model.ui.value.books.first { it.id==book.id }; check(switched.onlineUrl=="$base/book/2" && switched.position==1 && switched.onlineCharacterOffset>=120)
        runBlocking { model.switchOnlineSourceAt(book.id,OnlineResult(bookName=book.title,url="$base/book/3",sourceName="空源"),text,120,1){}.join() }
        check(model.ui.value.books.first { it.id==book.id }.onlineUrl==switched.onlineUrl)
        record("NOVEL_SOURCE_SWITCH_RECOVERY_OK validated new chapter and text anchor; empty new directory keeps prior source and progress")
        val controller=model.manga
        val pages=runBlocking { controller.ensurePages(comic.id,0) }; check(pages.size==2)
        // Both chapters deliberately share URLs: seed each scoped cache through the real reader path.
        // URL-only fixtures would now be ambiguous, so they must not bypass chapter isolation.
        runBlocking { for (chapterIndex in 0..1) {
            val refs = controller.ensurePages(comic.id, chapterIndex)
            refs.indices.forEach { page -> controller.readerImage(comic.id, chapterIndex, page) }
        } }
        controller.savePosition(comic.id,1,1,.25f,pages[1]); runBlocking { controller.flushReadingProgress().join() }
        val chapterReads=mangaHits.get(); val images=imageHits.get()
        test.runOnMainSync { owner.clear() }; newModel()
        runBlocking { model.manga.saveReaderPreferences(comic.id,ReadingPreferences.encode(MangaSettings(autoCrop=true, autoNextChapter=false,prefetchPages=0))).join() }
        val analysis=runBlocking { loadMangaImageInfo(model.manga.engine,pages[0],source.id,
            fileLoader = { stage -> model.manga.readerImage(comic.id, 0, 0, onStage = stage) }) }
        check(analysis.detectedCropPercent==9 && !analysis.wideSpread)
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaReaderScreen(model,navigator,comic.id) } } }
        pagingWait("cached manga image displayed") { shown("第 2 页") }
        check(mangaHits.get()==chapterReads && imageHits.get()==images) { "manga reopen fetched cached chapter or image" }
        test.uiAutomation.takeScreenshot()?.let { shot -> File(test.targetContext.getExternalFilesDir(null),"qa-reading-cache-crop.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }; shot.recycle() }
        record("MANGA_DURABLE_REOPEN_OK new controller and UI reopen saved chapter/page from disk without requesting chapter or image")
        runBlocking { model.manga.ensurePages(comic.id,1,refreshPages=true) }
        check(mangaHits.get()==chapterReads+1)
        runBlocking { model.manga.ensurePages(comic.id,0) }; check(mangaHits.get()==chapterReads+1)
        chapterCount.set(3)
        runBlocking { model.manga.checkUpdates(comic.id).join() }
        val updated=model.manga.store.books().single()
        check(updated.chapters.size==3 && updated.chapterIndex==1 && updated.pageIndex==1 && updated.chapters[0].pages.isNotEmpty())
        record("MANGA_DIRECTORY_REFRESH_OK added chapter preserves current chapter/page and old image addresses; manual refresh fetches once and cached next chapter stays cached")
    } finally {
        test.runOnMainSync { activity.setContent { Text("阅读增强回归结束") }; owner.clear() }
        server.close(); workers.shutdownNow(); accept.join(1000); root.deleteRecursively()
    }
}
