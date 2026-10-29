package com.shengzimao.reader.mobile.data

/** Chapter indices are used online; local books store character positions. */
object NovelChapterNavigation {
    fun firstUnread(book: Book): Int? = if (book.newChapterCount <= 0 || book.chapters.isEmpty()) null
        else (book.chapters.size - book.newChapterCount).coerceIn(book.chapters.indices)

    fun moveTo(book: Book, index: Int, expectedTitle: String? = null, now: Long): Book {
        val target = if (expectedTitle == null) index else {
            index.takeIf { book.chapters.getOrNull(it)?.title == expectedTitle }
                ?: book.chapters.indexOfFirst { it.title == expectedTitle }
        }
        require(target in book.chapters.indices) { "此版本没有对应章节，请刷新目录后重试。" }
        return book.copy(
            position = if (book.onlineOnly) target else book.chapters[target].position,
            onlineOffset = 0, onlineCharacterOffset = -1, onlineParagraphFraction = 0f,
            lastReadAt = now,
            newChapterCount = minOf(book.newChapterCount, book.chapters.size - target - 1),
        )
    }
}
