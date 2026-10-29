package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MangaReaderSessionTest {
    @Test fun automaticPaceAndStatusRoundTripWithoutPersistingRunningState() {
        val settings = MangaSettings(showStatusBar = false, autoReadIntervalSeconds = 17, autoScrollDpPerSecond = 75)
        val raw = ReadingPreferences.encode(settings)
        assertEquals(settings, ReadingPreferences.manga(raw))
        assertFalse(raw.contains("autoPlaying"))
    }

    @Test fun oldPreferencesInheritPaceAndInvalidValuesAreBounded() {
        val defaults = MangaSettings(autoReadIntervalSeconds = 13, autoScrollDpPerSecond = 48, showStatusBar = false)
        assertEquals(defaults, ReadingPreferences.manga("{}", defaults))
        val low = ReadingPreferences.manga("""{"autoReadIntervalSeconds":-1,"autoScrollDpPerSecond":0}""")
        assertEquals(2, low.autoReadIntervalSeconds); assertEquals(12, low.autoScrollDpPerSecond)
        val high = ReadingPreferences.manga("""{"autoReadIntervalSeconds":999,"autoScrollDpPerSecond":999}""")
        assertEquals(60, high.autoReadIntervalSeconds); assertEquals(120, high.autoScrollDpPerSecond)
    }

    private val chapters = (1..10).map { MangaChapter("第${it}话 原创", "https://source.example/$it") }
    private val book = MangaBook("session-test", "原创漫画", sourceId = "test", chapters = chapters,
        cachedChapters = setOf(chapters[1].url, chapters[7].url), chapterIndex = 7)

    @Test fun catalogReverseAndCacheFilterKeepOriginalChapterIndexes() {
        val filtered = mangaCatalogEntries(book, "", reversed = true, cachedOnly = true)
        assertEquals(listOf(7, 1), filtered.map { it.index })
        assertEquals(chapters, book.chapters)
        assertEquals(7, book.chapterIndex)
    }

    @Test fun catalogSearchTrimsQueryAndLocalPagesAreAvailableOffline() {
        assertEquals(listOf(7), mangaCatalogEntries(book, " 第8话 ", false, false).map { it.index })
        assertTrue(mangaCatalogEntries(book, "不存在", true, true).isEmpty())
        assertEquals(10, mangaCatalogEntries(book.copy(sourceId = ""), "", false, true).size)
    }

    @Test fun imageContextSurvivesSerializationAndOldLibraryRemainsReadable() {
        val image = "https://images.example/1.png"
        val chapter = MangaChapter("第一话", "https://source.example/chapter/1", listOf(image),
            imageHeaders = mapOf(image to mapOf("Referer" to "https://source.example/chapter/1", "region" to "0")))
        assertEquals(chapter, MangaChapter.parse(chapter.json()))
        assertTrue(MangaChapter.parse(JSONObject("""{"title":"旧章节","url":"https://source.example/old","pages":[]}""")).imageHeaders.isEmpty())
        val invalid = chapter.json().getJSONObject("imageHeaders").getJSONObject(image).put("Host", "other.example")
        assertNotNull(invalid)
        val raw = chapter.json(); raw.getJSONObject("imageHeaders").put(image, invalid)
        assertTrue(runCatching { MangaChapter.parse(raw) }.isFailure)
    }
}
