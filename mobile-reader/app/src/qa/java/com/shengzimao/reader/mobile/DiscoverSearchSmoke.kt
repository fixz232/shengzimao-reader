package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual controller/UI with 97 local sources, including a held request and a failed source. */
fun discoverSearchSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val sandbox = File(test.targetContext.cacheDir, "discover-search-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = sandbox }
    val container = AppContainer(context)
    val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val workers = Executors.newFixedThreadPool(8)
    val slow = CountDownLatch(1)
    val slowStarted = CountDownLatch(1)
    val novelSlow = CountDownLatch(1)
    val novelStarted = CountDownLatch(1)
    val laterStarted = CountDownLatch(1)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val browseRequests = AtomicInteger()
    val detailsRequests = AtomicInteger()
    val novelChapterRequests = AtomicInteger()
    val accept = Thread {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            workers.submit { runCatching { socket.use {
                val input = it.getInputStream().bufferedReader()
                val path = input.readLine().orEmpty().split(' ').getOrElse(1) { "/" }.substringBefore('?')
                while (!input.readLine().isNullOrEmpty()) { }
                val search = path.startsWith("/search/")
                val id = path.substringAfterLast('/').toIntOrNull() ?: -1
                if (search) {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { n -> maxOf(n, count) }
                    if (id == 0) { slowStarted.countDown(); check(slow.await(20, TimeUnit.SECONDS)) }
                    if (id >= 4) laterStarted.countDown()
                }
                val status = if (search && id == 2) "503 Unavailable" else "200 OK"
                val html = when {
                    search -> "<div class='book'><a href='/book/$id'>合成漫画$id</a></div>"
                    path.startsWith("/browse/") -> {
                        browseRequests.incrementAndGet()
                        (1..80).joinToString("") { n -> "<div class='book'><a href='/book/$n'>浏览原创漫画$n</a></div>" }
                    }
                    path.startsWith("/book/") -> {
                        detailsRequests.incrementAndGet()
                        "<h1>原创渐进小说</h1><span class='author'>测试作者</span><a class='chapter' href='/chapter/1'>第一话</a>"
                    }
                    path.startsWith("/novel/") -> {
                        if (path.contains("slow")) { novelStarted.countDown(); check(novelSlow.await(20, TimeUnit.SECONDS)) }
                        "<div class='result'><a class='name' href='$base/book/1'>原创渐进小说</a><span class='author'>测试作者</span></div>"
                    }
                    path == "/chapter/1" -> {
                        novelChapterRequests.incrementAndGet()
                        "<h1>第一话</h1><div id='content'>" + (1..20).joinToString("") { "<p>这是即时阅读回归使用的原创正文第${it}段，快源已返回，慢源仍在等待，读者应立即打开阅读。</p>" } + "</div>"
                    }
                    else -> "<img src='/image.png'>"
                }
                val bytes = html.toByteArray(Charsets.UTF_8)
                it.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                if (search) active.decrementAndGet()
            } } }
        }
    }.apply { isDaemon = true; start() }
    val sources = (0 until 97).map { index -> MangaSource.importText(JSONObject()
        .put("id", "stream-$index").put("name", "渐进源$index").put("baseUrl", base)
        .put("search", JSONObject().put("url", "/search/$index?q={query}&page={page}").put("items", ".book").put("title", "a").put("link", "a@href"))
        .put("detail", JSONObject().put("chapters", ".chapter").put("title", "text").put("link", "href"))
        .put("reader", JSONObject().put("pages", "img@src"))
        .put("discover", JSONObject().put("items", ".book").put("title", "a").put("link", "a@href")
            .put("groups", JSONArray().put(JSONObject().put("name", "原创分类").put("entries", JSONArray()
                .put(JSONObject().put("name", "同名分类").put("url", "/browse/a"))
                .put(JSONObject().put("name", "同名分类").put("url", "/browse/b")))))).toString()).single() }
    MangaStore(File(sandbox, "comics")).saveSources(sources)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    fun shown(label: String) = pagingNode(qaRoot(test)) { node ->
        node.isVisibleToUser && (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true)
    } != null
    try {
        test.runOnMainSync {
            model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(container) as T
            })[ReaderViewModel::class.java]
            navigator.openDiscover()
            activity.setContent { ShengZiMaoTheme(false) { MainScaffold(model, navigator) } }
        }
        pagingWait("97 sources loaded") { model.ui.value.loaded && model.manga.state.value.sources.size == 97 }
        repeat(8) {
            test.runOnMainSync { model.message("发现页面瞬时消息$it") }
            test.runOnMainSync { model.consumeMessage(); navigator.openMangaOnline() }
            test.waitForIdleSync()
            test.runOnMainSync { navigator.openDiscover() }
        }
        pagingWait("discover page remains responsive") { shown("搜索书名或作者") }
        record("DISCOVER_NAVIGATION_OK repeated discover/book/manga transitions and transient messages with 97 sources")
        val started = SystemClock.uptimeMillis()
        test.runOnMainSync { navigator.openMangaOnline(); model.manga.selectSource(MangaController.ALL_SOURCES); model.manga.search("合成漫画") }
        check(slowStarted.await(5, TimeUnit.SECONDS))
        pagingWait("fast sources displayed before slow source") { model.manga.state.value.let { it.searching && it.results.isNotEmpty() && it.searchedSources in 1..96 } }
        check(laterStarted.await(5, TimeUnit.SECONDS)) { "slow source blocked later source dispatch" }
        val firstMs = SystemClock.uptimeMillis() - started
        check(model.manga.state.value.hits.none { it.sourceId == sources[0].id })
        pagingWait("partial search visible on screen") { shown("合成漫画") }
        record("MANGA_STREAMING_97_PARTIAL_OK firstMs=$firstMs completed=${model.manga.state.value.searchedSources}/97 slowSourceStillPending=true rollingWorkers=true")
        slow.countDown()
        pagingWait("97 sources complete") { !model.manga.state.value.searching && model.manga.state.value.searchedSources == 97 }
        val finished = model.manga.state.value
        check(finished.results.size == 96 && finished.hits.count { it.error.isNotBlank() } == 1)
        check(finished.results.distinctBy { it.id }.size == finished.results.size)
        check(peak.get() <= 4) { "source concurrency exceeded 4: ${peak.get()}" }
        record("MANGA_STREAMING_97_COMPLETE_OK results=96 failed=1 peak=${peak.get()} stableUniqueIds=true")
        test.runOnMainSync { model.manga.selectSources(setOf(sources[1].id, sources[3].id)); model.manga.search("合成漫画") }
        pagingWait("only selected sources searched") { model.manga.state.value.let { !it.searching && it.searchedSources == 2 } }
        check(model.manga.state.value.hits.map { it.sourceId }.toSet() == setOf(sources[1].id, sources[3].id))
        record("MANGA_SELECTED_SEARCH_OK only two checked sources queried")
        test.runOnMainSync { model.manga.openBrowse(sources[0].id) }
        pagingWait("auto browse and duplicate categories") { model.manga.state.value.browseResults.size == 80 && shown("同名分类") }
        test.waitForIdleSync()
        val before = browseRequests.get()
        test.runOnMainSync { model.manga.browse("同名分类", entryUrl = "/browse/b") }
        pagingWait("second same-name category") { model.manga.state.value.let { !it.browseLoading && it.browseEntryUrl == "/browse/b" } }
        test.runOnMainSync { model.manga.browse("同名分类", entryUrl = "/browse/a") }
        pagingWait("cached category") { model.manga.state.value.let { !it.browseLoading && it.browseEntryUrl == "/browse/a" } }
        check(browseRequests.get() == before + 1) { "recent category reloaded over network" }
        check(model.manga.state.value.browseResults.size == 80)
        test.runOnMainSync { model.manga.closeBrowse() }
        pagingWait("source browser closes") { shown("搜索范围：") }
        record("MANGA_SOURCE_BROWSER_OK duplicate labels; auto load; 80-item lazy grid; category identity; recent result cache; back navigation")
        val book = model.manga.state.value.results.first()
        test.runOnMainSync { model.manga.openDetails(book) }
        pagingWait("manga detail loaded") { model.manga.state.value.let { !it.detailLoading && it.detail?.chapters?.isNotEmpty() == true } }
        val reads = detailsRequests.get()
        test.runOnMainSync { model.manga.keep(book) }
        pagingWait("cached details reused to keep manga") { model.manga.state.value.books.any { it.id == book.id } }
        check(detailsRequests.get() == reads)
        test.runOnMainSync { model.manga.closeDetails(); model.manga.search("合成漫画"); model.manga.cancelSearch() }
        Thread.sleep(400)
        check(!model.manga.state.value.searching)
        record("MANGA_DETAIL_CACHE_CANCEL_OK keep reuses directory; cancelled search stays cancelled")
        val rules = File(sandbox, "novel-streaming-rules.json")
        rules.writeText(JSONArray().apply { for (kind in listOf("slow", "fast")) put(JSONObject("""{
            "url":"$base/","name":"原创渐进$kind","needProxy":false,"language":"zh-CN",
            "search":{"url":"$base/novel/$kind/search?q=%s","method":"get","result":".result","bookName":".name","author":".author"},
            "book":{"url":"/book/(\\d+)","bookName":"h1","author":".author"},"toc":{"item":".chapter"},
            "chapter":{"title":"h1","content":"#content","paragraphTagClosed":true,"filterTxt":"(?!)","filterTag":"script"},
            "crawl":{"minInterval":0,"maxInterval":0,"maxAttempts":1,"retryMinInterval":0,"retryMaxInterval":0}}
            """)) }.toString())
        val engine = SonovelEngine(context, rules)
        check(engine.available) { engine.loadError.orEmpty() }
        // Keep the released asset manifest valid, as the real controller loads the default rule file.
        val main = File(sandbox, "sonovel/rules/main.json")
        val originalSize = main.length().toInt()
        val fixture = rules.readBytes()
        check(fixture.size <= originalSize)
        main.writeBytes(fixture + ByteArray(originalSize - fixture.size) { 32 })
        test.runOnMainSync {
            activity.setContent { ShengZiMaoTheme(false) { when (val screen = navigator.screen) {
                is Screen.Reading -> OnlineReaderScreen(model, navigator, screen.bookId)
                else -> MainScaffold(model, navigator)
            } } }
            navigator.openOnline(); model.searchOnline("原创渐进小说")
        }
        check(novelStarted.await(5, TimeUnit.SECONDS))
        pagingWait("fast novel source visible while slow source pending") {
            model.ui.value.online.let { it.running && it.results.size == 1 && it.message.contains("1/2") } && shown("原创渐进小说")
        }
        var read = pagingNode(qaRoot(test)) { it.text?.toString() == "在线阅读" } ?: error("partial result read button missing")
        while (!read.isClickable) read = read.parent ?: error("partial result read button disabled")
        check(read.isEnabled && read.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        pagingWait("open partial result without waiting for slow source") { shown("这是即时阅读回归") }
        check(novelSlow.count == 1L) { "slow request was released before reader opened" }
        check(novelChapterRequests.get() == 1)
        check(model.ui.value.online.results.isNotEmpty() && !model.ui.value.online.searching)
        record("NOVEL_STREAMING_OPEN_READ_OK actual partial-result button opens body while held source remains blocked; search cancelled and returned results preserved")
        novelSlow.countDown()
    } catch (failure: Throwable) {
        fun describe(node: AccessibilityNodeInfo?): String = if (node==null) "" else
            (listOfNotNull(node.text?.toString(),node.contentDescription?.toString()) + (0 until node.childCount).map { describe(node.getChild(it)) }).filter { it.isNotBlank() }.joinToString(" | ")
        record("DISCOVER_FAILURE_STATE screen=${navigator.screen} online=${model.ui.value.online.message} error=${model.ui.value.message?.text} books=${model.ui.value.books.size} requests=${novelChapterRequests.get()} ui=${describe(qaRoot(test)).take(2500)}")
        throw failure
    } finally {
        slow.countDown(); novelSlow.countDown()
        test.runOnMainSync { activity.setContent { Text("发现与渐进搜索回归完成") }; owner.viewModelStore.clear() }
        server.close(); workers.shutdownNow(); accept.join(1000)
        sandbox.deleteRecursively()
    }
}
