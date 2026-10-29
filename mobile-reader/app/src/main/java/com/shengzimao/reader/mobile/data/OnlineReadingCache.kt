package com.shengzimao.reader.mobile.data

import java.io.File
import org.json.JSONObject

/** Raw, provider-specific reading cache. Separate from deliberate offline downloads. */
class OnlineReadingCache(private val root: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    private fun file(book: String, chapter: String) = File(root, mangaHash("$book\n$chapter") + ".json")
    @Synchronized fun contains(book: String, chapter: String): Boolean = file(book, chapter).isFile
    @Synchronized fun read(book: String, chapter: String): String? = runCatching {
        val target = file(book, chapter)
        if (!target.isFile || target.length() !in 1..12_000_000) return null
        val data = JSONObject(target.readText())
        val text = data.getString("text")
        if (data.getString("book") != book || data.getString("chapter") != chapter || text.isBlank() ||
            text.length > 2_000_000 || data.getString("digest") != mangaHash(text)) return null
        target.setLastModified(System.currentTimeMillis()); text
    }.getOrNull()
    @Synchronized fun write(book: String, chapter: String, text: String) {
        require(text.isNotBlank() && text.length <= 2_000_000) { "章节正文为空或过大" }
        val target = file(book, chapter)
        atomicJson(target, JSONObject().put("book", book).put("chapter", chapter).put("text", text).put("digest", mangaHash(text)).toString())
        val files = root.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (old in files) {
            if (bytes <= maxBytes) break
            if (old == target) continue
            val length = old.length(); if (old.delete()) bytes -= length
        }
    }
}
