package com.shengzimao.reader.mobile.reader

import com.shengzimao.reader.mobile.data.Chapter

/** 一处全文搜索命中。 */
data class SearchHit(
    val position: Int,
    val length: Int,
    val snippet: String,
    val chapterTitle: String,
)

/**
 * 全文搜索。按章节分段扫描，命中按位置升序返回；
 * 每章只保留若干条并继续计数，避免超长书一次性构造上万条结果卡住滚动。
 */
object TextSearch {

    const val MAX_TOTAL = 2000
    const val MAX_PER_CHAPTER = 200
    const val SNIPPET_RADIUS = 28

    fun search(
        text: String,
        chapters: List<Chapter>,
        bookTitle: String,
        keyword: String,
        from: Int = 0,
        limit: Int = MAX_TOTAL,
    ): List<SearchHit> {
        val query = keyword.trim()
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val needle = query.lowercase()
        val bounds = chapterBounds(text, chapters, bookTitle)
        val hits = ArrayList<SearchHit>()
        for ((index, range) in bounds.withIndex()) {
            if (hits.size >= limit) break
            var cursor = range.first
            var perChapter = 0
            while (cursor < range.last + 1 && perChapter < MAX_PER_CHAPTER && hits.size < limit) {
                val found = text.indexOf(needle, cursor, ignoreCase = true)
                if (found < 0 || found + needle.length > range.last + 1) break
                hits.add(
                    SearchHit(
                        position = found,
                        length = needle.length,
                        snippet = snippetOf(text, found, needle.length),
                        chapterTitle = chapterTitle(chapters, index, bookTitle),
                    )
                )
                cursor = found + needle.length
                perChapter++
            }
        }
        return hits
    }

    private fun chapterTitle(chapters: List<Chapter>, index: Int, bookTitle: String): String =
        chapters.getOrNull(index)?.title ?: bookTitle

    /** 每章的字符区间；无章节时整本当一章。 */
    fun chapterBounds(text: String, chapters: List<Chapter>, bookTitle: String): List<IntRange> {
        if (chapters.isEmpty()) return listOf(text.indices.let { IntRange(0, maxOf(0, text.length - 1)) })
        val ordered = if (chapters.size > 1 && chapters.zipWithNext().any { (a, b) -> a.position > b.position }) {
            chapters.sortedBy { it.position }
        } else {
            chapters
        }
        return ordered.mapIndexed { index, chapter ->
            val start = chapter.position.coerceIn(0, maxOf(0, text.length - 1))
            val end = if (index + 1 < ordered.size) {
                (ordered[index + 1].position - 1).coerceIn(start, maxOf(start, text.length - 1))
            } else {
                maxOf(start, text.length - 1)
            }
            start..end
        }
    }

    /** 命中处的上下文，两侧截断并压掉换行。 */
    fun snippetOf(text: String, position: Int, length: Int, radius: Int = SNIPPET_RADIUS): String {
        val start = (position - radius).coerceAtLeast(0)
        val end = (position + length + radius).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val body = text.substring(start, end)
            .replace('\n', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return prefix + body + suffix
    }
}
