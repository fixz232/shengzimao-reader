package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.graphics.BitmapFactory
import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme

/** 可选真实源诊断：指定作品首章首中末图及真实阅读页，不操作正式书库。 */
fun mangaLiveSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) = runBlocking {
    val root = File(test.targetContext.cacheDir, "manga-live-${System.nanoTime()}").apply { mkdirs() }
    try {
        val all = MangaSource.importText(test.context.assets.open("legado-sample.json").bufferedReader().use { it.readText() })
        val store = MangaStore(File(root, "comics")); store.saveSources(all)
        val engine = MangaEngine(store)
        val failures = mutableListOf<String>()
        for ((tag, source) in listOf("yyds" to all.first { it.name.contains("YYDS") }, "baozi" to all.first { it.name.endsWith("包子漫画") })) {
        runCatching {
        record("MANGA_LIVE_START source=${source.name} legacy=${source.legacy != null}")
        val results = engine.search(source, "道诡异仙", 1)
        record("MANGA_LIVE_SEARCH source=$tag results=${results.size}")
        val book = results.firstOrNull { it.title.contains("道诡异仙") } ?: error("YYDS 没有返回指定作品")
        val details = engine.details(source, book)
        record("MANGA_LIVE_DIRECTORY source=$tag chapters=${details.chapters.size}")
        val chapter = details.chapters.first()
        record("MANGA_LIVE_CHAPTER source=$tag url=${chapter.url}")
        okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build().newCall(
            okhttp3.Request.Builder().url(chapter.url).apply { source.headers.forEach { (k, v) -> header(k, v) } }.build()
        ).execute().use { response ->
            val html = response.body.string()
            File(test.targetContext.getExternalFilesDir(null), "qa-181-$tag-chapter.html").writeText(html)
            record("MANGA_LIVE_CHAPTER_HTML source=$tag status=${response.code} chars=${html.length}")
        }
        val pages = engine.pages(source, chapter)
        File(test.targetContext.getExternalFilesDir(null), "qa-181-$tag-pages.json").writeText(org.json.JSONArray(pages).toString())
        record("MANGA_LIVE_PAGES source=$tag count=${pages.size} chapter=${chapter.title}")
        for (index in listOf(0, pages.size / 2, pages.lastIndex).distinct()) {
        val url = URI(pages[index])
        record("MANGA_LIVE_IMAGE_URL source=$tag index=$index ${url.scheme}://${url.rawAuthority}${url.rawPath}")
        check(!mangaPlaceholder(pages[index])) { "不能把加载占位图当作漫画正文" }
        val image = engine.imageFile(pages[index], source.id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.absolutePath, bounds)
        check(bounds.outWidth > 100 && bounds.outHeight > 100 && image.length() > 2000)
        record("MANGA_LIVE_IMAGE_OK source=$tag index=$index bytes=${image.length()} width=${bounds.outWidth} height=${bounds.outHeight}")
        }
        val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
        val container = AppContainer(context)
        container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
        val preferences = ReadingPreferences.encode(MangaSettings(mode = MangaReadingMode.HORIZONTAL, immersive = false,
            autoNextChapter = false, prefetchPages = 0))
        store.upsert(details.copy(readerPreferences = preferences))
        val owner = ViewModelStore()
        lateinit var model: ReaderViewModel
        test.runOnMainSync { model = ReaderViewModel(container); owner.put("live", model) }
        try {
            pagingWait("$tag live library") { model.manga.state.value.loaded }
            test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) { MangaReaderScreen(model, Navigator(), details.id) } } }
            pagingWait("$tag live reader image") { pagingNode(qaRoot(test)) { it.contentDescription?.toString() == "第 1 页" } != null }
            Thread.sleep(1000)
            test.uiAutomation.takeScreenshot()?.let { original ->
                val crop = android.graphics.Bitmap.createBitmap(original, 0, 180, original.width, original.height - 360)
                File(test.targetContext.getExternalFilesDir(null), "qa-181-$tag-reader.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle(); original.recycle()
            }
            record("MANGA_LIVE_READER_UI_OK source=$tag image displayed in production MangaReaderScreen")
        } finally {
            test.runOnMainSync { activity.setContent { androidx.compose.material3.Text("真实阅读页验证完成") }; owner.clear() }
        }
        }.onFailure { error ->
            failures += "$tag: ${error.message}"
            record("MANGA_LIVE_SOURCE_FAILED source=$tag ${error.javaClass.simpleName}: ${error.message}")
        }
        }
        check(failures.isEmpty()) { failures.joinToString("; ") }
    } finally { root.deleteRecursively() }
}
