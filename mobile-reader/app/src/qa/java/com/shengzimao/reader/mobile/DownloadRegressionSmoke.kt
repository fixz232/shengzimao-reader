package com.shengzimao.reader.mobile

import android.content.Context
import android.content.ContextWrapper
import com.shengzimao.reader.mobile.data.SonovelEngine
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** QA 变体内的原创 HTTP 书源：验证持续补位、并发上限、目录快照与正文顺序。 */
fun downloadRegressionSmoke(context: Context, record: (String) -> Unit) {
    for (limit in listOf(4, 1)) {
        val sandbox = File(context.cacheDir, "pipeline-$limit-${System.nanoTime()}").apply { mkdirs() }
        val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val handlers = Executors.newFixedThreadPool(4)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val chapterRequests = AtomicInteger()
        val bookRequests = AtomicInteger()
        val fifthStart = AtomicLong()
        val fourthEnd = AtomicLong()
        val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                handlers.submit { socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty().substringBefore('?')
                    while (true) { if (reader.readLine().isNullOrEmpty()) break }
                    val number = path.substringAfterLast('/').toIntOrNull() ?: 0
                    val chapter = path.startsWith("/chapter/")
                    if (chapter) {
                        chapterRequests.incrementAndGet()
                        val current = active.incrementAndGet()
                        peak.updateAndGet { value -> maxOf(value, current) }
                        if (number == 5) fifthStart.set(System.nanoTime())
                        Thread.sleep(if (number == 4 || number == 5) 700 else 60)
                        if (number == 4) fourthEnd.set(System.nanoTime())
                    } else bookRequests.incrementAndGet()
                    val content = if (chapter) "<h1>第${number}章</h1><div id=\"content\">这是第${number}章的原创回归测试正文，包含完整内容。</div>"
                        else "<h1>流水线测试书</h1><span class=\"author\">原创测试</span><div id=\"toc\">" +
                            (1..8).joinToString("") { i -> "<a href=\"$base/chapter/$i\">第${i}章</a>" } + "</div>"
                    val bytes = "<html><meta charset=\"UTF-8\">$content</html>".toByteArray()
                    if (chapter) active.decrementAndGet()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } }
            }
        }.apply { isDaemon = true; start() }
        try {
            val rules = File(sandbox, "rules.json")
            rules.writeText("""[{"url":"$base/","name":"原创流水线","needProxy":false,"language":"zh-CN","book":{"url":"/book/(\\d+)","bookName":"h1","author":".author"},"toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content","paragraphTagClosed":true,"filterTxt":"(?!)","filterTag":"script"},"crawl":{"minInterval":0,"maxInterval":0,"concurrency":$limit,"maxAttempts":1,"retryMinInterval":0,"retryMaxInterval":0}}]""")
            val isolated = object : ContextWrapper(context) { override fun getFilesDir() = sandbox }
            val engine = SonovelEngine(isolated, rules)
            check(engine.available) { engine.loadError.orEmpty() }
            val started = System.nanoTime()
            val result = engine.download("$base/book/1", "txt")
            try {
                val text = result.text.readText()
                var previous = -1
                for (i in 1..8) {
                    val offset = text.indexOf("第${i}章")
                    check(offset > previous && text.contains("这是第${i}章的原创回归测试正文")) { "chapter order or body lost" }
                    previous = offset
                }
                check(peak.get() <= limit && peak.get() > 0) { "source concurrency not respected: ${peak.get()} > $limit" }
                check(bookRequests.get() == 2) { "book/directory downloaded more than once: ${bookRequests.get()} requests" }
                if (limit == 4) check(fifthStart.get() in 1 until fourthEnd.get()) { "next chapter waited for the entire previous batch" }
                record("AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=$limit peak=${peak.get()} bookRequests=${bookRequests.get()} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                val beforeRetry = chapterRequests.get()
                val resumed = engine.download("$base/book/1", "txt")
                check(chapterRequests.get() == beforeRetry && resumed.text.readText() == text) { "verified checkpoint chapters were downloaded again" }
                val part = File(result.job, "downloads/chapter-checkpoints").listFiles()?.firstOrNull { it.extension == "txt" }
                    ?: result.job.walkTopDown().first { it.extension == "txt" && it.parentFile?.name == "chapter-checkpoints" }
                part.writeText("corrupted")
                val repaired = engine.download("$base/book/1", "txt")
                check(chapterRequests.get() == beforeRetry + 1 && repaired.text.readText() == text) { "corrupt checkpoint was reused or all chapters downloaded again" }
                record("AGGREGATE_CHECKPOINT_OK retry uses zero requests; corrupted chapter re-fetches exactly once")
            } finally { result.cleanup() }
        } finally {
            server.close(); worker.join(1000); handlers.shutdownNow(); sandbox.deleteRecursively()
        }
    }
}
