package com.shengzimao.reader.mobile.data

/**
 * 章节识别，与桌面版 app/library.cjs 的 findChapters 保持同一套规则：
 * 只认整行标题，支持“第 12 章 / 第 12 回 / 第 12 卷 / 第十二话 / chapter 12”，
 * 单行最多 100 字，最多 30000 章，跳过后一标题与前一标题重叠的行。
 */
object ChapterParser {
    private val CHAPTER = Regex(
        "^[\\t ]*((?:第[\\t ]*[\\d零〇一二三四五六七八九十百千万两]+[\\t ]*[章回卷节部集篇话話]|chapter\\s+\\d+)[^\\r\\n]{0,100})[\\t ]*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    const val MAX_CHAPTERS = 30_000

    fun find(text: CharSequence): List<Chapter> {
        if (text.isEmpty()) return emptyList()
        val chapters = ArrayList<Chapter>()
        var match = CHAPTER.find(text)
        while (match != null && chapters.size < MAX_CHAPTERS) {
            val line = match.value
            val title = match.groupValues[1].trim()
            // 与桌面版一致：起点取标题在行内的真实偏移（跳过行首空白）。
            val leading = line.indexOfFirst { it != ' ' && it != '\t' }.coerceAtLeast(0)
            val position = match.range.first + leading
            val previous = chapters.lastOrNull()
            val duplicate = previous != null && position <= previous.position + previous.title.length
            if (!duplicate) chapters.add(Chapter(title, position))
            match = CHAPTER.find(text, match.range.first + 1)
        }
        return chapters
    }

    /** 书名去掉扩展名，作为默认标题。 */
    fun titleFromFileName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val raw = if (dot > 0) fileName.substring(0, dot) else fileName
        return raw.trim().ifEmpty { "未命名书籍" }.take(250)
    }

    /** “《书名》 作者：张三.txt” 这类文件名，抽出作者信息。 */
    fun authorFromFileName(fileName: String): String {
        val base = titleFromFileName(fileName)
        val match = Regex("[（(【\\[]\\s*(?:作者[:：]?)?([^)）】\\]]{1,40})\\s*[)）】\\]]").find(base)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }
}
