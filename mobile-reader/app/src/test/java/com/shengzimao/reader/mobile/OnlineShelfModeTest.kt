package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class OnlineShelfModeTest {
    @Test fun onlineShelfPersistsChapterProgressWithoutTextFile() {
        val book = Book(id = "online-shelf-001", title = "原创书", onlineOnly = true,
            onlineUrl = "https://example.invalid/book", length = 2, position = 1, onlineOffset = 14,
            chapters = listOf(Chapter("一", 0), Chapter("二", 1)),
            onlineManifest = OnlineChapterCodec.encode(listOf(OnlineChapter("一", "https://example.invalid/1"), OnlineChapter("二", "https://example.invalid/2"))))
        val decoded = LibraryJson.decode(LibraryJson.encode(Library(books = listOf(book)))).books.single()
        assertTrue(decoded.onlineOnly); assertEquals("", decoded.file)
        assertEquals(1, decoded.position); assertEquals(14, decoded.onlineOffset)
        assertEquals(2, decoded.onlineChapters.size)
        assertFalse("进入最后一章不应自动标记读完", decoded.isDone)
        assertTrue(decoded.copy(completed = true).isDone)
    }

    @Test fun oldEntriesRemainOfflineByDefault() {
        val book = LibraryJson.decode("""{"books":[{"id":"offline-book-001","title":"旧书","file":"offline-book-001.txt"}]}""").books.single()
        assertFalse(book.onlineOnly)
        val manga = MangaBook.parse(JSONObject("""{"id":"old-manga","title":"旧漫画","sourceId":"remote"}"""))
        assertFalse(manga.onlineOnly)
    }

    @Test fun backupIncludesOnlineMetadataWithoutMissingTextAndMergesSafely() {
        val dir = Files.createTempDirectory("shelf-mode-backup").toFile()
        try {
            val source = LibraryStore(java.io.File(dir, "source"))
            val shelf = Book(id = "online-shelf-001", title = "原创书", onlineOnly = true, length = 2, position = 1,
                onlineUrl = "https://example.invalid/book", chapters = listOf(Chapter("一", 0), Chapter("二", 1)))
            val buffer = ByteArrayOutputStream()
            val report = Backup.export(source, Library(books = listOf(shelf)), null, buffer)
            assertEquals(0, report.missing); assertEquals(0, report.included)
            val target = LibraryStore(java.io.File(dir, "target"))
            val imported = Backup.import(target, Library(), buffer.toByteArray().inputStream())
            assertEquals(1, imported.imported); assertEquals(0, imported.missingText)
            val restored = target.load().books.single()
            assertTrue(restored.onlineOnly); assertFalse(restored.missing); assertEquals(1, restored.position)
            val again = Backup.import(target, target.load(), buffer.toByteArray().inputStream())
            assertEquals(1, again.skipped)
        } finally { dir.deleteRecursively() }
    }

    @Test fun mangaOnlineFlagSurvivesPersistence() {
        val book = MangaBook("online-manga", "原创漫画", sourceId = "remote", onlineOnly = true,
            chapters = listOf(MangaChapter("第一话", "https://example.invalid/1")))
        assertTrue(MangaBook.parse(book.json()).onlineOnly)
        assertFalse(MangaBook.parse(book.copy(onlineOnly = false).json()).onlineOnly)
    }
}
