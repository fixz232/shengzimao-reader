package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.OnlineMatcher
import com.shengzimao.reader.mobile.data.TomatoEngine
import com.shengzimao.reader.mobile.data.TomatoParser
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TomatoTest {
    @Test fun keywordSearchFiltersAudioComicsAndRecommendationsWithoutLosingIds() {
        val json = JSONObject("""{"code":0,"data":{"ret_data":[
          {"book_id":"7276384138653862966","title":"我不是戏神","author":"三九音域","genre":"0"},
          {"book_id":"7276384138653862966","title":"重复"},
          {"book_id":"7387309822351264830","title":"我不是戏神","genre":"4"},
          {"book_id":"7639588356636036120","title":"我不是戏神","genre":"905"},
          {"book_id":"123","title":"推荐其他书"},
          {"book_id":"bad","title":"我不是戏神"}
        ]}}""")
        val books = OnlineMatcher.filter("我不是戏神", TomatoParser.searchResponse(json))
        assertEquals(1, books.size)
        assertEquals("https://fanqienovel.com/page/7276384138653862966", books.single().url)
        assertEquals("三九音域", books.single().author)
    }

    @Test fun nestedInitialStatePreservesJsonBeforeHtmlCleaning() {
        val book = JSONObject().put("bookName", "书名含 } 和 \" 引号").put("author", "甲")
            .put("abstract", "小说简介 <b>正文</b>").put("description", "作者简介")
            .put("creationStatus", 0).put("status", 1).put("wordNumber", 123456)
            .put("categoryV2", """[{"Name":"都市高武"}]""").put("chapterTotal", 12)
            .put("lastChapterTitle", "完本感言").put("lastPublishTime", 1704067200)
        val state = JSONObject().put("page", book)
        val variants = listOf(
            "<script>window.__INITIAL_STATE__=$state;</script>",
            "<script type=\"application/json\" id=\"__INITIAL_STATE__\">\n$state\n</script>",
            "<script id=\"__NEXT_DATA__\">${JSONObject().put("props", state)}</script>",
        )
        for (html in variants) {
            val result = TomatoParser.bookHtml(html, "123")
            assertEquals("书名含 } 和 \" 引号", result.bookName)
            assertEquals("小说简介 正文", result.intro)
            assertEquals("都市高武", result.category)
            assertEquals("已完结", result.status)
            assertEquals("123456", result.wordCount)
            assertEquals("2024-01-01 08:00:00", result.lastUpdateTime)
            assertEquals(12, result.chapterCount)
        }
    }

    @Test fun directoriesKeepOrderFlattenVolumesAndIgnoreUntrustedUrls() {
        val chapters = TomatoParser.directory(JSONObject("""{"data":{"chapterListWithVolume":[
          [{"itemId":"123","title":"第一章","url":"https://evil.invalid/a"}],
          {"chapters":[{"chapter_id":"124","chapter_title":"第二章"},{"id":"123","title":"重复"}]}
        ]}}"""))
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
        assertEquals("https://fanqienovel.com/reader/123", chapters.first().url)
    }

    @Test fun publicParagraphsAreReadableAndRestrictedBodiesAreRejected() {
        assertEquals("第一段。\n第二段&文字。", TomatoParser.chapterHtml("<article><p>第一段。</p><p>第二段&amp;文字。</p></article>"))
        assertEquals("", TomatoParser.chapterHtml("<article>\uE123字体加密</article>"))
        assertEquals("", TomatoParser.chapterHtml("<article>登录后阅读</article>"))
        assertEquals("", TomatoParser.chapterHtml("<html>需要验证</html>"))
    }

    @Test fun exactContentClassAndBalancedNestedTagsPreserveAllParagraphs() {
        val html = """<article><nav>目录</nav><div class="muye-reader-content-16"><h1>第一章</h1>
            <div class="muye-reader-content"><div><p>第一段。</p></div><p>第二段。</p><div><p>第三段。</p></div></div>
            <footer>下一章</footer></div></article>"""
        assertEquals("第一段。\n第二段。\n第三段。", TomatoParser.chapterHtml(html, "第一章"))
    }

    @Test fun titlesDirectoriesAndNavigationCannotBecomeChapterBodies() {
        for (html in listOf(
            "<div class='muye-reader-content-16'><h1>第一章</h1><nav>目录</nav></div>",
            "<article><h1>第一章</h1><nav>上一章 下一章</nav></article>",
            "<article><a href='/reader/1'>第一章</a><a href='/reader/2'>第二章</a></article>",
            "<div class='muye-reader-content'>第一章</div>",
            "<div class='muye-reader-content'><p>目录</p><p>下一章</p></div>",
        )) assertEquals(html, "", TomatoParser.chapterHtml(html, "第一章"))
    }

    @Test fun onlyKnownPublicFontIsDecodedAndUnknownCharactersRemainRejected() {
        val markers = "<style>DNMrHsV173Pd4pgy dc027189e0ba4cd</style>"
        val body = "<div class='muye-reader-content'><p>&#58345;&#58346;。</p></div>"
        assertEquals("在主。", TomatoParser.chapterHtml(markers + body))
        assertEquals("", TomatoParser.chapterHtml(body))
        assertEquals("", TomatoParser.chapterHtml(markers.replace("dc027189e0ba4cd", "new-font") + body))
        assertEquals("", TomatoParser.chapterHtml(markers + "<article>&#60000;</article>"))
    }

    @Test fun idsAndLinksRequirePublicHttpsHosts() {
        assertEquals("7276384138653862966", TomatoParser.bookId("7276384138653862966"))
        assertEquals("123", TomatoParser.bookId("https://fanqienovel.com/page/123?foo=1"))
        for (value in listOf("http://fanqienovel.com/page/123", "https://evil.invalid/page/123", "https://fanqienovel.com.evil.invalid/page/123", "https://user:pass@fanqienovel.com/page/123", "https://fanqienovel.com:444/page/123")) {
            assertEquals("", TomatoParser.bookId(value))
            assertFalse(TomatoParser.allowedUrl(value, true))
        }
    }

    @Test fun realRequestUsesDesktopParametersAndDirectIdSkipsKeywordSearch() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().url.toString())
            val body = if (chain.request().url.encodedPath.startsWith("/page/")) "<h1>直接定位书籍</h1>"
                else """{"code":0,"data":{"ret_data":[{"book_id":"123","title":"测试书","author":"甲"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val engine = TomatoEngine(client)
        assertEquals("测试书", engine.search("测试书").single().bookName)
        val request = okhttp3.Request.Builder().url(requests.first()).build().url
        assertEquals("1967", request.queryParameter("aid"))
        assertEquals("android", request.queryParameter("device_platform"))
        assertEquals("测试书", request.queryParameter("q"))
        assertEquals("直接定位书籍", engine.search("123").single().bookName)
        assertEquals("https://fanqienovel.com/page/123", requests.last())
    }

    @Test fun emptyResponsesValidationPagesAndBusinessErrorsAreFailures() = runBlocking {
        for (body in listOf("", "<html>验证</html>", """{"code":-2,"data":{}}""")) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            assertTrue(runCatching { TomatoEngine(client).search("测试") }.isFailure)
        }
    }
}
