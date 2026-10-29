package com.shengzimao.reader.mobile.data

/** Presentation order never changes the source's chapter indexes or saved progress. */
internal fun mangaCatalogEntries(book: MangaBook, query: String, reversed: Boolean, cachedOnly: Boolean): List<IndexedValue<MangaChapter>> {
    val matched = book.chapters.withIndex().filter { (_, chapter) ->
        (query.isBlank() || chapter.title.contains(query.trim(), true)) && (!cachedOnly || book.local || chapter.url in book.cachedChapters)
    }
    return if (reversed) matched.asReversed() else matched
}
