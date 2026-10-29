package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.ui.ReaderSettingsSheet
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real settings sheet + pointer events, with delayed saves like the per-book library. */
fun typographySettingsSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val initial = ReaderSettings(fontSize = 30, lineSpacingPercent = 215, paddingDp = 20, lineSpacingFollowsFont = true)
    val persisted = mutableStateOf(initial)
    val visible = mutableStateOf(true)
    val commits = AtomicInteger()
    val handler = Handler(Looper.getMainLooper())
    var delaySave = 400L
    fun node(label: String) = pagingNode(qaRoot(test)) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    fun click(label: String) {
        var target = node(label) ?: error("missing typography action: $label")
        repeat(6) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent ?: error("typography action not clickable: $label")
        }
        error("typography click failed: $label")
    }
    fun slider(min: Float, max: Float) = pagingNode(qaRoot(test)) {
        it.rangeInfo?.let { range -> range.min == min && range.max == max } == true
    } ?: error("missing typography slider: $min..$max")
    fun scroll(forward: Boolean) {
        val column = pagingNode(qaRoot(test)) { it.isScrollable } ?: error("settings scroll column missing")
        check(column.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        Thread.sleep(450)
    }
    fun gesture(min: Float, max: Float, endFraction: Float, unchanged: Int, afterMoves: (() -> Unit)? = null) {
        val bounds = Rect().also { slider(min, max).getBoundsInScreen(it) }
        val screen = Rect()
        test.runOnMainSync { activity.window.decorView.getWindowVisibleDisplayFrame(screen) }
        val safeBottom = screen.bottom - 72 * activity.resources.displayMetrics.density
        check(bounds.centerY() < safeBottom && bounds.centerY() > screen.top) { "slider outside safe touch area: $bounds" }
        check(qaRoot(test)?.packageName?.toString() == test.targetContext.packageName)
        val start = bounds.left + bounds.width() * .22f
        val end = bounds.left + bounds.width() * endFraction
        val down = SystemClock.uptimeMillis()
        var maxHeartbeat = 0L
        fun inject(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, bounds.centerY().toFloat(), 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, start)
        repeat(28) { i ->
            Thread.sleep(12)
            inject(MotionEvent.ACTION_MOVE, start + (end - start) * (i + 1) / 28)
            val latch = CountDownLatch(1); val sent = SystemClock.uptimeMillis()
            handler.post { latch.countDown() }
            check(latch.await(2, TimeUnit.SECONDS)) { "font drag blocked the main thread for 2s" }
            maxHeartbeat = maxOf(maxHeartbeat, SystemClock.uptimeMillis() - sent)
            check(commits.get() == unchanged) { "typography saved while dragging: ${commits.get()} != $unchanged" }
        }
        Thread.sleep(120)
        check(abs(slider(min, max).rangeInfo!!.current - min) > 1)
        afterMoves?.invoke()
        inject(MotionEvent.ACTION_UP, end)
        pagingWait("one typography commit after release") { commits.get() == unchanged + 1 }
        record("TYPOGRAPHY_DRAG_OK range=$min..$max moves=28 savesDuringDrag=0 savesAfterRelease=1 maxMainHeartbeatMs=$maxHeartbeat")
    }
    try {
        test.runOnMainSync {
            activity.setContent { ShengZiMaoTheme(false) {
                if (visible.value) ReaderSettingsSheet(persisted.value,
                    onUpdate = { transform ->
                        commits.incrementAndGet()
                        val next = transform(persisted.value)
                        handler.postDelayed({ persisted.value = next }, delaySave)
                    }, onDismiss = { visible.value = false }, onPickFont = {}, onOpenAllSettings = {},
                    onReset = { persisted.value = initial })
                else Text("字号测试已关闭")
            } }
        }
        pagingWait("font sheet shown") { node("字号") != null }
        Thread.sleep(750)
        val before = Rect().also { node("字号")!!.getBoundsInScreen(it) }.top
        gesture(12f, 40f, .88f, 0) {
            val after = Rect().also { node("字号")!!.getBoundsInScreen(it) }.top
            check(abs(before - after) <= 2) { "preview moves font controls: $before -> $after" }
        }
        pagingWait("font save acknowledged") { persisted.value.fontSize > 30 }
        check(persisted.value.lineSpacingPercent == ReaderSettings.withFontSize(initial, persisted.value.fontSize).lineSpacingPercent)
        gesture(100f, 260f, .58f, 1)
        pagingWait("line spacing persisted") { persisted.value.lineSpacingPercent < 230 }
        scroll(true)
        gesture(8f, 40f, .73f, 2)
        pagingWait("padding persisted") { persisted.value.paddingDp > 24 }
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        pagingWait("reopen sheet after padding") { node("字号测试已关闭") != null }
        test.runOnMainSync { visible.value = true }
        pagingWait("font buttons visible after reopen") { node("减小字号") != null }
        Thread.sleep(700)
        // Exercise delayed acknowledgements while using + repeatedly.
        delaySave = 700L
        val oldSize = persisted.value.fontSize
        click("减小字号")
        pagingWait("first button preview") { node("${oldSize - 1} sp") != null }
        click("减小字号")
        pagingWait("rapid button updates") { commits.get() == 5 }
        pagingWait("rapid button persistence") { persisted.value.fontSize == oldSize - 2 }
        val final = persisted.value
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        pagingWait("sheet dismissed") { node("字号测试已关闭") != null }
        check(commits.get() == 5) { "dismiss duplicates saved settings" }
        test.runOnMainSync { visible.value = true }
        pagingWait("settings reopen") { node("${final.fontSize} sp") != null && node("${final.paddingDp} dp") != null }
        Thread.sleep(600)
        record("TYPOGRAPHY_REOPEN_OK font=${final.fontSize} spacing=${final.lineSpacingPercent} padding=${final.paddingDp} saves=${commits.get()} rapidButtonsPreserved=true")
        click("恢复跟随全局设置")
        pagingWait("settings reset") { node("30 sp") != null && node("215%") != null && node("20 dp") != null }
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        pagingWait("reset sheet dismissed") { node("字号测试已关闭") != null }
        check(commits.get() == 5) { "reset restored stale pending settings" }
        record("TYPOGRAPHY_RESET_OK no stale draft or duplicate save on close")
    } finally {
        test.runOnMainSync { activity.setContent { Text("字号设置回归完成") } }
        test.waitForIdleSync()
    }
}
