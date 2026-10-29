package com.shengzimao.reader.mobile

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReadingPreferences
import com.shengzimao.reader.mobile.reader.rememberPageStyle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual Android text layout; body intentionally has no leading whitespace like downloaded TXT. */
fun novelIndentSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val text = "第一章 原创试读\n\n第一段正文没有预先插入空格，用于验证阅读设置。\n第二段正文也没有前导空格，用于验证每一段的缩进。\n第三段正文用于验证开关关闭后的左对齐。"
    val offsets = listOf("第一段", "第二段", "第三段").map { text.indexOf(it) }
    val ready = CountDownLatch(1)
    val layouts = AtomicReference<Pair<TextLayoutResult, TextLayoutResult>>()
    var expectedIndent = 0f
    test.runOnMainSync { activity.setContent {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val oldSnapshot = ReadingPreferences.encode(ReaderSettings(fontSize = 20, indentParagraphs = false))
        val on = rememberPageStyle(ReadingPreferences.novelForBook(oldSnapshot, ReaderSettings(indentParagraphs = true)), FontFamily.Serif)
        val off = rememberPageStyle(ReadingPreferences.novelForBook(oldSnapshot, ReaderSettings(indentParagraphs = false)), FontFamily.Serif)
        SideEffect {
            expectedIndent = with(density) { on.textIndent!!.firstLine.toPx() }
            val constraints = Constraints(maxWidth = with(density) { 300.dp.roundToPx() })
            layouts.set(measurer.measure(text, on, constraints = constraints) to measurer.measure(text, off, constraints = constraints))
            ready.countDown()
        }
        Text("首行缩进排版回归")
    } }
    try {
        check(ready.await(15, TimeUnit.SECONDS)) { "indent layout not ready" }
        val (on, off) = layouts.get()
        val indented = offsets.map { on.getHorizontalPosition(it, true) }
        val aligned = offsets.map { off.getHorizontalPosition(it, true) }
        record("NOVEL_INDENT_LAYOUT expected=$expectedIndent on=$indented off=$aligned")
        check(indented.all { abs(it - expectedIndent) <= 2f }) { "paragraph indent was not applied to every body paragraph" }
        check(aligned.all { abs(it) <= 2f }) { "disabled indent did not left-align body paragraphs" }
        record("NOVEL_INDENT_LAYOUT_OK legacy book snapshot; every native paragraph follows global indentation")
    } finally { test.runOnMainSync { activity.setContent { Text("首行缩进回归完成") } } }
}
