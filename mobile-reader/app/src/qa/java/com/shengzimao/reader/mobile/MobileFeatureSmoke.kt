package com.shengzimao.reader.mobile

// QA 变体内执行 UI 回归，使 R8 能同时看到页面及其测试调用。

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun awaitFeature(label: String, timeout: Long = 15_000, ready: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeout
    while (SystemClock.uptimeMillis() < deadline) {
        if (ready()) return
        Thread.sleep(50)
    }
    check(ready()) { "timed out: $label" }
}

private fun containsText(node: AccessibilityNodeInfo?, value: String): Boolean {
    if (node == null) return false
    if (node.text?.toString()?.contains(value) == true || node.contentDescription?.toString()?.contains(value) == true) return true
    return (0 until node.childCount).any { containsText(node.getChild(it), value) }
}

private fun clickText(test: Instrumentation, value: String) {
    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
        for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
        return null
    }
    var node = find(qaRoot(test)) ?: error("button missing: $value")
    repeat(5) {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        node = node.parent ?: error("button not clickable: $value")
    }
    error("button action failed: $value")
}

private fun featureContent(activity: ComponentActivity, model: ReaderViewModel, navigator: Navigator) {
    activity.setContent { ShengZiMaoTheme(false) {
        when (val screen = navigator.screen) {
            is Screen.Reading -> OnlineReaderScreen(model, navigator, screen.bookId)
            is Screen.ComicReading -> MangaReaderScreen(model, navigator, screen.bookId, screen.chapter)
            else -> MainScaffold(model, navigator)
        }
    } }
}

fun onlineShelfSmoke(test: Instrumentation, activity: ComponentActivity, context: Context, rules: File,
    details: Updates.Details, record: (String) -> Unit) {
    // QA 私有目录内替换默认规则，保持资源清单长度，使普通在线控制器走同一个原创站点。
    val main = File(context.filesDir, "sonovel/rules/main.json")
    val old = main.readBytes()
    val replacement = rules.readBytes()
    check(replacement.size <= old.size)
    main.writeBytes(replacement + ByteArray(old.size - replacement.size) { 32 })
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = ViewModelStore()
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    test.runOnMainSync { model = ReaderViewModel(container); owner.put("online-fixture", model) }
    try {
        awaitFeature("online model") { model.ui.value.loaded }
        test.runOnMainSync { model.searchOnline("合成测试小说"); navigator.openOnline(); featureContent(activity, model, navigator) }
        try {
            awaitFeature("book search card") { containsText(qaRoot(test), "在线阅读") }
        } catch (failure: IllegalStateException) {
            val ui = model.ui.value
            throw IllegalStateException("book search card: consent=${ui.consentAccepted}, results=${ui.online.results.size}, running=${ui.online.running}, error=${ui.online.error}, message=${ui.online.message}, window=${qaRoot(test)?.packageName}", failure)
        }
        clickText(test, "在线阅读")
        awaitFeature("online shelf") { model.ui.value.books.any { it.onlineUrl == details.book.url } }
        val book = model.ui.value.books.first { it.onlineUrl == details.book.url }
        check(book.onlineOnly && book.file.isBlank() && !book.missing && !container.store.exists(book))
        awaitFeature("online reader body") { containsText(qaRoot(test), "自动化验证生成的第一章正文") }
        clickText(test, "这是自动化验证生成的第一章正文。")
        awaitFeature("online chapter toolbar") { containsText(qaRoot(test), "更多阅读工具") }
        clickText(test, "下章")
        awaitFeature("online second chapter body") { containsText(qaRoot(test), "自动化验证生成的第二章正文") }
        awaitFeature("online progress") { container.store.load().books.first { it.id == book.id }.position == 1 }
        awaitFeature("online shared toolbar") { containsText(qaRoot(test), "更多阅读工具") }
        clickText(test, "更多阅读工具")
        awaitFeature("online marks menu") { containsText(qaRoot(test), "书签") }
        clickText(test, "书签")
        awaitFeature("online marks panel") { containsText(qaRoot(test), "添加当前阅读位置书签") }
        clickText(test, "添加当前阅读位置书签")
        awaitFeature("online annotation saved") {
            runCatching {
                val marks = org.json.JSONArray(model.ui.value.books.first { it.id == book.id }.onlineAnnotations)
                marks.length() == 1 && marks.getJSONObject(0).let { mark ->
                    mark.getString("chapterUrl") == details.toc[1].url && mark.getInt("chapter") == 1 && mark.getString("quote").isNotBlank()
                }
            }.getOrDefault(false)
        }
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitFeature("online marks dismissed") { !containsText(qaRoot(test), "添加当前阅读位置书签") }
        awaitFeature("online body after sheet") { containsText(qaRoot(test), "自动化验证生成的第二章正文") }
        test.waitForIdleSync(); Thread.sleep(350)
        if (!containsText(qaRoot(test), "更多阅读工具")) clickText(test, "这是自动化验证生成的第二章正文。")
        awaitFeature("online toolbar restored") { containsText(qaRoot(test), "更多阅读工具") }
        clickText(test, "更多阅读工具")
        awaitFeature("online settings menu") { containsText(qaRoot(test), "阅读设置") }
        clickText(test, "阅读设置")
        awaitFeature("online settings panel") { containsText(qaRoot(test), "恢复跟随全局设置") }
        clickText(test, "左右翻页")
        awaitFeature("online paging preference") { ReadingPreferences.novel(model.ui.value.books.first { it.id == book.id }.readerPreferences).turnMode == TurnMode.HORIZONTAL }
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitFeature("online settings dismissed") { !containsText(qaRoot(test), "恢复跟随全局设置") }
        record("ONLINE_TOOLS_UI_OK shared menu, current-chapter tools, saved annotation and independent settings panel")
        verifyHorizontalNovel(test, "ONLINE", record)
        verifyOnlineChapterBoundary(test, { model.ui.value.books.first { it.id == book.id }.position }, record)
        awaitFeature("online first page saved") { model.ui.value.books.first { it.id == book.id }.onlineCharacterOffset == 0 }
        val archive = ByteArrayOutputStream()
        val shelfLibrary = container.store.load().let { it.copy(books = it.books.filter { entry -> entry.id == book.id }) }
        val report = Backup.export(container.store, shelfLibrary, null, archive)
        check(report.included == 0 && report.missing == 0)
        val restored = LibraryStore(File(context.filesDir, "online-restore"))
        val imported = Backup.import(restored, Library(), archive.toByteArray().inputStream())
        check(imported.missingText == 0 && restored.load().books.single().onlineOnly && !restored.load().books.single().missing)
        record("ONLINE_SHELF_READER_BACKUP_OK actual search-card read action; two online chapter bodies; progress and backup metadata")
        test.runOnMainSync { model.downloadShelfBook(book) }
        awaitFeature("download online shelf", 30_000) { model.ui.value.books.first { it.id == book.id }.let { !it.onlineOnly && container.store.exists(it) } }
        val offline = model.ui.value.books.first { it.id == book.id }
        check(container.store.readText(offline).contains("自动化验证生成的第二章正文"))
        check(offline.position == offline.chapters[1].position) { "chapter position not translated to text position" }
        check(offline.bookmarks.any { it.position in offline.chapters[1].position until offline.length }) { "online bookmark disappeared after offline conversion" }
        record("ONLINE_TO_OFFLINE_OK same shelf entry, complete body and translated reading position")
    } catch (failure: Throwable) {
        val root = qaRoot(test)
        fun describe(node: AccessibilityNodeInfo?): String {
            if (node == null || node.packageName?.toString() != test.targetContext.packageName) return ""
            return (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).map { describe(node.getChild(it)) }).filter { it.isNotBlank() }.joinToString(" | ")
        }
        record("ONLINE_FAILURE_UI screen=${navigator.screen} mode=${model.ui.value.settings.turnMode} window=${root?.packageName} ${describe(root).take(4000)}")
        Thread.getAllStackTraces().filter { (_, stack) -> stack.any { it.className.contains("sonovel") || it.className.contains("OnlineEngine") || it.className.contains("AndroidEngine") } }
            .forEach { (thread, stack) -> record("ONLINE_FAILURE_THREAD ${thread.name}: ${stack.take(24).joinToString(" <- ")}") }
        throw failure
    } finally {
        test.runOnMainSync { activity.setContent { Text("在线阅读回归完成") }; owner.clear() }
        main.writeBytes(old)
    }
}

fun mangaFeatureSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val sandbox = File(test.targetContext.cacheDir, "manga-feature-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = sandbox }
    val sourceText = test.context.assets.open("legado-sample.json").bufferedReader().use { it.readText() }
    val converted = MangaSource.importText(sourceText)
    check(converted.size == 97 && MangaSource.lastSkipped.isEmpty())
    record("ANDROID_LEGADO_97_OK imported=${converted.size} skipped=${MangaSource.lastSkipped.size}; ART regex compatibility")
    val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    val png = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
    }.toByteArray()
    val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val hits = AtomicInteger()
    val downloading = AtomicInteger()
    val downloadPeak = AtomicInteger()
    val handlers = Executors.newFixedThreadPool(4)
    val worker = Thread {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            handlers.submit { socket.use {
                val reader = it.getInputStream().bufferedReader()
                val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty().substringBefore('?')
                while (true) { if (reader.readLine().isNullOrEmpty()) break }
                val bytes = if (path.startsWith("/download-page")) {
                    val active = downloading.incrementAndGet()
                    downloadPeak.updateAndGet { maxOf(it, active) }
                    try { Thread.sleep(300); png } finally { downloading.decrementAndGet() }
                } else if (path.endsWith(".gif")) gif else if (path.endsWith(".png")) png else {
                    if (path.startsWith("/chapter")) hits.incrementAndGet()
                    (when (path) {
                        "/search" -> "<div class='item'><a href='/book/1'><span class='title'>原创测试漫画</span></a></div>"
                        "/book/1" -> "<h1>原创测试漫画</h1><div class='chapters'><a href='/chapter/1'>第一话</a><a href='/chapter/2'>第二话</a></div>"
                        "/chapter/1" -> "<div class='pages'><img src='/image1.png'><img src='/image2.gif'></div>"
                        "/chapter/2" -> "<div class='pages'>" + (1..6).joinToString("") { "<img src='/download-page$it.png'>" } + "</div>"
                        else -> "<h1>空目录漫画</h1>"
                    }).toByteArray()
                }
                it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: ${if (path.endsWith(".gif")) "image/gif" else if (path.endsWith(".png")) "image/png" else "text/html; charset=utf-8"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } }
        }
    }.apply { isDaemon = true; start() }
    val seedStore = MangaStore(File(sandbox, "comics"))
    val source = MangaSource.parse(org.json.JSONObject("""{"id":"qa-manga","name":"原创漫画测试源","baseUrl":"$base","search":{"url":"/search?q={query}","items":".item","title":".title","link":"a@href"},"detail":{"title":"h1","chapters":".chapters a","chapterTitle":"","chapterUrl":"@href"},"reader":{"pages":".pages img","image":"@src"}}"""))
    seedStore.saveSources(listOf(source))
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = ViewModelStore()
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    test.runOnMainSync { model = ReaderViewModel(container); owner.put("manga-fixture", model) }
    val store = model.manga.store
    try {
        awaitFeature("manga model loaded") { model.manga.state.value.loaded && model.ui.value.loaded }
        val controller = model.manga
        test.runOnMainSync { controller.search("原创测试漫画"); navigator.openMangaOnline(); featureContent(activity, model, navigator) }
        awaitFeature("manga search card") { controller.state.value.results.isNotEmpty() && containsText(qaRoot(test), "在线看漫") }
        val found = controller.state.value.results.single()
        val legacyRaw = org.json.JSONObject().put("bookSourceName", "原创旧式源").put("bookSourceUrl", base)
            .put("ruleSearchUrl", "$base/search?q=searchKey&page=searchPage").put("ruleSearchList", ".item")
            .put("ruleSearchName", ".title@text").put("ruleSearchNoteUrl", "a@href@js:result")
            .put("ruleChapterList", ".chapters a").put("ruleChapterName", "@text").put("ruleContentUrl", "@href")
            .put("ruleBookContent", "@js:var n=org.jsoup.Jsoup.parse(result).select('img'); var out=[]; for(var i=0;i<n.size();i++)out.push(n.get(i).attr('src'));out;")
        val legacy = LegadoConverter.convert(legacyRaw).source!!
        check(legacy.legacy != null && legacy.enabled)
        val legacyBook = runBlocking { controller.engine.details(legacy, controller.engine.search(legacy, "原创", 1).single()) }
        check(legacyBook.chapters.size == 2)
        check(runBlocking { controller.engine.pages(legacy, legacyBook.chapters.first()) }.size == 2)
        record("LEGACY_RULES_ART_OK selectors, JS links, Jsoup facade, directory and image array")
        check(found.chapters.isEmpty())
        clickText(test, "在线看漫")
        awaitFeature("keep search result without toc") { controller.state.value.books.any { it.id == found.id } && !controller.state.value.saving }
        val book = controller.state.value.books.first { it.id == found.id }
        check(book.chapters.size == 2 && book.onlineOnly)
        record("MANGA_KEEP_NO_TOC_OK actual search-card online action fetches directory; no main-thread exception")
        test.runOnMainSync { controller.keep(found.copy(id = "empty-book", url = "$base/empty")) }
        awaitFeature("empty toc handled") { !controller.state.value.saving && controller.state.value.detailError.isNotBlank() }
        check(controller.state.value.books.none { it.id == "empty-book" })
        test.runOnMainSync { controller.dismissMessage() }
        val before = hits.get()
        repeat(2) { runBlocking { check(controller.ensurePages(book.id, 0).size == 2) } }
        check(hits.get() <= before + 1) { "cached online chapter was fetched repeatedly" }
        val cachedReads = hits.get()
        runBlocking { controller.ensurePages(book.id, 0, refreshPages = true) }
        check(hits.get() == cachedReads + 1) { "explicit chapter refresh did not request new image addresses" }
        test.runOnMainSync { controller.saveReaderPreferences(book.id, ReadingPreferences.encode(MangaSettings(
            mode = MangaReadingMode.VERTICAL, immersive = false, autoNextChapter = true, prefetchPages = 0))) }
        awaitFeature("manga fixture preferences") { controller.state.value.books.first { it.id == book.id }.readerPreferences.isNotBlank() }
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaReaderScreen(model, navigator, book.id) } } }
        awaitFeature("manga reader fetched pages") { store.cacheUsage().onlineCount > 0 }
        awaitFeature("manga image rendered") { containsText(qaRoot(test), "第 1 页") }
        Thread.sleep(600)
        check(store.books().first { it.id == book.id }.chapterIndex == 0) { "programmatic image layout skipped the first chapter" }
        record("MANGA_ONLINE_READER_OK cached chapter reused; explicit refresh fetches once; actual image displayed/downloaded")
        test.runOnMainSync { activity.setContent { Text("漫画下载并发回归") } }
        awaitFeature("reader disposed before download") { containsText(qaRoot(test), "漫画下载并发回归") }
        downloadPeak.set(0)
        runBlocking { controller.download(book.id, -1) { _, _, _ -> } }
        check(downloadPeak.get() in 2..3) { "active manga download concurrency=${downloadPeak.get()}, expected bounded parallel requests" }
        record("MANGA_DOWNLOAD_CONCURRENCY_OK six uncached images peak=${downloadPeak.get()} bounded by engine; not serialized by background prefetch")
        val downloaded = store.books().first { it.id == book.id }
        check(!downloaded.onlineOnly && downloaded.cachedChapters.size == 2)
        val gifFile = runBlocking { controller.engine.imageFile("$base/image2.gif", source.id) }
        val decodedGif = decodeMangaTile(MangaImageInfo(gifFile, 1, 1), 0, 1, 1)
        check(decodedGif.width == 1 && decodedGif.height == 1); decodedGif.recycle()
        record("MANGA_GIF_FALLBACK_OK non-region format decodes successfully")
        val cropped = decodeMangaTile(MangaImageInfo(runBlocking { controller.engine.imageFile("$base/image1.png", source.id) }, 64, 96), 0, 96, 1, 10)
        check(cropped.width < 64); cropped.recycle()
        record("MANGA_CROP_OK bounded horizontal crop")
        val networkAfterDownload = hits.get()
        runBlocking { controller.ensurePages(book.id, 1).forEach { controller.engine.imageFile(it, source.id) } }
        check(hits.get() == networkAfterDownload) { "offline manga still fetched chapter" }
        store.clearImageCache()
        check(store.books().first { it.id == book.id }.let { !it.onlineOnly && it.cachedChapters.size == 2 })
        record("MANGA_DOWNLOAD_OFFLINE_CACHE_OK all pages cached; offline chapter requires zero requests; clearing temporary cache preserves protected downloads")
        val cbz = File(sandbox, "local-test.cbz")
        ZipOutputStream(cbz.outputStream()).use { zip -> for (name in listOf("10.png", "2.png", "1.png")) { zip.putNextEntry(ZipEntry(name)); zip.write(png); zip.closeEntry() } }
        val locals = runBlocking { store.import(context, listOf(Uri.fromFile(cbz))) }
        check(locals.single().local && !locals.single().onlineOnly && locals.single().chapters.single().pages.size == 3)
        locals.single().chapters.single().pages.forEach { check(store.localImage(it).isFile) }
        record("MANGA_CBZ_IMPORT_OK local archive yields three readable images")
        val archive = ByteArrayOutputStream()
        Backup.export(container.store, Library(), ReadingPreferences.encode(ReaderSettings(ttsSleepMinutes = 30)), archive) { zip ->
            MangaBackup.write(zip, store, MangaSettings(doublePage = true, cropPercent = 8), true)
        }
        val restoredComics = MangaStore(File(sandbox, "restored-comics"))
        MangaBackup.Restore(restoredComics, sandbox).use { extra ->
            val imported = Backup.import(LibraryStore(File(sandbox, "restored-novels")), Library(), archive.toByteArray().inputStream(), extra::accept)
            check(imported.settingsJson!!.contains("ttsSleepMinutes"))
            val (count, preferences) = extra.finish()
            check(count == 2 && ReadingPreferences.manga(preferences!!, MangaSettings()).doublePage)
        }
        val restoredOnline = restoredComics.books().first { !it.local }
        check(restoredOnline.cachedChapters.size == 2 && !restoredOnline.onlineOnly)
        check(restoredOnline.chapters.flatMap { it.pages }.all { restoredComics.cachedImage(restoredOnline.sourceId, it).isFile })
        val restoredLocal = restoredComics.books().first { it.local }
        check(restoredLocal.chapters.flatMap { it.pages }.all { restoredComics.localImage(it).isFile })
        record("FULL_BACKUP_RESTORE_OK protected images, local files, sources and independent settings")
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MainScaffold(model, navigator) } } }
        // 底部收敛为 3 个入口；书架内部再分小说/漫画。
        for ((tab, label) in listOf(MainTab.LIBRARY to "书架", MainTab.DISCOVER to "发现", MainTab.MINE to "我的")) {
            test.runOnMainSync { navigator.selectTab(tab) }
            awaitFeature("tab rendered: $tab") { containsText(qaRoot(test), label) }
        }
        // 书架分区：小说与漫画都能从同一入口到达。
        for ((section, label) in listOf(LibrarySection.NOVEL to "小说", LibrarySection.COMIC to "漫画")) {
            test.runOnMainSync { navigator.selectTab(MainTab.LIBRARY); navigator.librarySection = section }
            awaitFeature("library section rendered: $section") { containsText(qaRoot(test), label) }
        }
        // 在线搜书 / 在线搜漫仍可从「发现」到达。
        test.runOnMainSync { navigator.openOnline() }
        awaitFeature("discover: online books") { containsText(qaRoot(test), "搜索书名或作者") }
        test.runOnMainSync { navigator.openTomato() }
        awaitFeature("discover: default Tomato service") { containsText(qaRoot(test), "默认番茄服务") }
        check(!containsText(qaRoot(test), "搜索范围")) { "Tomato must not show imported source selection" }
        test.runOnMainSync { navigator.openMangaOnline() }
        awaitFeature("discover: online comics") { containsText(qaRoot(test), "漫画名或作者") }
        record("MOBILE_NAVIGATION_OK shelf(books/comics), discover(aggregate/default Tomato/comics), mine render")
    } catch (failure: Throwable) {
        fun describe(node: AccessibilityNodeInfo?): String {
            if (node == null || node.packageName?.toString() != test.targetContext.packageName) return ""
            return (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).map { describe(node.getChild(it)) }).filter { it.isNotBlank() }.joinToString(" | ")
        }
        record("MANGA_FAILURE_UI ${describe(qaRoot(test)).take(4000)}")
        throw failure
    } finally {
        test.runOnMainSync { activity.setContent { Text("手机版功能回归完成") }; owner.clear() }
        server.close(); worker.join(1000); handlers.shutdownNow(); sandbox.deleteRecursively()
    }
}
