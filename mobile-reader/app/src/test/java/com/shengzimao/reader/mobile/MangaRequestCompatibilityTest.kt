package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MangaRequestCompatibilityTest {
    @Test fun scriptHeaderLiteralDoesNotRecurseOrGetRemoved() {
        val raw = "@js:'https://source.example/search@Header:{\"Cookie\":\"public=1\"}'"
        assertEquals(raw, splitLegacyHeaders(raw).rule)
        assertTrue(splitLegacyHeaders(raw).objects.isEmpty())
        assertEquals(listOf("https://source.example/search@Header:{\"Cookie\":\"public=1\"}"),
            LegacyRuleRuntime().evaluate(raw, "", "https://source.example"))
    }

    @Test fun headerMapAcceptsOldEqualsAndQuotedCookieValues() {
        assertEquals(mapOf("Cookie" to "articlevisited=1"), legacyHeaderObject("{Cookie=articlevisited=1}"))
        assertEquals(mapOf("Cookie" to "xmanhua_lang=2", "Referer" to "thisPage"),
            legacyHeaderObject("{Cookie:\"xmanhua_lang=2\",Referer:thisPage}"))
        assertTrue(runCatching { legacyHeaderObject("{Cookie=unsafe\r\nX-Injected=yes}") }.isFailure)
        assertTrue(runCatching { legacyHeaderObject("{Cookie:{script:'bad'}}") }.isFailure)
    }

    @Test fun javaPostReturnsTextBodyAndPreservesExplicitHeaders() {
        var calls = 0
        val runtime = LegacyRuleRuntime { url, body, headers ->
            calls++
            assertEquals("https://source.example/pics", url)
            assertEquals("id=1&page=0", body)
            assertEquals("application/x-www-form-urlencoded; charset=UTF-8", headers["Content-Type"])
            assertEquals("1", headers["platform"])
            """{"data":{"pic":["https://image.example/1.png","https://image.example/2.png"]}}"""
        }
        val images = runtime.evaluate("@js:JSON.parse(java.post('/pics','id=1&page=0',{platform:'1'}).body()).data.pic", "", "https://source.example/chapter/1", true)
        assertEquals(listOf("https://image.example/1.png", "https://image.example/2.png"), images)
        assertEquals(1, calls)
        assertTrue(runCatching { runtime.evaluate("@js:java.getClass()", "", "https://source.example") }.isFailure)
    }

    @Test fun javaPostStillEnforcesRequestBudget() {
        var calls = 0
        val runtime = LegacyRuleRuntime { _, _, _ -> calls++; "fixture" }
        assertTrue(runCatching {
            runtime.evaluate("@js:for(var i=0;i<13;i++){java.post('/pics','',{}).body();}", "", "https://source.example")
        }.isFailure)
        assertEquals(12, calls)
    }

    @Test fun reservedAndMalformedHeadersAreRejected() {
        for ((name, value) in listOf("Host" to "evil.example", "Connection" to "close", "Content-Length" to "3",
            "Cookie" to "a=1\r\nX-Evil:1", "Referer" to "https://example/\u0000")) {
            assertTrue("accepted reserved or malformed header $name", runCatching { MangaRequestHeaders.validate(name, value) }.isFailure)
        }
        for (name in listOf("region", "webp", "Cookie", "Platform", "Origin")) MangaRequestHeaders.validate(name, "public-fixture")
        assertFalse(MangaRequestHeaders.sameOrigin("https://source.example/".toHttpUrl(), "http://source.example/".toHttpUrl()))
        assertFalse(MangaRequestHeaders.sameOrigin("https://source.example/".toHttpUrl(), "https://source.example:8443/".toHttpUrl()))
    }

    @Test fun realEngineKeepsCookieOnSameOriginAndDropsItPermanentlyAfterCrossOriginRedirect() = runBlocking {
        val root = Files.createTempDirectory("manga-header-redirect").toFile()
        try {
            val cookies = mutableListOf<String?>()
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                val request = chain.request()
                cookies += request.header("Cookie")
                assertEquals("0", request.header("region"))
                val next = when (request.url.encodedPath) {
                    "/search" -> "https://source.example/middle"
                    "/middle" -> "https://other.example/final"
                    "/final" -> "https://source.example/end"
                    else -> null
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (next == null) 200 else 302).message("fixture")
                    .body("<div class='book'><a href='/comic/1'>原创测试漫画</a></div>".toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .apply { if (next != null) header("Location", next) }.build()
            }.build()
            val source = MangaSource.parse(JSONObject("""{"id":"redirect-fixture","name":"隔离测试源","baseUrl":"https://source.example/",
                "headers":{"Cookie":"public=1","region":"0"},"search":{"url":"/search?q={query}","items":".book","title":"a","link":"a@href"},
                "detail":{"chapters":".chapter"},"reader":{"pages":"img@src"}}"""))
            assertEquals(1, MangaEngine(MangaStore(root), client).search(source, "fixture", 1).size)
            assertEquals(listOf("public=1", "public=1", null, null), cookies)
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyBaoziDirectoryReportsUpstreamNoticeWithoutInventingChapters() {
        val html = "<div class='empty_chapters_tips'><div class='tips-box-right'>章节数据缺少，已屏蔽处理</div></div>"
        val url = "https://cn.bzmanga.com/comic/fixture"
        val notice = BaoziReader.emptyDirectoryMessage(html, url).orEmpty()
        assertTrue(notice.contains("原站未提供可读章节") && notice.contains("屏蔽处理"))
        assertNull(BaoziReader.emptyDirectoryMessage(html, "https://other.example/comic/fixture"))
        assertNull(BaoziReader.emptyDirectoryMessage("<a href='/chapter/1'>第一话</a>", url))
    }

    @Test fun emptyBaoziAppDirectoryChecksPublicNoticeAndNeverInventsChapters() {
        val base = "https://cn.bzmanga.com"
        val app = "https://appgb2-vdhk.baozimh.com/baozimhapp/comic/fixture"
        assertEquals("$base/comic/fixture", BaoziReader.publicDetail(base, app))
        assertNull(BaoziReader.publicDetail("https://example.com", app))
        assertNull(BaoziReader.publicDetail(base, app.replace("baozimh.com", "baozimh.com.evil.com")))
        assertNull(BaoziReader.publicDetail(base, "https://appgb2-vdhk.baozimh.com/login"))
        val source = MangaSource.importText("""{"bookSourceName":"包子目录测试","bookSourceUrl":"$base","ruleSearchUrl":"/search?q=searchKey","ruleSearchList":".book","ruleSearchName":"a@text","ruleSearchNoteUrl":"a@href","ruleChapterList":"@js:[]","ruleChapterName":"text","ruleContentUrl":"href","ruleBookContent":"img@src"}""").single()
        val requests = mutableListOf<String>()
        val engine = LegacyMangaEngine({ url, _, _, _ ->
            requests += url
            if (url == app) "<h1>原创作品</h1>" else "<div class='empty_chapters_tips'><div class='tips-box-right'>章节缺失，屏蔽处理</div></div>"
        }) { _, _, _ -> }
        val failure = runCatching { engine.details(source, MangaBook("fixture", "原创作品", source.id, source.name, app)) }.exceptionOrNull()
        assertTrue(failure?.message, failure?.message.orEmpty().contains("屏蔽处理"))
        assertEquals(listOf(app, "$base/comic/fixture"), requests)
    }
}
