package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MangaLegacyAuditRegressionTest {
    private val base = "https://source.example"
    private fun source(search: String) = MangaSource.importText(JSONObject().put("bookSourceName", "审计兼容夹具")
        .put("bookSourceUrl", base).put("ruleSearchUrl", search).put("ruleSearchList", "a")
        .put("ruleSearchName", "text").put("ruleSearchNoteUrl", "href")
        .put("ruleChapterList", "a").put("ruleChapterName", "text").put("ruleContentUrl", "href")
        .put("ruleBookContent", "img@src").toString()).single()

    @Test fun longCharsetSuffixIsMetadataInsteadOfPartOfTheRequestUrl() {
        var address = ""
        val engine = LegacyMangaEngine({ url, _, _, _ -> address = url; "<a href='/book'>原创</a>" }) { _, _, _ -> }
        assertEquals(1, engine.search(source("/search?q=searchKey&page=searchPage|charset=gbk"), "中文", 2).size)
        assertEquals("$base/search?q=%D6%D0%CE%C4&page=2", address)
    }

    @Test fun bracesAroundAnEncodedFormAreNotTreatedAsJson() {
        var payload = ""
        var contentType = ""
        val engine = LegacyMangaEngine({ _, _, body, headers ->
            payload = body.orEmpty(); contentType = headers["Content-Type"].orEmpty(); "<a href='/book'>原创</a>"
        }) { _, _, _ -> }
        engine.search(source("/search/@{show=title%2Cwriter&keyboard=searchKey}"), "中文", 1)
        assertEquals("show=title%2Cwriter&keyboard=%E4%B8%AD%E6%96%87", payload)
        assertTrue(contentType.startsWith("application/x-www-form-urlencoded"))
    }

    @Test fun doubleBraceObjectBindingsSurviveJsonFieldExtraction() {
        val runtime = LegacyRuleRuntime()
        assertEquals(listOf("原创"), runtime.evaluate("$.title@put:{comic_id:{{result.comic_id}}}",
            """{"title":"原创","comic_id":123}""", base))
        assertEquals("$base/chapter/123", runtime.interpolate("$base/chapter/@get:{comic_id}", "", base))
    }

    @Test fun jsoupPostExecuteFacadeUsesTheExistingBoundedTransport() {
        var requested = ""; var posted = ""
        val runtime = LegacyRuleRuntime(fetch = { url, body, _ -> requested = url; posted = body.orEmpty(); "fixture-response" })
        val script = """@js:org.jsoup.Jsoup.connect('/api').requestBody('key=fixture')
            .followRedirects(true).method(org.jsoup.Connection.Method.POST).execute().body();"""
        assertEquals(listOf("fixture-response"), runtime.evaluate(script, "", base))
        assertEquals("$base/api", requested); assertEquals("key=fixture", posted)
        assertTrue(runCatching { runtime.evaluate("@js:org.jsoup.Jsoup.connect('/api').method('DELETE').execute()", "", base) }.isFailure)
    }
}
