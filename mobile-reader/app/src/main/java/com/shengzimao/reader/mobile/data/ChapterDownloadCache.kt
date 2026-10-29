package com.shengzimao.reader.mobile.data

import java.io.File
import org.json.JSONObject

/** 仅主动下载使用；在线阅读的正文不会经过此持久缓存。 */
class ChapterDownloadCache(private val root: File) {
    private fun file(chapter: OnlineChapter) = File(root, mangaHash(chapter.url + "|" + chapter.title) + ".json")
    fun read(chapter: OnlineChapter): String? = runCatching {
        val file = file(chapter)
        if (!file.isFile || file.length() !in 1..8_000_000) return null
        val j = JSONObject(file.readText())
        val text = j.getString("text")
        text.takeIf { it.isNotBlank() && j.getString("digest") == mangaHash(it) && j.getString("url") == chapter.url }
    }.getOrNull()
    fun save(chapter: OnlineChapter, text: String) {
        require(text.isNotBlank() && text.length <= 2_000_000) { "章节正文为空或过大" }
        atomicJson(file(chapter), JSONObject().put("url", chapter.url).put("text", text).put("digest", mangaHash(text)).toString())
    }
    fun clear() { if (root.isDirectory) root.deleteRecursively() }
}
