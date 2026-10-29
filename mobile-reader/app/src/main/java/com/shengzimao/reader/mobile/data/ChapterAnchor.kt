package com.shengzimao.reader.mobile.data

object ChapterAnchor {
    data class Match(val index: Int, val exact: Boolean)
    fun match(previous: List<OnlineChapter>, next: List<OnlineChapter>, index: Int): Match {
        if (next.isEmpty()) return Match(0, false)
        val old = previous.getOrNull(index)
        val url = next.indexOfFirst { old != null && it.url == old.url }
        if (url >= 0) return Match(url, true)
        fun clean(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase().replace(Regex("[\\s\\p{P}]+"), "")
        val title = old?.title?.let(::clean).orEmpty()
        val names = next.withIndex().filter { title.isNotBlank() && clean(it.value.title) == title }
        if (names.size == 1) return Match(names.first().index, true)
        fun number(value: String): Int? {
            val token = Regex("^(?:第)?([0-9零〇一二两三四五六七八九十百千]+)(?:章|话|回|节)").find(clean(value))?.groupValues?.get(1) ?: return null
            token.toIntOrNull()?.let { return it }
            var sum = 0; var digit = 0
            for (c in token) when (c) {
                '十', '百', '千' -> { sum += maxOf(1, digit) * when(c) { '十' -> 10; '百' -> 100; else -> 1000 }; digit = 0 }
                else -> { digit = "零一二三四五六七八九".indexOf(c).takeIf { it >= 0 } ?: if(c == '两') 2 else 0 }
            }
            return sum + digit
        }
        val oldNumber = old?.title?.let(::number)
        val numbered = next.withIndex().filter { oldNumber != null && number(it.value.title) == oldNumber }
        if (numbered.size == 1) return Match(numbered.first().index, false)
        val ratio = if (previous.size > 1) index.toFloat() / (previous.size - 1) else 0f
        return Match((ratio * (next.size - 1)).toInt().coerceIn(0, next.lastIndex), false)
    }
}
