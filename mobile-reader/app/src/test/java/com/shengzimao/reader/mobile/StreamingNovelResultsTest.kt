package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.OnlineResult
import com.shengzimao.reader.mobile.data.StreamingNovelResults
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamingNovelResultsTest {
    private fun book(id: String, title: String = "三国演义", group: Int = 2, source: String = "https://source.invalid") =
        OnlineResult(bookName = title, author = "罗贯中", url = "https://books.invalid/$id", sourceGroup = group, sourceKey = source)

    @Test fun fastSourceAppearsWhileSlowSourceIsStillBlocked() {
        val results = StreamingNovelResults("三国演义")
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val slow = worker.submit<List<OnlineResult>> {
                slowStarted.countDown()
                check(releaseSlow.await(5, TimeUnit.SECONDS))
                results.append(listOf(book("slow")))
            }
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("https://books.invalid/fast"), results.append(listOf(book("fast"))).map { it.url })
            assertFalse(slow.isDone)
            releaseSlow.countDown()
            assertEquals(listOf("https://books.invalid/fast", "https://books.invalid/slow"), slow.get(2, TimeUnit.SECONDS).map { it.url })
        } finally { releaseSlow.countDown(); worker.shutdownNow() }
    }

    @Test fun laterExactMatchDoesNotReorderOrRemoveEarlierRelevantCards() {
        val results = StreamingNovelResults("三国演义")
        val first = results.append(listOf(book("first", "三国演义续篇")))
        val later = results.append(listOf(book("exact")))
        assertEquals(first.single(), later.first())
        assertEquals(listOf("https://books.invalid/first", "https://books.invalid/exact"), later.map { it.url })
        assertTrue(later.last().matchScore > later.first().matchScore)
    }

    @Test fun finalBatchAndRepeatedResponsesDoNotDuplicateCards() {
        val results = StreamingNovelResults("三国演义")
        results.append(listOf(book("first")))
        results.append(listOf(book("second")))
        assertEquals(listOf("https://books.invalid/first", "https://books.invalid/second"),
            results.append(listOf(book("second"), book("first"))).map { it.url })
    }

    @Test fun emptyFailedOrUnrelatedResponseKeepsVisibleResults() {
        val results = StreamingNovelResults("三国演义")
        val first = results.append(listOf(book("first")))
        assertEquals(first, results.append(emptyList()))
        assertEquals(first, results.append(listOf(book("unrelated", "西游记"))))
    }

    @Test fun matchingAddressInDifferentSourcesOrGroupsRemainsSeparate() {
        val results = StreamingNovelResults("三国演义")
        assertEquals(3, results.append(listOf(book("one"), book("one", group = 3), book("one", source = "https://other.invalid"))).size)
    }

    @Test fun capKeepsFirstArrivalsAndAllowsExistingMetadataToUpdate() {
        val results = StreamingNovelResults("三国演义", limit = 2)
        val original = book("one")
        results.append(listOf(original))
        results.append(listOf(book("two"), book("three")))
        val updated = results.append(listOf(original.copy(latestChapter = "新增章节")))
        assertEquals(2, updated.size)
        assertEquals("新增章节", updated.first().latestChapter)
        assertEquals("https://books.invalid/two", updated.last().url)
    }
}
