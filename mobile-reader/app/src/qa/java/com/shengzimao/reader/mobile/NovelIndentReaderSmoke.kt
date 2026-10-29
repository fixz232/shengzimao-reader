package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.Navigator
import com.shengzimao.reader.mobile.ui.ReaderScreen
import com.shengzimao.reader.mobile.ui.ReaderViewModel
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

/** Check real Canvas output and live global setting changes without rewriting downloaded TXT. */
fun novelIndentReaderSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val sandbox = File(test.targetContext.cacheDir, "novel-indent-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = sandbox }
    val container = AppContainer(context)
    val originalSettings = runBlocking { container.settings.load() }
    val text = "第一章 原创试读\n\n第一段正文没有预先插入空格，用于验证阅读设置。\n第二段正文也没有前导空格，用于验证每一段的缩进。\n第三段正文用于验证开关关闭后的左对齐。"
    val snapshot = ReadingPreferences.encode(ReaderSettings(fontSize = 20, indentParagraphs = false,
        fullScreen = true, showFooter = false, paddingDp = 20))
    val books = listOf("聚合搜书", "番茄小说").mapIndexed { index, source ->
        val id = "indent-download-$index"
        Book(id = id, title = "$source 缩进测试", sourceName = source, format = "TXT",
            file = container.store.writeText(id, text), length = text.length,
            digest = LibraryStore.digest(text), chapters = ChapterParser.find(text), readerPreferences = snapshot)
    }
    container.store.save(Library(books = books))
    val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    val navigator = Navigator()
    lateinit var model: ReaderViewModel
    test.runOnMainSync {
        model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(container) as T
        })[ReaderViewModel::class.java]
    }
    fun surface(): AccessibilityNodeInfo? = pagingNode(qaRoot(test)) {
        it.contentDescription?.toString()?.contains("页，共") == true
    }
    fun capture(tag: String): Int {
        pagingWait("indent reader visible") { surface() != null }
        test.waitForIdleSync()
        Thread.sleep(350)
        val bounds = Rect().also { surface()!!.getBoundsInScreen(it) }
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("indent screenshot unavailable")
        try {
            val crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
            try { File(test.targetContext.getExternalFilesDir(null), "qa-indent-$tag.png").outputStream().use {
                check(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { crop.recycle() }
            // Scan only the first line, excluding the system bars and footer.
            val firstLineHeight = (20 * context.resources.displayMetrics.scaledDensity * 1.6f).toInt()
            var leftmost = bitmap.width
            for (y in bounds.top.coerceAtLeast(0) until (bounds.top + firstLineHeight).coerceAtMost(bitmap.height)) {
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) {
                        leftmost = minOf(leftmost, x)
                    }
                }
            }
            check(leftmost < bitmap.width) { "no text found in first reader line: $bounds" }
            return leftmost
        } finally { bitmap.recycle() }
    }
    try {
        pagingWait("indent fixture loaded") { model.ui.value.loaded }
        for ((index, book) in books.withIndex()) {
            test.runOnMainSync {
                model.updateSettings { it.copy(indentParagraphs = false) }
                activity.setContent { ShengZiMaoTheme(false) {
                    // Keep the sampled app pixels below system status/heads-up notification surfaces.
                    Box(Modifier.fillMaxSize().padding(top = 160.dp)) {
                        ReaderScreen(container, model, book.id, navigator)
                    }
                } }
            }
            val off = capture("$index-off")
            test.runOnMainSync { model.updateSettings { it.copy(indentParagraphs = true) } }
            val on = capture("$index-on")
            val expected = 40 * context.resources.displayMetrics.scaledDensity
            check(abs(on - off - expected) <= 3f) { "${book.sourceName}: global toggle did not move text two characters: off=$off on=$on expected=$expected" }
            test.runOnMainSync { model.updateSettings { it.copy(indentParagraphs = false) } }
            val offAgain = capture("$index-off-again")
            check(abs(offAgain - off) <= 2) { "${book.sourceName}: disabling indentation did not restore alignment" }
            check(container.store.readText(book) == text) { "downloaded body was modified" }
            check(container.store.load().books.first { it.id == book.id }.readerPreferences == snapshot) { "other per-book preferences were modified" }
            record("NOVEL_DOWNLOADED_INDENT_OK source=${book.sourceName} off=$off on=$on offAgain=$offAgain originalTextUnchanged=true")
            test.runOnMainSync { activity.setContent { Text("切换缩进测试书籍") } }
            test.waitForIdleSync()
        }
        record("NOVEL_INDENT_READER_OK actual offline Canvas; old per-book snapshots; live toggle both directions; both download origins")
    } finally {
        test.runOnMainSync { activity.setContent { Text("首行缩进阅读回归完成") }; owner.viewModelStore.clear() }
        runBlocking { container.settings.save(originalSettings) }
        sandbox.deleteRecursively()
    }
}
