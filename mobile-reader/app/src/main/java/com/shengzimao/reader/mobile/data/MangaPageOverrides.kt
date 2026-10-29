package com.shengzimao.reader.mobile.data

import org.json.JSONObject

data class MangaPageOverride(val original: Boolean = false, val layout: String = "auto")

object MangaPageOverrides {
    fun key(chapter: MangaChapter, page: Int) = mangaHash("${chapter.cacheIdentity.ifBlank { chapter.url }}|$page")
    fun get(raw: String, chapter: MangaChapter, page: Int): MangaPageOverride = runCatching {
        val value = JSONObject(raw.ifBlank { "{}" }).optJSONObject(key(chapter, page))
        MangaPageOverride(value?.optBoolean("original") ?: false, value?.optString("layout", "auto") ?: "auto")
    }.getOrDefault(MangaPageOverride())
    fun set(raw: String, chapter: MangaChapter, page: Int, value: MangaPageOverride): String {
        require(value.layout in setOf("auto", "single", "join"))
        val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val key = key(chapter, page)
        if (value == MangaPageOverride()) json.remove(key)
        else json.put(key, JSONObject().put("original", value.original).put("layout", value.layout))
        require(json.length() <= 2000) { "单部漫画的页面调整已达 2000 项上限" }
        return json.toString()
    }
    fun groups(count: Int, doublePage: Boolean, smart: Boolean, wide: Set<Int>, overrides: Map<Int, MangaPageOverride>): List<List<Int>> {
        val result = arrayListOf<List<Int>>()
        var page = 0
        while (page < count) {
            val mode = overrides[page]?.layout ?: "auto"
            val join = page + 1 < count && overrides[page + 1]?.layout != "single" && when (mode) {
                "single" -> false
                "join" -> true
                else -> doublePage && (!smart || (page != 0 && page !in wide && page + 1 !in wide))
            }
            val end = minOf(count, page + if (join) 2 else 1)
            result += (page until end).toList()
            page = end
        }
        return result
    }
}

internal object MangaSearchGrouping {
    fun identity(book: MangaBook): String {
        fun normalized(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .lowercase().replace(Regex("[\\s\\p{P}]+"), "")
        return normalized(book.title) + "|" + normalized(book.author)
    }
    fun groups(books: List<MangaBook>): List<List<MangaBook>> = books.groupBy(::identity).values.toList()
}
