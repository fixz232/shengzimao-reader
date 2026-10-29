package com.shengzimao.reader.mobile.data

/** Each completed response is visible immediately; later ranking cannot remove earlier cards. */
class StreamingNovelResults(private val keyword: String, private val limit: Int = 500) {
    private val visible = LinkedHashMap<String, OnlineResult>()

    @Synchronized
    fun append(incoming: List<OnlineResult>): List<OnlineResult> {
        OnlineMatcher.filter(keyword, incoming).forEach { book ->
            val key = "${book.sourceGroup}|${book.sourceKey}|${book.sourceId}|${book.url}"
            if (key in visible || visible.size < limit) visible[key] = book
        }
        return visible.values.toList()
    }
}
