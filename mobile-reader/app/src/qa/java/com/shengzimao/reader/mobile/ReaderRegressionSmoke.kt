package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.ChapterParser
import com.shengzimao.reader.mobile.data.Library
import com.shengzimao.reader.mobile.reader.Paginator
import com.shengzimao.reader.mobile.ui.Navigator
import com.shengzimao.reader.mobile.ui.ReaderScreen
import com.shengzimao.reader.mobile.ui.ReaderViewModel
import com.shengzimao.reader.mobile.ui.Screen
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** QA 变体内的实际阅读页回归：检查百万字、无换行正文以及主线程心跳。 */
fun readerRegressionSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val sandbox = File(test.targetContext.cacheDir, "reader-regression-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = sandbox }
    val container = AppContainer(context)
    val normal = buildString {
        for (chapter in 1..600) {
            append("第${chapter}章 原创测试\n\n")
            repeat(35) { append("这是用于长篇阅读排版验证的原创文字。阅读器应该保持响应，完整展示每一页，不丢失正文，也不阻塞返回操作。\n") }
            append('\n')
        }
    }
    val unbroken = "无换行原创正文".repeat(25_000)
    val books = listOf("reader-long-fixture" to normal, "reader-unbroken-fixture" to unbroken).map { (id, body) ->
        Book(id = id, title = id, file = container.store.writeText(id, body), length = body.length, chapters = ChapterParser.find(body),
            readerPreferences = com.shengzimao.reader.mobile.data.ReadingPreferences.encode(com.shengzimao.reader.mobile.data.ReaderSettings(turnMode = com.shengzimao.reader.mobile.data.TurnMode.HORIZONTAL)))
    }
    container.store.save(Library(books = books))
    lateinit var model: ReaderViewModel
    val navigator = Navigator()
    test.runOnMainSync {
        model = ReaderViewModel(container)
        activity.setContent {
            ShengZiMaoTheme(darkTheme = false) {
                val screen = navigator.screen
                if (screen is Screen.Reading) ReaderScreen(container, model, screen.bookId, navigator)
                else Text("阅读回归测试书库")
            }
        }
    }
    try {
        val loadingDeadline = SystemClock.uptimeMillis() + 10_000
        while (!model.ui.value.loaded && SystemClock.uptimeMillis() < loadingDeadline) Thread.sleep(25)
        check(model.ui.value.loaded) { "test library did not load" }
        awaitRegressionLibrary(test)
        for ((index, book) in books.withIndex()) {
            val body = if (index == 0) normal else unbroken
            val started = SystemClock.uptimeMillis()
            test.runOnMainSync { navigator.openReader(book.id) }
            var ready = false
            var longestHeartbeat = 0L
            while (!ready && SystemClock.uptimeMillis() - started < 30_000) {
                val heartbeat = CountDownLatch(1)
                val sent = SystemClock.uptimeMillis()
                Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                check(heartbeat.await(2, TimeUnit.SECONDS)) { "reader blocked the main thread for 2 seconds" }
                longestHeartbeat = maxOf(longestHeartbeat, SystemClock.uptimeMillis() - sent)
                ready = hasReaderSurface(qaRoot(test))
                if (!ready) Thread.sleep(50)
            }
            check(ready) { "reader did not finish pagination for ${book.id}" }
            if (index == 0) verifyHorizontalNovel(test, "OFFLINE", record)
            val ranges = Paginator.sliceBoundaries(body)
            check(ranges.all { it.last - it.first + 1 <= Paginator.COLUMN_CHARS })
            record("READER_LARGE_BOOK_OK kind=${if (index == 0) "chapters" else "unbroken"} chars=${body.length} openMs=${SystemClock.uptimeMillis() - started} maxMainHeartbeatMs=$longestHeartbeat")
            test.runOnMainSync { navigator.openLibrary() }
            // 必须先确认旧阅读页已经移除，否则下一本可能误命中前一本的可访问性节点。
            awaitRegressionLibrary(test)
        }
        // 排版中立即退出再打开另一书，不应继续占用主线程或发布旧书页表。
        test.runOnMainSync { navigator.openReader(books.first().id) }
        Thread.sleep(50)
        test.runOnMainSync { navigator.openLibrary() }
        awaitRegressionLibrary(test)
        record("READER_CANCEL_OK returned to test library during pagination")
    } finally {
        test.runOnMainSync { activity.setContent { Text("阅读回归测试完成") } }
        sandbox.deleteRecursively()
    }
}

private fun awaitRegressionLibrary(test: Instrumentation) {
    fun hasLibrary(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.text?.toString() == "阅读回归测试书库") return true
        return (0 until node.childCount).any { hasLibrary(node.getChild(it)) }
    }
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (SystemClock.uptimeMillis() < deadline) {
        val root = qaRoot(test)
        if (hasLibrary(root) && !hasReaderSurface(root)) return
        Thread.sleep(50)
    }
    error("reader did not return to the test library")
}

private fun hasReaderSurface(node: AccessibilityNodeInfo?): Boolean {
    if (node == null) return false
    if (node.contentDescription?.toString()?.contains("页，共") == true) return true
    for (index in 0 until node.childCount) if (hasReaderSurface(node.getChild(index))) return true
    return false
}
