package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.LegacyMangaEngine
import com.shengzimao.reader.mobile.data.MangaSource
import com.shengzimao.reader.mobile.data.legacySearchPost
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacySearchPostTest {
    private fun source(search: String, forceLegacy: Boolean = false): MangaSource = MangaSource.importText(JSONObject()
        .put("bookSourceName", "POST 搜索回归")
        .put("bookSourceUrl", "https://source.example")
        .put("httpUserAgent", "SourceDeclaredAgent/1.0")
        .put("ruleSearchUrl", search)
        .put("ruleSearchList", "div[itemscope]")
        .put("ruleSearchName", "h3[itemprop=name]@text")
        .put("ruleSearchNoteUrl", "a[itemprop=url]@href")
        .put("ruleSearchCoverUrl", "img.wp-post-image@src")
        .put("ruleChapterList", ".chapters a")
        .put("ruleChapterName", "text")
        .put("ruleContentUrl", "href")
        .put("ruleBookContent", ".comic_img@data-original")
        .put("ruleChapterUrlNext", if (forceLegacy) "@js:''" else "")
        .toString()).single()

    private val result = """<div itemscope><h3 itemprop="name">测试漫画</h3><a itemprop="url" href="/book/1">详情</a><img class="wp-post-image" src="/cover.jpg"></div>"""

    @Test fun formSearchIsKeptLegacyAndSendsPostBodyRatherThanUrlSuffix() {
        val source = source("https://source.example/e/search/index.php@keyboard=searchKey&show=title,writer,byr&searchget=1")
        assertNotNull(source.legacy)
        assertTrue(source.enabled)
        assertEquals("SourceDeclaredAgent/1.0", source.headers["User-Agent"])
        var calls = 0
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            calls++
            assertEquals("https://source.example/e/search/index.php", url)
            assertEquals("keyboard=%E9%AD%94%E5%92%92%20%26%20A&show=title,writer,byr&searchget=1", body)
            assertEquals("application/x-www-form-urlencoded; charset=UTF-8", headers["Content-Type"])
            result
        }, { _, _, _ -> })
        val books = engine.search(source, "魔咒 & A", 1)
        assertEquals(1, calls)
        assertEquals("https://source.example/book/1", books.single().url)
    }

    @Test fun formCanHaveHeadersAndPageExpressions() {
        val source = source("/search@q=searchKey&page={{searchPage+1}}@Header:{\"Content-Type\":\"application/x-www-form-urlencoded\",\"Referer\":\"https://source.example/\"}")
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            assertEquals("https://source.example/search", url)
            assertEquals("q=test&page=3", body)
            assertEquals("application/x-www-form-urlencoded", headers["Content-Type"])
            assertEquals("https://source.example/", headers["Referer"])
            result
        }, { _, _, _ -> })
        assertEquals(1, engine.search(source, "test", 2).size)
    }

    @Test fun ordinaryGetRetainsQueryAndHasNoBody() {
        val source = source("/search?q=searchKey&page=searchPage", true)
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            assertEquals("https://source.example/search?q=a%20b&page=2", url)
            assertNull(body)
            assertFalse(headers.keys.any { it.equals("Content-Type", true) })
            result
        }, { _, _, _ -> })
        assertEquals(1, engine.search(source, "a b", 2).size)
    }

    @Test fun atInHeadersEmailOrUrlScriptIsNotTreatedAsPost() {
        for (raw in listOf("https://source.example/search?q=user@example.com",
            "/search@Header:{\"Referer\":\"https://source.example/path@a=b\"}",
            "@js:'https://source.example/search@q=x'")) {
            assertEquals(raw to null, legacySearchPost(raw))
        }
    }

    @Test fun jsonPostPreservesUnicodeAndGraphqlBraces() {
        val template = "/api/query@{\"variables\":{\"keyword\":\"searchKey\"},\"query\":\"query { authors { id,name } }\"}"
        val source = source(template)
        assertNotNull(source.legacy)
        val keyword = "魔咒 \"A\" & B"
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            assertEquals("https://source.example/api/query", url)
            assertEquals(keyword, JSONObject(body!!).getJSONObject("variables").getString("keyword"))
            assertEquals("query { authors { id,name } }", JSONObject(body).getString("query"))
            assertEquals("application/json; charset=utf-8", headers["Content-Type"])
            result
        }, { _, _, _ -> })
        assertEquals(1, engine.search(source, keyword, 1).size)
    }

    @Test fun dynamicSearchCanReturnHeadersWithoutParsingTheJavascriptLiteral() {
        val source = source("""{{"https://source.example/search?q="+encodeURIComponent(searchKey)+"&offset="+12*(searchPage-1)+"@Header:"+JSON.stringify({Cookie:"webp=1",region:"0",webp:"1"})}}""")
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            assertEquals("https://source.example/search?q=%E9%AD%94%E5%92%92%20%26%20A&offset=24", url)
            assertNull(body)
            assertEquals("webp=1", headers["Cookie"])
            assertEquals("0", headers["region"])
            assertEquals("1", headers["webp"])
            result
        }, { _, _, _ -> })
        assertEquals(1, engine.search(source, "魔咒 & A", 3).size)
    }

    @Test fun mapHeadersAndCharsetSuffixWorkOnAndroidCompatibleSyntax() {
        val source = source("https://source.example/search.php?q=searchKey|char=gbk@Header:{Cookie=articlevisited=1}")
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            assertEquals("https://source.example/search.php?q=%C4%A7%D6%E4", url)
            assertNull(body)
            assertEquals("articlevisited=1", headers["Cookie"])
            result
        }, { _, _, _ -> })
        assertEquals(1, engine.search(source, "魔咒", 1).size)
    }

    @Test fun optionalPaginationChoosesFirstThenRepeatsLastVariant() {
        val source = source("https://source.example/s/searchKey{,_psearchPage}.html")
        var expected = ""
        val engine = LegacyMangaEngine({ url, _, _, _ -> assertEquals(expected, url); result }, { _, _, _ -> })
        for (page in listOf(1, 2, 9)) {
            expected = "https://source.example/s/test" + (if (page == 1) "" else "_p$page") + ".html"
            assertEquals(1, engine.search(source, "test", page).size)
        }
    }

    @Test fun urlScriptRetainsItsDeclaredSuffixHeaders() {
        val source = source("""@js:'https://source.example/search?q=test'@Header:{"Cookie":"public=1"}""")
        val engine = LegacyMangaEngine({ url, _, body, headers ->
            assertEquals("https://source.example/search?q=test", url)
            assertNull(body)
            assertEquals("public=1", headers["Cookie"])
            result
        }, { _, _, _ -> })
        assertEquals(1, engine.search(source, "test", 1).size)
    }
}
