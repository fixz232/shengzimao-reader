package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线搜书相关的数据模型与纯函数。
 *
 * 排序/过滤规则与桌面版 app/search-matching.cjs 对齐：
 * NFKC 归一 + 去除非字母数字，书名与作者同时参与打分，存在精确命中时丢弃近似结果。
 */
data class OnlineResult(
    val bookName: String = "",
    val author: String = "",
    val url: String = "",
    val sourceId: Int = -1,
    val sourceName: String = "",
    val category: String = "",
    val intro: String = "",
    val latestChapter: String = "",
    val lastUpdateTime: String = "",
    val status: String = "",
    val wordCount: String = "",
    val matchScore: Int = 0,
    val matchType: String = "",
    val chapterCount: Int = 0,
    val sourceGroup: Int = 1,
    val sourceKey: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bookName", bookName); put("author", author); put("url", url)
        put("sourceId", sourceId); put("sourceName", sourceName); put("category", category)
        put("intro", intro); put("latestChapter", latestChapter); put("lastUpdateTime", lastUpdateTime)
        put("status", status); put("wordCount", wordCount); put("matchScore", matchScore)
        put("matchType", matchType); put("chapterCount", chapterCount)
        put("sourceGroup", sourceGroup)
        put("sourceKey", sourceKey)
    }

    companion object {
        fun fromJson(json: JSONObject): OnlineResult = OnlineResult(
            bookName = json.optString("bookName"),
            author = json.optString("author"),
            url = json.optString("url"),
            sourceId = json.optInt("sourceId", -1),
            sourceName = json.optString("sourceName"),
            category = json.optString("category"),
            intro = json.optString("intro"),
            latestChapter = json.optString("latestChapter"),
            lastUpdateTime = json.optString("lastUpdateTime"),
            status = json.optString("status"),
            wordCount = json.optString("wordCount"),
            matchScore = json.optInt("matchScore", 0),
            matchType = json.optString("matchType"),
            chapterCount = json.optInt("chapterCount", 0),
            sourceGroup = json.optInt("sourceGroup", 1).takeIf { it in 1..5 } ?: 1,
            sourceKey = json.optString("sourceKey"),
        )

        fun fromEngine(json: JSONObject): OnlineResult = OnlineResult(
            bookName = json.optString("bookName"),
            author = json.optString("author"),
            url = json.optString("url"),
            sourceId = json.optInt("sourceId", -1),
            sourceName = json.optString("sourceName"),
            category = json.optString("category"),
            intro = json.optString("intro"),
            latestChapter = json.optString("latestChapter"),
            lastUpdateTime = json.optString("lastUpdateTime"),
            status = json.optString("status"),
            wordCount = json.optString("wordCount"),
        )

        fun listFrom(array: JSONArray): List<OnlineResult> =
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { fromEngine(it) } }
    }
}

/** 章节（在线目录项）。 */
data class OnlineChapter(val title: String, val url: String, val ruleState: String = "", val nextUrl: String = "", val previousUrl: String = "")

object OnlineMatcher {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]")
    private val AUTHOR_PREFIX = Regex("^\\s*作者\\s*[:：]?\\s*")

    fun normalize(value: String?): String =
        (value ?: "")
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC) }
            .lowercase()
            .replace(NON_WORD, "")

    fun titleOf(book: OnlineResult): String = normalize(book.bookName)

    fun authorOf(book: OnlineResult): String = normalize(book.author.replace(AUTHOR_PREFIX, ""))

    /** 码点级 Levenshtein 相似度，与桌面版一致。 */
    fun similarity(left: String, right: String): Double {
        val a = left.codePoints().toArray()
        val b = right.codePoints().toArray()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var row = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val next = IntArray(b.size + 1)
            next[0] = i
            for (j in 1..b.size) {
                next[j] = minOf(
                    next[j - 1] + 1,
                    row[j] + 1,
                    row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            row = next
        }
        return 1.0 - row[b.size].toDouble() / maxOf(a.size, b.size)
    }

    /**
     * 过滤与排序。返回带 matchScore/matchType 的结果；
     * 只要存在严格命中就只保留严格命中，否则保留相似度 ≥0.7 的近似结果。
     */
    fun filter(query: String, books: List<OnlineResult>): List<OnlineResult> {
        val tokens = query.trim().split(Regex("[\\s\\u3000]+")).map { normalize(it) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return books.map { it.copy(matchScore = 0) }
        val whole = normalize(query)
        data class Scored(val book: OnlineResult, val strict: Boolean, val exact: Boolean, val near: Double)
        val candidates = books.filter { titleOf(it).isNotEmpty() }.map { book ->
            val title = titleOf(book)
            val author = authorOf(book)
            val values = tokens.map { token ->
                maxOf(
                    fieldScore(title, token),
                    fieldScore(author, token),
                )
            }
            val exact = title == whole || author == whole
            val strict = values.all { it > 0 }
            val near = if (!strict && tokens.size == 1 && whole.length >= 3) {
                maxOf(similarity(whole, title.take(160)), similarity(whole, author.take(160)))
            } else 0.0
            val score = when {
                exact -> 100
                strict -> Math.round(values.average()).toInt()
                else -> Math.round(near * 100).toInt()
            }
            val type = when {
                title == whole -> "书名完全匹配"
                author == whole -> "作者完全匹配"
                strict -> if (tokens.size > 1) "多关键词匹配" else "关键词匹配"
                else -> "近似匹配"
            }
            Scored(book.copy(matchScore = score, matchType = type), strict, exact, near)
        }
        val hasStrict = candidates.any { it.strict }
        return candidates.filter { if (hasStrict) it.strict else it.near >= 0.7 }
            .sortedWith(
                compareByDescending<Scored> { it.exact }
                    .thenByDescending { it.book.matchScore }
                    .thenBy { titleOf(it.book) }
                    .thenBy { authorOf(it.book) },
            )
            .map { it.book }
    }

    private fun fieldScore(field: String, token: String): Int = when {
        field == token -> 100
        field.contains(token) -> 92 - minOf(12, field.length - token.length)
        else -> 0
    }

    /** 同书多站点聚合键：归一后的书名 + 作者。 */
    fun groupKey(book: OnlineResult): String = titleOf(book) + "\u0000" + authorOf(book)
}

/** 批量任务条目状态。 */
data class BatchItem(
    val index: Int = -1,
    val id: String = "",
    val bookName: String = "",
    val sourceName: String = "",
    val state: String = "pending",
    val message: String = "",
)

data class OnlineBatch(
    val kind: String = "download",
    val format: String = "txt",
    val items: List<BatchItem> = emptyList(),
) {
    val total: Int get() = items.size
    val done: Int get() = items.count { it.state == "done" }
    val failed: Int get() = items.count { it.state == "failed" }
    val finished: Int get() = items.count { it.state in setOf("done", "failed", "skipped") }
}

/** 在线任务的界面状态。 */
data class NovelSearchFailure(val sourceUrl: String, val sourceName: String, val message: String)

data class OnlineUiState(
    val sourceGroup: Int = 1,
    val sourceCounts: List<Int> = List(5) { 0 },
    val sourceEntries: List<NovelSourceEntry> = emptyList(),
    val selectedSourceUrls: Set<String>? = null,
    val sourcesReady: Boolean = false,
    val importingSources: Boolean = false,
    val queuedDownload: Boolean = false,
    val running: Boolean = false,
    val searching: Boolean = false,
    val keyword: String = "",
    val results: List<OnlineResult> = emptyList(),
    val message: String = "",
    val error: String = "",
    val batch: OnlineBatch? = null,
    val elapsedMs: Long = 0,
    val revision: Int = 0,
    val engineReady: Boolean = true,
    val details: OnlineResult? = null,
    val detailsToc: List<OnlineChapter> = emptyList(),
    val detailsIndex: Int = -1,
    val searchHistory: List<String> = emptyList(),
    val searchFailures: List<NovelSearchFailure> = emptyList(),
    val searchCompleted: Int = 0,
    val searchTotal: Int = 0,
)

/** 手机版在线使用声明，包含小说与用户自定义漫画源。 */
object Consent {
    const val VERSION = "2026-10-24-v2"
    val LINKS = mapOf(
        "project" to "https://github.com/freeok/so-novel",
        "disclaimer" to "https://github.com/freeok/so-novel/blob/main/bundle/DISCLAIMER.md",
        "license" to "https://github.com/freeok/so-novel/blob/main/LICENSE",
    )

    fun accepted(version: String?, accepted: Boolean): Boolean = version == VERSION && accepted
}
