package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Native HTTP/decoder/cache tests use original colored fixtures in an isolated private library. */
fun mangaReadingUpgradeSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val root = File(test.targetContext.cacheDir, "reading-upgrade-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    fun png(color: Int): ByteArray = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).let { bitmap ->
        bitmap.eraseColor(color)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }
    val red = png(Color.RED); val blue = png(Color.BLUE)
    val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val workers = Executors.newFixedThreadPool(6)
    val refreshes = AtomicInteger(); val imageHits = AtomicInteger(); val goodHits = AtomicInteger()
    val missingWorks = AtomicBoolean(false)
    val stickyFresh = AtomicBoolean(false)
    val stickyFailures = java.util.concurrent.CountDownLatch(2)
    val stickyRefreshes = AtomicInteger()
    val accept = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            workers.submit { runCatching { socket.use { connection ->
                connection.soTimeout = 5000
                val input = connection.getInputStream().bufferedReader()
                val path = input.readLine().orEmpty().split(' ').getOrElse(1) { "/" }.substringBefore('?')
                val headers = mutableMapOf<String, String>()
                while (true) { val line = input.readLine(); if (line.isNullOrEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                var code = 200
                val bytes = when (path) {
                    "/chapter/expired" -> { refreshes.incrementAndGet(); "<img src='$base/fresh.png'>".toByteArray() }
                    "/chapter/download" -> "<img src='$base/good.png'><img src='$base/missing.png'>".toByteArray()
                    "/chapter/sticky" -> { stickyRefreshes.incrementAndGet(); stickyFresh.set(true); "<img src='$base/sticky0.png'><img src='$base/sticky1.png'>".toByteArray() }
                    "/sticky0.png", "/sticky1.png" -> if (stickyFresh.get()) blue else {
                        stickyFailures.countDown(); check(stickyFailures.await(4, java.util.concurrent.TimeUnit.SECONDS))
                        code = 403; "expired".toByteArray()
                    }
                    "/chapter/locked" -> "<img src='$base/locked.png'>".toByteArray()
                    "/locked.png" -> { code = 403; "verification required".toByteArray() }
                    "/locked-book" -> "<a class='chapter' href='/chapter/locked'>第一话</a>".toByteArray()
                    "/bad-book" -> "<a class='chapter' href='/chapter/bad'>第一话</a>".toByteArray()
                    "/good-book" -> "<a class='chapter' href='/chapter/good'>第一话</a>".toByteArray()
                    "/chapter/bad" -> "<img src='$base/fake.png'>".toByteArray()
                    "/chapter/good" -> "<img src='$base/fresh.png'>".toByteArray()
                    "/expired.png" -> { code = 403; "expired".toByteArray() }
                    "/fake.png" -> "<html>not an image</html>".toByteArray()
                    "/shared.png" -> { imageHits.incrementAndGet(); if (headers["referer"]?.endsWith("/a") == true) red else blue }
                    "/missing.png" -> if (missingWorks.get()) { imageHits.incrementAndGet(); blue } else { code = 404; "missing".toByteArray() }
                    "/good.png" -> { goodHits.incrementAndGet(); imageHits.incrementAndGet(); red }
                    "/fresh.png" -> { imageHits.incrementAndGet(); blue }
                    "/search" -> "<div class='book'><a href='/good-book'>原创作品</a></div>".toByteArray()
                    "/searchlocked" -> "<div class='book'><a href='/locked-book'>原创验证样本</a></div>".toByteArray()
                    else -> "<img src='$base/fresh.png'>".toByteArray()
                }
                val type = if (path.endsWith(".png") && code == 200 && path != "/fake.png") "image/png" else "text/html; charset=utf-8"
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $code Fixture\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            } } }
        }
    }.apply { isDaemon = true; start() }
    fun source(id: String, searchPath: String = "/search") = MangaSource.parse(JSONObject("""{"id":"$id","name":"$id","baseUrl":"$base",
        "search":{"url":"$searchPath?q={query}","items":".book","title":"a","link":"a@href"},
        "detail":{"chapters":".chapter","chapterTitle":"","chapterUrl":"@href"},"reader":{"pages":"img@src"}}"""))
    val primary = source("qa-reading-primary"); val secondary = source("qa-reading-secondary")
    val locked = source("qa-reading-locked", "/searchlocked")
    val store = MangaStore(File(root, "comics")); store.saveSources(listOf(primary, secondary, locked))
    val shared = MangaBook("qa-upgrade-shared", "原创共享地址", primary.id, primary.name, "$base/shared-book", chapters = listOf(
        MangaChapter("第一话", "$base/chapter/a", listOf("$base/shared.png")), MangaChapter("第二话", "$base/chapter/b", listOf("$base/shared.png"))))
    val expired = MangaBook("qa-upgrade-expired", "原创过期链接", primary.id, primary.name, "$base/expired-book",
        chapters = listOf(MangaChapter("第一话", "$base/chapter/expired", listOf("$base/expired.png"))))
    val download = MangaBook("qa-upgrade-download", "原创部分下载", primary.id, primary.name, "$base/download-book",
        chapters = listOf(MangaChapter("第一话", "$base/chapter/download")))
    val sticky = MangaBook("qa-upgrade-sticky", "原创并发刷新", primary.id, primary.name, "$base/sticky-book",
        chapters = listOf(MangaChapter("第一话", "$base/chapter/sticky", listOf("$base/sticky0.png", "$base/sticky1.png"))))
    listOf(shared, expired, download, sticky).forEach(store::upsert)
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = ViewModelStore(); lateinit var model: ReaderViewModel
    fun newModel() {
        test.runOnMainSync { model = ReaderViewModel(container); owner.put("upgrade", model) }
        pagingWait("upgrade model loaded") { model.ui.value.loaded && model.manga.state.value.loaded }
    }
    fun color(file: File): Int = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)).let { image -> image.getPixel(20, 20).also { image.recycle() } }
    fun shown(text: String) = pagingNode(qaRoot(test)) { it.text?.toString()?.contains(text) == true } != null
    fun screenshot(name: String) { test.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(test.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() } }
    try {
        newModel()
        val stages = arrayListOf<String>()
        val recovered = runBlocking { model.manga.readerImage(expired.id, 0, 0, onStage = { stages += it }) }
        check(color(recovered) == Color.BLUE && refreshes.get() == 1 && stages.any { it.contains("重新获取") })
        record("UPGRADE_EXPIRED_URL_OK real HTTP 403 refreshes the chapter once and decodes recovered image")
        val recoveredTogether = runBlocking { (0..1).map { page -> async { model.manga.readerImage(sticky.id, 0, page) } }.awaitAll() }
        check(recoveredTogether.all { color(it) == Color.BLUE } && stickyRefreshes.get() == 1)
        record("UPGRADE_REFRESH_COALESCING_OK two simultaneous 403s sharing unchanged URLs refresh the chapter once and both recover")
        val engine = MangaEngine(store)
        val readable = runBlocking { engine.test(primary, "原创") }
        val blocked = runBlocking { engine.test(locked, "原创") }
        check(readable.readability == MangaReadability.READABLE && readable.sampledImages > 0)
        check(blocked.readability == MangaReadability.VERIFICATION && blocked.sampledImages == 0)
        record("UPGRADE_READABILITY_OK real decoded samples are readable; HTTP 403 samples stay verification-required")
        val accessRequests = AtomicInteger()
        val restrictedEngine = MangaEngine(store, okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            accessRequests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(403)
                .message("Forbidden fixture").body("expired signature".toResponseBody()).build()
        }.build())
        val accessFailure = runCatching { runBlocking { restrictedEngine.imageFile(
            "https://s1.baozimh.com/scomic/qa-original/0/0.jpg", primary.id) } }.exceptionOrNull()
        check(accessFailure is MangaImageAddressException && accessFailure.status == 403 && accessRequests.get() == 1)
        record("UPGRADE_ACCESS_REFRESH_SIGNAL_OK HTTP 403 on a multi-CDN image stays an address error and does not exhaust the page deadline")
        val a = runBlocking { model.manga.readerImage(shared.id, 0, 0) }
        val b = runBlocking { model.manga.readerImage(shared.id, 1, 0) }
        check(a != b && color(a) == Color.RED && color(b) == Color.BLUE)
        val hits = imageHits.get()
        test.runOnMainSync { owner.clear() }; newModel()
        check(color(runBlocking { model.manga.readerImage(shared.id, 0, 0) }) == Color.RED)
        check(color(runBlocking { model.manga.readerImage(shared.id, 1, 0) }) == Color.BLUE)
        check(imageHits.get() == hits)
        record("UPGRADE_SCOPED_CACHE_OK same URL with different chapter Referers stays isolated; new controller reopens with zero image requests")
        val original = store.books().first { it.id == shared.id }
        runBlocking { model.manga.switchSource(shared.id, original.copy(sourceId = secondary.id, sourceName = secondary.name, url = "$base/bad-book", chapters = emptyList()), 1) {}.join() }
        check(store.books().first { it.id == shared.id }.sourceId == primary.id && model.manga.state.value.sourceError.isNotBlank())
        record("UPGRADE_SWITCH_REJECTS_HTML_OK non-image bytes cannot commit a source switch")
        var metrics = Triple(0, 0, 0)
        val incomplete = runCatching { runBlocking { model.manga.download(download.id, 0, { _, _, _ -> }, null) { done, total, failed -> metrics = Triple(done, total, failed) } } }
        check(incomplete.isFailure && metrics == Triple(1, 2, 1))
        check(store.books().first { it.id == download.id }.cachedChapters.isEmpty())
        val before = goodHits.get(); missingWorks.set(true)
        runBlocking { model.manga.download(download.id, 0, { _, _, _ -> }, null) { done, total, failed -> metrics = Triple(done, total, failed) } }
        check(metrics == Triple(2, 2, 0) && goodHits.get() == before)
        record("UPGRADE_DOWNLOAD_RECOVERY_OK failed page counted, incomplete chapter remains incomplete, retry reuses successful image")
        shared.chapters.forEachIndexed { index, chapter -> store.protectImage(shared.sourceId, chapter.pages[0], MangaImageContext.of(shared, chapter, 0)) }
        store.patch(shared.id) { it.copy(cachedChapters = it.chapters.map { chapter -> chapter.url }.toSet()) }
        val archive = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { MangaBackup.write(it, store, MangaSettings(), true) } }.toByteArray()
        val restored = MangaStore(File(root, "restored"))
        MangaBackup.Restore(restored, root).use { restore ->
            ZipInputStream(archive.inputStream()).use { input -> while (true) { val entry = input.nextEntry ?: break; restore.accept(entry.name, input.readBytes()) } }
            restore.finish()
        }
        val restoredBook = restored.books().first { it.id == shared.id }
        val restoredEngine = MangaEngine(restored)
        for (i in 0..1) {
            val chapter = restoredBook.chapters[i]
            val image = runBlocking { restoredEngine.imageFile(chapter.pages[0], restoredBook.sourceId, context = MangaImageContext.of(restoredBook, chapter, 0)) }
            check(color(image) == if (i == 0) Color.RED else Color.BLUE)
        }
        check(restoredBook.cachedChapters.size == 2)
        record("UPGRADE_BACKUP_SCOPES_OK backup and restore retain distinct images sharing a URL")
        val unread = store.books().first { it.id == shared.id }.copy(newChapters = 1,
            unreadChapterUrls = setOf(shared.chapters[1].url), tracking = true)
        store.upsert(unread)
        check(model.manga.firstUnread(unread) == 1)
        store.stagePosition(shared.id, 1, 0, 0f, shared.chapters[1].pages.first())
        store.flushProgress()
        check(store.books().first { it.id == shared.id }.let { it.newChapters == 0 && it.unreadChapterUrls.isEmpty() })
        record("UPGRADE_UNREAD_PROGRESS_OK first unread chapter resolves correctly and reading clears only its unread marker")
        runBlocking { model.manga.switchSource(expired.id, expired.copy(sourceId = secondary.id, sourceName = secondary.name,
            url = "$base/good-book", chapters = emptyList()), 1) {}.join() }
        check(store.books().first { it.id == expired.id }.let { it.sourceId == secondary.id && it.chapters.single().pages.single() == "$base/fresh.png" })
        record("UPGRADE_SWITCH_COMMITS_READABLE_OK source changes only after a real image decodes successfully")
        runBlocking { model.manga.savePageOverride(shared.id, 0, 0, MangaPageOverride(original = true, layout = "single")).join() }
        store.patch(expired.id) { it.copy(newChapters = 1, unreadChapterUrls = setOf(it.chapters.first().url), tracking = true) }
        test.runOnMainSync { owner.clear() }; newModel()
        val latest = model.manga.state.value.books.first { it.id == shared.id }
        check(MangaPageOverrides.get(latest.pageOverrides, latest.chapters[0], 0) == MangaPageOverride(original = true, layout = "single"))
        record("UPGRADE_PAGE_OVERRIDE_PERSISTS_OK one-page crop and spread overrides survive a fresh controller")
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) {
            MangaPageAdjustSheet(model.manga, latest, 0, 0, MangaSettings()) { }
        } } }
        pagingWait("page preview real image") { shown("原图") && shown("保存本页设置") }
        screenshot("qa-reading-upgrade-page-preview.png")
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaUpdatesSheet(model.manga, Navigator()) { } } } }
        pagingWait("updates center shown") { shown("漫画更新中心") && shown("最近阅读") && shown("读第一章未读") }
        screenshot("qa-reading-upgrade-updates.png")
        test.runOnMainSync { activity.setContent { Text("阅读增强验证完成") } }
        record("UPGRADE_UI_OK page adjustment preview and updates center render in production Compose components")
    } finally {
        test.runOnMainSync { activity.setContent { Text("验证结束") }; owner.clear() }
        server.close(); accept.join(1000); workers.shutdownNow(); root.deleteRecursively()
    }
}
