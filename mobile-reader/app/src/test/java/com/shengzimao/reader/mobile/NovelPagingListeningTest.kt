package com.shengzimao.reader.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextIndent
import com.shengzimao.reader.mobile.data.TapTarget
import com.shengzimao.reader.mobile.reader.*
import org.junit.Assert.*
import org.junit.Test

class NovelPagingListeningTest {
    @Test fun continuationPageDoesNotIndentItsFirstLineAgain() {
        val text = "上一页的段落，继续阅读到下一页。\n新的自然段。"
        val page = Paginator.pageText(text, 7, text.length)
        assertEquals(text.substring(7), page.text)
        assertEquals(1, page.paragraphStyles.size)
        val override = page.paragraphStyles.single()
        assertEquals(TextIndent.None, override.item.textIndent)
        assertEquals(0, override.start)
        assertEquals(page.text.indexOf('\n') + 1, override.end)
    }
    @Test fun naturalParagraphPageKeepsTheConfiguredIndent() {
        val text = "上一段。\n新的自然段。"
        assertTrue(Paginator.pageText(text, 0, text.length).paragraphStyles.isEmpty())
        assertTrue(Paginator.pageText(text, text.indexOf('新'), text.length).paragraphStyles.isEmpty())
    }
    @Test fun continuationWithoutNewlinePreservesAllCharacters() {
        val text = "连续长段落".repeat(100)
        val value = Paginator.pageText(text, 25, 234)
        assertEquals(text.substring(25, 234), value.text)
        assertEquals(value.length, value.paragraphStyles.single().end)
    }
    @Test fun shortDeliberateHorizontalSwipesWorkOnHighDensityScreens() {
        assertEquals(SwipeClassifier.Result.NEXT, SwipeClassifier.decide(-90f, 35f, 1440f, 3000f, true, false, 24f))
        assertEquals(SwipeClassifier.Result.PREV, SwipeClassifier.decide(90f, -35f, 1440f, 3000f, true, false, 24f))
    }
    @Test fun diagonalHorizontalSwipesAreNotRejectedByScreenHeightPercentage() {
        assertEquals(SwipeClassifier.Result.NEXT, SwipeClassifier.decide(-300f, 150f, 1440f, 3000f, true, false, 24f))
        assertEquals(SwipeClassifier.Result.NONE, SwipeClassifier.decide(-100f, 200f, 1440f, 3000f, true, false, 24f))
    }
    @Test fun touchSlopStillPreventsJitterFromTurningPages() {
        assertEquals(SwipeClassifier.Result.NONE, SwipeClassifier.decide(25f, 3f, 400f, 800f, true, false, 24f))
    }
    @Test fun sideTapZonesUseTheFullConfiguredFraction() {
        assertEquals(TapTarget.PREV, TapClassifier.classify(Offset(250f, 1900f), 1000f, 2000f, false, .32f))
        assertEquals(TapTarget.NEXT, TapClassifier.classify(Offset(750f, 20f), 1000f, 2000f, false, .32f))
    }
    @Test fun speechChunksPreserveParagraphTextAndIndices() {
        val paragraphs = listOf("开头。", "长段。".repeat(700), "最后一段。")
        val chunks = SpeechText.chunks(paragraphs)
        // 不再以「是否不超 3000 字」为标准，而是「按句切、且拼回去等于原文」。
        assertTrue(chunks.all { it.text.length <= 220 })
        paragraphs.forEachIndexed { i, text ->
            assertEquals(text, chunks.filter { it.segment == i }.joinToString("") { it.text })
        }
    }

    @Test fun speechSplitsOnChineseSentenceEndAndKeepsTrailingQuotes() {
        val sentences = SpeechText.splitSentences("他说：“今天很好。”然后就走了。", minChars = 1)
        assertEquals(listOf("他说：“今天很好。”", "然后就走了。"), sentences)
    }

    @Test fun speechMergesVeryShortFragments() {
        // 「好。」只有两字，按默认 minChars 应与下一句合并，避免单独朗读。
        val sentences = SpeechText.splitSentences("好。我们一起回家吧。", minChars = 8)
        assertEquals(1, sentences.size)
    }

    @Test fun speechChunkBoundaryDoesNotSplitEmojiSurrogates() {
        val text = "字".repeat(219) + "😀" + "尾".repeat(4000)
        val chunks = SpeechText.chunks(listOf(text))
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
    }

    @Test fun speechEmptyInputProducesNoUtterance() {
        assertTrue(SpeechText.chunks(emptyList()).isEmpty())
        assertTrue(SpeechText.chunks(listOf("")).isEmpty())
        assertTrue(SpeechText.chunks(listOf("   ", "\u3000\u3000")).isEmpty())
    }

    @Test fun speechNormalizesIdeographicSpaceAndNewlines() {
        val chunks = SpeechText.chunks(listOf("\u3000\u3000第一段\n第二行。"))
        assertEquals(1, chunks.size)
        // 缩进与换行都应被清掉，否则引擎会念出奇怪的停顿。
        assertFalse(chunks.single().text.contains('\u3000'))
        assertFalse(chunks.single().text.contains('\n'))
    }
}
