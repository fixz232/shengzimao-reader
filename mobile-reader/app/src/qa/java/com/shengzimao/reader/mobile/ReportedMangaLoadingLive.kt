package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import kotlinx.coroutines.runBlocking
import java.io.File

/** Uses an explicitly staged source export and an isolated library; never opens the release library. */
fun reportedMangaLoadingLive(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) = runBlocking {
    val sourcesFile = File(test.targetContext.getExternalFilesDir(null), "qa-manga-sources.json")
    check(sourcesFile.isFile) { "Stage the phone's source export in the independent QA package first" }
    val sources = MangaSource.importText(sourcesFile.readText())
    val selected = sources.filter { java.net.URI(it.baseUrl).host in setOf("m.g-mh.org", "baozimh.org") }
    check(selected.size == 2)
    for (source in selected) {
        record("REPORTED_SOURCE_START name=${source.name} host=${java.net.URI(source.baseUrl).host}")
        val root = File(test.targetContext.cacheDir, "reported-manga-${System.nanoTime()}").apply { mkdirs() }
        val store = MangaStore(File(root, "comics")); store.saveSources(listOf(source))
        val engine = MangaEngine(store)
        val owner = ViewModelStore()
        try {
            val library = mutableListOf<MangaBook>()
            for (title in listOf("水边之夜", "道诡异仙", "爱情魔咒")) {
                val hits = engine.search(source, title, 1)
                val hit = hits.firstOrNull { it.title.contains(title) } ?: error("No matching live search result for $title")
                val book = engine.details(source, hit)
                val index = book.chapters.indexOfFirst { it.title.contains("第01话") || it.title.contains("01 师傅") }.coerceAtLeast(0)
                val chapter = book.chapters[index]
                val pages = engine.pages(source, chapter)
                check(pages.size > 1)
                record("REPORTED_DIRECTORY_OK source=${source.name} title=${book.title} chapters=${book.chapters.size} pages=${pages.size}")
                for (page in listOf(0, pages.size / 2, pages.lastIndex).distinct()) {
                    val file = engine.imageFile(pages[page], source.id)
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Real image decode failed")
                    check(bitmap.width > 50 && bitmap.height > 50)
                    record("REPORTED_IMAGE_OK source=${source.name} title=${book.title} page=$page bytes=${file.length()} size=${bitmap.width}x${bitmap.height}")
                    bitmap.recycle()
                }
                // Existing users have a saved directory and expired image URLs, not a fresh search result.
                val stale = chapter.copy(pages = listOf(if (java.net.URI(source.baseUrl).host == "baozimh.org")
                    "https://c-nd3-1.6wm.top/hp/expired-fixture.webp" else "https://f40-1-4.g-mh.online/hp/expired-fixture.webp"),
                    imageHeaders = emptyMap())
                val saved = book.copy(chapterIndex = index, chapters = book.chapters.mapIndexed { i, c -> if (i == index) stale else c },
                    readerPreferences = ReadingPreferences.encode(MangaSettings(mode = MangaReadingMode.HORIZONTAL,
                        immersive = false, autoNextChapter = false, prefetchPages = 0)))
                store.upsert(saved); library += saved
            }
            val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
            val container = AppContainer(context)
            container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
            lateinit var model: ReaderViewModel
            test.runOnMainSync { model = ReaderViewModel(container); owner.put("reported-live", model) }
            pagingWait("reported library ready") { model.manga.state.value.loaded }
            for (book in library) {
                test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaReaderScreen(model, Navigator(), book.id) } } }
                pagingWait("reported real first image ${book.title}") { pagingNode(qaRoot(test)) {
                    it.contentDescription?.toString() == "第 1 页"
                } != null }
                val refreshed = store.books().first { it.id == book.id }.chapters[book.chapterIndex]
                check(refreshed.pages.size > 1 && refreshed.pages.none(GmhReader::retiredImage))
                record("REPORTED_SAVED_LIBRARY_RECOVERY_OK source=${source.name} title=${book.title} pages=${refreshed.pages.size} readerImageVisible=true")
                val screenshot = test.uiAutomation.takeScreenshot()
                val readerCrop = android.graphics.Bitmap.createBitmap(screenshot, 0, screenshot.height / 4,
                    screenshot.width, screenshot.height * 3 / 5)
                File(test.targetContext.getExternalFilesDir(null), "reported-${source.id}-${book.id}.png").outputStream().use {
                    check(readerCrop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                readerCrop.recycle(); screenshot.recycle()
                test.runOnMainSync { activity.setContent { androidx.compose.material3.Text("切换验证作品") } }
                pagingWait("reported reader disposed") { pagingNode(qaRoot(test)) { it.text?.toString() == "切换验证作品" } != null }
            }
        } finally {
            test.runOnMainSync { activity.setContent { androidx.compose.material3.Text("报告作品验证完成") }; owner.clear() }
            root.deleteRecursively()
        }
    }
}

fun reportedBaoziRoutesLive(test: Instrumentation, record: (String) -> Unit) = runBlocking {
    val root = File(test.targetContext.cacheDir, "reported-baozi-${System.nanoTime()}").apply { mkdirs() }
    try {
        val sources = MangaSource.importText(File(test.targetContext.getExternalFilesDir(null), "qa-manga-sources.json").readText())
        val source = sources.first { it.name == "◯ 包子漫画" }
        val store = MangaStore(root); store.saveSources(listOf(source))
        val engine = MangaEngine(store)
        // The report already has a saved directory. Probe that exact chapter and image,
        // so a fresh-search failure cannot prevent all CDN routes from being measured.
        val chapter = MangaChapter("01 师傅",
            "https://appgb1-vdkr.baozimh.com/baozimhapp/comic/chapter/daoguiyixian-yuewenmanhua/0_0.html")
        val reportedImage = "https://s1.bzcdn.net/scomic/daoguiyixian-yuewenmanhua/0/1-fq4a/1.jpg"
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        var readable = 0
        for (url in BaoziReader.imageCandidates(reportedImage)) {
            val host = java.net.URI(url).host
            try {
                client.newCall(okhttp3.Request.Builder().url(url).header("Referer", source.baseUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val file = File(root, "probe-image")
                    response.body.byteStream().use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(8192); var total = 0
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            total += n; check(total <= 24 * 1024 * 1024); out.write(buffer, 0, n) }
                    } }
                    MangaStore.checkImage(file)
                    readable++; record("REPORTED_BAOZI_ROUTE_OK host=$host bytes=${file.length()}")
                }
            } catch (failure: Exception) { record("REPORTED_BAOZI_ROUTE_FAILED host=$host reason=${failure.message}") }
        }
        check(readable > 0) { "Current public Baozi image routes failed on this phone; directory extraction alone is not reading success" }
        record("REPORTED_BAOZI_PAGES_START")
        val pages = kotlinx.coroutines.withTimeout(20_000) { engine.pages(source, chapter) }
        check(pages.size > 50) { "The reported chapter spans continuation pages: got ${pages.size}" }
        record("REPORTED_BAOZI_COMPLETE_CHAPTER pages=${pages.size}")
        val file = engine.imageFile(pages.first(), source.id)
        check(file.length() > 2000)
        record("REPORTED_BAOZI_ENGINE_IMAGE_OK bytes=${file.length()}")
    } finally { root.deleteRecursively() }
}
