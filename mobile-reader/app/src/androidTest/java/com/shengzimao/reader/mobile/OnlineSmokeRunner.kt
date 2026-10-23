package com.shengzimao.reader.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.shengzimao.reader.mobile.data.SonovelEngine
import com.shengzimao.reader.mobile.data.TomatoEngine
import com.shengzimao.reader.mobile.data.Updates
import com.shengzimao.reader.mobile.data.LibraryStore
import com.shengzimao.reader.mobile.data.Library
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.OnlineChapterCodec
import android.content.ContextWrapper
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import kotlinx.coroutines.runBlocking

/** 独立测试包运行真实 ART/dex 初始化；不读取或修改用户的发布版书库。 */
class OnlineSmokeRunner : Instrumentation() {
    private var liveNetwork = false
    private val report = StringBuilder()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        liveNetwork = arguments?.getString("network") == "true"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val engine = SonovelEngine(targetContext)
            if (!engine.available) throw IllegalStateException(engine.loadError ?: "engine unavailable", engine.loadFailure)
            val probe = engine.probe()
            check(probe.getInt("sources") > 0) { "no sources loaded" }
            record("ENGINE_OK $probe")
            if (liveNetwork) runBlocking {
                val tomato = TomatoEngine()
                val books = tomato.search("我不是戏神")
                val book = books.first { it.url == "https://fanqienovel.com/page/7276384138653862966" }
                check(book.author == "三九音域") { "unexpected author: ${book.author}" }
                record("TOMATO_SEARCH_OK ${books.size} results, ${book.bookName} / ${book.author}")
                val details = tomato.details(book.url)
                check(details.toc.size > 1000 && details.book.intro.isNotBlank()) { "invalid metadata or directory" }
                record("TOMATO_DETAILS_OK ${details.chapterCount} chapters, ${details.book.category}")
            }
            syntheticSource()
            output.putString("stream", report.toString())
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            val engineStack = (error as? com.shengzimao.reader.mobile.data.OnlineEngineException)?.engineStack.orEmpty()
            output.putString("stream", "${report}SMOKE_FAILED ${android.util.Log.getStackTraceString(error)}\n$engineStack\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun record(message: String) {
        report.appendLine(message)
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }

    private fun syntheticSource() {
        val sandbox = File(targetContext.cacheDir, "online-smoke-${System.nanoTime()}").apply { mkdirs() }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val request = it.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?')
                    val html = when (path) {
                        "/search" -> """<div class="result"><a class="name" href="$base/book/1"> 合成测试小说 </a><span class="author">合成作者</span></div>"""
                        "/book/1" -> """<h1>合成测试小说</h1><span class="author">合成作者</span><p class="intro">测试专用原创短文</p><div id="toc"><a href="$base/chapter/1">第一章 开始</a><a href="$base/chapter/2">第二章 结束</a></div>"""
                        "/chapter/1" -> "<h1>第一章 开始</h1><div id=\"content\"><p>这是自动化验证生成的第一章正文。</p></div>"
                        "/chapter/2" -> "<h1>第二章 结束</h1><div id=\"content\"><p>这是自动化验证生成的第二章正文。</p></div>"
                        else -> "<p>unknown fixture</p>"
                    }
                    val bytes = "<html><meta charset=\"UTF-8\">$html</html>".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val rules = File(sandbox, "fixture-rules.json")
            rules.writeText("""[{"url":"$base/","name":"本地合成站点","needProxy":true,"language":"zh-CN","search":{"url":"$base/search?q=%s","method":"get","result":".result","bookName":".name@js:r=r.trim()","author":".author"},"book":{"url":"/book/(\\d+)","bookName":"h1","author":".author","intro":".intro"},"toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content","paragraphTagClosed":true,"filterTxt":"(?!)","filterTag":"script"},"crawl":{"minInterval":0,"maxInterval":0,"concurrency":1}}]""")
            val context = object : ContextWrapper(targetContext) { override fun getFilesDir() = sandbox }
            val engine = SonovelEngine(context, rules)
            if (!engine.available) throw IllegalStateException(engine.loadError, engine.loadFailure)
            val result = engine.search("合成测试小说")
            check(result.length() == 1) { "fixture search returned $result" }
            check(result.getJSONObject(0).getString("bookName") == "合成测试小说") { "JS rule bridge failed" }
            record("AGGREGATE_HTTP_SEARCH_OK Rhino rule + local HTTP source")
            val details = Updates.validateDetails(engine.details("$base/book/1"))
            check(details.chapterCount == 2) { "fixture directory mismatch" }
            val downloaded = engine.download("$base/book/1", "txt")
            try {
                check(downloaded.metadata?.isFile == true) { "download metadata not in job directory" }
                val text = downloaded.text.readText()
                check(text.contains("第一章") && text.contains("第二章")) { "incomplete fixture download" }
                check(text.contains("自动化验证生成的第一章正文") && text.contains("自动化验证生成的第二章正文")) { "chapter body missing or error placeholder imported" }
                val store = LibraryStore(File(sandbox, "fixture-library"))
                val file = store.writeText("fixture-book", text)
                val chapters = Updates.mapOnlineChapters(text, details.toc)
                check(chapters.size == 2)
                val book = Book(id = "fixture-book", title = details.book.bookName, file = file,
                    chapters = chapters, onlineUrl = details.book.url, onlineManifest = OnlineChapterCodec.encode(details.toc))
                store.save(Library(books = listOf(book)))
                check(store.load().books.single().onlineUrl == "$base/book/1")
                check(store.readText(book) == text)
                record("AGGREGATE_DETAILS_DOWNLOAD_IMPORT_OK 2 synthetic chapters, private test library")
            } finally { downloaded.cleanup() }
            val epub = engine.download("$base/book/1", "epub")
            try {
                val parsed = com.shengzimao.reader.mobile.data.EpubParser.parse(epub.text.readBytes(), "合成测试小说")
                check(parsed.text.contains("自动化验证生成的第一章正文") && parsed.text.contains("自动化验证生成的第二章正文"))
                record("AGGREGATE_EPUB_OK native export and import preserves both chapter bodies")
            } finally { epub.cleanup() }
        } finally {
            server.close()
            worker.join(1000)
            sandbox.deleteRecursively()
        }
    }
}
