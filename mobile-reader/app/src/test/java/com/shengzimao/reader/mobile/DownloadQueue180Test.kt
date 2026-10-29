package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class DownloadQueue180Test {
    @Test fun autoResumeBatchesOnlyInterruptedTasksAndDeduplicates() = runBlocking {
        val dir = Files.createTempDirectory("queue-auto-resume").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = java.util.concurrent.atomic.AtomicInteger()
        try {
            val interrupted = (1..102).map { DownloadTask(title = "b$it", source = "fixture", key = "$it", payload = "{}", status = DownloadStatus.INTERRUPTED) }
            val paused = interrupted[0].copy(id = "paused", key = "paused", status = DownloadStatus.PAUSED)
            val cancelled = paused.copy(id = "cancelled", key = "cancelled", status = DownloadStatus.CANCELLED)
            val duplicate = interrupted[0].copy(id = "duplicate")
            val file = File(dir, "tasks.json").apply { writeText(org.json.JSONArray().also { a -> (interrupted + duplicate + paused + cancelled).forEach { a.put(it.json()) } }.toString()) }
            val queue = DownloadQueue(file, scope, canRun = { false }, onEnqueued = { started.incrementAndGet() }) { _, _ -> error("must wait for network") }
            queue.resumeInterrupted().join()
            assertEquals(99, queue.tasks.value.count { it.status == DownloadStatus.QUEUED })
            assertEquals(100, queue.tasks.value.count { it.active })
            assertEquals(99, queue.tasks.value.filter { it.status == DownloadStatus.QUEUED }.map { it.key }.toSet().size)
            assertEquals(DownloadStatus.INTERRUPTED, queue.tasks.value.first { it.id == "duplicate" }.status)
            assertEquals(DownloadStatus.PAUSED, queue.tasks.value.first { it.id == "paused" }.status)
            assertEquals(DownloadStatus.CANCELLED, queue.tasks.value.first { it.id == "cancelled" }.status)
            assertEquals(1, started.get())
            queue.resumeInterrupted().join()
            assertEquals(1, started.get())
        } finally { scope.cancel(); delay(50); dir.deleteRecursively() }
    }

    @Test fun networkLossKeepsPartialPageCountsAndResumesWhenOnline() = runBlocking {
        val dir = Files.createTempDirectory("queue-network-resume").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val release = CompletableDeferred<Unit>()
        var executions = 0
        try {
            val queue = DownloadQueue(File(dir, "tasks.json"), scope, canRun = { connected.get() }) { _, progress ->
                executions++
                if (executions == 1) { progress(1, 3, "partial"); release.await(); throw java.io.IOException("network lost") }
                progress(3, 3, "complete"); DownloadOutcome("book")
            }
            val task = DownloadTask(title = "book", source = "fixture", key = "book", payload = "{}")
            queue.enqueue(listOf(task))
            withTimeout(3000) { queue.tasks.first { it.any { t -> t.done == 1 } } }
            queue.reportPages(task.id, 1, 3, 1)
            connected.set(false); release.complete(Unit)
            val waiting = withTimeout(3000) { queue.tasks.first { it.any { t -> t.status == DownloadStatus.QUEUED && t.message.contains("网络中断") } } }.first()
            assertEquals(Triple(1, 3, 1), Triple(waiting.pagesDone, waiting.pagesTotal, waiting.failedPages))
            val restored = DownloadTask.parse(waiting.json())
            assertEquals(1, restored.failedPages); assertEquals(3, restored.pagesTotal)
            connected.set(true)
            withTimeout(5000) { queue.tasks.first { it.any { t -> t.status == DownloadStatus.DONE } } }
            assertEquals(2, executions)
        } finally { scope.cancel(); delay(50); dir.deleteRecursively() }
    }

    @Test fun pauseResumeAndCheckpointProgressArePreserved() = runBlocking {
        val dir = Files.createTempDirectory("queue-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var executions = 0
            val queue = DownloadQueue(File(dir, "tasks.json"), scope) { _, progress ->
                executions++; progress(1, 2, "checkpoint saved"); delay(500); DownloadOutcome("book")
            }
            val task = DownloadTask(title = "book", source = "fixture", key = "book", payload = "{}")
            queue.enqueue(listOf(task))
            withTimeout(3000) { queue.tasks.first { it.any { t -> t.done == 1 } } }
            queue.pause(task.id)
            assertEquals(DownloadStatus.PAUSED, queue.tasks.value.first().status)
            assertEquals(1, queue.tasks.value.first().done)
            delay(100)
            queue.retry(task.id)
            withTimeout(3000) { queue.tasks.first { it.any { t -> t.status == DownloadStatus.DONE } } }
            assertEquals(2, executions); assertEquals(2, queue.tasks.value.first().done)
        } finally { scope.cancel(); delay(50); dir.deleteRecursively() }
    }
    @Test fun queuePriorityIsAppliedBeforeTheNextTask() = runBlocking {
        val dir = Files.createTempDirectory("queue-order").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            val queue = DownloadQueue(File(dir, "tasks.json"), scope) { task, _ ->
                order.add(task.key); if (task.key == "1") release.await(); DownloadOutcome(task.key)
            }
            val tasks = (1..3).map { DownloadTask(title = "book$it", source = "fixture", key = "$it", payload = "{}") }
            queue.enqueue(tasks)
            withTimeout(3000) { queue.tasks.first { it.any { t -> t.key == "1" && t.status == DownloadStatus.RUNNING } } }
            queue.prioritize(tasks[2].id); release.complete(Unit)
            withTimeout(3000) { queue.tasks.first { it.count { t -> t.status == DownloadStatus.DONE } == 3 } }
            assertEquals(listOf("1", "3", "2"), order.toList())
        } finally { scope.cancel(); delay(50); dir.deleteRecursively() }
    }
    @Test fun chapterMatchingPrefersStableIdAndMarksFallback() {
        val old = listOf(OnlineChapter("第一章", "https://a/1"), OnlineChapter("第二章", "https://a/2"))
        val same = listOf(OnlineChapter("第一章", "https://b/1"), OnlineChapter("第二章", "https://b/2"))
        val exact = ChapterAnchor.match(old, same, 1)
        assertTrue(exact.exact); assertEquals(1, exact.index)
        assertFalse(ChapterAnchor.match(old, listOf(OnlineChapter("新增合章", "https://c/3")), 1).exact)
    }
}
