package com.shengzimao.reader.mobile.data

import java.util.UUID

/**
 * 章节锚点。position 为章节标题在全文中的字符偏移（与桌面版 offset 语义一致）。
 */
data class Chapter(val title: String, val position: Int)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val position: Int,
    val label: String,
    val createdAt: Long,
) {
    companion object {
        fun snippet(text: String, position: Int, length: Int = 40): String {
            if (text.isEmpty()) return ""
            val start = position.coerceIn(0, text.length - 1)
            val end = (start + length).coerceAtMost(text.length)
            return text.substring(start, end)
                .replace('\n', ' ')
                .replace('\u3000', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

/**
 * 划线 / 摘录 / 笔记。都锚在全文字符区间上，重排字号不影响定位。
 * [note] 非空即视为“带笔记的划线”。
 */
data class Highlight(
    val id: String = UUID.randomUUID().toString(),
    val start: Int,
    val end: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** 划线下的原文，冗余保存：正文被追更改写时仍能回显。 */
    val snippet: String = "",
    val note: String = "",
    /** 划线颜色，0 表示跟随主题强调色。 */
    val colorIndex: Int = 0,
    /** 章节标题，仅用于列表展示。 */
    val chapterTitle: String = "",
    val tags: List<String> = emptyList(),
) {
    val isNote: Boolean get() = note.isNotBlank()
    val length: Int get() = (end - start).coerceAtLeast(0)
}

/** 划线颜色板：与五种内置主题都能保持可读。 */
object HighlightColors {
    val palette: List<Long> = listOf(
        0xFFFFD666, // 琥珀
        0xFF8FD6A8, // 豆绿
        0xFF8FC2F0, // 天蓝
        0xFFF0A0B8, // 藕粉
        0xFFC9A6F0, // 淡紫
    )

    /** 取调色板颜色并替换透明度。[alpha] 取 0..255。 */
    fun argb(index: Int, alpha: Int = 0x66): Long {
        val base = palette[index.coerceIn(0, palette.lastIndex)]
        return ((alpha.coerceIn(0, 255).toLong() shl 24) or (base and 0xFFFFFF))
    }
}

/** 书库中的一本书。正文不进 JSON，按需从 books/<file> 读取。 */
data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "",
    val file: String = "",
    val format: String = "TXT",
    val length: Int = 0,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val updatedAt: Long = 0L,
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val completed: Boolean = false,
    val category: String = "",
    val tags: List<String> = emptyList(),
    val intro: String = "",
    val sourceName: String = "本地",
    val readingSeconds: Long = 0L,
    val readingDailySeconds: Map<String, Long> = emptyMap(),
    val chapters: List<Chapter> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val digest: String = "",
    /** 源文件缺失（例如导入后文件被移动），仍保留条目可从备份恢复。 */
    val missing: Boolean = false,
    /** 在线书目原始地址；为空表示本地导入的书。 */
    val onlineUrl: String = "",
    /** 聚合书源分组；旧书与番茄书保留分组 1。 */
    val onlineSourceGroup: Int = 1,
    val onlineSourceKey: String = "",
    /** 在线目录清单（JSON），用于安全地核对增量更新。 */
    val onlineManifest: String = "",
    /** 是否加入追更。 */
    val tracking: Boolean = false,
    /** 最近一次检查/成功更新时间。 */
    val lastUpdateCheckAt: Long = 0L,
    val lastUpdatedAt: Long = 0L,
    val newChapterCount: Int = 0,
    /** 在线来源的展示字段。 */
    val latestChapter: String = "",
    val lastUpdateTime: String = "",
    val status: String = "",
    /** 追更替换前的历史正文文件（最多保留 2 份）。 */
    val updateBackupFiles: List<String> = emptyList(),
    /** 在线书架仅保存目录与进度，正文按章联网读取。旧书默认保留离线模式。 */
    val onlineOnly: Boolean = false,
    val onlineOffset: Int = 0,
    val onlineCharacterOffset: Int = -1,
    val onlineParagraphFraction: Float = 0f,
    val onlineAnnotations: String = "[]",
    val readerPreferences: String = "",
) {
    val progress: Float
        get() = if (onlineOnly) {
            if (completed) 1f else if (length <= 0) 0f else (position.toFloat() / length).coerceIn(0f, 1f)
        } else if (length <= 1) 0f else (position.toFloat() / (length - 1)).coerceIn(0f, 1f)

    val isDone: Boolean
        get() = completed || (!onlineOnly && progress >= 0.999f)

    /** 当前阅读到的章节下标，未识别章节时为 -1。 */
    val chapterIndex: Int
        get() {
            var index = -1
            for (i in chapters.indices) {
                if (chapters[i].position <= position) index = i else break
            }
            return index
        }

    val chapterLabel: String
        get() {
            val index = chapterIndex
            return if (index >= 0 && chapters.isNotEmpty()) "第 ${index + 1} 章 / 共 ${chapters.size} 章" else "暂无章节"
        }

    /** 某个偏移落在第几章（从 0 开始），未识别章节时返回 -1。 */
    fun chapterIndexOf(offset: Int): Int {
        var found = -1
        for (i in chapters.indices) {
            if (chapters[i].position <= offset) found = i else break
        }
        return found
    }

    fun chapterTitleAt(offset: Int): String {
        val index = chapterIndexOf(offset)
        return if (index in chapters.indices) chapters[index].title else title
    }
}

data class Library(
    val version: Int = 1,
    val books: List<Book> = emptyList(),
    val activeId: String = "",
    /** 在线使用声明确认状态（不进备份，换设备需重新确认）。 */
    val consentVersion: String = "",
    val consentAccepted: Boolean = false,
    val consentAt: Long = 0L,
    val searchHistory: List<String> = emptyList(),
    val autoUpdate: Boolean = false,
)
