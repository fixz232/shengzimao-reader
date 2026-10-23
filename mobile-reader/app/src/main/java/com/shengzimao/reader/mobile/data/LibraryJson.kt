package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 JSON 映射：避免引入序列化框架，字段缺省时保持向前兼容（新增字段只影响新版本）。
 */
object LibraryJson {
    fun encode(library: Library): String {
        val root = JSONObject()
        root.put("version", library.version)
        root.put("activeId", library.activeId)
        val books = JSONArray()
        library.books.forEach { books.put(encodeBook(it)) }
        root.put("books", books)
        root.put("consentVersion", library.consentVersion)
        root.put("consentAccepted", library.consentAccepted)
        root.put("consentAt", library.consentAt)
        root.put("autoUpdate", library.autoUpdate)
        root.put("searchHistory", JSONArray(library.searchHistory))
        return root.toString()
    }

    fun decode(text: String): Library {
        val root = JSONObject(text)
        val version = root.optInt("version", 1)
        val books = mutableListOf<Book>()
        val array = root.optJSONArray("books") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            decodeBook(item)?.let { books.add(it) }
        }
        val history = mutableListOf<String>()
        root.optJSONArray("searchHistory")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let { history.add(it) }
        }
        return Library(
            version = version,
            books = books,
            activeId = root.optString("activeId", ""),
            consentVersion = root.optString("consentVersion", ""),
            consentAccepted = root.optBoolean("consentAccepted", false),
            consentAt = root.optLong("consentAt", 0L),
            searchHistory = history,
            autoUpdate = root.optBoolean("autoUpdate", false),
        )
    }

    private fun encodeBook(book: Book): JSONObject = JSONObject().apply {
        put("id", book.id)
        put("title", book.title)
        put("author", book.author)
        put("file", book.file)
        put("format", book.format)
        put("length", book.length)
        put("position", book.position)
        put("createdAt", book.createdAt)
        put("lastReadAt", book.lastReadAt)
        put("updatedAt", book.updatedAt)
        put("favorite", book.favorite)
        put("pinned", book.pinned)
        put("completed", book.completed)
        put("category", book.category)
        put("intro", book.intro)
        put("sourceName", book.sourceName)
        put("readingSeconds", book.readingSeconds)
        put("digest", book.digest)
        put("missing", book.missing)
        put("onlineUrl", book.onlineUrl)
        put("tracking", book.tracking)
        put("onlineChapters", book.onlineManifest)
        put("latestChapter", book.latestChapter)
        put("lastUpdateTime", book.lastUpdateTime)
        put("status", book.status)
        put("updateBackupFiles", JSONArray(book.updateBackupFiles))
        put("lastUpdateCheckAt", book.lastUpdateCheckAt)
        put("lastUpdatedAt", book.lastUpdatedAt)
        put("newChapterCount", book.newChapterCount)
        put("tags", JSONArray(book.tags))
        val chapters = JSONArray()
        book.chapters.forEach { chapters.put(JSONObject().put("title", it.title).put("position", it.position)) }
        put("chapters", chapters)
        val bookmarks = JSONArray()
        book.bookmarks.forEach {
            bookmarks.put(JSONObject().put("id", it.id).put("position", it.position).put("label", it.label).put("createdAt", it.createdAt))
        }
        put("bookmarks", bookmarks)
        put("readingDailySeconds", JSONObject().apply { book.readingDailySeconds.forEach { (key, value) -> put(key, value) } })
    }

    private fun decodeBook(item: JSONObject): Book? {
        val title = item.optString("title", "").trim()
        val id = item.optString("id", "")
        if (title.isEmpty() || id.isEmpty()) return null
        val chapters = mutableListOf<Chapter>()
        item.optJSONArray("chapters")?.let { array ->
            for (i in 0 until array.length()) {
                val chapter = array.optJSONObject(i) ?: continue
                chapters.add(Chapter(chapter.optString("title", ""), chapter.optInt("position", 0)))
            }
        }
        val bookmarks = mutableListOf<Bookmark>()
        item.optJSONArray("bookmarks")?.let { array ->
            for (i in 0 until array.length()) {
                val mark = array.optJSONObject(i) ?: continue
                bookmarks.add(
                    Bookmark(
                        id = mark.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() },
                        position = mark.optInt("position", 0),
                        label = mark.optString("label", ""),
                        createdAt = mark.optLong("createdAt", 0L),
                    )
                )
            }
        }
        val daily = mutableMapOf<String, Long>()
        item.optJSONObject("readingDailySeconds")?.let { dailyJson ->
            dailyJson.keys().forEach { key -> daily[key] = dailyJson.optLong(key, 0L) }
        }
        return Book(
            id = id,
            title = title.take(250),
            author = item.optString("author", ""),
            file = item.optString("file", ""),
            format = item.optString("format", "TXT"),
            length = item.optInt("length", 0),
            position = item.optInt("position", 0),
            createdAt = item.optLong("createdAt", 0L),
            lastReadAt = item.optLong("lastReadAt", 0L),
            updatedAt = item.optLong("updatedAt", 0L),
            favorite = item.optBoolean("favorite", false),
            pinned = item.optBoolean("pinned", false),
            completed = item.optBoolean("completed", false),
            category = item.optString("category", ""),
            intro = item.optString("intro", ""),
            sourceName = item.optString("sourceName", "本地"),
            readingSeconds = item.optLong("readingSeconds", 0L),
            readingDailySeconds = daily,
            chapters = chapters,
            bookmarks = bookmarks,
            digest = item.optString("digest", ""),
            missing = item.optBoolean("missing", false),
            onlineUrl = item.optString("onlineUrl", ""),
            tracking = item.optBoolean("tracking", false),
            onlineManifest = if (item.has("onlineManifest")) item.optString("onlineManifest", "") else item.optString("onlineChapters", ""),
            lastUpdateCheckAt = item.optLong("lastUpdateCheckAt", 0L),
            lastUpdatedAt = item.optLong("lastUpdatedAt", 0L),
            newChapterCount = item.optInt("newChapterCount", 0),
            latestChapter = item.optString("latestChapter", ""),
            lastUpdateTime = item.optString("lastUpdateTime", ""),
            status = item.optString("status", ""),
            updateBackupFiles = item.optJSONArray("updateBackupFiles")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
        )
    }

    fun encodeBookmarks(bookmarks: List<Bookmark>): String = JSONArray().also { array ->
        bookmarks.forEach {
            array.put(JSONObject().put("id", it.id).put("position", it.position).put("label", it.label).put("createdAt", it.createdAt))
        }
    }.toString()
}
