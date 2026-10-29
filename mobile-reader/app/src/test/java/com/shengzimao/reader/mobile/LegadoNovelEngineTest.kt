package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LegadoNovelEngineTest {
    private fun source(): JSONObject = JSONObject().put("bookSourceName", "测试小说源")
        .put("bookSourceUrl", "https://novels.invalid")
        .put("searchUrl", "/search?q={{key}}&page={{page}}")
        .put("ruleSearch", JSONObject().put("bookList", ".book").put("name", "a.0@text")
            .put("author", "span.0@text").put("bookUrl", "a.0@href"))
        .put("ruleBookInfo", JSONObject().put("name", "h1@text").put("tocUrl", ".toc@href"))
        .put("ruleToc", JSONObject().put("chapterList", ".chapters a").put("chapterName", "text").put("chapterUrl", "href")
            .put("nextTocUrl", ".next@href"))
        .put("ruleContent", JSONObject().put("content", "#content@html").put("nextContentUrl", ".next@href"))

    private fun <T> fixture(raw: JSONObject = source(), fetch: (NovelRequest) -> NovelPage, test: (LegadoNovelEngine, File) -> T): T {
        val dir = Files.createTempDirectory("legado-novel-test").toFile()
        try { return test(LegadoNovelEngine(NovelLegadoRules.normalize(raw), dir, fetch), dir) }
        finally { dir.deleteRecursively() }
    }

    private fun page(request: NovelRequest, text: String) = NovelPage(request.url, text)
    private val searchHtml = "<div class=book><a href='/book/1'>三国演义</a><span>罗贯中</span></div>"
    private val detailHtml = "<h1>三国演义</h1><a class=toc href='/toc/1'>目录</a>"
    private val tocHtml = "<div class=chapters><a href='/chapter/1'>第一回</a><a href='/chapter/2'>第二回</a></div>"

    @Test fun modernHtmlSearchResolvesLinksAndKeepsSourceIdentity() = fixture(fetch = { req ->
        assertTrue(req.url.contains("page=1")); page(req, searchHtml)
    }) { engine, _ ->
        val result = engine.search("三国演义").single()
        assertEquals("三国演义", result.bookName)
        assertEquals("罗贯中", result.author)
        assertEquals("https://novels.invalid/book/1", result.url)
        assertEquals("https://novels.invalid", result.sourceKey)
    }

    @Test fun modernJsonAndJavascriptSearchAreEvaluated() {
        val raw = source().put("searchUrl", "/api, {\"method\":\"POST\",\"body\":\"q={{key}}\"}")
            .put("ruleSearch", JSONObject().put("bookList", "$.items").put("name", "$.title@js:result + '·测试'")
                .put("bookUrl", "$.link").put("author", "$.author"))
        fixture(raw, { req ->
            assertEquals("POST", req.method); assertTrue(req.body!!.startsWith("q="))
            page(req, """{"items":[{"title":"三国演义","link":"/book/1","author":"罗贯中"}]}""")
        }) { engine, _ -> assertEquals("三国演义·测试", engine.search("三国演义").single().bookName) }
    }

    @Test fun searchKeywordsAreEncodedInUrlsAndFormBodies() {
        val query = "三国 & 演义+外传"
        val raw = source().put("searchUrl", "/search?q={{key}}, {\"method\":\"POST\",\"body\":\"q={{key}}\"}")
        fixture(raw, { req ->
            assertEquals(query, req.url.toHttpUrl().queryParameter("q"))
            assertEquals(query, java.net.URLDecoder.decode(req.body!!.substringAfter("q="), "UTF-8"))
            page(req, searchHtml)
        }) { engine, _ -> assertEquals(1, engine.search(query).size) }
    }

    @Test fun jsonRequestBodyKeepsLiteralKeywordAndEscapesQuotes() {
        val query = "三国 \"演义\" & +"
        val raw = source().put("searchUrl", "/search, {\"method\":\"POST\",\"body\":{\"query\":\"{{key}}\"},\"headers\":{\"Content-Type\":\"application/json\"}}")
        fixture(raw, { req ->
            assertEquals(query, JSONObject(req.body!!).getString("query")); page(req, searchHtml)
        }) { engine, _ -> assertEquals(1, engine.search(query).size) }
    }

    @Test fun spacedKeyTemplatesWorkInGetAndFormRequests() {
        val query = "德萨罗人鱼 & + $1 \\ 尾声"
        listOf("/search?q={{ key }}&page={{ page }}",
            "/search?q={{  key  }}, {\"method\":\"POST\",\"body\":\"q={{ key }}\"}").forEach { template ->
            fixture(source().put("searchUrl", template), { req ->
                assertEquals(query, req.url.toHttpUrl().queryParameter("q"))
                if (req.method == "POST") assertEquals(query,
                    java.net.URLDecoder.decode(req.body!!.substringAfter("q="), "UTF-8"))
                else assertEquals("1", req.url.toHttpUrl().queryParameter("page"))
                page(req, searchHtml)
            }) { engine, _ -> assertEquals(1, engine.search(query).size) }
        }
    }

    @Test fun storedVariableRulesPreserveLiteralValuesAndCanFeedJavascript() {
        val runtime = LegacyRuleRuntime(mutableMapOf("saved_title" to "德萨罗人鱼 $1 \\ 尾声")).apply { novelMode = true }
        assertEquals(listOf("德萨罗人鱼 $1 \\ 尾声"), runtime.evaluate("@get:{saved_title}", "", "https://n.invalid"))
        assertEquals(listOf("德萨罗人鱼 $1 \\ 尾声·书"), runtime.evaluate("@get:{saved_title}@js:result + '·书'", "", "https://n.invalid"))
    }

    @Test fun detailsFollowSeparateAndPaginatedToc() = fixture(fetch = { req ->
        page(req, when (req.url.substringAfter("novels.invalid")) {
            "/book/1" -> detailHtml
            "/toc/1" -> "<div class=chapters><a href='/chapter/1'>第一回</a></div><a class=next href='/toc/2'>下页</a>"
            "/toc/2" -> "<div class=chapters><a href='/chapter/2'>第二回</a></div>"
            else -> error("Unexpected URL ${req.url}")
        })
    }) { engine, _ ->
        val details = engine.details("https://novels.invalid/book/1")
        assertEquals("三国演义", details.book.bookName)
        assertEquals(listOf("第一回", "第二回"), details.toc.map { it.title })
        assertEquals(details.toc.last().url, details.toc.first().nextUrl)
        assertEquals(details.toc.first().url, details.toc.last().previousUrl)
        assertEquals(details.toc, OnlineChapterCodec.decode(OnlineChapterCodec.encode(details.toc)))
    }

    @Test fun detailsReadsEveryAdvertisedTocPageInsteadOfOnlyTheFirstLink() = fixture(fetch = { req ->
        page(req, when (req.url.substringAfter("novels.invalid")) {
            "/book/1" -> detailHtml
            "/toc/1" -> "<div class=chapters><a href='/chapter/1'>第一回</a></div><a class=next href='/toc/2'>2</a><a class=next href='/toc/3'>3</a><a class=next href='/toc/2'>2</a>"
            "/toc/2" -> "<div class=chapters><a href='/chapter/2'>第二回</a></div><a class=next href='/toc/3'>3</a>"
            "/toc/3" -> "<div class=chapters><a href='/chapter/3'>第三回</a></div><a class=next href='/toc/1'>1</a>"
            else -> error("Unexpected URL ${req.url}")
        })
    }) { engine, _ -> assertEquals(listOf("第一回", "第二回", "第三回"), engine.details("https://novels.invalid/book/1").toc.map { it.title }) }

    @Test fun plusPrefixedTocListMatchesUpstream() {
        val raw = source().also { it.getJSONObject("ruleToc").put("chapterList", "+.chapters a") }
        fixture(raw, { req -> page(req, "<h1>三国演义</h1>$tocHtml") }) { engine, _ ->
            assertEquals(listOf("第一回", "第二回"), engine.details("https://novels.invalid/book/1").toc.map { it.title })
        }
    }

    @Test fun chapterReadsEveryAdvertisedContentPageWithoutEnteringNextChapter() = fixture(fetch = { req ->
        page(req, when {
            req.url.endsWith("/chapter/1") -> "<div id=content>开头段。</div><a class=next href='/chapter/1_2'>2</a><a class=next href='/chapter/1_3'>3</a><a class=next href='/chapter/2'>下一章</a>"
            req.url.endsWith("/chapter/1_2") -> "<div id=content>中间段。</div><a class=next href='/chapter/1_3'>3</a>"
            req.url.endsWith("/chapter/1_3") -> "<div id=content>结尾段。</div>"
            else -> error("Must not fetch adjacent chapter ${req.url}")
        })
    }) { engine, _ ->
        val body = engine.chapter("https://novels.invalid/book/1", OnlineChapter("第一回", "https://novels.invalid/chapter/1", nextUrl = "https://novels.invalid/chapter/2"))
        assertTrue(body.indexOf("开头段") < body.indexOf("中间段"))
        assertTrue(body.indexOf("中间段") < body.indexOf("结尾段"))
        assertEquals(1, Regex("结尾段").findAll(body).count())
    }

    @Test fun importedRulesCanSearchOpenDirectoryAndReadContentEndToEnd() {
        val dir = Files.createTempDirectory("novel-import-read").toFile()
        try {
            val store = NovelSourceGroups(File(dir, "sources")) { "[]" }
            assertEquals(1, store.import(2, JSONArray().put(source()).toString()).added)
            assertEquals(1, store.searchEntries(2).size)
            val requests = AtomicInteger()
            val engine = LegadoNovelEngine(store.rules(2).getJSONObject(0), File(dir, "engine")) { req ->
                requests.incrementAndGet()
                page(req, when {
                    req.url.contains("/search?") -> searchHtml
                    req.url.endsWith("/book/1") -> detailHtml
                    req.url.endsWith("/toc/1") -> tocHtml
                    req.url.endsWith("/chapter/1") -> "<div id=content><p>这是第一回的正文。</p></div>"
                    req.url.endsWith("/chapter/2") -> "<div id=content><p>这是第二回的正文。</p></div>"
                    else -> error("Unexpected URL ${req.url}")
                })
            }
            val result = engine.search("三国演义").single()
            val detail = engine.details(result.url)
            assertEquals(2, detail.toc.size)
            assertTrue(engine.chapter(result.url, detail.toc.first()).contains("这是第一回的正文。"))
            assertEquals(detail.toc, OnlineChapterCodec.decode(OnlineChapterCodec.encode(detail.toc)))
            val cache = ChapterDownloadCache(File(dir, "download-parts"))
            val downloaded = runBlocking { engine.chapters(result.url, detail.toc, cache, 2) { _, _, _ -> } }
            assertTrue(downloaded.contains("这是第一回的正文。"))
            assertTrue(downloaded.contains("这是第二回的正文。"))
            assertTrue(downloaded.indexOf("第一回") < downloaded.indexOf("第二回"))
            val beforeCacheRead = requests.get()
            assertEquals(downloaded, runBlocking { engine.chapters(result.url, detail.toc, cache, 2) { _, _, _ -> } })
            assertEquals(beforeCacheRead, requests.get())
        } finally { dir.deleteRecursively() }
    }

    @Test fun chapterKeepsParagraphsAndExcludesAdjacentChapters() = fixture(fetch = { req ->
        page(req, when {
            req.url.endsWith("/chapter/1") -> "<div id=content><p>第一段。</p><p>第二段。<br>第三行。</p></div><a class=next href='/chapter/1_2'>下一页</a>"
            req.url.endsWith("/chapter/1_2") -> "<div id=content>后半章。</div><a class=next href='/chapter/1'>上一页</a><a class=next href='/chapter/2'>下一章</a>"
            else -> error("Must not fetch adjacent chapter: ${req.url}")
        })
    }) { engine, _ ->
        val text = engine.chapter("https://novels.invalid/book/1", OnlineChapter("第一回", "https://novels.invalid/chapter/1", nextUrl = "https://novels.invalid/chapter/2"))
        assertTrue(text.startsWith("第一回\n\n")); assertTrue(text.contains("第一段。\n"))
        assertTrue(text.contains("第二段。\n第三行。")); assertTrue(text.contains("后半章。"))
    }

    @Test fun plainTextAndEntityContentKeepNewlines() {
        val runtime = LegacyRuleRuntime().apply { novelMode = true }
        assertEquals(listOf("甲\n乙"), runtime.evaluate("#body@text", "<div id=body>甲<br>乙</div>", "https://n.invalid"))
        assertEquals(listOf("甲\n乙"), runtime.evaluate("##广告", "甲\n广告乙", "https://n.invalid"))
    }

    @Test fun emptyTerminalTemplateReturningToDirectoryDoesNotDiscardEarlierPages() = fixture(fetch = { req ->
        page(req, if (req.url.endsWith("_2"))
            "<div id=content><script>void(0)</script><br>&nbsp;　</div><a class=next href='/book/1'>返回目录</a>"
        else "<div id=content>已获取的正文。</div><a class=next href='/chapter/1_2'>下一页</a>")
    }) { engine, _ ->
        assertTrue(engine.chapter("https://novels.invalid/book/1", OnlineChapter("第一回", "https://novels.invalid/chapter/1")).contains("已获取的正文。"))
    }

    @Test fun defaultAttributeAndIndexedSelectorsWork() {
        val runtime = LegacyRuleRuntime().apply { novelMode = true }
        val html = "<table><tbody><tr><td>一</td><td>二</td></tr></tbody></table>"
        assertEquals(listOf("二"), runtime.evaluate("table@tbody@tr@td.1@text", html, "https://n.invalid"))
        assertEquals(listOf("/book"), runtime.evaluate("href", "<a href=/book>书</a>", "https://n.invalid"))
        assertEquals(listOf("两种类名"), runtime.evaluate("class.first second@text", "<div class='first second'>两种类名</div>", "https://n.invalid"))
    }

    @Test fun putGetAndJavascriptLibraryWorkInBookInfo() {
        val raw = source().put("jsLib", "function formatTitle(v){ return v + '·书'; }")
            .put("ruleBookInfo", JSONObject().put("init", "@put:{title:\"h1@text\"}").put("name", "@get:{title}@js:formatTitle(result)"))
        fixture(raw, { req -> page(req, "<h1>三国演义</h1>$tocHtml") }) { engine, _ ->
            assertEquals("三国演义·书", engine.details("https://novels.invalid/book/1").book.bookName)
        }
    }

    @Test fun chapterVariablesSurviveDirectorySerializationAndEngineReopen() {
        val raw = source().put("ruleBookInfo", JSONObject()).also {
            it.getJSONObject("ruleToc").put("chapterUrl", "@put:{cid:\"data-id\"}href")
            it.getJSONObject("ruleContent").put("content", "@js:'章节ID：' + java.get('cid')")
        }
        fixture(raw, { req -> page(req, "<div class=chapters><a href='/chapter/1' data-id='one'>第一回</a><a href='/chapter/2' data-id='two'>第二回</a></div>") }) { engine, dir ->
            val toc = engine.details("https://novels.invalid/book/1").toc
            val reopened = LegadoNovelEngine(NovelLegadoRules.normalize(raw), dir) { req -> page(req, "anything") }
            assertTrue(reopened.chapter("https://novels.invalid/book/1", toc[0]).contains("one"))
            assertTrue(reopened.chapter("https://novels.invalid/book/1", toc[1]).contains("two"))
        }
    }

    @Test fun oldFormatCanSearchAndReadText() {
        val old = JSONObject().put("bookSourceName", "旧式小说源").put("bookSourceUrl", "https://novels.invalid")
            .put("ruleSearchUrl", "/search@q=searchKey&page=searchPage").put("ruleSearchList", ".book")
            .put("ruleSearchName", "a@text").put("ruleSearchNoteUrl", "a@href")
            .put("ruleChapterList", ".chapters a").put("ruleChapterName", "text").put("ruleContentUrl", "href")
            .put("ruleBookContent", "#content@html")
        fixture(old, { req -> page(req, if (req.method == "POST") searchHtml else "<div id=content>内容<br>下一段</div>") }) { engine, _ ->
            assertEquals("三国演义", engine.search("三国演义").single().bookName)
            assertTrue(engine.chapter("https://novels.invalid/book/1", OnlineChapter("一", "https://novels.invalid/chapter/1")).contains("内容\n下一段"))
        }
    }

    @Test fun sourceAndGroupIsolationSurviveSerialization() {
        val result = OnlineResult(bookName = "三国演义", sourceGroup = 4, sourceKey = "https://novels.invalid", url = "https://novels.invalid/book/1")
        assertEquals(result, OnlineResult.fromJson(result.toJson()))
        val book = Book(id = "test-book-001", title = result.bookName, onlineSourceGroup = 4, onlineSourceKey = result.sourceKey)
        assertEquals(result.sourceKey, LibraryJson.decode(LibraryJson.encode(Library(books = listOf(book)))).books.single().onlineSourceKey)
        assertNotEquals(NovelSourceGroups.identity(result.url, 4), NovelSourceGroups.identity(result.url, 4, result.sourceKey))
        assertNotEquals(NovelSourceGroups.identity(result.url, 4, result.sourceKey), NovelSourceGroups.identity(result.url, 3, result.sourceKey))
    }

    @Test fun sourceImportsCanMixFormatsWithoutFeedingLegadoToDexEngine() {
        val dir = Files.createTempDirectory("mixed-novel-rules").toFile()
        try {
            val groups = NovelSourceGroups(dir) { "[]" }
            val so = JSONObject().put("name", "SoNovel").put("url", "https://so.invalid")
                .put("toc", JSONObject().put("item", "a")).put("chapter", JSONObject().put("content", "#content"))
            val report = groups.import(2, JSONArray().put(so).put(source()).toString())
            assertEquals(2, report.added)
            assertEquals(setOf("SoNovel", "阅读/Legado"), groups.entries(2).map { it.format }.toSet())
            assertEquals("SoNovel", JSONArray(groups.sonovelRulesFile(2).readText()).getJSONObject(0).getString("name"))
            assertEquals(1, JSONArray(groups.sonovelRulesFile(2).readText()).length())
            assertTrue(groups.entries(2).last().searchable)
            assertEquals(1, groups.import(2, source().put("bookSourceName", "更新后的名字").toString()).updated)
            assertEquals(2, groups.entries(2).size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun decoratedLegadoSourceKeysRemainIndependentWithoutPollutingRequests() {
        val dir = Files.createTempDirectory("legado-source-labels").toFile()
        try {
            val store = NovelSourceGroups(dir) { "[]" }
            val first = source().put("bookSourceUrl", "https://novels.invalid##测试版本 一")
            val second = source().put("bookSourceUrl", "https://novels.invalid##测试版本 二")
            assertEquals(2, store.import(2, JSONArray().put(first).put(second).toString()).total)
            assertNotEquals(store.entries(2)[0].url, store.entries(2)[1].url)
            fixture(first, { req ->
                assertFalse(req.url.contains("版本")); assertFalse(req.url.contains("#")); page(req, searchHtml)
            }) { engine, _ -> assertEquals(1, engine.search("三国演义").size) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun singleChapterSourcesMayReuseTheCurrentPage() {
        val raw = source().put("ruleBookInfo", JSONObject()).also {
            it.getJSONObject("ruleToc").put("chapterList", "h1").put("chapterName", "text").remove("chapterUrl")
        }
        fixture(raw, { req -> page(req, "<h1>单篇内容</h1><div id=content>正文内容</div>") }) { engine, _ ->
            val detail = engine.details("https://novels.invalid/book/1")
            assertEquals("https://novels.invalid/book/1", detail.toc.single().url)
            assertTrue(engine.chapter(detail.book.url, detail.toc.single()).contains("正文内容"))
        }
    }

    @Test fun multipleChaptersWithoutAddressesDoNotBecomeOneFakeChapter() {
        val raw = source().put("ruleBookInfo", JSONObject()).also { it.getJSONObject("ruleToc").remove("chapterUrl") }
        fixture(raw, { req -> page(req, tocHtml) }) { engine, _ ->
            assertTrue(runCatching { engine.details("https://novels.invalid/book/1") }.isFailure)
        }
    }

    @Test fun downloadResumesCachedChaptersAndKeepsOrder() = runBlocking {
        val calls = AtomicInteger()
        fixture(fetch = { req -> calls.incrementAndGet(); page(req, "<div id=content>小说正文</div>") }) { engine, dir ->
            val chapters = (1..3).map { OnlineChapter("第${it}回", "https://novels.invalid/chapter/$it") }
            val cache = ChapterDownloadCache(File(dir, "download"))
            val text = runBlocking { engine.chapters("https://novels.invalid/book/1", chapters, cache, 3) { _, _, _ -> } }
            assertEquals(3, calls.get()); assertTrue(text.indexOf("第1回") < text.indexOf("第3回"))
            val again = runBlocking { engine.chapters("https://novels.invalid/book/1", chapters, cache, 3) { _, _, _ -> } }
            assertEquals(text, again); assertEquals(3, calls.get())
        }
    }

    @Test fun invalidAndNonNovelSourcesAreRejectedWithoutErasingGroup() {
        val dir = Files.createTempDirectory("legado-invalid-import").toFile()
        try {
            val groups = NovelSourceGroups(dir) { "[]" }
            groups.import(2, source().toString())
            val original = groups.rulesFile(2).readBytes()
            listOf(source().put("bookSourceType", 2), source().put("bookSourceUrl", "file:///tmp/test"),
                source().also { it.remove("ruleContent") }).forEach {
                assertTrue(runCatching { groups.import(2, it.toString()) }.isFailure)
                assertArrayEquals(original, groups.rulesFile(2).readBytes())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun emptyDirectoryAndEmptyContentAreReported() = fixture(fetch = { req -> page(req, "<h1>空作品</h1>") }) { engine, _ ->
        assertTrue(runCatching { engine.details("https://novels.invalid/book/1") }.exceptionOrNull()!!.message!!.contains("目录"))
        assertTrue(runCatching { engine.chapter("https://novels.invalid/book/1", OnlineChapter("一", "https://novels.invalid/chapter/1")) }.exceptionOrNull()!!.message!!.contains("正文"))
    }

    @Test fun requestOptionsAndSavedReferenceArePreserved() {
        val req = NovelRequest.parse("/chapter/1, {\"method\":\"POST\",\"body\":{\"id\":7},\"headers\":{\"X-Test\":\"yes\"},\"charset\":\"GBK\"}", "https://n.invalid/books")
        assertEquals("POST", req.method); assertEquals("GBK", req.charset); assertEquals("yes", req.headers["X-Test"])
        assertEquals(7, JSONObject(req.body!!).getInt("id"))
        assertEquals(req.reference, NovelRequest.parse(req.reference, "https://n.invalid").reference)
    }

    @Test fun unsafeRequestsAndBrowserOnlyRequestsFailClearly() {
        listOf("file:///tmp/secret", "https://user:pass@n.invalid/", "/x,{\"webView\":true}", "/x,{\"headers\":{\"Host\":\"evil.invalid\"}}",
            "/x,{\"method\":\"DELETE\"}").forEach {
            assertTrue(it, runCatching { NovelRequest.parse(it, "https://n.invalid") }.isFailure)
        }
    }

    @Test fun sandboxRejectsJavaClassAccessAndUnboundedScripts() {
        val runtime = LegacyRuleRuntime().apply { novelMode = true }
        assertTrue(runCatching { runtime.evaluate("@js:java.lang.Runtime.getRuntime()", "", "https://n.invalid") }.isFailure)
        assertTrue(runCatching { runtime.evaluate("@js:while(true){}", "", "https://n.invalid") }.isFailure)
    }

    @Test fun commonLegadoHelpersStayInsideTheSandbox() {
        val runtime = LegacyRuleRuntime().apply {
            novelMode = true
            scriptObjects = mapOf("source" to "{\"bookSourceUrl\":\"https://n.invalid\",\"bookSourceName\":\"原创源\"}")
        }
        assertEquals(listOf("abc"), runtime.evaluate("@js:java.base64Decode(java.base64Encode('abc'))", "", "https://n.invalid"))
        assertEquals(listOf("900150983cd24fb0d6963f7d28e17f72"), runtime.evaluate("@js:java.md5Encode('abc')", "", "https://n.invalid"))
        assertEquals(listOf("https://n.invalid"), runtime.evaluate("@js:source.getKey()", "", "https://n.invalid"))
        assertEquals(listOf("段落"), runtime.evaluate("@js:java.getElement('#text').text()", "<div id=text>段落</div>", "https://n.invalid"))
    }

    @Test fun httpTransportHandlesRedirectCharsetAndPerSourceCookies() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { e -> e.responseHeaders.add("Location", "/text"); e.responseHeaders.add("Set-Cookie", "session=one; Path=/"); e.sendResponseHeaders(302, -1); e.close() }
        server.createContext("/text") { e ->
            val payload = "<meta charset=GBK>中文正文 ${e.requestHeaders.getFirst("Cookie").orEmpty()}".toByteArray(java.nio.charset.Charset.forName("GBK"))
            e.sendResponseHeaders(200, payload.size.toLong()); e.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val transport = NovelHttpTransport()
            val first = transport.fetch(NovelRequest.parse("/start", base, sourceKey = "one"))
            assertEquals("$base/text", first.url); assertTrue(first.text.contains("中文正文")); assertTrue(first.text.contains("session=one"))
            assertFalse(transport.fetch(NovelRequest.parse("/text", base, sourceKey = "two")).text.contains("session=one"))
        } finally { server.stop(0) }
    }
}
