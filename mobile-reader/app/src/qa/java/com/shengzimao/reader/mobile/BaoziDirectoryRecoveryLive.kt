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

/** Explicit live check for the two reported sources, using only an isolated library. */
fun baoziDirectoryRecoveryLive(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) = runBlocking {
    val root = File(test.targetContext.cacheDir, "baozi-directory-live-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = root }
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val sources = MangaSource.importText(test.context.assets.open("legado-sample.json").bufferedReader().use { it.readText() })
        .filter { it.name.endsWith("包子漫画web") || it.name.endsWith("包子漫画") }
    check(sources.size == 2)
    MangaStore(File(root, "comics")).saveSources(sources)
    val owner = ViewModelStore(); lateinit var model: ReaderViewModel
    val navigator = Navigator()
    fun waitLive(label: String, ready: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 60_000
        while (!ready()) { check(android.os.SystemClock.uptimeMillis() < deadline) { "live timeout: $label" }; Thread.sleep(200) }
    }
    try {
        test.runOnMainSync { model = ReaderViewModel(container); owner.put("baozi-live", model) }
        waitLive("model ready") { model.ui.value.loaded && model.manga.state.value.loaded }
        test.runOnMainSync { activity.setContent { ShengZiMaoTheme(false) {
            val screen = navigator.screen
            if (screen is Screen.ComicReading) MangaReaderScreen(model, navigator, screen.bookId, screen.chapter) else MangaOnlineScreen(model, navigator)
        } } }
        for (source in sources) {
            val blocked = model.manga.engine.search(source, "一人之下", 1).first { it.url.contains("michengzi") }
            test.runOnMainSync { model.manga.openDetails(blocked) }
            waitLive("blocked notice") { !model.manga.state.value.detailLoading && model.manga.state.value.detailError.contains("屏蔽处理") }
            check(model.manga.state.value.detail?.chapters?.isEmpty() == true)
            pagingWait("visible recovery button") { pagingNode(qaRoot(test)) { it.text?.toString() == "查找其他版本 / 漫源" } != null }
            fun click(label: String) {
                pagingWait("live click $label") {
                    var node = pagingNode(qaRoot(test)) { it.text?.toString() == label } ?: return@pagingWait false
                    while (!node.isClickable) node = node.parent ?: return@pagingWait false
                    node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            click("查看完整说明")
            pagingWait("full unavailable notice dialog") { pagingNode(qaRoot(test)) { it.text?.toString() == "目录加载说明" } != null }
            click("知道了")
            record("BAOZI_RECOVERY_LIVE_BLOCKED_OK source=${source.name} original=${blocked.url}")
            waitLive("automatic validated same-source alternate edition") { model.manga.state.value.detailAlternatives.any {
                it.sourceId == source.id && it.url.endsWith("/yirenzhixia-dongmantang") && it.chapters.isNotEmpty()
            } }
            val candidate = model.manga.state.value.detailAlternatives.first { it.sourceId == source.id && it.url.endsWith("/yirenzhixia-dongmantang") }
            check(model.manga.state.value.books.none { it.id == blocked.id })
            check(candidate.author != blocked.author || candidate.url != blocked.url)
            record("BAOZI_AUTO_ALTERNATIVE_OK source=${source.name} originalAuthor=${blocked.author} alternativeAuthor=${candidate.author} chapters=${candidate.chapters.size} noOriginalOverwrite=true")
            test.runOnMainSync { model.manga.openDetails(candidate) }
            waitLive("real candidate directory") { !model.manga.state.value.detailLoading }
            val detail = model.manga.state.value.detail ?: error("missing candidate")
            check(detail.url == candidate.url && detail.chapters.size > 100) { model.manga.state.value.detailError }
            val firstIndex = detail.chapters.indexOfFirst { chapter ->
                chapter.url.endsWith("/0_0.html") || java.net.URI(chapter.url).query.orEmpty().split('&').let {
                    "section_slot=0" in it && "chapter_slot=0" in it
                }
            }.also { check(it >= 0) { "first chapter missing from real directory" } }
            val pages = model.manga.engine.pages(source, detail.chapters[firstIndex])
            check(pages.isNotEmpty())
            val image = model.manga.engine.imageFile(pages.first(), source.id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            check(bounds.outWidth > 100 && bounds.outHeight > 100 && image.length() > 2000)
            record("BAOZI_RECOVERY_LIVE_IMAGE_OK source=${source.name} author=${candidate.author} chapters=${detail.chapters.size} firstChapterPages=${pages.size} bytes=${image.length()} width=${bounds.outWidth} height=${bounds.outHeight}")
            test.runOnMainSync { model.manga.keep(detail) { id -> model.manga.closeDetails(); navigator.openComic(id, firstIndex) } }
            waitLive("actual reader image") { pagingNode(qaRoot(test)) { it.contentDescription?.toString() == "第 1 页" } != null }
            record("BAOZI_RECOVERY_LIVE_READER_OK source=${source.name} production reader image displayed")
            test.runOnMainSync { navigator.openMangaOnline() }
        }
    } finally {
        test.runOnMainSync { activity.setContent { androidx.compose.material3.Text("包子目录实测完成") } }
        pagingWait("live screen disposed") { pagingNode(qaRoot(test)) { it.text?.toString() == "包子目录实测完成" } != null }
        test.runOnMainSync { owner.clear() }
        root.deleteRecursively()
    }
}
