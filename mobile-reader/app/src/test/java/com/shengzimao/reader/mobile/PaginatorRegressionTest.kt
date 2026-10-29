package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.reader.Paginator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorRegressionTest {
    @Test
    fun longUnbrokenTextStillHasBoundedSlices() {
        val text = "正文".repeat(60_000)
        val ranges = Paginator.sliceBoundaries(text)
        assertTrue(ranges.size > 1)
        assertTrue(ranges.all { it.last - it.first + 1 <= Paginator.COLUMN_CHARS })
        assertEquals(text, ranges.joinToString("") { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun aDistantNewlineDoesNotExpandTheMeasurementSlice() {
        val text = "正文".repeat(40_000) + "\n结束"
        val ranges = Paginator.sliceBoundaries(text)
        assertTrue(ranges.all { it.last - it.first + 1 <= Paginator.COLUMN_CHARS })
        assertEquals(text.length, ranges.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun sliceEdgesDoNotSplitSurrogatePairs() {
        val text = "字".repeat(Paginator.COLUMN_CHARS - 1) + "😀" + "尾".repeat(Paginator.COLUMN_CHARS)
        val ranges = Paginator.sliceBoundaries(text)
        assertTrue(ranges.all { !text[it.last].isHighSurrogate() && !text[it.first].isLowSurrogate() })
        assertEquals(text, ranges.joinToString("") { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun emptyTextHasNoSlices() {
        assertTrue(Paginator.sliceBoundaries("").isEmpty())
    }
}
