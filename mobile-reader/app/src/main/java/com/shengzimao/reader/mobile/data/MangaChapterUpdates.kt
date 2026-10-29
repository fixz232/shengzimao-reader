package com.shengzimao.reader.mobile.data

import java.text.Normalizer

/** Match each old chapter at most once; a changed host/signature must not announce the entire book. */
internal object MangaChapterUpdates {
    data class Result(val previousIndices: List<Int>, val addedIndices: Set<Int>)
    private fun title(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[\\s\\p{P}]+"), "")

    fun reconcile(previous: List<MangaChapter>, next: List<MangaChapter>): Result {
        val used = hashSetOf<Int>()
        val urls = previous.withIndex().groupBy({ it.value.url }, { it.index })
        val titles = previous.withIndex().filter { title(it.value.title).isNotBlank() }.groupBy({ title(it.value.title) }, { it.index })
        val matched = next.map { chapter ->
            val index = urls[chapter.url].orEmpty().firstOrNull { it !in used }
                ?: titles[title(chapter.title)].orEmpty().firstOrNull { it !in used } ?: -1
            if (index >= 0) used += index
            index
        }
        return Result(matched, matched.indices.filter { matched[it] < 0 }.toSet())
    }
}
