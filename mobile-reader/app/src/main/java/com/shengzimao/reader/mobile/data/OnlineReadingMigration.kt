package com.shengzimao.reader.mobile.data

import org.json.JSONArray

/** 在线转离线时，把章节内锚点和笔记映射到完整正文；保留原始在线笔记供备份追溯。 */
object OnlineReadingMigration {
    fun apply(book: Book, text: String, toc: List<OnlineChapter>, local: List<Chapter>): Book {
        if (!book.onlineOnly || local.isEmpty() || text.isEmpty()) return book
        fun chapterIndex(url: String, fallback: Int): Int {
            val exact = toc.indexOfFirst { url.isNotBlank() && it.url == url }
            return if (exact >= 0) exact else if (url.isBlank()) fallback.takeIf { it in toc.indices } ?: -1 else -1
        }
        fun bounds(index: Int): IntRange? {
            val start = local.getOrNull(index)?.position?.coerceIn(0, text.length - 1) ?: return null
            val end = (local.getOrNull(index + 1)?.position ?: text.length).coerceIn(start + 1, text.length)
            return start until end
        }
        val currentUrl = book.onlineChapters.getOrNull(book.position)?.url.orEmpty()
        val current = bounds(chapterIndex(currentUrl, book.position))
        val position = current?.let { (it.first + book.onlineCharacterOffset.coerceAtLeast(0)).coerceIn(it) } ?: 0
        val bookmarks = book.bookmarks.toMutableList()
        val highlights = book.highlights.toMutableList()
        val annotations = runCatching { JSONArray(book.onlineAnnotations) }.getOrDefault(JSONArray())
        for (i in 0 until minOf(annotations.length(), 2000)) {
            val mark = annotations.optJSONObject(i) ?: continue
            val index = chapterIndex(mark.optString("chapterUrl"), mark.optInt("chapter", -1))
            val range = bounds(index) ?: continue
            val quote = mark.optString("quote").take(600)
            val exact = if (quote.isBlank()) -1 else text.indexOf(quote, range.first).takeIf { it >= range.first && it + quote.length <= range.last + 1 } ?: -1
            val start = if (exact >= 0) exact else (range.first + mark.optInt("position").coerceAtLeast(0)).coerceIn(range)
            val note = mark.optString("note").take(2000)
            val tags = mark.optString("tags").split(Regex("\\s+")).filter { it.isNotBlank() }.take(20).map { it.take(40) }
            val id = "online-" + LibraryStore.digest("${book.id}|$i|$mark").take(24)
            if (note.isBlank() && tags.isEmpty()) {
                if (bookmarks.none { it.id == id }) bookmarks += Bookmark(id, start,
                    (if (exact < 0) "位置近似 · " else "") + quote.ifBlank { local[index].title }.take(80), System.currentTimeMillis())
            } else if (highlights.none { it.id == id }) {
                highlights += Highlight(id = id, start = start, end = (start + quote.length.coerceAtLeast(1)).coerceAtMost(range.last + 1),
                    snippet = quote, note = note, tags = tags, chapterTitle = local[index].title + if (exact < 0) "（位置近似）" else "")
            }
        }
        return book.copy(position = position, bookmarks = bookmarks.sortedBy { it.position }, highlights = highlights.sortedBy { it.start })
    }
}
