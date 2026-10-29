package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LegacyRuleCryptoTest {
    @Test fun collectedS007RuleMatchesIndependentAesFixture() {
        val rows = JSONArray(resource("manga-st-rule-vectors.json"))
        assertEquals(1, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertEquals(row.getString("plain"), LegacyRuleCrypto.decode(row.getString("raw"), row.getString("url")))
        }
    }

    @Test fun unsupportedEnvelopeFamiliesAreRetainedAndNeverPassedToCssParser() {
        for (format in listOf("R002", "S009", "T005")) {
            val raw = JSONObject("""{"bookSourceName":"旧格式回归","bookSourceUrl":"https://fixture.example",
                "ruleSearchUrl":"/s","ruleSearchList":"a","ruleSearchName":"text","ruleSearchNoteUrl":"href",
                "ruleChapterList":"a","ruleChapterName":"text","ruleContentUrl":"href","ruleBookContent":"img@src"}""")
                .put("ruleChapterList", ":_${format}_DEADBEEF")
            val source = LegadoConverter.convert(raw).source!!
            assertFalse(source.enabled)
            assertTrue(source.compatibilityNote.contains("尚未适配加密规则 $format"))
            assertEquals(raw.getString("ruleChapterList"), source.legacy!!.getString("ruleChapterList"))
        }
    }

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun fixtures() = JSONArray(resource("manga-private-rule-sources.json"))
    private fun vectors() = JSONObject(resource("manga-private-rule-vectors.json"))
    private fun fixture(index: Int): JSONObject = fixtures().let { all ->
        (0 until all.length()).map { all.getJSONObject(it) }.first { it.getInt("catalogIndex") == index }
    }
    private fun encrypt(text: String, url: String, version: String = "H006"): String {
        val constant = if (version == "H006") "t7UNxE8HiRWWOY1Ob9YUSJ4sfQlcU4x8" else error("fixture version")
        val key = MessageDigest.getInstance("MD5").digest((LegacyRuleCrypto.hostMaterial(url) + constant).toByteArray())
            .joinToString("") { "%02x".format(it) }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec("1234567890123456".toByteArray()))
        return ":_${version}_" + Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray()))
    }

    @Test fun all39SourcesAnd71FieldsMatchIndependentExpectedPlaintextHashes() {
        val all = fixtures()
        assertEquals(39, all.length())
        var fields = 0
        var bindings = 0
        for (i in 0 until all.length()) {
            val row = all.getJSONObject(i)
            val raw = row.getJSONObject("source")
            val inspection = LegacyRuleCrypto.inspect(raw)
            assertNull("${row.getInt("catalogIndex")} ${inspection.failure}", inspection.failure)
            if (inspection.bindingUrl != null) bindings++
            val hashes = row.getJSONObject("decodedSha256")
            for (key in hashes.keys()) {
                val plain = LegacyRuleCrypto.readRule(raw, key)
                assertEquals("index=${row.getInt("catalogIndex")} $key", hashes.getString(key), mangaHash(plain))
                assertEquals(plain, LegacyRuleCrypto.decode(raw.getString(key), row.getString("encryptionBaseUrl")))
                fields++
            }
        }
        assertEquals(71, fields)
        assertEquals(6, bindings)
    }

    @Test fun allSixVersionsAndOriginalOddNonHexAndEscapePackingHaveFixedVectors() {
        val v = vectors()
        val rules = v.getJSONArray("vectors")
        for (i in 0 until rules.length()) {
            val row = rules.getJSONObject(i)
            assertEquals(row.getString("plain"), LegacyRuleCrypto.decode(row.getString("raw"), row.getString("url")))
            // 版本分隔符不能换行；只允许 Base64 载荷换行。
            val marker = row.getString("raw").substringBeforeLast('_') + "_"
            val body = row.getString("raw").substringAfterLast('_').chunked(20).joinToString("\r\n")
            assertEquals(row.getString("plain"), LegacyRuleCrypto.decode(" $marker$body\n", row.getString("url")))
        }
        val materials = v.getJSONArray("materials")
        for (i in 0 until materials.length()) {
            val row = materials.getJSONObject(i)
            val actual = LegacyRuleCrypto.hostMaterial(row.getString("url")).toByteArray().joinToString("") { "%02x".format(it) }
            assertEquals(row.getString("url"), row.getString("utf8Hex"), actual)
        }
    }

    @Test fun damagedUnknownOversizedAndWrongBindingRulesStayDisabled() {
        val row = fixture(14)
        val raw = row.getJSONObject("source")
        val ciphertext = raw.getString("ruleChapterList")
        for (bad in listOf(":_H999_AAAA", ":_H006_!bad", ":_H006_AAAA", ":_H006_" + "A".repeat(200_001))) {
            val broken = JSONObject(raw.toString()).put("ruleChapterList", bad)
            val source = LegadoConverter.convert(broken).source!!
            assertFalse(source.enabled)
            assertTrue(source.compatibilityNote.contains("原配置已保存"))
            assertEquals(bad, source.legacy!!.getString("ruleChapterList"))
            assertTrue(runCatching { LegacyRuleCrypto.readRule(broken, "ruleChapterList") }.isFailure)
        }
        assertTrue(runCatching { LegacyRuleCrypto.decode(ciphertext, "https://different.invalid") }.isFailure)
        val alias = fixture(154).getJSONObject("source")
        assertTrue(runCatching { LegacyRuleCrypto.readRule(JSONObject(alias.toString()).put("bookSourceUrl", "https://unrelated.invalid"), "ruleChapterList") }.isFailure)
        val tampered = alias.getString("ruleChapterList").dropLast(8) + "AAAAAAAA"
        assertTrue(runCatching { LegacyRuleCrypto.readRule(JSONObject(alias.toString()).put("ruleChapterList", tampered), "ruleChapterList") }.isFailure)
    }

    @Test fun importAndReloadKeepCiphertextUserDisableAndCurrentNetworkAddress() {
        val root = Files.createTempDirectory("private-rules-test").toFile()
        try {
            val all = fixtures()
            val originals = JSONArray((0 until all.length()).map { all.getJSONObject(it).getJSONObject("source") })
            val (sources, skipped) = LegadoConverter.convertAll(originals)
            assertEquals(37, sources.size); assertEquals(2, skipped.size)
            assertTrue(skipped.all { it.contains("id 重复") })
            for (i in 0 until originals.length()) {
                val source = LegadoConverter.convert(originals.getJSONObject(i)).source!!
                assertEquals(originals.getJSONObject(i).toString(), source.legacy!!.toString())
                assertFalse(source.compatibilityNote.contains("私有加密规则未就绪"))
                assertEquals(originals.getJSONObject(i).optBoolean("enable", true), source.enabled)
                if (!source.enabled) assertTrue(source.compatibilityNote.isNotBlank())
            }
            val original = fixture(154).getJSONObject("source").put("enable", false)
            val source = LegadoConverter.convert(original).source!!
            MangaStore(root).saveSources(listOf(source))
            val reloaded = MangaStore(root).sources().single()
            assertFalse(reloaded.enabled)
            assertEquals("https://www.copymanga.site", reloaded.baseUrl)
            assertEquals("https://www.copymanga.tv", reloaded.rules.getString("legacyEncryptionBaseUrl"))
            assertEquals(original.toString(), reloaded.legacy!!.toString())
            val old = MangaSource.parse(reloaded.rules.put("compatibilityNote", "此源使用私有加密规则，原配置已保存，需适配后启用"))
            assertTrue(old.compatibilityNote.contains("已解封")); assertTrue(old.compatibilityNote.contains("仍停用"))
            assertFalse(old.enabled)
        } finally { root.deleteRecursively() }
    }

    @Test fun encryptedDirectoryAndPageRulesRunThroughRealEngineWithHeadersAndJsonItems() {
        val url = "https://fixture.example"
        val raw = JSONObject().put("bookSourceName", "加密链路回归").put("bookSourceUrl", url)
            .put("ruleSearchUrl", "/search?q=searchKey").put("ruleSearchList", ".book")
            .put("ruleSearchName", "a@text").put("ruleSearchNoteUrl", "a@href")
            .put("ruleChapterList", encrypt("@js:org.jsoup.Jsoup.parse(result).select('.chapters a').toArray().map(function(a){return {title:a.text(),href:a.attr('href')};});", url))
            .put("ruleChapterName", "$.title").put("ruleContentUrl", "$.href")
            .put("ruleBookContent", encrypt("@js:java.atob(result.match(/data-pages=\"([^\"]+)\"/)[1]).split('|')@Header:{\"Referer\":\"host\"}", url))
        val source = LegadoConverter.convert(raw).source!!
        assertTrue(source.enabled)
        val pages = mapOf("/search" to "<div class=book><a href='/book'>测试书</a></div>",
            "/book" to "<div class=chapters><a href='/chapter'>第一话</a></div>",
            "/chapter" to "<div data-pages=\"${Base64.getEncoder().encodeToString("/one.jpg|/two.jpg".toByteArray())}\"></div>")
        var headers = emptyMap<String, String>()
        val engine = LegacyMangaEngine({ target, _, _, _ -> pages[java.net.URI(target).path]!! }, { _, _, h -> headers = h })
        val book = engine.details(source, engine.search(source, "测试", 1).single())
        assertEquals("第一话", book.chapters.single().title)
        assertEquals(listOf("$url/one.jpg", "$url/two.jpg"), engine.pages(source, book.chapters.single()))
        assertEquals(url, headers["Referer"])
    }

    @Test fun realEncryptedBaoziDirectoryAndPagesUseOnlyRestrictedDomFacades() {
        val raw = fixture(165).getJSONObject("source")
        val source = LegadoConverter.convert(raw).source!!
        val runtime = LegacyRuleRuntime()
        val directory = "<h2 class=section-title>章节目录</h2><div><a class=comics-chapters__item href='/c'>第一话</a></div>"
        val items = runtime.evaluate(LegacyRuleCrypto.readRule(raw, "ruleChapterList"), directory, source.baseUrl, true)
        assertEquals(1, items.size); assertTrue(items.single().contains("href=\"/c\""))
        val page = "<amp-state id=chapter1><script type='application/json'>{\"url\":\"https://i.baozicdn.com/p.jpg\"}</script></amp-state>"
        val images = runtime.evaluate(LegacyRuleCrypto.readRule(raw, "ruleBookContent"), page, source.baseUrl, true)
        assertEquals(listOf("https://i.baozimh.com/p.jpg"), images)
    }

    @Test fun auditedJavaAesRulesUseNarrowAdapterAndUnrecognizedJavaStaysBlocked() {
        for (index in listOf(170, 187)) {
            val raw = fixture(index).getJSONObject("source")
            val rule = LegacyRuleCrypto.readRule(raw, "ruleBookContent")
            assertFalse(LegacyPrivateScripts.adapt(rule).contains("JavaImporter"))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec("cxNB23W8xzKJV26O".toByteArray(), "AES"), IvParameterSpec("opb4x7z21vg1f3gI".toByteArray()))
            }
            val data = Base64.getEncoder().encodeToString(cipher.doFinal("[\"https://images.example/a.jpg\"]".toByteArray()))
            val html = "<script>var chapterImages='$data';var chapterPath='/c';</script>"
            val runtime = LegacyRuleRuntime { _, _, _ -> "var hosts=[\"https://images.example\"]" }
            assertEquals(listOf("https://images.example/a.jpg"), runtime.evaluate(rule, html, "https://fixture.example/c", true))
            val modified = rule.replace("var Magua", "var Other")
            assertEquals(modified, LegacyPrivateScripts.adapt(modified))
            assertTrue(runCatching { runtime.evaluate(modified, html, "https://fixture.example/c", true) }.isFailure)
        }
        assertEquals(listOf("undefined|undefined|undefined"), LegacyRuleRuntime().script("typeof Packages+'|'+typeof JavaImporter+'|'+typeof java.lang", "", "https://fixture.example"))
    }

    @Test fun domConnectionFacadeUsesExistingRequestBudgetAndPreservesPostBody() {
        val requests = mutableListOf<Triple<String, String?, Map<String, String>>>()
        val runtime = LegacyRuleRuntime { url, body, headers -> requests += Triple(url, body, headers); "<b>ok</b>" }
        assertEquals(listOf("ok"), runtime.script("org.jsoup.Jsoup.connect('/api').requestBody('id=1&id2=2').post().text()", "", "https://fixture.example/book"))
        assertEquals("https://fixture.example/api", requests.single().first)
        assertEquals("id=1&id2=2", requests.single().second)
        assertTrue(requests.single().third.getValue("Content-Type").startsWith("application/x-www-form-urlencoded"))
        assertTrue(runCatching { runtime.script("for(var i=0;i<13;i++)org.jsoup.Jsoup.connect('/api').get();", "", "https://fixture.example/book") }.isFailure)
        assertEquals(12, requests.size)
    }
}
