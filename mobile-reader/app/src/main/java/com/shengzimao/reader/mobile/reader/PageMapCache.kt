package com.shengzimao.reader.mobile.reader

import com.shengzimao.reader.mobile.data.LibraryStore
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream

/** 排版缓存仅保存偏移数组；损坏、正文或字体比例变化时重新排版。 */
class PageMapCache(private val dir: File) {
    fun key(text: String, signature: String, chapters: String): String = LibraryStore.digest(LibraryStore.digest(text) + "\u0000" + signature + "\u0000" + chapters)
    fun read(key: String, length: Int, chapterCount: Int): PageMap? = runCatching {
        val file = File(dir, key)
        require(file.length() in 20..8_000_020)
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == Paginator.VERSION && input.readInt() == length)
            val count = input.readInt(); require(count in 1..1_000_000 && file.length() == 12L + count * 8L)
            val pages = IntArray(count) { input.readInt() }
            val chapters = IntArray(count) { input.readInt() }
            require(pages.first() == 0 && pages.all { it in 0 until length.coerceAtLeast(1) })
            require(pages.indices.drop(1).all { pages[it] > pages[it - 1] })
            require(chapters.all { it >= -1 && it < chapterCount })
            file.setLastModified(System.currentTimeMillis()); PageMap(pages, chapters, length)
        }
    }.getOrNull()
    fun write(key: String, map: PageMap) = runCatching {
        dir.mkdirs()
        val target = File(dir, key); val temp = File.createTempFile("pages-", ".part", dir)
        try {
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(Paginator.VERSION); output.writeInt(map.textLength); output.writeInt(map.pageCount)
                map.pages.forEach(output::writeInt); map.pageChapter.forEach(output::writeInt)
            }
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
            dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { if (total > 32L * 1024 * 1024) { val size = it.length(); if (it.delete()) total -= size } }
        } finally { temp.delete() }
    }
}
