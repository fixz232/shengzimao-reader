package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MangaChapterLoadingTest {
    @Test fun baoziAppNoticeAndImagesFromOtherWorksCannotPassAsChapterBody() {
        val url = "https://cn.bzmanga.com/comic/chapter/yirenzhixia-dongmantang/0_820.html"
        val notice = "https://s1.baozimh.com/scomic/meishaonianbangjiashijian-aojianyixi2/0/2-df6a/1.jpg"
        assertTrue(mangaPlaceholder(notice))
        assertFalse(mangaPlaceholder(notice.replace("s1.baozimh.com", "s1.baozimh.com.evil.example")))
        fun page(image: String) = "<amp-img class='comic-contain__item' data-src='$image'></amp-img>"
        assertTrue(runCatching { BaoziReader.pages(page(notice), url) }.exceptionOrNull()!!.message!!.contains("APP 引导图"))
        val body = "https://s1.baozimh.com/scomic/yirenzhixia-dongmantang/0/820-real/1.jpg"
        assertEquals(listOf(body), BaoziReader.pages(page(body), url))
    }

    @Test fun scriptImageAddressesPreservePerImageHeadersAndEncodeSpaces() {
        val raw = JSONObject("""{"bookSourceName":"逐图上下文","bookSourceUrl":"https://source.example",
            "ruleSearchUrl":"/s","ruleSearchList":"a","ruleSearchName":"text","ruleSearchNoteUrl":"href",
            "ruleChapterList":"a","ruleChapterName":"text","ruleContentUrl":"href"}""")
        raw.put("ruleBookContent", """@Header:{Accept:'image/*'}<js>
            ['https://cdn.example/a b.jpg@Header:{Referer:"https://source.example/read/1",Platform:1}',
             '/images/two.png@Header:{Referer:thisPage,Platform:2}'];
            </js>""")
        val source = MangaSource.importText(raw.toString()).single()
        val observed = linkedMapOf<String, Map<String, String>>()
        val engine = LegacyMangaEngine({ _, _, _, _ -> "<html></html>" }) { _, image, headers -> observed[image] = headers }
        val pages = engine.pages(source, MangaChapter("正文", "https://source.example/read/1"))
        assertEquals(listOf("https://cdn.example/a%20b.jpg", "https://source.example/images/two.png"), pages)
        assertEquals(mapOf("Accept" to "image/*", "Referer" to "https://source.example/read/1", "Platform" to "1"), observed[pages[0]])
        assertEquals(mapOf("Accept" to "image/*", "Referer" to "https://source.example/read/1", "Platform" to "2"), observed[pages[1]])
    }

    @Test fun oldGmhImageContextRefreshesOnceWithoutDiscardingAvailableOfflineFiles() {
        val source = "https://baozimh.org"
        val chapter = MangaChapter("第一话", "https://v2.apikk.top/api/v2/chapter/getinfo?m=32333&c=1675225",
            pages = listOf("https://c-nd3-1.6wm.top/hp/fixture.webp"))
        assertTrue(GmhReader.requiresPageRefresh(source, chapter) { false })
        assertFalse(GmhReader.requiresPageRefresh(source, chapter) { true })
        val updated = chapter.copy(imageHeaders = mapOf(chapter.pages.single() to mapOf("Referer" to source)))
        assertFalse(GmhReader.requiresPageRefresh(source, updated) { false })
        assertFalse(GmhReader.requiresPageRefresh("https://other.example", chapter) { false })
        assertTrue(mangaPlaceholder("https://c-nd3-1.6wm.top/link/link.webp"))
        assertFalse(mangaPlaceholder("https://c-nd3-1.6wm.top.evil.example/link/link.webp"))
    }

    @Test fun gmhNoticeRedirectCannotBeCountedAsAReadableImageRoute() {
        val root = Files.createTempDirectory("manga-image-notice").toFile()
        try {
            val visited = mutableListOf<String>()
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                val request = chain.request(); visited += request.url.toString()
                val notice = request.url.host.startsWith("c-") && request.url.encodedPath.startsWith("/hp/")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (notice) 302 else 200)
                    .message("fixture").apply { if (notice) header("Location", "/link/link.webp") }
                    .header("Content-Type", "image/webp").body(byteArrayOf(1, 2, 3).toResponseBody()).build()
            }.build()
            val source = MangaSource.parse(JSONObject(MangaSource.template))
            val method = MangaEngine::class.java.getDeclaredMethod("requestImage", String::class.java,
                MangaSource::class.java, Map::class.java).apply { isAccessible = true }
            (method.invoke(MangaEngine(MangaStore(root), client), "https://c-nd2-1.6wm.top/hp/fixture.webp",
                source, emptyMap<String, String>()) as Response).use {
                assertEquals("t-nd2-1.6wm.top", it.request.url.host)
            }
            assertEquals(3, visited.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun baoziContinuationCannotSkipPagesOrDriftIntoAnotherChapter() {
        val current = "https://cn.bzmanga.com/comic/chapter/work/0_0.html"
        fun link(target: String) = "<a id='next-chapter' href='$target'>下一页</a>"
        assertEquals(current.removeSuffix(".html") + "_2.html", BaoziReader.nextPage(link("0_0_2.html"), current))
        assertNull(BaoziReader.nextPage(link("0_1.html"), current))
        assertNull(BaoziReader.nextPage(link("0_0_3.html"), current))
        assertNull(BaoziReader.nextPage(link("https://evil.example/comic/chapter/work/0_0_2.html"), current))
        assertNull(BaoziReader.nextPage(link("0_0.html"), current))
        assertTrue(GmhReader.retiredImage("https://f40-1-4.g-mh.online/hp/fixture.webp"))
        assertFalse(GmhReader.retiredImage("https://f40-1-4.g-mh.online.evil.example/hp/fixture.webp"))
    }

    @Test fun binaryContentTypeIsPassedToTheImageValidatorInsteadOfMisreportedAsHttp200Failure() {
        val root = Files.createTempDirectory("manga-image-content-type").toFile()
        try {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .header("Content-Type", "application/octet-stream").body(byteArrayOf(1, 2, 3).toResponseBody()).build()
            }.build()
            val source = MangaSource.parse(JSONObject(MangaSource.template))
            val method = MangaEngine::class.java.getDeclaredMethod("requestImage", String::class.java, MangaSource::class.java, Map::class.java)
                .apply { isAccessible = true }
            (method.invoke(MangaEngine(MangaStore(root), client), "https://c-nd2-1.6wm.top/hp/fixture.webp", source, emptyMap<String, String>()) as Response).use {
                assertEquals(200, it.code)
            }
            assertEquals(1, requests)
        } finally { root.deleteRecursively() }
    }

    @Test fun chapterHeadersSurviveDirectorySaveReopenAndPagination() {
        val source = MangaSource.importText("""{"bookSourceName":"请求上下文回归","bookSourceUrl":"https://source.example",
            "ruleSearchUrl":"/s","ruleSearchList":"a","ruleSearchName":"text","ruleSearchNoteUrl":"href",
            "ruleChapterList":"a","ruleChapterName":"text","ruleContentUrl":"href@Header:{Referer:host,Platform:1}",
            "ruleBookContent":"img@src","ruleContentUrlNext":"a@href@Header:{Referer:thisPage,Platform:2}"}""").single()
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val engine = LegacyMangaEngine({ url, _, _, headers ->
            calls += url to headers
            when (url) {
                "https://source.example/book" -> "<a href='https://api.example/chapter'>第一话</a>"
                "https://api.example/chapter" -> "<img src='https://image.example/1.png'><a href='/page2'>下一页</a>"
                else -> "<img src='https://image.example/2.png'>"
            }
        }) { _, _, _ -> }
        val detail = engine.details(source, MangaBook("b", "原创测试", source.id, source.name, "https://source.example/book"))
        val reopened = MangaBook.parse(detail.json()).chapters.single()
        assertEquals(mapOf("Referer" to "https://api.example", "Platform" to "1"), reopened.requestHeaders)
        assertEquals(2, engine.pages(source, reopened).size)
        assertEquals(reopened.requestHeaders, calls[1].second)
        assertEquals(mapOf("Referer" to "https://api.example/page2", "Platform" to "2"), calls[2].second)
        assertTrue(MangaChapter.parse(JSONObject("""{"title":"旧目录","url":"https://source.example/1"}""")).requestHeaders.isEmpty())
    }

    @Test fun currentGmhProtocolDecodesReportedChapterAndUsesItsDeclaredImageLine() {
        val url = "https://v2.apikk.top/api/v2/chapter/getinfo?m=31176&c=1559518"
        val text = javaClass.getResource("/gmh-chapter-v2.json")!!.readText()
        val pages = GmhReader.pages(text, url)
        assertEquals(42, pages.size)
        assertEquals("https://c-nd2-1.6wm.top/hp/shuibianzhiyewanzhengban/1/0_b685e6e07a6b787514cc1b501ddfa563.webp", pages.first())
        assertEquals(2, GmhReader.imageCandidates(pages.first()).size)
        assertEquals(listOf("https://c-nd2-1.6wm.top.evil.example/hp/1"),
            GmhReader.imageCandidates("https://c-nd2-1.6wm.top.evil.example/hp/1"))
        assertTrue(runCatching { GmhReader.pages(text, url.replace("m=31176", "m=7")) }.isFailure)
        assertTrue(runCatching { GmhReader.decode("J7rinvalidnQ") }.isFailure)
    }

    @Test fun onlyVerifiedRetiredGmhEndpointIsMigratedAndCredentialsAreNotForwarded() = runBlocking {
        assertEquals("https://v2.apikk.top/api/v2/chapter/getinfo?m=41&c=886083",
            GmhReader.currentChapterUrl("https://godamh.com", "https://api-get.mgsearcher.com/api/chapter/getinfo?m=41&c=886083"))
        assertNull(GmhReader.currentChapterUrl("https://godamh.com.evil.example", "https://api-get.mgsearcher.com/api/chapter/getinfo?m=41&c=886083"))
        val old = "https://api-get-v2.mgsearcher.com/api/chapter/getinfo?m=31176&c=1559518"
        assertEquals("https://v2.apikk.top/api/v2/chapter/getinfo?m=31176&c=1559518", GmhReader.currentChapterUrl("https://m.g-mh.org", old))
        val current = "https://v2.apikk.top/api/v2/chapter/getinfo?m=31176&c=1559518"
        assertEquals(current, GmhReader.currentChapterUrl("https://baozimh.org", current))
        assertNull(GmhReader.currentChapterUrl("https://other.example", old))
        assertNull(GmhReader.currentChapterUrl("https://m.g-mh.org", old.replace("mgsearcher.com", "mgsearcher.com.evil.example")))
        val root = Files.createTempDirectory("gmh-chapter-regression").toFile()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("v2.apikk.top", chain.request().url.host)
                assertEquals("/api/v2/chapter/getinfo", chain.request().url.encodedPath)
                assertNull(chain.request().header("Cookie"))
                assertNull(chain.request().header("Authorization"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(javaClass.getResource("/gmh-chapter-v2.json")!!.readText().toResponseBody()).build()
            }.build()
            val source = MangaSource.importText("""{"bookSourceName":"G 回归","bookSourceUrl":"https://m.g-mh.org",
                "ruleSearchUrl":"/s","ruleSearchList":"a","ruleSearchName":"text","ruleSearchNoteUrl":"href",
                "ruleChapterList":"a","ruleChapterName":"text","ruleContentUrl":"href","ruleBookContent":"img@src"}""").single()
            assertEquals(42, MangaEngine(MangaStore(root), client).pages(source,
                MangaChapter("第01话", old, requestHeaders = mapOf("Cookie" to "fixture=1", "Authorization" to "fixture"))).size)
        } finally { root.deleteRecursively() }
    }
}
