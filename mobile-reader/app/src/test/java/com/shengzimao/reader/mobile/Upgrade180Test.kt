package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import com.shengzimao.reader.mobile.reader.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Upgrade180Test {
    @Test fun fullSourceDefinitionsSurvivePersistence() {
        val raw = JSONArray(javaClass.classLoader!!.getResourceAsStream("legado-sample.json")!!.bufferedReader().readText())
        val (sources, skipped) = LegadoConverter.convertAll(raw)
        assertEquals(97, sources.size); assertTrue(skipped.isEmpty())
        sources.filter { it.legacy != null }.forEach { source ->
            val restored = MangaSource.parse(source.json())
            val original = (0 until raw.length()).map { raw.getJSONObject(it) }.first { it.optString("bookSourceName").trim().take(80) == source.name }
            assertEquals(original.toString(), restored.legacy!!.toString())
            if (!source.enabled) assertTrue(source.compatibilityNote.isNotBlank())
        }
    }
    @Test fun legacyRuntimeSupportsSelectorsScriptsAndVariables() {
        val runtime = LegacyRuleRuntime()
        assertEquals("https://example.com/q/2", runtime.interpolate("https://example.com/q/{{1+1}}", "", "https://example.com"))
        assertEquals(listOf("Chapter"), runtime.evaluate(".t@text@js:result.trim()", "<b class=t> Chapter </b>", "https://example.com"))
        assertEquals(listOf("A", "B"), runtime.evaluate("a@text@js:result.map(function(x){return x.toUpperCase()})", "<a>a</a><a>b</a>", "https://example.com", true))
        assertEquals(listOf("7"), runtime.script("java.put('x','7');java.get('x')", "", "https://example.com"))
        assertEquals("7", runtime.variables["x"])
        assertEquals(listOf("ok"), runtime.script("org.jsoup.Jsoup.parse(result).select('b').text()", "<b>ok</b>", "https://example.com"))
    }
    @Test fun legacyRuntimeRejectsJavaAndStopsInfiniteLoop() {
        val runtime = LegacyRuleRuntime()
        assertEquals(listOf("undefined|undefined"), runtime.script("typeof Packages+'|'+typeof java.lang", "", "https://example.com"))
        assertTrue(runCatching { runtime.script("while(true){}", "", "https://example.com") }.isFailure)
        assertEquals(listOf("ok"), runtime.script("'ok'", "", "https://example.com"))
    }
    @Test fun legacyVariablesDoNotLeakBetweenWorks() {
        val first = LegacyRuleRuntime(); val second = LegacyRuleRuntime()
        first.script("java.put('id','first')", "", "https://example.com")
        assertEquals(listOf(""), second.script("java.get('id')", "", "https://example.com"))
    }
    @Test fun legacyFragmentAttributesProduceUsableDirectoryLinks() {
        val raw = JSONObject().put("bookSourceName", "fixture").put("bookSourceUrl", "https://example.com")
            .put("ruleSearchUrl", "https://example.com/search").put("ruleSearchList", ".item")
            .put("ruleSearchName", ".title@text").put("ruleSearchNoteUrl", "a@href@js:result")
            .put("ruleChapterList", ".chapters a").put("ruleChapterName", "@text").put("ruleContentUrl", "@href")
            .put("ruleBookContent", "img@src")
        val source = LegadoConverter.convert(raw).source!!
        val pages = mapOf("/search" to "<div class=item><a href='/book/1'><span class=title>Book</span></a></div>",
            "/book/1" to "<div class=chapters><a href='/chapter/1'>Chapter</a></div>",
            "/chapter/1" to "<img src='/page.gif'>")
        val engine = LegacyMangaEngine({ url, _, _, _ -> pages[java.net.URI(url).path]!! }, { _, _, _ -> })
        val book = engine.details(source, engine.search(source, "Book", 1).single())
        assertEquals("https://example.com/chapter/1", book.chapters.single().url)
        assertEquals("Chapter", book.chapters.single().title)
        assertEquals(listOf("https://example.com/page.gif"), engine.pages(source, book.chapters.single()))
    }
    @Test fun invalidBackupMetadataDoesNotAddSourcesOrEarlierBooks() {
        val dir = Files.createTempDirectory("manga-restore-validation").toFile()
        try {
            val store = MangaStore(java.io.File(dir, "comics"))
            val source = MangaSource.parse(JSONObject(MangaSource.template))
            val books = JSONArray().put(MangaBook("valid-book-123", "Valid").json()).put(MangaBook("../invalid", "Invalid").json())
            val metadata = JSONObject().put("version", 1).put("sources", JSONArray().put(source.json())).put("books", books)
            MangaBackup.Restore(store, dir).use { restore ->
                restore.accept("comics/metadata.json", metadata.toString().toByteArray())
                assertTrue(runCatching { restore.finish() }.isFailure)
            }
            assertTrue(store.sources().isEmpty()); assertTrue(store.books().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun mangaProgressBookmarksAndPreferencesRoundTrip() {
        val book = MangaBook("book-12345", "测试", pageFraction = .56f, pageReference = "https://example.com/page",
            bookmarks = listOf(MangaBookmark("chapter", 8, fraction = .45f)), readerPreferences = ReadingPreferences.encode(MangaSettings(doublePage = true, cropPercent = 8)))
        val restored = MangaBook.parse(book.json())
        assertEquals(book, restored)
        val preferences = ReadingPreferences.manga(restored.readerPreferences, MangaSettings())
        assertTrue(preferences.doublePage); assertEquals(8, preferences.cropPercent)
    }
    @Test fun novelsRetainOnlineAnchorsAndTags() {
        val settings = ReaderSettings(ttsSleepMinutes = 30, downloadWifiOnly = true)
        val restored = ReadingPreferences.novel(ReadingPreferences.encode(settings), ReaderSettings())
        assertEquals(30, restored.ttsSleepMinutes); assertTrue(restored.downloadWifiOnly)
    }
    @Test fun localZipFoldersProduceOrderedChapters() {
        val chapters = MangaStore.organizeLocalChapters("test-id", listOf("卷10/2.png" to "b", "卷2/10.png" to "d", "卷2/2.png" to "c"))
        assertEquals(listOf("卷2", "卷10"), chapters.map { it.title })
        assertEquals(listOf("c", "d"), chapters.first().pages)
    }
    @Test fun paginationCacheRejectsDamageAndTextChange() {
        val dir = Files.createTempDirectory("page-cache").toFile()
        try {
            val cache = PageMapCache(dir); val key = cache.key("abcdef", "layout", "chapters")
            cache.write(key, PageMap(intArrayOf(0, 3), intArrayOf(0, 0), 6))
            assertArrayEquals(intArrayOf(0, 3), cache.read(key, 6, 1)!!.pages)
            assertNull(cache.read(key, 7, 1)); assertNull(cache.read(cache.key("ghijkl", "layout", "chapters"), 6, 1))
            java.io.File(dir, key).writeText("bad"); assertNull(cache.read(key, 6, 1))
        } finally { dir.deleteRecursively() }
    }
    @Test fun backupRedactsCredentialFields() {
        val clean = MangaBackup.redact(JSONObject().put("token", "private").put("headers", JSONObject().put("Cookie", "session").put("Referer", "https://example.com"))) as JSONObject
        assertFalse(clean.has("token")); assertFalse(clean.getJSONObject("headers").has("Cookie"))
        assertEquals("https://example.com", clean.getJSONObject("headers").getString("Referer"))
    }
    @Test fun onlineConversionKeepsCharacterAnchorBookmarksAndNotes() {
        val text = "第一章\n开始\n\n第二章\n目标段落\n结尾"
        val toc = listOf(OnlineChapter("第一章", "one"), OnlineChapter("第二章", "two"))
        val local = listOf(Chapter("第一章", 0), Chapter("第二章", text.indexOf("第二章")))
        val marks = JSONArray().put(JSONObject().put("chapterUrl", "two").put("position", 4).put("quote", "目标段落"))
            .put(JSONObject().put("chapterUrl", "two").put("position", 4).put("quote", "目标段落").put("note", "保留笔记").put("tags", "重要 待看"))
        val book = Book(id = "online-migration", title = "测试", onlineOnly = true, position = 1,
            onlineManifest = OnlineChapterCodec.encode(toc), onlineCharacterOffset = 5, onlineAnnotations = marks.toString())
        val saved = OnlineReadingMigration.apply(book, text, toc, local)
        assertEquals(local[1].position + 5, saved.position)
        assertEquals(text.indexOf("目标段落"), saved.bookmarks.single().position)
        assertEquals("保留笔记", saved.highlights.single().note)
        assertEquals(listOf("重要", "待看"), saved.highlights.single().tags)
        assertEquals(book.onlineAnnotations, saved.onlineAnnotations)
    }
    @Test fun onlineConversionDoesNotAttachNotesToMissingChapters() {
        val toc = listOf(OnlineChapter("当前", "current"))
        val book = Book(title = "测试", onlineOnly = true, onlineManifest = OnlineChapterCodec.encode(toc), onlineAnnotations =
            JSONArray().put(JSONObject().put("chapterUrl", "missing").put("quote", "旧书源文字").put("note", "笔记")).toString())
        val saved = OnlineReadingMigration.apply(book, "当前正文", toc, listOf(Chapter("当前", 0)))
        assertTrue(saved.bookmarks.isEmpty()); assertTrue(saved.highlights.isEmpty())
        assertEquals(book.onlineAnnotations, saved.onlineAnnotations)
    }
}
