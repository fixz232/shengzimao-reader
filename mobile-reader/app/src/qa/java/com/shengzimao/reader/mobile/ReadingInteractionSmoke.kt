package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.ui.*
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import java.io.File
import kotlin.math.abs

private fun waitInteraction(label: String, ready: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 15_000
    while (SystemClock.uptimeMillis() < deadline) {
        if (ready()) return
        Thread.sleep(60)
    }
    error("interaction timed out: $label")
}

private fun interactionNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (node == null) return null
    if (predicate(node)) return node
    for (i in 0 until node.childCount) interactionNode(node.getChild(i), predicate)?.let { return it }
    return null
}

private fun interactionClick(test: Instrumentation, label: String) {
    var node = interactionNode(qaRoot(test)) { it.text?.toString() == label || it.contentDescription?.toString() == label }
        ?: error("missing interaction button: $label")
    repeat(6) {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        node = node.parent ?: error("nonclickable interaction: $label")
    }
    error("interaction click failed: $label")
}

private fun interactionSwipe(test: Instrumentation, x: Float, top: Float, bottom: Float) {
    val started = SystemClock.uptimeMillis()
    fun inject(action: Int, y: Float) {
        val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
        try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    inject(MotionEvent.ACTION_DOWN, bottom)
    for (i in 1..12) { Thread.sleep(20); inject(MotionEvent.ACTION_MOVE, bottom + (top - bottom) * i / 12) }
    inject(MotionEvent.ACTION_UP, top)
    Thread.sleep(200)
}

/** 原创长图 + 真实控件：不接触正式包、源站或用户书库。 */
fun readingInteractionSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val sandbox = File(test.targetContext.cacheDir, "reading-interaction-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = sandbox }
    val container = AppContainer(context)
    val store = MangaStore(File(sandbox, "comics"))
    val id = "interaction-comic"
    val image = File(store.localDir, "$id/long.png").apply { parentFile!!.mkdirs() }
    val bitmap = Bitmap.createBitmap(320, 12_000, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        val paint = Paint()
        for (i in 0 until 40) {
            paint.color = android.graphics.Color.rgb(70 + i % 3 * 35, 100 + i % 4 * 25, 130 + i % 5 * 18)
            drawRect(0f, i * 300f, 320f, (i + 1) * 300f, paint)
            paint.color = android.graphics.Color.WHITE; paint.textSize = 32f
            drawText("QA band $i", 20f, i * 300f + 100, paint)
        }
    }
    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    val pages = (1..6).map { index ->
        File(image.parentFile, "$index.png").also { image.copyTo(it) }.toURI().toString()
    }
    store.upsert(MangaBook(id, "原创长图交互测试", cover = pages.first(), chapters = listOf(
        MangaChapter("第一话", "local:one", pages), MangaChapter("第二话", "local:two", pages))))
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val owner = ViewModelStore()
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    var generation = 0
    test.runOnMainSync { model = ReaderViewModel(container); owner.put("interaction", model) }
    fun mount(narrow: Boolean = false) {
        val stamp = ++generation
        test.runOnMainSync { activity.setContent {
            key(stamp) { ShengZiMaoTheme(false) {
                if (narrow) {
                    val density = LocalDensity.current
                    val configuration = Configuration(LocalConfiguration.current).apply { screenWidthDp = 320; fontScale = 1.6f }
                    CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, 1.6f)) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) { MangaReaderScreen(model, navigator, id) }
                    }
                } else MangaReaderScreen(model, navigator, id)
            } }
        } }
    }
    fun has(text: String) = interactionNode(qaRoot(test)) { it.text?.toString() == text } != null
    fun seek(fraction: Float, page: Int) {
        val node = interactionNode(qaRoot(test)) { it.isVisibleToUser && it.rangeInfo != null }
            ?: error("manga slider missing")
        check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, fraction) }))
        waitInteraction("seek page $page") { has("$page / 6 页") && interactionNode(qaRoot(test)) {
            it.isVisibleToUser && it.contentDescription?.toString() == "第 $page 页"
        } != null }
    }
    try {
        waitInteraction("interaction library") { model.manga.state.value.loaded && model.ui.value.loaded }
        for (mode in MangaReadingMode.entries) {
            val preferences = ReadingPreferences.encode(MangaSettings(mode = mode, immersive = false, autoNextChapter = false, prefetchPages = 0))
            test.runOnMainSync { model.manga.saveReaderPreferences(id, preferences) }
            waitInteraction("mode saved") { model.manga.state.value.books.first { it.id == id }.readerPreferences == preferences }
            mount()
            waitInteraction("mode reader") { has("1 / 6 页") }
            seek(.8f, 5)
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            waitInteraction("volume next ${mode.name}") { has("6 / 6 页") }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            waitInteraction("volume previous ${mode.name}") { has("5 / 6 页") }
            seek(0f, 1)
            if (mode != MangaReadingMode.VERTICAL) verifyHorizontalManga(test, mode.reversed, record)
            if (mode != MangaReadingMode.VERTICAL) {
                seek(1f, 6)
                val bounds = android.graphics.Rect(); qaRoot(test)!!.getBoundsInScreen(bounds)
                val left = bounds.left + bounds.width() * .15f; val right = bounds.left + bounds.width() * .85f
                val y = bounds.top + bounds.height() * .52f
                Thread.sleep(500)
                pagingTouch(test, if (mode.reversed) left else right, y)
                waitInteraction("manga next chapter") { has("第二话") && has("1 / 6 页") }
                waitInteraction("manga next chapter image") { interactionNode(qaRoot(test)) { it.contentDescription?.toString() == "第 1 页" } != null }
                Thread.sleep(500)
                pagingTouch(test, if (mode.reversed) right else left, y)
                waitInteraction("manga previous chapter last page") { has("第一话") && has("6 / 6 页") }
                seek(0f, 1)
                record("MANGA_CHAPTER_BOUNDARY_OK mode=${mode.name} next first page and previous last page")
            }
            val root = qaRoot(test) ?: error("QA application window is not visible")
            val bounds = android.graphics.Rect(); root.getBoundsInScreen(bounds)
            repeat(4) { interactionSwipe(test, bounds.centerX().toFloat(), bounds.top + bounds.height() * .3f, bounds.top + bounds.height() * .75f) }
            waitInteraction("long image anchor ${mode.name}") { store.books().single().pageFraction > .02f }
            val saved = store.books().single().pageFraction
            test.runOnMainSync { activity.setContent { Text("交互恢复中") } }
            Thread.sleep(1600)
            test.runOnMainSync { model.manga.reloadLibrary() }
            waitInteraction("anchor reloaded") { abs(model.manga.state.value.books.single().pageFraction - saved) < .025f }
            mount(narrow = true)
            waitInteraction("restored page") { has("1 / 6 页") }
            Thread.sleep(2600)
            check(abs(store.books().single().pageFraction - saved) < .06f) { "${mode.name} lost long-image anchor: $saved -> ${store.books().single().pageFraction}" }
            if (mode == MangaReadingMode.HORIZONTAL) {
                test.uiAutomation.takeScreenshot()?.let { screenshot ->
                    File(test.targetContext.getExternalFilesDir(null), "qa-1.8.0-manga-320dp.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
            record("MANGA_INTERACTION_OK mode=${mode.name} slider+volume+image anchor=$saved restored at 320dp/fontScale=1.6")
            test.runOnMainSync { activity.setContent { Text("方向切换中") } }
            Thread.sleep(1500)
            test.runOnMainSync { store.stagePosition(id, 0, 0, 0f, pages.first()); store.flushProgress(); model.manga.reloadLibrary() }
            waitInteraction("position reset") { model.manga.state.value.books.single().pageFraction == 0f }
        }
        test.runOnMainSync { model.updateMangaSettings { it.copy(libraryCompact = false) }; activity.setContent { ShengZiMaoTheme(false) { MangaLibraryScreen(model, navigator) } } }
        waitInteraction("library layout") { interactionNode(qaRoot(test)) { it.contentDescription?.toString() == "切换紧凑列表" } != null }
        interactionClick(test, "切换紧凑列表")
        waitInteraction("compact layout") { model.mangaSettings.value.libraryCompact && has("原创长图交互测试") }
        record("MANGA_COMPACT_LIBRARY_OK persisted grid/list control")
    } catch (failure: Throwable) {
        fun snapshot(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            return (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).map { snapshot(node.getChild(it)) }).filter { it.isNotBlank() }.joinToString(" | ")
        }
        record("INTERACTION_FAILURE_UI ${snapshot(qaRoot(test)).take(3000)}")
        test.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(test.targetContext.getExternalFilesDir(null), "qa-1.8.0-interaction-failure.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        throw failure
    } finally {
        test.runOnMainSync { activity.setContent { Text("阅读交互回归完成") }; owner.clear() }
        sandbox.deleteRecursively()
    }
}
