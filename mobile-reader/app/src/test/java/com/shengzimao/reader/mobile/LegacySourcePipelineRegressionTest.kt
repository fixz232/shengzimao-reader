package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Shapes reproduced from the connected phone's imported source rules; no remote dependency. */
class LegacySourcePipelineRegressionTest {
    private val base = "https://source.example"
    private fun source(nameRule: String, linkRule: String): MangaSource = MangaSource.importText(JSONObject()
        .put("bookSourceName", "原创目录夹具").put("bookSourceUrl", base)
        .put("ruleSearchUrl", "/search?q=searchKey").put("ruleSearchList", ".book")
        .put("ruleSearchName", "a@text").put("ruleSearchNoteUrl", "a@href")
        .put("ruleChapterList", "@js:[{title:'第一话',url:'/chapter/1'},{title:'第二话',url:'/chapter/2'}]")
        .put("ruleChapterName", nameRule).put("ruleContentUrl", linkRule)
        .put("ruleBookContent", "img@src").toString()).single()

    @Test fun objectChapterItemsKeepPropertiesThroughTheWholeEngine() {
        val source = source("@js:result.title", "@js:result.url")
        val engine = LegacyMangaEngine({ _, _, _, _ -> "<h1>原创作品</h1>" }) { _, _, _ -> }
        val book = engine.details(source, MangaBook("fixture", "原创作品", source.id, source.name, "$base/book/1"))
        assertEquals(listOf("第一话", "第二话"), book.chapters.map { it.title })
        assertEquals(listOf("$base/chapter/1", "$base/chapter/2"), book.chapters.map { it.url })
    }

    @Test fun objectItemsStillSupportRulesWhichExplicitlyParseTheText() {
        val source = source("@js:JSON.parse(result).title", "@js:JSON.parse(result).url")
        val engine = LegacyMangaEngine({ _, _, _, _ -> "fixture" }) { _, _, _ -> }
        assertEquals(2, engine.details(source, MangaBook("fixture", "原创", source.id, source.name, "$base/book/1")).chapters.size)
    }

    @Test fun singleAmpersandCombinesSelectorsAndClassAttribute() {
        val runtime = LegacyRuleRuntime()
        val html = "<div class='first'><a>甲</a></div><ul class='second'><li>乙</li></ul>"
        assertEquals(listOf("甲", "乙"), runtime.evaluate("class.first&class.second@tag.li", html, base, true)
            .map { org.jsoup.Jsoup.parse(it).text() })
        assertEquals(listOf("lock", "第一话"), runtime.evaluate("tag.div.0@class&tag.span@text", "<div class='lock'></div><span>第一话</span>", base))
        assertEquals(listOf("第一话"), runtime.evaluate("tag.div.0@class&tag.span@text@js:result.replace(/comic-cover /g,'')",
            "<div class='comic-cover'></div><span>第一话</span>", base))
    }

    @Test fun jsonAlternativeDoesNotParseHtmlAsJson() {
        assertTrue(LegacyRuleRuntime().evaluate("class.missing@text||$.name", "<li>暂无匹配</li>", base).isEmpty())
    }

    @Test fun jsonPlaceholdersAndStoredVariablesBuildChapterAddresses() {
        val runtime = LegacyRuleRuntime()
        assertEquals("$base/book/123", runtime.interpolate("$base/book/{\$.comic_id}", """{"comic_id":123}""", base))
        assertEquals(listOf("第一话"), runtime.evaluate("$.title@put:{cid:$.id}", """{"id":37,"title":"第一话"}""", base))
        assertEquals("$base/chapter/37", runtime.interpolate("$base/chapter/@get:{cid}", "", base))
    }

    @Test fun recursiveJsonPathAndExistenceFiltersDoNotDropSearchResults() {
        val runtime = LegacyRuleRuntime()
        val input = """{"data":{"titleList":[{"title":"甲"},{"title":"乙"}]},"other":{"titleList":[{"title":"丙"}]}}"""
        assertEquals(2, runtime.evaluate("$..titleList", input, base, true).size)
        val filtered = runtime.evaluate("$.*[?(@.Title)]", """{"items":[{"Title":"甲"},{"other":"skip"},{"Title":"乙"}]}""", base, true)
        assertEquals(listOf("甲", "乙"), filtered.map { JSONObject(it).getString("Title") })
    }

    @Test fun jsonListsReachFollowingScriptsAsObjectsInsteadOfEncodedStrings() {
        assertEquals(listOf("甲", "乙"), LegacyRuleRuntime().evaluate("$.items@js:result.map(x=>x.title)",
            """{"items":[{"title":"甲"},{"title":"乙"}]}""", base, true))
    }

    @Test fun authorizationHeadersFollowTheSameOriginCredentialPolicy() {
        val headers = mapOf("Authorization" to "Bearer public-fixture", "region" to "0", "Cookie" to "public=1")
        assertEquals(headers, MangaRequestHeaders.scoped(headers, true))
        assertEquals(mapOf("region" to "0"), MangaRequestHeaders.scoped(headers, false))
    }

    @Test fun selectedHtmlListSupportsStringMatchingUsedToBuildDirectories() {
        val rule = "a@js:var m=result.match(/href=\"([^\"]+)\"/); [{title:'第一话',url:m[1]}]"
        val result = LegacyRuleRuntime().evaluate(rule, "<a href='/chapter/1'>第一话</a>", base, true)
        assertEquals("/chapter/1", JSONObject(result.single()).getString("url"))
    }

    @Test fun ycyByteBase64BridgeProducesRealImageAddresses() {
        val address = "$base/原创.png"
        val encoded = java.util.Base64.getEncoder().encodeToString(address.toByteArray())
        assertEquals(listOf(address), LegacyRuleRuntime().evaluate("img@src@js:result.map(x=>ycy.atob(ycy.toBytes(x)))",
            "<img src='$encoded'>", base, true))
    }

    @Test fun imageCoverFallbackKeepsReplacementRegexSeparate() {
        val runtime = LegacyRuleRuntime()
        val rule = "tag.img@src|class.mh-cover@style#.*\\(([^\\)]+)\\)#$1"
        assertEquals(listOf("/cover.png"), runtime.evaluate(rule, "<img src='/cover.png'>", base))
        assertEquals(listOf("/fallback.png"), runtime.evaluate(rule, "<div class='mh-cover' style='background:url(/fallback.png)'></div>", base))
    }

    private fun readerVector(name: String): String {
        val vectors = JSONObject(javaClass.classLoader!!.getResourceAsStream("phone-manga-reader-rule-vectors.json")!!.bufferedReader().use { it.readText() })
        return LegacyRuleCrypto.readRule(vectors.getJSONObject(name), "ruleBookContent")
    }

    @Test fun publicMd5ImageApiRunsWithoutGivingScriptsJavaAccess() {
        var calls = 0
        val runtime = LegacyRuleRuntime { url, _, _ ->
            calls++
            val parsed = java.net.URI(url)
            assertEquals("www.yibenmanhua.com", parsed.host)
            assertTrue(parsed.query.contains("cartoon_id=7&chapter_id=13&mode=supper"))
            assertTrue(Regex("sign=[A-F0-9]{32}$").containsMatchIn(parsed.query))
            """{"data":{"list":[{"img":"https://image.example/1.png"},{"img":"https://image.example/2.png"}]}}"""
        }
        val pages = runtime.evaluate(readerVector("md5"), "<script>var cartoon_id=7;var chapter_id=13;</script>", base, true)
        assertEquals(listOf("https://image.example/1.png", "https://image.example/2.png"), pages)
        assertEquals(1, calls)
        assertTrue(runCatching { runtime.evaluate("@js:new JavaImporter()", "", base) }.isFailure)
    }

    @Test fun nonceReaderCallbackProducesImageListInsteadOfEmptyCssResults() {
        val encoded = java.util.Base64.getEncoder().encodeToString("""{"picture":[{"url":"https://image.example/1.png"},{"url":"https://image.example/2.png"}]}""".toByteArray())
        val html = "<script>window.fixture='1a';</script>data:'${encoded.take(1)}X${encoded.drop(1)}',"
        assertEquals(listOf("https://image.example/1.png", "https://image.example/2.png"), LegacyRuleRuntime().evaluate(readerVector("nonce"), html, base, true))
    }

    @Test fun zeroNextPageDoesNotAppendUnrelatedHomepageImages() {
        val original = source("@js:result.title", "@js:result.url")
        val raw = original.json().getJSONObject("legacy").put("ruleContentUrlNext", "@js:0")
        val source = MangaSource.importText(raw.toString()).single()
        val requests = mutableListOf<String>()
        val engine = LegacyMangaEngine({ url, _, _, _ -> requests += url; "<img src='/body.png'>" }) { _, _, _ -> }
        assertEquals(listOf("$base/body.png"), engine.pages(source, MangaChapter("第一话", "$base/chapter/1")))
        assertEquals(listOf("$base/chapter/1"), requests)
    }

    @Test fun publicBaoziPageDirectUsesTheFullBodyImageParser() {
        val chapter = "https://cn.bzmanga.com/user/page_direct?comic_id=original&section_slot=0&chapter_slot=82"
        assertEquals("https://cn.bzmanga.com/comic/chapter/original/0_82.html", BaoziReader.publicChapter("https://cn.baozimhcn.com", chapter))
        assertEquals("https://cn.bzmanga.com/comic/chapter/original/0_82.html", BaoziReader.publicChapter("https://cn.bzmanga.com", "https://cn.bzmanga.com/comic/chapter/original/0_82.html"))
        assertNull(BaoziReader.publicChapter("https://source.example", chapter))
        assertNull(BaoziReader.publicChapter("https://cn.bzmanga.com", chapter.replace("cn.bzmanga.com", "cn.bzmanga.com.evil.example")))
        assertNull(BaoziReader.publicChapter("https://cn.bzmanga.com", chapter.replace("comic_id=original", "comic_id=../../login")))
    }

    @Test fun slowSuccessfulNetworkDoesNotConsumeTheScriptComputationBudget() {
        val runtime = LegacyRuleRuntime { _, _, _ ->
            Thread.sleep(1700)
            """{"images":["https://image.example/body.png"]}"""
        }
        val rule = "@js:var data=JSON.parse(java.aJax('/fixture')); var sum=0; for(var i=0;i<4000;i++){sum+=i;} data.images"
        assertEquals(listOf("https://image.example/body.png"), runtime.evaluate(rule, "", base, true))
    }

    @Test fun actualImportedImageDecoderMatchesIndependentExpectedUrls() {
        val vector = JSONObject(javaClass.classLoader!!.getResourceAsStream("phone-manga-image-decoder-fixture.json")!!.bufferedReader().use { it.readText() })
        val expected = vector.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(expected.size > 10)
        assertEquals(expected, LegacyRuleRuntime().evaluate(vector.getString("rule"), vector.getString("input"), base, true))
    }

    @Test fun binaryAesReaderBridgeDecodesImageListsWithoutJavaImports() {
        val iv = ByteArray(16) { it.toByte() }
        val key = "1234567890abcdef".toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key,"AES"), javax.crypto.spec.IvParameterSpec(iv))
        val encoded = java.util.Base64.getEncoder().encodeToString(iv + cipher.doFinal("""{"images":["https://image.example/body.png"]}""".toByteArray()))
        val rule = "@js:var bytes=ycy.atobToBytes(ycy.toBytes(result)); JSON.parse(ycy.decrypt('AES/CBC/PKCS7Padding',bytes.slice(16),ycy.toBytes('1234567890abcdef'),bytes.slice(0,16))).images"
        assertEquals(listOf("https://image.example/body.png"), LegacyRuleRuntime().evaluate(rule, encoded, base, true))
        assertTrue(runCatching { LegacyRuleRuntime().evaluate("@js:ycy.decrypt('AES/ECB',[],[],[])", "", base) }.isFailure)
    }
}
