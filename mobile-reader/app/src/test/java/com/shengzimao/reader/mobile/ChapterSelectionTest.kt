package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.OnlineChapter
import com.shengzimao.reader.mobile.ui.ChapterRange
import com.shengzimao.reader.mobile.ui.resolve
import com.shengzimao.reader.mobile.ui.summary
import org.junit.Assert.assertEquals
import org.junit.Test

/** 在线搜书的章节选择下载：范围解析的纯逻辑回归。 */
class ChapterSelectionTest {

    private val toc = (1..100).map { OnlineChapter("第${it}章", "https://e.com/$it") }

    @Test
    fun allRangeCoversEveryChapterInOrder() {
        val picked = ChapterRange.All.resolve(toc)
        assertEquals(100, picked.size)
        assertEquals("第1章", picked.first().title)
        assertEquals("第100章", picked.last().title)
    }

    @Test
    fun pickedRangeKeepsDirectoryOrderRegardlessOfSelectionOrder() {
        // 故意乱序勾选。
        val range = ChapterRange.Picked(setOf(9, 2, 5))
        val picked = range.resolve(toc)
        assertEquals(listOf("第3章", "第6章", "第10章"), picked.map { it.title })
    }

    @Test
    fun intervalIsInclusiveOnBothEnds() {
        val picked = ChapterRange.Interval(from = 3, to = 7).resolve(toc)
        assertEquals(5, picked.size)
        assertEquals("第3章", picked.first().title)
        assertEquals("第7章", picked.last().title)
    }

    @Test
    fun intervalIsClampedToDirectoryBounds() {
        val picked = ChapterRange.Interval(from = 0, to = 9999).resolve(toc)
        assertEquals(100, picked.size)
        assertEquals("第1章", picked.first().title)
        assertEquals("第100章", picked.last().title)
    }

    @Test
    fun reversedIntervalDoesNotCrash() {
        // 结束章小于起始章时，退化为起始章一章，而不是抛异常或回空。
        val picked = ChapterRange.Interval(from = 20, to = 5).resolve(toc)
        assertEquals(1, picked.size)
        assertEquals("第20章", picked.first().title)
    }

    @Test
    fun emptySelectionResolvesToNothing() {
        assertEquals(0, ChapterRange.Picked(emptySet()).resolve(toc).size)
    }

    @Test
    fun outOfRangePickedIndexesAreIgnored() {
        val picked = ChapterRange.Picked(setOf(0, 500, -3)).resolve(toc)
        assertEquals(listOf("第1章"), picked.map { it.title })
    }

    @Test
    fun summaryTextDescribesTheSelection() {
        assertEquals("全部 100 章", ChapterRange.All.summary(toc))
        assertEquals("已选 2 章", ChapterRange.Picked(setOf(1, 2)).summary(toc))
        assertEquals("未选择章节", ChapterRange.Picked(emptySet()).summary(toc))
        assertEquals("第 3–7 章（共 5 章）", ChapterRange.Interval(3, 7).summary(toc))
    }

    @Test
    fun emptyDirectoryDoesNotCrashAnyRange() {
        assertEquals(0, ChapterRange.All.resolve(emptyList()).size)
        assertEquals(0, ChapterRange.Picked(setOf(0)).resolve(emptyList()).size)
        assertEquals(0, ChapterRange.Interval(1, 5).resolve(emptyList()).size)
    }
}
