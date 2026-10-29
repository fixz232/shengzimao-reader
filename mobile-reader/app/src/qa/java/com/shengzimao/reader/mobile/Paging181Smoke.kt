package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 部分系统将手势条窗口标记为 active，回归仅定位本测试包可见的应用窗口。 */
private var qaCacheRefresh = 0L
internal fun qaRoot(test: Instrumentation): AccessibilityNodeInfo? {
    val now = SystemClock.uptimeMillis()
    if (android.os.Build.VERSION.SDK_INT >= 34 && now - qaCacheRefresh >= 500) {
        test.uiAutomation.clearCache(); qaCacheRefresh = now
    }
    val info = test.uiAutomation.serviceInfo
    if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
    }
    test.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == test.targetContext.packageName }?.let { return it }
    return test.uiAutomation.windows.firstNotNullOfOrNull { window ->
        window.root?.takeIf { window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
            it.packageName?.toString() == test.targetContext.packageName && it.isVisibleToUser }
    } ?: test.uiAutomation.rootInActiveWindow
}

internal fun pagingNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (node == null) return null
    if (predicate(node)) return node
    for (i in 0 until node.childCount) pagingNode(node.getChild(i), predicate)?.let { return it }
    return null
}

internal fun pagingWait(label: String, ready: () -> Boolean) {
    val until = SystemClock.uptimeMillis() + 15_000
    while (SystemClock.uptimeMillis() < until) {
        if (ready()) return
        Thread.sleep(50)
    }
    check(ready()) { "paging timeout: $label" }
}

internal fun pagingTouch(test: Instrumentation, fromX: Float, y: Float, toX: Float = fromX) {
    val time = SystemClock.uptimeMillis()
    fun send(action: Int, x: Float, at: Long) {
        val event = MotionEvent.obtain(time, at, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { test.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
    }
    send(MotionEvent.ACTION_DOWN, fromX, time)
    if (fromX != toX) for (i in 1..8) { Thread.sleep(24); send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 8, SystemClock.uptimeMillis()) }
    else Thread.sleep(40)
    send(MotionEvent.ACTION_UP, toX, SystemClock.uptimeMillis())
}

internal fun verifyHorizontalNovel(test: Instrumentation, kind: String, record: (String) -> Unit) {
    fun surface() = pagingNode(qaRoot(test)) { it.contentDescription?.toString()?.startsWith("第 ") == true && it.contentDescription.toString().contains("页，共") }
    fun page() = surface()?.contentDescription?.toString()?.let { Regex("第 (\\d+) 页，共 (\\d+) 页").find(it)?.groupValues?.get(1)?.toInt() }
    pagingWait("$kind initial") { page() == 1 }
    val node = surface()!!
    val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
    val x1 = bounds.left + bounds.width() * .80f; val x2 = bounds.left + bounds.width() * .20f
    val y = bounds.centerY().toFloat()
    pagingTouch(test, x1, y, x2); pagingWait("$kind swipe next") { page() == 2 }
    Thread.sleep(350)
    pagingTouch(test, x2, y, x1); pagingWait("$kind swipe previous") { page() == 1 }
    Thread.sleep(350)
    pagingTouch(test, bounds.left + bounds.width() * .94f, y); pagingWait("$kind right tap") { page() == 2 }
    Thread.sleep(350)
    pagingTouch(test, bounds.left + bounds.width() * .06f, y); pagingWait("$kind left tap") { page() == 1 }
    record("HORIZONTAL_NOVEL_OK kind=$kind left-right swipe and side taps, restored to first page")
}

internal fun verifyHorizontalManga(test: Instrumentation, reversed: Boolean, record: (String) -> Unit) {
    fun page(number: Int) = pagingNode(qaRoot(test)) { it.text?.toString() == "$number / 6 页" } != null
    pagingWait("manga initial image") { page(1) && pagingNode(qaRoot(test)) { it.contentDescription?.toString() == "第 1 页" } != null }
    val bounds = android.graphics.Rect(); qaRoot(test)!!.getBoundsInScreen(bounds)
    val left = bounds.left + bounds.width() * .15f; val right = bounds.left + bounds.width() * .85f
    val y = bounds.top + bounds.height() * .52f
    Thread.sleep(600)
    pagingTouch(test, if (reversed) left else right, y); pagingWait("manga side tap next") { page(2) }
    pagingWait("manga second image") { pagingNode(qaRoot(test)) { it.contentDescription?.toString() == "第 2 页" } != null }
    Thread.sleep(600)
    pagingTouch(test, if (reversed) right else left, y); pagingWait("manga side tap previous") { page(1) }
    Thread.sleep(600)
    pagingTouch(test, if (reversed) left else right, y, if (reversed) right else left)
    pagingWait("manga swipe next") { page(2) }
    Thread.sleep(600)
    pagingTouch(test, if (reversed) right else left, y, if (reversed) left else right)
    pagingWait("manga swipe previous") { page(1) }
    record("HORIZONTAL_MANGA_OK reversed=$reversed side taps and both swipe directions")
}

internal fun verifyOnlineChapterBoundary(test: Instrumentation, chapter: () -> Int, record: (String) -> Unit) {
    fun surface() = pagingNode(qaRoot(test)) { it.contentDescription?.toString()?.startsWith("第 ") == true && it.contentDescription.toString().contains("页，共") }
    fun indices() = Regex("第 (\\d+) 页，共 (\\d+) 页").find(surface()?.contentDescription?.toString().orEmpty())?.groupValues?.drop(1)?.map(String::toInt)
    val bounds = android.graphics.Rect(); surface()!!.getBoundsInScreen(bounds)
    pagingTouch(test, bounds.left + bounds.width() * .06f, bounds.centerY().toFloat())
    pagingWait("online previous chapter last page") { chapter() == 0 && indices()?.let { it[0] == it[1] } == true }
    Thread.sleep(400)
    pagingTouch(test, bounds.left + bounds.width() * .94f, bounds.centerY().toFloat())
    pagingWait("online next chapter first page") { chapter() == 1 && indices()?.firstOrNull() == 1 }
    record("ONLINE_HORIZONTAL_CHAPTER_BOUNDARY_OK previous last page and next first page, progress persisted")
}
