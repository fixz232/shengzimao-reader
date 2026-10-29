package com.shengzimao.reader.mobile.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.shengzimao.reader.mobile.data.Chapter
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

    /** 第 [page] 页在本章内的序号（1 起）与本章总页数。 */
    fun chapterPage(page: Int): Pair<Int, Int> {
        val chapter = pageChapter.getOrNull(page) ?: -1
        if (chapter < 0) return (page + 1) to pageCount
        var first = page
        while (first > 0 && pageChapter[first - 1] == chapter) first--
        var last = page
        while (last < pageCount - 1 && pageChapter[last + 1] == chapter) last++
        return (page - first + 1) to (last - first + 1)
    }

    /** 全书阅读进度 0..1（按字符位置，比分页更稳定）。 */
    fun progressOf(page: Int): Float =
        if (textLength <= 1) 0f else (pageStart(page).toFloat() / (textLength - 1)).coerceIn(0f, 1f)
}

/**
 * 分页器：每个有界切片只测量一次，按真实行高生成页首。
 * 长章节按 [COLUMN_CHARS] 切片逐段测量，页面从不跨切片，切片边界因此可以作为并行任务边界。
 */
object Paginator {
    const val VERSION = 7
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
        // 不缓存整本书的版面，也不把测量失败降级为逐字排版；错误由阅读页显示。
        val layout = measurer.measure(
            text = pageText(text, start, end),
            style = style,
            softWrap = true,
            constraints = Constraints(maxWidth = width),
            skipCache = true,
        )
        val pages = ArrayList<Int>()
        pages.add(start)
        var pageTop = layout.getLineTop(0)
        for (line in 1 until layout.lineCount) {
            if (layout.getLineBottom(line) - pageTop > height) {
                val position = start + layout.getLineStart(line)
                if (position > pages.last() && position < end) pages.add(position)
                pageTop = layout.getLineTop(line)
            }
        }
        return pages.toIntArray()
    }

    /** 续页首行属于上一页的段落，后续自然段仍保留首行缩进；原文偏移不变。 */
    fun pageText(text: String, start: Int, end: Int): AnnotatedString {
        val value = text.substring(start, end)
        if (value.isEmpty() || isLineStart(text, start)) return AnnotatedString(value)
        val firstEnd = value.indexOfAny(charArrayOf('\n', '\r')).let { if (it < 0) value.length else it + 1 }
        return AnnotatedString(value, paragraphStyles = listOf(
            AnnotatedString.Range(ParagraphStyle(textIndent = TextIndent.None), 0, firstEnd),
        ))
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

    /** 计算每页区间，供绘制时使用。 */
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
                // 向前找段尾，不能为寻找下一个换行而把整本无换行文本塞进一片。
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline >= start) end = newline + 1
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            ranges.add(start until end)
            start = end
        }
        return ranges
    }
}

/**
 * 段落切分：朗读按段入队、TTS 高亮按段定位都依赖它。
 * 段落 = 连续非空行（不含行首缩进），结果带全文字符区间。
 */
object Paragraphs {

    data class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /**
     * 切出 [from, to) 区间内的段落。
     * 段落按“非空行”切分（软换行的长段会被分成多行，朗读时逐行播报，
     * 高亮也逐行推进）；行首缩进不并入段落。
     */
    fun of(text: String, from: Int = 0, to: Int = text.length): List<Span> {
        if (text.isEmpty()) return emptyList()
        val spanEnd = to.coerceIn(0, text.length)
        var index = from.coerceIn(0, spanEnd)
        val result = ArrayList<Span>()
        while (index < spanEnd) {
            val lineEnd = text.indexOf('\n', index).let { if (it < 0 || it > spanEnd) spanEnd else it }
            var lineStart = index
            while (lineStart < lineEnd && (text[lineStart] == '\u3000' || text[lineStart] == ' ' || text[lineStart] == '\r')) lineStart++
            if (lineEnd > lineStart) {
                result.add(Span(lineStart, lineEnd))
            }
            index = if (lineEnd < spanEnd) lineEnd + 1 else spanEnd
        }
        return result
    }

    /** 某偏移所在的段落区间；找不到时返回单字区间。 */
    fun spanAt(text: String, offset: Int, from: Int = 0, to: Int = text.length): Span? {
        if (text.isEmpty()) return null
        val target = offset.coerceIn(0, text.length - 1)
        return of(text, from, to).firstOrNull { target in it.start..it.end } ?: run {
            // 偏移落在空行里：向前找最近一个段落的末尾。
            of(text, from, to).lastOrNull { it.end <= target }
        }
    }
}
