package com.shengzimao.reader.mobile

import androidx.compose.ui.geometry.Offset
import com.shengzimao.reader.mobile.data.Highlight
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.TapTarget
import com.shengzimao.reader.mobile.data.TapZone
import com.shengzimao.reader.mobile.data.TurnMode
import com.shengzimao.reader.mobile.reader.PageMap
import com.shengzimao.reader.mobile.reader.Paragraphs
import com.shengzimao.reader.mobile.reader.ScrollMath
import com.shengzimao.reader.mobile.reader.SwipeClassifier
import com.shengzimao.reader.mobile.reader.TapClassifier
import com.shengzimao.reader.mobile.reader.TextSearch
import com.shengzimao.reader.mobile.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {

    private val width = 1000f
    private val height = 2000f

    @Test
    fun `tap zones left right`() {
        assertEquals(TapTarget.PREV, TapClassifier.classify(Offset(width * 0.1f, 500f), width, height, vertical = false, edge = 0.32f))
        assertEquals(TapTarget.MENU, TapClassifier.classify(Offset(width * 0.5f, 500f), width, height, vertical = false, edge = 0.32f))
        assertEquals(TapTarget.NEXT, TapClassifier.classify(Offset(width * 0.9f, 500f), width, height, vertical = false, edge = 0.32f))
    }

    @Test
    fun `tap zones top bottom`() {
        assertEquals(TapTarget.PREV, TapClassifier.classify(Offset(500f, height * 0.1f), width, height, vertical = true, edge = 0.32f))
        assertEquals(TapTarget.MENU, TapClassifier.classify(Offset(500f, height * 0.5f), width, height, vertical = true, edge = 0.32f))
        assertEquals(TapTarget.NEXT, TapClassifier.classify(Offset(500f, height * 0.9f), width, height, vertical = true, edge = 0.32f))
    }

    @Test
    fun `swipe left is next and swipe right is prev`() {
        assertEquals(SwipeClassifier.Result.NEXT, SwipeClassifier.decide(dx = -width * 0.3f, dy = 0f, width, height, allowHorizontal = true, allowVertical = false))
        assertEquals(SwipeClassifier.Result.PREV, SwipeClassifier.decide(dx = width * 0.3f, dy = 0f, width, height, allowHorizontal = true, allowVertical = false))
    }

    @Test
    fun `small swipe is ignored`() {
        assertEquals(SwipeClassifier.Result.NONE, SwipeClassifier.decide(dx = -width * 0.03f, dy = 0f, width, height, allowHorizontal = true, allowVertical = false))
    }

    @Test
    fun `scroll mode prefers vertical drags`() {
        assertEquals(
            SwipeClassifier.Result.SCROLL,
            SwipeClassifier.decide(dx = 0f, dy = -height * 0.2f, width, height, allowHorizontal = false, allowVertical = true),
        )
        // 竖直位移占比大时，即使横向也超过阈值也按滚动处理。
        assertEquals(
            SwipeClassifier.Result.SCROLL,
            SwipeClassifier.decide(dx = -width * 0.2f, dy = -height * 0.2f, width, height, allowHorizontal = true, allowVertical = true),
        )
    }

    @Test
    fun `horizontal turn disabled in scroll mode`() {
        assertEquals(
            SwipeClassifier.Result.NONE,
            SwipeClassifier.decide(dx = -width * 0.3f, dy = 0f, width, height, allowHorizontal = false, allowVertical = true),
        )
    }

    /**
     * 回归：竖直阈值原为 0.004f（约 10px），点按时的轻微抖动会被判成滚动并吞掉点按，
     * 表现为「点中间呼出菜单」时灵时不灵。现在约 1% 屏高以内的抖动必须视为点按。
     */
    @Test
    fun `tiny vertical jitter is not treated as scroll`() {
        assertEquals(
            SwipeClassifier.Result.NONE,
            SwipeClassifier.decide(dx = 0f, dy = -height * 0.01f, width, height, allowHorizontal = false, allowVertical = true),
        )
        // 真正的滚动意图仍然生效。
        assertEquals(
            SwipeClassifier.Result.SCROLL,
            SwipeClassifier.decide(dx = 0f, dy = -height * 0.05f, width, height, allowHorizontal = false, allowVertical = true),
        )
    }

    @Test
    fun `scroll math snaps to pages`() {
        assertEquals(0, ScrollMath.snapPage(0.04f, 10))
        assertEquals(9, ScrollMath.snapPage(0.96f, 10))
        assertEquals(5, ScrollMath.snapPage(0.5f, 10))
        assertEquals(0, ScrollMath.snapPage(0.5f, 1))
    }
}

class PageMapTest {

    private val map = PageMap(
        pages = intArrayOf(0, 100, 200, 300),
        pageChapter = intArrayOf(0, 0, 1, 1),
        textLength = 400,
    )

    @Test
    fun `pageOf finds the page containing an offset`() {
        assertEquals(0, map.pageOf(0))
        assertEquals(0, map.pageOf(99))
        assertEquals(1, map.pageOf(100))
        assertEquals(3, map.pageOf(399))
        // 越界夹紧。
        assertEquals(3, map.pageOf(9999))
        assertEquals(0, map.pageOf(-5))
    }

    @Test
    fun `chapterPage counts pages within a chapter`() {
        // 第 0/1 页属第 0 章；第 2/3 页属第 1 章。
        assertEquals(1 to 2, map.chapterPage(0))
        assertEquals(2 to 2, map.chapterPage(1))
        assertEquals(1 to 2, map.chapterPage(2))
        assertEquals(2 to 2, map.chapterPage(3))
    }

    @Test
    fun `progress follows character position`() {
        assertEquals(0f, map.progressOf(0))
        assertTrue(Math.abs(map.progressOf(2) - 0.5f) < 0.01f)
        assertTrue(map.progressOf(3) > 0.7f)
    }
}

class ParagraphsTest {

    @Test
    fun `paragraphs skip empty lines and leading spaces`() {
        val text = "　　第一段内容。\n\n第二段。\n　　第三段。"
        val spans = Paragraphs.of(text)
        assertEquals(3, spans.size)
        assertEquals("第一段内容。", text.substring(spans[0].start, spans[0].end))
        assertEquals("第二段。", text.substring(spans[1].start, spans[1].end))
        assertEquals("第三段。", text.substring(spans[2].start, spans[2].end))
    }

    @Test
    fun `spanAt finds containing paragraph`() {
        val text = "第一段。\n\n第二段有更多内容。"
        val span = Paragraphs.spanAt(text, 12)
        assertTrue(span != null && text.substring(span.start, span.end).contains("第二段"))
    }
}

class TextSearchTest {

    private val text = buildString {
        append("第一章 开端\n这是正文第一段，包含关键词。\n")
        append("第二章 发展\n这里再次出现关键词，然后关键词又出现。\n")
    }

    @Test
    fun `finds hits across chapters with titles`() {
        val chapters = listOf(
            Chapter("第一章 开端", 0),
            Chapter("第二章 发展", text.indexOf("第二章")),
        )
        val hits = TextSearch.search(text, chapters, "测试书", "关键词")
        assertTrue(hits.size >= 3)
        assertTrue(hits.all { it.snippet.contains("关键词") })
        assertTrue(hits.first().chapterTitle.startsWith("第一章"))
        assertTrue(hits.last().chapterTitle.startsWith("第二章"))
    }

    @Test
    fun `case insensitive and empty query safe`() {
        val hits = TextSearch.search("Hello World", emptyList(), "书", "hello")
        assertEquals(1, hits.size)
        assertTrue(TextSearch.search("abc", emptyList(), "书", "  ").isEmpty())
    }
}

class ReaderSettingsTest {

    @Test
    fun `font size scaling adjusts line spacing`() {
        val base = ReaderSettings(fontSize = 20, lineSpacingPercent = 160, lineSpacingFollowsFont = true)
        val bigger = ReaderSettings.withFontSize(base, 30)
        assertEquals(30, bigger.fontSize)
        assertEquals(240, bigger.lineSpacingPercent)

        // 关闭联动时行距保持不变。
        val fixed = ReaderSettings(fontSize = 20, lineSpacingPercent = 160, lineSpacingFollowsFont = false)
        assertEquals(160, ReaderSettings.withFontSize(fixed, 30).lineSpacingPercent)
    }

    @Test
    fun `custom theme resolves colors and darkness`() {
        val custom = ReaderSettings(theme = com.shengzimao.reader.mobile.data.ReaderTheme.CUSTOM, customBackground = 0xFF101010, customForeground = 0xFFEEEEEE)
        assertEquals(0xFF101010, custom.pageBackground)
        assertEquals(0xFFEEEEEE, custom.pageForeground)
        assertTrue(custom.isDarkPage)

        val light = ReaderSettings(theme = com.shengzimao.reader.mobile.data.ReaderTheme.CUSTOM, customBackground = 0xFFF5F0E6)
        assertTrue(!light.isDarkPage)
    }

    @Test
    fun `contrast distinguishes readable pairs`() {
        val good = ReaderSettings.contrast(0xFF000000, 0xFFFFFFFF)
        val bad = ReaderSettings.contrast(0xFF777777, 0xFF888888)
        assertTrue(good > 4.5f)
        assertTrue(bad < 2f)
    }

    @Test
    fun `highlight snippet collapses whitespace`() {
        val snippet = com.shengzimao.reader.mobile.data.Bookmark.snippet("　　这是　一段\n  带空白的内容。", 0, 40)
        assertEquals("这是 一段 带空白的内容。", snippet)
    }
}
