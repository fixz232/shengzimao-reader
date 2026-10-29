package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Actual imported rules, checked against independent text hashes and deterministic page responses. */
class LegacyReaderCallbackRegressionTest {
    private val base = "https://reader.example/chapter/1"
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun rule(index: Int, field: String = "ruleBookContent") = JSONObject(resource("phone-legacy-reader-rules.json")).getJSONObject(index.toString()).getString(field)

    @Test fun everyEncryptedFieldInTheActualImportedLibraryDecodesToTheIndependentTextHash() {
        val vectors = JSONArray(resource("phone-imported-rule-envelopes.json"))
        assertEquals(156, vectors.length())
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val field = vector.getString("field")
            val source = JSONObject().put("bookSourceUrl", vector.getString("sourceUrl")).put(field, vector.getString("ciphertext"))
            val text = LegacyRuleCrypto.readRule(source, field)
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals("source=${vector.getInt("index")} field=$field", vector.getString("plaintextSha256"), hash)
        }
    }

    @Test fun importedLegacyCallbackExecutesInsteadOfBeingTreatedAsASelector() {
        val html = "<script>eval(\"newImgs=['https://images.example/1.png','https://images.example/2.png'];\")</script>"
        assertEquals(listOf("https://images.example/1.png", "https://images.example/2.png"), LegacyRuleRuntime().evaluate(rule(39), html, base, true))
    }

    @Test fun importedCallbackUsesTheBoundedJsEngineNetworkAlias() {
        val runtime = LegacyRuleRuntime(mutableMapOf("coid" to "/123.html")) { url, body, _ ->
            assertTrue(url.contains("cn_svr.asp?z=1&s=fixture&cid=2&coid=123"))
            assertNull(body)
            "\"https://images.example/\""
        }
        val html = "<script>eval(\"atsvr=1;img_s='fixture';link_z='colist_2.html';msg='1.png|2.png';\")</script>"
        assertEquals(listOf("https://images.example/1.png", "https://images.example/2.png"), runtime.evaluate(rule(36), html, base, true))
    }

    @Test fun importedManbenCallbackRequestsAndReturnsAllDeclaredPages() {
        val requests = mutableListOf<String>()
        val runtime = LegacyRuleRuntime { url, _, _ ->
            requests += url
            val page = java.net.URI(url).query.split('&').first { it.startsWith("page=") }.substringAfter('=')
            """{"Images":"/$page.png","ImagePix":"https://images.example"}"""
        }
        val html = "<html style=''><script>var CHAPTER_ID = 37;</script><div class='bottomCode'><span>99</span></div><div class='bottomCode'><span>20</span></div></html>"
        assertEquals((1..20).map { "https://images.example/$it.png" }, runtime.evaluate(rule(54), html, base, true))
        assertEquals(20, requests.size)
    }

    @Test fun negativeLegacyIndicesSelectTheLastMatchingGroupAndKeepItsAttributes() {
        val html = "<div class='group'><a href='/wrong'>前</a></div><div class='group'><a href='/right'>后</a></div>"
        val runtime = LegacyRuleRuntime()
        assertEquals(listOf("后"), runtime.evaluate("class.group.-1@tag.a@text", html, base))
        assertEquals(listOf("/right"), runtime.evaluate("class.group.-1@tag.a@href", html, base))
        assertTrue(runtime.evaluate("class.group.-3@tag.a@href", html, base).isEmpty())
    }

    @Test fun ordinaryFieldScriptsStillHaveTheOriginalNetworkRequestLimit() {
        var calls = 0
        val runtime = LegacyRuleRuntime { _, _, _ -> calls++; "fixture" }
        assertTrue(runCatching { runtime.evaluate("@js:for(var i=0;i<13;i++){java.aJax('/page');}", "", base) }.isFailure)
        assertEquals(12, calls)
    }

    @Test fun chapterCallbacksCannotExceedTheirBoundedNetworkBudget() {
        var calls = 0
        val runtime = LegacyRuleRuntime { _, _, _ -> calls++; "fixture" }
        assertTrue(runCatching { runtime.evaluate("\$function getImgList(){for(var i=0;i<129;i++){JsEngine.aJax('/page');}return [];}", "", base, true) }.isFailure)
        assertEquals(128, calls)
    }

    @Test fun callbackNetworkFailuresKeepTheirActualTypeAndMessage() {
        val error = java.io.IOException("fixture connection reset")
        val runtime = LegacyRuleRuntime { _, _, _ -> throw error }
        val failure = runCatching { runtime.evaluate("\$function getImgList(){return [JsEngine.aJax('/page')];}", "", base, true) }.exceptionOrNull()
        assertSame(error, failure)
    }

    @Test fun importedCallbackCombinesAbsoluteAndRelativeImageAddresses() {
        val html = "<script>var chapterImages = ['1.png','https://images.example/2.png'];var chapterPath = \"chapter/\";</script>"
        assertEquals(listOf("https://res.xiaoqinre.com/chapter/1.png", "https://images.example/2.png"), LegacyRuleRuntime().evaluate(rule(69), html, base, true))
    }

    @Test fun stringListsProvideTheLegacyToArrayBridgeAndToastDoesNotCrash() {
        val script = "@js:ycy.toast('fixture');java.getStringList('img@src',false).toArray().map(x=>JsEngine.atob(x))"
        val encoded = java.util.Base64.getEncoder().encodeToString("https://images.example/1.png".toByteArray())
        assertEquals(listOf("https://images.example/1.png"), LegacyRuleRuntime().evaluate(script, "<img src='$encoded'>", base, true))
    }

    @Test fun importedDirectoryPostPreservesItsRequiredReferer() {
        var calls = 0
        val runtime = LegacyRuleRuntime { url, body, headers ->
            calls++
            assertEquals("http://m.qmanwu2.com/bookchapter/", url)
            assertEquals("id=9&id2=10", body)
            assertEquals(base, headers["Referer"])
            assertTrue(headers.getValue("Content-Type").startsWith("application/x-www-form-urlencoded"))
            """[{"id":"222","name":"第二话"}]"""
        }
        val html = "<div class='catalog-list'><a href='/111.html'>第一话</a></div><script>var fixture={\"id\": 9, \"id2\": 10};</script>"
        val result = runtime.evaluate(rule(76, "ruleChapterList"), html, base, true).map(::JSONObject)
        assertEquals(listOf("第二话", "第一话"), result.map { it.getString("name") })
        assertEquals(1, calls)
    }
}
