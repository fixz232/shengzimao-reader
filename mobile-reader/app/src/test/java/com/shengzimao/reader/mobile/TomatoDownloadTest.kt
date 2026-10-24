package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.OnlineChapter
import com.shengzimao.reader.mobile.data.TomatoEngine
import com.shengzimao.reader.mobile.data.TomatoParser
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class TomatoDownloadTest {
    private fun chapters(count: Int) = (1..count).map { OnlineChapter("第${it}章", "https://fanqienovel.com/reader/$it") }
    private fun response(request: Request, html: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(html.toResponseBody()).build()

    @Test fun boundedParallelDownloadKeepsDirectoryOrderAndMonotonicProgressAndSavesTime() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val finishOrder = Collections.synchronizedList(mutableListOf<Int>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            val id = chain.request().url.pathSegments.last().toInt()
            try {
                Thread.sleep(if (id % 4 == 1) 700 else 300)
                finishOrder.add(id)
                response(chain.request(), "<div class='muye-reader-content'><p>原创正文${id}。</p></div>")
            } finally { active.decrementAndGet() }
        }.build()
        try {
            val toc = chapters(8)
            val progress = mutableListOf<Int>()
            var downloaded = ""
            val parallelMs = measureTimeMillis { downloaded = TomatoEngine(client).chapters(toc) { done, total ->
                assertEquals(8, total)
                progress.add(done)
            } }
            assertEquals((1..8).toList(), progress)
            assertTrue("peak=$peak", peak.get() in 2..4)
            assertNotEquals((1..8).toList(), finishOrder.toList())
            for (id in 1..7) assertTrue(downloaded.indexOf("原创正文$id。") < downloaded.indexOf("原创正文${id + 1}。"))
            assertEquals(8, Regex("原创正文").findAll(downloaded).count())
            val serialMs = measureTimeMillis {
                for ((index, chapter) in toc.withIndex()) {
                    if (index > 0) delay(200) // Previous mobile downloader's per-chapter delay.
                    client.newCall(Request.Builder().url(chapter.url).build()).execute().use {
                        assertTrue(TomatoParser.chapterHtml(it.body.string(), chapter.title).isNotBlank())
                    }
                }
            }
            println("TOMATO_DOWNLOAD_BENCHMARK chapters=8 serialMs=$serialMs parallelMs=$parallelMs peak=${peak.get()}")
            assertTrue("serial=$serialMs parallel=$parallelMs", parallelMs < serialMs * 0.8)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun missingBodyFailsWholeDownloadAndDoesNotStartLaterBatches() = runBlocking {
        val requested = Collections.synchronizedList(mutableListOf<Int>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.pathSegments.last().toInt()
            requested.add(id)
            response(chain.request(), if (id == 2) "<article><h1>第2章</h1><nav>目录</nav></article>"
                else "<article><p>原创正文$id。</p></article>")
        }.build()
        try {
            val result = runCatching { TomatoEngine(client).chapters(chapters(12)) }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("没有可读取"))
            assertTrue(requested.all { it <= 4 })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun cancellationCancelsCallsAndStopsStartingChapters() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<okhttp3.Call>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.add(chain.call())
            Thread.sleep(450)
            response(chain.request(), "<article><p>原创测试正文。</p></article>")
        }.build()
        try {
            val download = async { TomatoEngine(client).chapters(chapters(12)) }
            while (calls.isEmpty()) delay(10)
            download.cancelAndJoin()
            val before = calls.size
            delay(550)
            assertTrue(download.isCancelled)
            assertEquals(before, calls.size)
            assertTrue(calls.all { it.isCanceled() })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
