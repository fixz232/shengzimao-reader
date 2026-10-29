package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Empty details -> retry -> streaming same-source edition -> actual reader; isolated original fixture. */
fun mangaDirectoryRecoverySmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val root = File(test.targetContext.cacheDir, "manga-directory-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val workers = Executors.newFixedThreadPool(4)
    val blockedRequests = AtomicInteger()
    val releaseSlow = CountDownLatch(1)
    val slowStarted = CountDownLatch(1)
    val query = java.util.concurrent.atomic.AtomicReference("")
    val png = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(200, 500, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(55, 90, 120)); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
        }
    }.toByteArray()
    val accept = Thread {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            workers.submit { runCatching { socket.use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                while (!reader.readLine().isNullOrEmpty()) { }
                val path = request.substringBefore('?')
                val body = when (path) {
                    "/blocked" -> { blockedRequests.incrementAndGet(); "<h1>原创漫画（别名版）</h1><div>源站目录为空</div>".toByteArray() }
                    "/search" -> { query.set(java.net.URLDecoder.decode(request.substringAfter("q="), "UTF-8"));
                        "<div class='book'><a href='/blocked'>原创漫画（别名版）</a><span class='author'>旧作者</span></div><div class='book'><a href='/readable'>原创漫画</a><span class='author'>原创作者</span></div>".toByteArray() }
                    "/slow" -> { slowStarted.countDown(); releaseSlow.await(15, TimeUnit.SECONDS); "<div class='book'><a href='/late'>原创漫画晚到结果</a></div>".toByteArray() }
                    "/readable" -> "<h1>原创漫画</h1><a class='chapter' href='/chapter/1'>第一话 原创</a>".toByteArray()
                    "/chapter/1" -> "<img class='page' src='/page.png'>".toByteArray()
                    "/page.png" -> png
                    else -> "<h1>原创页面</h1>".toByteArray()
                }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: ${if (path.endsWith(".png")) "image/png" else "text/html; charset=utf-8"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } }
        }
    }.apply { isDaemon = true; start() }
    fun source(id: String, search: String) = MangaSource.parse(JSONObject("""{"id":"$id","name":"$id","baseUrl":"$base","search":{"url":"$search?q={query}","items":".book","title":"a","link":"a@href","author":".author"},"detail":{"title":"h1","chapters":".chapter","chapterTitle":"","chapterUrl":"@href"},"reader":{"pages":"img.page","image":"@src"}}"""))
    val source = source("qa-directory", "/search")
    MangaStore(File(root, "comics")).saveSources(listOf(source, source("qa-slow", "/slow")))
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = ViewModelStore(); lateinit var model: ReaderViewModel
    val navigator = Navigator()
    val blocked = MangaBook("qa-empty-directory", "原创漫画（别名版）", source.id, source.name, "$base/blocked", author = "旧作者")
    fun node(label: String) = pagingNode(qaRoot(test)) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    fun click(label: String) {
        pagingWait("click recovery action: $label") {
            var target = node(label) ?: return@pagingWait false
            while (!target.isClickable) target = target.parent ?: return@pagingWait false
            target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    fun shot(name: String) { test.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(test.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    } }
    try {
        test.runOnMainSync { model = ReaderViewModel(container); owner.put("recovery", model) }
        pagingWait("recovery model ready") { model.ui.value.loaded && model.manga.state.value.loaded }
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) {
            val screen = navigator.screen
            if (screen is Screen.ComicReading) MangaReaderScreen(model, navigator, screen.bookId) else MangaOnlineScreen(model, navigator)
        } }; model.manga.openDetails(blocked) }
        pagingWait("empty directory recovery actions") { node("查找其他版本 / 漫源") != null && !model.manga.state.value.detailLoading }
        Thread.sleep(650)
        check(node("在线看漫") == null && node("搜索章节名称") == null)
        pagingWait("automatic same-source readable edition") {
            model.manga.state.value.detailAlternatives.singleOrNull()?.let {
                it.url == "$base/readable" && it.chapters.size == 1 && it.author == "原创作者"
            } == true && !model.manga.state.value.detailFinding
        }
        check(slowStarted.count == 1L && model.manga.state.value.books.isEmpty())
        check(node("阅读此版本") != null)
        record("MANGA_AUTO_SAME_SOURCE_OK readable edition directory verified automatically; author displayed; other sources not queried; no shelf writes")
        shot("qa-directory-empty.png")
        click("重试目录")
        pagingWait("retry issues another details request") { blockedRequests.get() == 2 && !model.manga.state.value.detailLoading }
        pagingWait("automatic same-source search completed after retry") { !model.manga.state.value.detailFinding }
        record("MANGA_EMPTY_DIRECTORY_RETRY_OK empty controls replaced by recovery; fresh network request; original item not saved")
        click("查找其他版本 / 漫源")
        check(slowStarted.await(5, TimeUnit.SECONDS))
        pagingWait("same-source candidate streams before slow source") { node("原创漫画") != null && model.manga.state.value.detailFinding }
        check(query.get() == "原创漫画")
        check(model.manga.state.value.detailAlternatives.size == 1 && model.manga.state.value.books.isEmpty())
        check(model.manga.state.value.detailAlternatives.single().url == "$base/readable")
        shot("qa-directory-candidate.png")
        click("原创漫画")
        releaseSlow.countDown()
        pagingWait("candidate directory validated") { node("在线看漫") != null && model.manga.state.value.detail?.chapters?.size == 1 }
        Thread.sleep(300)
        check(model.manga.state.value.detail?.url == "$base/readable" && !model.manga.state.value.detailFinding)
        check(model.manga.state.value.detailAlternatives.isEmpty())
        record("MANGA_DETAIL_ALTERNATIVES_STREAM_OK same-source editions included; alias suffix removed; blocked item excluded; slow search cancelled; no automatic shelf writes")
        click("在线看漫")
        pagingWait("recovered real manga image") { node("第 1 页") != null }
        check(model.manga.state.value.books.single().url == "$base/readable")
        shot("qa-directory-reader.png")
        record("MANGA_DIRECTORY_RECOVERY_READ_OK user-selected candidate directory verified and actual MangaReaderScreen displays original fixture image")
        test.runOnMainSync { navigator.openMangaOnline(); model.manga.openDetails(blocked) }
        pagingWait("automatic direct-read recovery entry") { node("阅读此版本") != null && !model.manga.state.value.detailLoading }
        click("阅读此版本")
        pagingWait("direct alternate edition image") { node("第 1 页") != null }
        check(model.manga.state.value.books.none { it.id == blocked.id })
        check(model.manga.state.value.books.single().author == "原创作者")
        record("MANGA_AUTO_ALTERNATIVE_DIRECT_READ_OK single action from failed original opens verified edition; original and author remain distinct")
    } finally {
        releaseSlow.countDown()
        test.runOnMainSync { activity.setContent { Text("目录恢复回归完成") } }
        pagingWait("recovery screen disposed") { node("目录恢复回归完成") != null }
        test.runOnMainSync { owner.clear() }
        server.close(); workers.shutdownNow(); accept.join(1000); root.deleteRecursively()
    }
}
