package com.shengzimao.reader.mobile.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.shengzimao.reader.mobile.data.Chapter
import kotlin.math.floor
import kotlin.math.max

/**
 * 分页结果。pages[i] 是第 i 页首行行首在全文中的偏移；pageChapter[i] 是该页所属章节的下标。
 */
class PageMap(
    val pages: IntArray,
    val pageChapter: IntArray,
    val textLength: Int,
) {
    val pageCount: Int get() = pages.size

    fun pageOf(position: Int): Int {
        val anchor = position.coerceIn(0, max(0, textLength - 1))
        var low = 0
        var high = pages.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (pages[mid] <= anchor) low = mid else high = mid - 1
        }
        return low
    }

    fun pageStart(page: Int): Int = pages[page.coerceIn(0, pages.size - 1)]

    fun pageEnd(page: Int): Int = if (page >= pages.size - 1) textLength else pages[page + 1]
}

/**
 * 分页器：用 TextMeasurer 按真实字形高度逐行推进，行数由可用高度与行高决定。
 * 长章节按 [COLUMN_CHARS] 切片逐段测量，页面从不跨切片，切片边界因此可以作为并行任务边界。
 */
object Paginator {
    const val VERSION = 4
    const val COLUMN_CHARS = 20_000

    /**
     * 测量 [columnStart, columnEnd) 内的页首偏移，返回绝对偏移数组。
     */
    fun paginate(
        measurer: TextMeasurer,
        density: Density,
        text: String,
        style: TextStyle,
        width: Int,
        height: Int,
        columnStart: Int = 0,
        columnEnd: Int = text.length,
    ): IntArray {
        val start = columnStart.coerceIn(0, text.length)
        val end = columnEnd.coerceIn(start, text.length)
        if (end <= start || width <= 0 || height <= 0) return intArrayOf(start)
        val lineHeight = with(density) { style.lineHeight.toPx() }
        val rows = floor(height / lineHeight).toInt().coerceAtLeast(1)
        val indentWidth = with(density) { 2 * style.fontSize.value * density.density }.toInt()
        val pages = ArrayList<Int>(max(8, (end - start) / (rows * 18)))
        val firstPage = Paginator.skipIndent(text, start, end)
        pages.add(if (firstPage in start until end) firstPage else start)
        var position = pages[0]
        var lineInPage = 0
        while (position < end) {
            val indented = lineInPage == 0 && style.textIndent != androidx.compose.ui.text.style.TextIndent.None && isLineStart(text, position)
            val available = if (indented) max(1, width - indentWidth) else width
            val lineStyle = if (indented) style else style.copy(textIndent = androidx.compose.ui.text.style.TextIndent.None)
            // 逐行测量一行能容纳多少字。TextMeasurer 没有区间重载，只能切片；
            // 为控制临时对象，切片长度上限取一行可能容纳的字数（约为可用宽度的一半，宽字符也够）。
            val sliceLength = (available / 2).coerceIn(16, 4096)
            val sliceEnd = minOf(end, position + sliceLength)
            val lineResult: androidx.compose.ui.text.TextLayoutResult? = runCatching {
                measurer.measure(
                    text = text.substring(position, sliceEnd),
                    style = lineStyle,
                    maxLines = 1,
                    softWrap = true,
                    constraints = Constraints(maxWidth = available),
                )
            }.getOrNull()
            val endOffset = (lineResult?.getLineEnd(0) ?: 1).coerceAtLeast(1)
            position += endOffset.coerceAtMost(end - position)
            lineInPage++
            if (lineInPage >= rows && position < end) {
                // 换页时跳过段首空白，但不能越过本切片：页面区间不允许跨片。
                val next = skipIndent(text, position, end)
                pages.add(if (next in position until end) next else position)
                lineInPage = 0
            }
        }
        return pages.toIntArray()
    }

    /** 页面起点若在行首，跳过段首的全角/半角空格，避免新页第一行缩进两次。 */
    fun skipIndent(text: String, position: Int, end: Int = text.length): Int {
        var index = position
        var skipped = 0
        while (index < end && skipped < 2 && isLineStart(text, index) && (text[index] == '\u3000' || text[index] == ' ')) {
            index++
            skipped++
        }
        return index
    }

    fun isLineStart(text: String, index: Int): Boolean {
        if (index <= 0) return true
        val previous = text[index - 1]
        return previous == '\n' || previous == '\r'
    }

    /** 计算每页区间，跳过行首空白，供绘制时使用。 */
    fun frames(map: PageMap, text: String): List<PageFrame> {
        val frames = ArrayList<PageFrame>(map.pageCount)
        for (i in 0 until map.pageCount) {
            val start = map.pages[i]
            val end = map.pageEnd(i)
            if (start >= end) {
                frames.add(PageFrame(start, end, 0f, 0f))
                continue
            }
            // 页首若是段落缩进（行首空白），去掉它，改由样式统一缩进。
            val indent = isLineStart(text, start) && (text[start] == '\u3000' || text[start] == ' ')
            frames.add(PageFrame(start, end, if (indent) 1f else 0f, 0f))
        }
        return frames
    }

    fun signatureFor(
        width: Int,
        height: Int,
        fontSize: Int,
        lineSpacingPercent: Int,
        fontKey: String,
        bold: Boolean,
        indent: Boolean,
        textLength: Int,
    ): String = listOf(VERSION, width, height, fontSize, lineSpacingPercent, fontKey, bold, indent, textLength).joinToString("|")

    /** 合并各切片的页表，并标记每页所属章节。 */
    fun assemble(text: String, chapters: List<Chapter>, slices: List<IntArray>): PageMap {
        val ordered = if (chapters.size > 1 && chapters.zipWithNext().any { (a, b) -> a.position > b.position }) {
            chapters.sortedBy { it.position }
        } else {
            chapters
        }
        val pages = ArrayList<Int>(slices.sumOf { it.size })
        slices.forEach { array -> array.forEach { pages.add(it) } }
        if (pages.isEmpty()) pages.add(0)
        val pageChapter = IntArray(pages.size)
        var chapterIndex = -1
        for (i in pages.indices) {
            while (chapterIndex + 1 < ordered.size && ordered[chapterIndex + 1].position <= pages[i]) chapterIndex++
            pageChapter[i] = chapterIndex
        }
        return PageMap(pages.toIntArray(), pageChapter, text.length)
    }

    /** 章内切片：每片不超过 COLUMN_CHARS，片首对齐到段首，保证页面不跨片。 */
    fun sliceBoundaries(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + COLUMN_CHARS)
            if (end < text.length) {
                val newline = text.indexOf('\n', end)
                end = if (newline >= 0) newline + 1 else text.length
            }
            ranges.add(start until end)
            start = end
        }
        return ranges
    }
}
