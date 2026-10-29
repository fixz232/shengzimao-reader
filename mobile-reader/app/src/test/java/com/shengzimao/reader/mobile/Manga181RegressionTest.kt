package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class Manga181RegressionTest {
    @Test fun importsBothUserBundlesWithoutLosingSources() {
        for ((id, count) in listOf(70 to 76, 71 to 97)) {
            val body = javaClass.classLoader!!.getResourceAsStream("manga-source-$id.txt")!!.bufferedReader().use { it.readText() }
            val sources = MangaSource.importText(body)
            assertEquals("id=$id", count, sources.size)
            assertTrue("id=$id skipped=${MangaSource.lastSkipped}", MangaSource.lastSkipped.isEmpty())
            sources.forEach { assertEquals(it.id, MangaSource.parse(it.json()).id) }
            println("USER_BUNDLE id=$id imported=${sources.size} enabled=${sources.count { it.enabled }} disabled=${sources.count { !it.enabled }}")
        }
    }

    private fun page(count: Int) = Jsoup.parse("<div id='imgsec'>" + (0 until count).joinToString("") {
        "<figure class='reader-pic-slot' data-chapter-id='2037465' data-aid='60851' data-pic-index='$it'><img src='/static/boodo/img/load.gif'></figure>"
    } + "</div>")

    @Test fun lazyReaderKeepsBatchOrderAndLastShortBatch() = runBlocking {
        val plan = BoodoReader.chapter(page(12))!!
        val images = BoodoReader.pages(plan, "https://reader.example/episode/1") { url, body ->
            assertEquals("https://reader.example/api/comic/read/pics", url)
            val offset = Regex("offset=(\\d+)").find(body)!!.groupValues[1].toInt()
            kotlinx.coroutines.delay((10 - offset).coerceAtLeast(0).toLong())
            JSONObject().put("code", 1).put("data", JSONObject().put("offset", offset).put("total", 12)
                .put("pic", org.json.JSONArray((offset until minOf(offset + 5, 12)).map { JSONObject().put("pic", "https://img.example/$it.webp") }))).toString()
        }
        assertEquals((0 until 12).map { "https://img.example/$it.webp" }, images)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialBatchCannotBecomeCompleteDownload(): Unit = runBlocking {
        BoodoReader.pages(BoodoReader.chapter(page(6))!!, "https://reader.example/chapter") { _, _ ->
            """{"code":1,"data":{"total":6,"pic":[]}}"""
        }; Unit
    }

    @Test fun placeholderIsNotRealImageAndUnrelatedSiteIsUnaffected() {
        assertTrue(mangaPlaceholder("http://reader.example/static/boodo/img/load.gif?t=1"))
        assertFalse(mangaPlaceholder("https://img.example/01.webp"))
        assertNull(BoodoReader.chapter(Jsoup.parse("<img src='01.webp'>")))
    }

    @Test fun legacyExclusionAndFallbackKeepActualChapterOrder() {
        val html = """<div class='l-box'><div class='pure-g'><a class='comics-chapters' href='/summary'>简介</a></div>
            <div class='pure-g'><a class='comics-chapters' href='/1'>一</a><a class='comics-chapters' href='/2'>二</a></div></div>"""
        val items = LegacyRuleRuntime.extract(".l-box@.pure-g!0@.comics-chapters||.pure-g@.comics-chapters", html, "https://e.example/", true)
        assertEquals(2, items.size)
        assertEquals(listOf("/1", "/2"), items.map { LegacyRuleRuntime.extract("@href", it, "https://e.example/", false).single() })
        assertEquals(listOf("一", "二"), LegacyRuleRuntime.extract(".missing@text|.comics-chapters!0@text", html, "https://e.example/", true))
    }

    @Test fun legacySingleHashReplacementIsNotIncludedInUrl() {
        assertEquals(listOf("https://m.example/1"), LegacyRuleRuntime.extract("a@href#www#m", "<a href='https://www.example/1'>作品</a>", "https://e.example/", false))
    }

    @Test fun baoziUsesCurrentPublicPageWithoutGuessingImageHosts() {
        val public = BaoziReader.publicChapter("https://cn.bzmanga.com", "https://appgb1-vdkr.baozimh.com/baozimhapp/comic/chapter/example/0_0.html")
        assertEquals("https://cn.bzmanga.com/comic/chapter/example/0_0.html", public)
        val html = """<amp-img class='comic-contain__item' data-src='https://s1.bzcdn.net/scomic/example/1.jpg'></amp-img>
            <img src='loading.gif'><amp-img class='comic-contain__item' data-src='https://s1.bzcdn.net/scomic/example/2.jpg'></amp-img>"""
        assertEquals(listOf("https://s1.bzcdn.net/scomic/example/1.jpg", "https://s1.bzcdn.net/scomic/example/2.jpg"), BaoziReader.pages(html, public!!))
        assertNull(BaoziReader.publicChapter("https://example.com", "https://evil.baozimh.com/baozimhapp/comic/chapter/example/0_0.html"))
        assertNull(BaoziReader.publicChapter("https://cn.bzmanga.com", "https://evil.example/baozimhapp/comic/chapter/example/0_0.html"))
    }

    @Test fun baoziRoutesOnlyVerifiedImageHostsAndKeepsPathAndQuery() {
        val input = "https://s1-mha1-nlams.baozicdn.com/scomic/work/0/1/2.jpg?token=public%2Fvalue"
        val routes = BaoziReader.imageCandidates(input)
        assertTrue(routes.first().startsWith("https://s1.baozimh.com/scomic/work/0/1/2.jpg?"))
        assertEquals(java.net.URI(input).rawQuery, java.net.URI(routes.first()).rawQuery)
        for (url in listOf("https://example.com/scomic/1.jpg", "https://s1.baozicdn.com.evil.com/scomic/1.jpg", "https://s1.baozicdn.com/login")) {
            assertEquals(listOf(url), BaoziReader.imageCandidates(url))
        }
    }
}
