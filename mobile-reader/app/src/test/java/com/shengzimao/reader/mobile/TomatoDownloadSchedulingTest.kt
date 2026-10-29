package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.OnlineChapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 番茄下载调度的回归测试。
 *
 * 旧实现「每 4 章一批 + 批间 awaitAll」，在单章较慢时并发收益被批边界吃掉。
 * 新实现用滑动窗口：始终维持 N 个请求在飞，返回即补位。
 * 这里用模拟的 fetch 复刻新调度逻辑，验证吞吐与顺序正确性。
 */
class TomatoDownloadSchedulingTest {

    private fun chapters(n: Int) = (0 until n).map { OnlineChapter("第${it + 1}章", "https://fanqienovel.com/reader/${it + 1}") }

    /** 复刻 TomatoEngine.chapters 的调度，但用可控的模拟 fetch。 */
    private suspend fun runWindow(
        total: Int,
        concurrency: Int,
        fetch: suspend (Int) -> String,
    ): String = coroutineScope {
        val results = arrayOfNulls<String>(total)
        val queue = Channel<Int>(Channel.UNLIMITED)
        for (i in 0 until total) queue.send(i)
        queue.close()
        val workers = (0 until concurrency).map {
            async {
                for (index in queue) results[index] = fetch(index)
            }
        }
        workers.awaitAll()
        results.joinToString("\n\n") { it ?: error("missing") }
    }

    /** 复刻旧的串行分批调度，用于对比。 */
    private suspend fun runBatched(total: Int, batch: Int, fetch: suspend (Int) -> String): String = coroutineScope {
        val text = StringBuilder()
        for (start in 0 until total step batch) {
            val bodies = (start until minOf(start + batch, total)).map { index -> async { fetch(index) } }.awaitAll()
            bodies.forEach { text.append(it).append("\n\n") }
        }
        text.toString()
    }

    @Test
    fun windowKeepsResultsInDirectoryOrderEvenWhenFinishingOutOfOrder() = runTest {
        // 让靠后的章节先返回，验证结果仍按索引落位。
        val text = runWindow(10, 4) { index ->
            delay(if (index >= 6) 1 else 20)
            "BODY$index"
        }
        val order = text.split("\n\n").filter { it.isNotBlank() }
        assertEquals((0 until 10).map { "BODY$it" }, order)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun windowIsFasterThanBatchingWhenOneChapterIsSlow() = runTest {
        val total = 24
        val concurrency = 4
        // 每第 4 章特别慢；旧的批处理会被这一章拖住整批。
        val fetch: suspend (Int) -> String = { index ->
            delay(if (index % 4 == 3) 120 else 10)
            "B$index"
        }
        // runTest skips delay using a virtual clock; wall time measures JVM scheduling/JIT noise.
        val windowStart = testScheduler.currentTime
        runWindow(total, concurrency, fetch)
        val windowMs = testScheduler.currentTime - windowStart
        val batchStart = testScheduler.currentTime
        runBatched(total, concurrency, fetch)
        val batchMs = testScheduler.currentTime - batchStart
        println("window=$windowMs ms, batched=$batchMs ms")
        assertTrue("滑动窗口应快于串行分批：window=$windowMs batched=$batchMs", windowMs < batchMs)
    }

    @Test
    fun concurrencyLimitIsRespected() = runTest {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        runWindow(20, 3) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(5)
            inFlight.decrementAndGet()
            "x"
        }
        assertTrue("并发不应超过上限 3，实际峰值 ${peak.get()}", peak.get() <= 3)
        assertEquals("应确实用满 3 路并发", 3, peak.get())
    }

    @Test
    fun allChaptersAreFetchedExactlyOnce() = runTest {
        val hits = AtomicInteger(0)
        runWindow(50, 4) { hits.incrementAndGet(); "b" }
        assertEquals(50, hits.get())
    }

}
