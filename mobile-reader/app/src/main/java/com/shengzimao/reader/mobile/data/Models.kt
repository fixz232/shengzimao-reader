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
)

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
    val digest: String = "",
    /** 源文件缺失（例如导入后文件被移动），仍保留条目可从备份恢复。 */
    val missing: Boolean = false,
    /** 在线书目原始地址；为空表示本地导入的书。 */
    val onlineUrl: String = "",
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
) {
    val progress: Float
        get() = if (length <= 1) 0f else (position.toFloat() / (length - 1)).coerceIn(0f, 1f)

    val isDone: Boolean
        get() = completed || progress >= 0.999f

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
