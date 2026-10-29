package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReadingEnhancementsTest {
    @Test fun cleanupKeepsOriginalWhenDisabled() {
        val raw = "  原文\r\n空行\n\n结尾"
        val result = ContentCleanup.apply(raw, emptyList())
        assertEquals(raw, result.text)
        (0..raw.length).forEach { assertEquals(it, result.rawOffset(it)); assertEquals(it, result.displayOffset(it)) }
    }
    @Test fun cleanupRemovalMapsBackToOriginalChapter() {
        val raw = "第一段\n广告请收藏\n最后一段"
        val result = ContentCleanup.apply(raw, ContentCleanup.parse("# 注释\n-广告"))
        assertEquals("第一段\n最后一段", result.text)
        assertEquals(raw.indexOf("最后"), result.rawOffset(result.text.indexOf("最后")))
        assertEquals(result.text.indexOf("最后"), result.displayOffset(raw.indexOf("最后")))
        assertEquals(result.text.indexOf("最后"), result.displayOffset(raw.indexOf("广告")))
        assertTrue(raw.contains("广告"))
    }
    @Test fun cleanupReplacementIsLiteralAndOffsetsStayMonotonic() {
        val raw = "a.*b 首段\n第二段末尾"
        val result = ContentCleanup.apply(raw, ContentCleanup.parse("a.*b=>替换\n首段=>"))
        assertEquals("替换 \n第二段末尾", result.text)
        assertEquals(raw.indexOf("第二"), result.rawOffset(result.text.indexOf("第二")))
        assertTrue((0 until result.text.length).all { result.rawOffset(it) <= result.rawOffset(it + 1) })
    }
    @Test fun cleanupRejectsMalformedOrUnboundedRules() {
        listOf("-", "=>x", "无分隔符", "x".repeat(12001), (1..51).joinToString("\n") { "a=>b" }, "x".repeat(301)+"=>y").forEach {
            assertTrue(runCatching { ContentCleanup.parse(it) }.isFailure)
        }
        assertEquals("", ContentCleanup.apply("广告", ContentCleanup.parse("-广告")).text)
    }
    @Test fun textAnchorFindsIdenticalPassageAfterPrefix() {
        val raw = "开始" + (1..80).joinToString("") { "段落${it}内容" }
        val next = "新源前言\n" + raw
        assertEquals(TextPositionAnchor.Match(105, true), TextPositionAnchor.match(raw, next, 100))
    }
    @Test fun cleanupRejectsExpansionBeforeAllocatingAnOversizedResult() {
        val rules=ContentCleanup.parse("a=>"+"x".repeat(290))
        assertTrue(runCatching { ContentCleanup.apply("a".repeat(10000),rules) }.isFailure)
    }
    @Test fun textAnchorIgnoresPunctuationDifferences() {
        val raw = "清风穿过山林读者翻开书页今天的故事从这里开始然后走到远方寻找新的旅程"
        val next = "前言。" + raw.toList().joinToString("，")
        val match = TextPositionAnchor.match(raw, next, 0)
        assertTrue(match.exact); assertEquals(3, match.offset)
    }
    @Test fun ambiguousTextFallsBackToRatio() {
        val raw = "重复内容".repeat(100)
        val next = raw.repeat(2)
        val match = TextPositionAnchor.match(raw, next, 100)
        assertFalse(match.exact); assertEquals(200, match.offset)
        assertEquals(0, TextPositionAnchor.match("", next, 999).offset)
    }
    @Test fun chapterAnchorMatchesChineseAndArabicNumbers() {
        val previous = listOf(OnlineChapter("第十二章 风起", "old"))
        val next = listOf(OnlineChapter("第11章", "a"), OnlineChapter("12话 不同标题", "b"), OnlineChapter("第13章", "c"))
        assertEquals(1, ChapterAnchor.match(previous, next, 0).index)
        assertFalse(ChapterAnchor.match(previous, next, 0).exact)
    }
    @Test fun duplicateChapterNumbersUseProportion() {
        val old = (1..3).map { OnlineChapter("第${it}章", "old$it") }
        val next = listOf(OnlineChapter("第2章 上", "a"), OnlineChapter("第2章 下", "b"), OnlineChapter("附录", "c"))
        assertEquals(1, ChapterAnchor.match(old, next, 1).index)
    }
    @Test fun prefetchStopsForLowMemoryAndRestrictedNetworks() {
        assertEquals(0, AdaptivePrefetch.count(true, true, 500, 1000, 3000, 5))
        assertEquals(0, AdaptivePrefetch.count(true, false, 23, 1000, 3000, 5))
        assertEquals(0, AdaptivePrefetch.count(false, false, 500, 1000, 3000, 5))
        assertEquals(1, AdaptivePrefetch.count(false, false, 500, 1000, 3000, 5, false))
        assertEquals(2, AdaptivePrefetch.count(true, false, 80, 1000, 3000, 5))
    }
    @Test fun prefetchAdjustsToReadingAndLoadSpeed() {
        val slow = AdaptivePrefetch.count(true, false, 500, 60000, 3000, 5)
        val fast = AdaptivePrefetch.count(true, false, 500, 1000, 3000, 5)
        assertEquals(1, slow); assertEquals(5, fast)
    }
    @Test fun spreadGroupingPreservesEveryPageForAllSettings() {
        for (count in 0..30) for (smart in listOf(false, true)) for (double in listOf(false, true)) {
            val groups = ComicPageAnalysis.spreads(count, double, smart, setOf(2, 6, 15))
            assertEquals((0 until count).toList(), groups.flatten())
            if (smart && double && count > 0) {
                assertEquals(listOf(0), groups.first())
                groups.filter { 2 in it || 6 in it || 15 in it }.forEach { assertEquals(1, it.size) }
            }
        }
    }
    @Test fun cropPreservesInkAndLeavesSafetyMargin() {
        val pixels = IntArray(100*50) { index -> if (index%100 in 0..9 || index%100 in 90..99) -1 else 0xff222222.toInt() }
        assertEquals(9, ComicPageAnalysis.cropPercent(100, 50, pixels))
        pixels[20*100] = 0xff000000.toInt()
        assertEquals(0, ComicPageAnalysis.cropPercent(100, 50, pixels))
        assertEquals(0, ComicPageAnalysis.cropPercent(100, 50, IntArray(5000) { 0xff111111.toInt() }))
    }
    @Test fun persistentCacheReopensWithoutSessionAndIsolatesProviders() {
        val root = Files.createTempDirectory("reading-cache").toFile()
        try {
            OnlineReadingCache(root).write("book-a", "chapter", "原始正文")
            val reopened = OnlineReadingCache(root)
            assertEquals("原始正文", reopened.read("book-a", "chapter"))
            assertNull(reopened.read("book-b", "chapter"))
            root.listFiles()!!.single().writeText("{broken")
            assertNull(reopened.read("book-a", "chapter"))
        } finally { root.deleteRecursively() }
    }
    @Test fun cacheQuotaEvictsOldestAndRejectsEmptyChapter() {
        val root = Files.createTempDirectory("reading-cache-quota").toFile()
        try {
            val cache = OnlineReadingCache(root, 500)
            cache.write("b", "one", "正文".repeat(50))
            root.listFiles()!!.single().setLastModified(1)
            cache.write("b", "two", "正文".repeat(50))
            assertNull(cache.read("b", "one")); assertNotNull(cache.read("b", "two"))
            assertTrue(runCatching { cache.write("b", "empty", " ") }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun downloadedChapterUsesCharacterOffsetsAndBounds() {
        val root = Files.createTempDirectory("downloaded-chapter").toFile()
        try {
            val store = LibraryStore(root)
            val text = "第一章\n甲乙丙\n第二章\n丁戊己\n"
            val split = text.indexOf("第二章")
            val toc = listOf(OnlineChapter("第一章", "1"), OnlineChapter("第二章", "2"))
            val book = Book(id="downloaded-test", title="书", length=text.length, file=store.writeText("downloaded-test", text),
                chapters=listOf(Chapter("第一章",0), Chapter("第二章",split)), onlineManifest=OnlineChapterCodec.encode(toc))
            assertEquals(text.substring(split), DownloadedChapterText.read(store, book, toc[1]))
            assertNull(DownloadedChapterText.read(store, book, OnlineChapter("错章", "2")))
        } finally { root.deleteRecursively() }
    }
    @Test fun mangaProgressPersistsUnreadAndPreferencesSurviveSerialization() {
        val root = Files.createTempDirectory("manga-progress").toFile()
        try {
            val store=MangaStore(root)
            store.upsert(MangaBook("reading-manga", "漫画", newChapters=2, tracking=true,
                chapters=(1..4).map { MangaChapter("第${it}话", "$it") }))
            store.stagePosition("reading-manga", 2, 3, .4f, "image-4"); store.flushProgress()
            val reopened=MangaStore(root).books().single()
            assertEquals(2,reopened.chapterIndex); assertEquals(3,reopened.pageIndex); assertEquals(.4f,reopened.pageFraction, .001f)
            assertEquals(1,reopened.newChapters); assertTrue(reopened.tracking)
            val settings=MangaSettings(autoCrop=true, smartSpreads=false)
            assertEquals(settings, ReadingPreferences.manga(ReadingPreferences.encode(settings), MangaSettings()))
        } finally { root.deleteRecursively() }
    }
}
