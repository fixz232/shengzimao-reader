package com.shengzimao.reader.mobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

enum class MangaImagePriority { VISIBLE, NEARBY, PREFETCH, DOWNLOAD, COVER }

internal class MangaImageAddressException(message: String, val status: Int = 0) : java.io.IOException(message)

/** A page has a stable identity even when its signed image URL changes. */
data class MangaImageContext(val book: String, val chapter: String, val page: Int, val rules: String = "", val image: String = "") {
    fun key() = "$book\u0000$chapter\u0000$page\u0000$rules\u0000$image"
    companion object {
        fun of(book: MangaBook, chapter: MangaChapter, page: Int) =
            MangaImageContext(book.url.ifBlank { book.id }, chapter.cacheIdentity.ifBlank { chapter.url }, page, chapter.imageRuleVersion,
                stableImage(chapter.pages.getOrNull(page).orEmpty()))
        internal fun stableImage(url: String): String = runCatching {
            val uri = java.net.URI(url)
            val volatile = Regex("(?i)^(sign|signature|token|expires|exp|ts|timestamp|auth_key|policy|key-pair-id|x-amz-.*|x-oss-.*)$")
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && !volatile.matches(it.substringBefore('=')) }.sorted().joinToString("&")
            "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}" + if (query.isBlank()) "" else "?$query"
        }.getOrDefault(url)
    }
}

/** Bounded queue shared by reading, prefetch and downloads. Running requests are not discarded. */
internal class MangaImageScheduler(private val parallelism: Int = 3) {
    private class Waiter(val priority: MangaImagePriority, val host: String, val order: Long) {
        val ready = CompletableDeferred<Lease>()
        var lease: Lease? = null
    }
    internal class Lease(private val release: () -> Unit) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    private val waiting = mutableListOf<Waiter>()
    private val running = mutableSetOf<Waiter>()
    private var sequence = 0L
    private var reading = false

    @Synchronized fun reading(active: Boolean) { reading = active; pump() }

    private fun pump() {
        while (running.size < parallelism) {
            val next = waiting.sortedWith(compareBy<Waiter> { it.priority.ordinal }.thenBy { it.order }).firstOrNull { candidate ->
                val background = candidate.priority >= MangaImagePriority.PREFETCH
                val backgroundCount = running.count { it.priority >= MangaImagePriority.PREFETCH }
                (!background || backgroundCount < if (reading) 1 else (parallelism - 1).coerceAtLeast(1)) &&
                    running.count { it.host == candidate.host } < 2
            } ?: break
            waiting.remove(next)
            running.add(next)
            val lease = Lease { synchronized(this) { running.remove(next); pump() } }
            next.lease = lease
            next.ready.complete(lease)
        }
    }

    private suspend fun acquire(priority: MangaImagePriority, host: String): Lease {
        val waiter = synchronized(this) { Waiter(priority, host, sequence++).also { waiting.add(it); pump() } }
        return try { waiter.ready.await() } catch (cancelled: CancellationException) {
            synchronized(this) { waiting.remove(waiter); waiter.lease?.close(); pump() }
            throw cancelled
        }
    }

    suspend fun <T> withPermit(priority: MangaImagePriority, host: String, block: suspend () -> T): T {
        val lease = acquire(priority, host)
        return try { block() } finally { lease.close() }
    }
}

/** Measurements exclude queueing and disk hits, so cached reads cannot lower the estimate to zero. */
internal class MangaLoadingTimes {
    private val values = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
    }
    @Synchronized fun record(source: String, millis: Long) {
        val sample = millis.coerceIn(100, 60_000)
        values[source] = values[source]?.let { (it * 3 + sample) / 4 } ?: sample
    }
    @Synchronized fun estimate(source: String): Long = values[source] ?: 3000L
}
