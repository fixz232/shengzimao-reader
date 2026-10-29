package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MangaReadingUpgradeTest {
    @Test fun visiblePageJumpsAheadOfQueuedDownloads() = runTest {
        val scheduler = MangaImageScheduler(1)
        val release = CompletableDeferred<Unit>()
        val order = arrayListOf<String>()
        val first = launch { scheduler.withPermit(MangaImagePriority.VISIBLE, "a") { release.await() } }
        runCurrent()
        val download = launch { scheduler.withPermit(MangaImagePriority.DOWNLOAD, "a") { order += "download" } }
        val nearby = launch { scheduler.withPermit(MangaImagePriority.NEARBY, "a") { order += "nearby" } }
        val current = launch { scheduler.withPermit(MangaImagePriority.VISIBLE, "a") { order += "visible" } }
        runCurrent(); release.complete(Unit)
        first.join(); download.join(); nearby.join(); current.join()
        assertEquals(listOf("visible", "nearby", "download"), order)
    }

    @Test fun readerReservesCapacityAndCancelledWaitersDoNotLeak() = runTest {
        val scheduler = MangaImageScheduler(3)
        scheduler.reading(true)
        val release = CompletableDeferred<Unit>()
        val first = launch { scheduler.withPermit(MangaImagePriority.DOWNLOAD, "a") { release.await() } }
        runCurrent()
        var backgroundStarted = false
        val queued = launch { scheduler.withPermit(MangaImagePriority.DOWNLOAD, "b") { backgroundStarted = true } }
        var foregroundStarted = false
        val foreground = launch { scheduler.withPermit(MangaImagePriority.VISIBLE, "a") { foregroundStarted = true } }
        runCurrent()
        assertTrue(foregroundStarted); assertFalse(backgroundStarted)
        queued.cancelAndJoin(); release.complete(Unit); first.join(); foreground.join()
        scheduler.withPermit(MangaImagePriority.DOWNLOAD, "b") { backgroundStarted = true }
        assertTrue(backgroundStarted)
    }

    @Test fun downloadingAlwaysLeavesOneSlotForAnOpeningReader() = runTest {
        val scheduler = MangaImageScheduler(3)
        val release = CompletableDeferred<Unit>()
        var started = 0
        val downloads = (0..2).map { launch { scheduler.withPermit(MangaImagePriority.DOWNLOAD, "host-$it") { started++; release.await() } } }
        runCurrent(); assertEquals(2, started)
        var visible = false
        scheduler.withPermit(MangaImagePriority.VISIBLE, "reader") { visible = true }
        assertTrue(visible); release.complete(Unit); downloads.forEach { it.join() }
    }

    @Test fun sameUrlCannotShareAcrossBooksChaptersPagesOrRuleVersions() {
        val url = "https://example.org/image"
        fun key(book: String = "b", chapter: String = "c", page: Int = 0, rules: String = "v1") =
            MangaDecoder.cacheName("s", url, null, context = MangaImageContext(book, chapter, page, rules))
        assertEquals(5, setOf(key(), key(book = "b2"), key(chapter = "c2"), key(page = 1), key(rules = "v2")).size)
        assertNotEquals(key(), MangaDecoder.cacheName("other", url, null, context = MangaImageContext("b", "c", 0, "v1")))
    }

    @Test fun refreshingSignatureKeepsCacheButChangingImageIdentityDoesNot() {
        val book = MangaBook("id", "原创", url = "https://example.org/book")
        fun context(url: String) = MangaImageContext.of(book, MangaChapter("第一话", "https://example.org/chapter", listOf(url)), 0)
        assertEquals(context("https://cdn.example.org/image?id=1&token=old&expires=1"), context("https://cdn.example.org/image?expires=2&token=new&id=1"))
        assertNotEquals(context("https://cdn.example.org/image?id=1"), context("https://cdn.example.org/image?id=2"))
    }

    @Test fun measuredLoadTimeExcludesAnUnboundedHistory() {
        val samples = MangaLoadingTimes()
        samples.record("s", 1000); samples.record("s", 5000)
        assertEquals(2000L, samples.estimate("s"))
        samples.record("other", 100_000)
        assertEquals(60_000L, samples.estimate("other"))
        assertEquals(3000L, samples.estimate("new"))
    }

    @Test fun changedChapterUrlsDoNotReportTheWholeDirectoryAsNew() {
        val previous = listOf(MangaChapter("第1话 开始", "https://old/a?sign=old"), MangaChapter("第2话 继续", "https://old/b"))
        val next = listOf(MangaChapter("第１话　开始", "https://new/a?sign=new"), MangaChapter("第2话 继续", "https://new/b"), MangaChapter("第3话 新章", "https://new/c"))
        val result = MangaChapterUpdates.reconcile(previous, next)
        assertEquals(listOf(0, 1, -1), result.previousIndices)
        assertEquals(setOf(2), result.addedIndices)
    }

    @Test fun duplicateChapterTitlesAreConsumedOnlyOnce() {
        val previous = listOf(MangaChapter("番外", "old1"), MangaChapter("番外", "old2"))
        val next = listOf(MangaChapter("番外", "new1"), MangaChapter("番外", "new2"), MangaChapter("番外", "new3"))
        assertEquals(setOf(2), MangaChapterUpdates.reconcile(previous, next).addedIndices)
    }

    @Test fun manualSpreadOverridesDoNotDropOrRepeatPages() {
        val result = MangaPageOverrides.groups(6, false, true, emptySet(), mapOf(1 to MangaPageOverride(layout = "join"), 3 to MangaPageOverride(layout = "single")))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5)), result)
        assertEquals((0..5).toList(), result.flatten())
        assertEquals(listOf(listOf(0), listOf(1)), MangaPageOverrides.groups(2, true, false, emptySet(), mapOf(1 to MangaPageOverride(layout = "single"))))
    }

    @Test fun searchGroupingKeepsDifferentAuthorsSeparate() {
        val a = MangaBook("1", "作品 A", author = "作者甲")
        val b = a.copy(id = "2", sourceId = "second", title = "作品Ａ")
        val c = a.copy(id = "3", author = "作者乙")
        assertEquals(listOf(2, 1), MangaSearchGrouping.groups(listOf(a, b, c)).map { it.size })
    }
}
