package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MangaDecoderTest {
    private fun samples() = JSONArray(javaClass.classLoader!!.getResourceAsStream("manga-decoder-sources.json")!!.bufferedReader().use { it.readText() })
    private fun sample(kind: MangaDecoder.Kind): JSONObject = samples().let { all ->
        (0 until all.length()).map { all.getJSONObject(it) }.first { MangaDecoder.identify(MangaDecoder.rule(it))?.kind == kind }
    }
    private fun spec(kind: MangaDecoder.Kind) = MangaDecoder.identify(MangaDecoder.rule(sample(kind)))!!
    private fun bika() = LegadoConverter.convert(sample(MangaDecoder.Kind.BIKA_AES)).source!!

    @Test fun allPublishedDecoderScriptsAreRecognizedWithoutRunningJava() {
        val all = samples()
        val kinds = mutableSetOf<MangaDecoder.Kind>()
        val fingerprints = mutableSetOf<String>()
        for (i in 0 until all.length()) {
            val raw = MangaDecoder.rule(all.getJSONObject(i))
            val decoder = MangaDecoder.identify(raw)
            assertNotNull("未识别 ${all.getJSONObject(i).optString("bookSourceName")}", decoder)
            kinds += decoder!!.kind; fingerprints += decoder.fingerprint
            assertEquals(decoder, MangaDecoder.identify("\n" + raw.replace("\n", "\r\n") + "\n"))
        }
        assertEquals(25, all.length())
        assertEquals(6, kinds.size)
        assertEquals(7, fingerprints.size)
    }

    @Test fun recognizedDecoderSourcesAndSupportedEncryptedRulesArePreserved() {
        val all = samples()
        var enabled = 0
        for (i in 0 until all.length()) {
            val original = all.getJSONObject(i)
            val source = LegadoConverter.convert(original).source!!
            assertNotNull(source.legacy)
            assertEquals(MangaDecoder.rule(original), MangaDecoder.rule(source.legacy))
            if (source.enabled) { enabled++; assertTrue(source.compatibilityNote.contains("已适配")) }
            else assertTrue(source.compatibilityNote.contains("私有加密规则"))
        }
        assertEquals(25, enabled)
    }

    @Test fun modifiedOrUnknownCodeIsNotExecutedOrEnabled() {
        assertNull(MangaDecoder.identify("@js:Packages.java.lang.Runtime.getRuntime().exec('anything')"))
        val original = sample(MangaDecoder.Kind.BIKA_AES).put("ruleBookContentDecoder", "@js:unknownDecoder(result)")
        val source = LegadoConverter.convert(original).source!!
        assertFalse(source.enabled)
        assertTrue(source.compatibilityNote.contains("尚未适配"))
    }

    @Test fun explicitDisableOnSupportedSourceIsRespected() {
        assertFalse(LegadoConverter.convert(sample(MangaDecoder.Kind.BIKA_AES).put("enable", false)).source!!.enabled)
    }

    @Test fun oldSavedDecoderStatusUpdatesWithoutEnablingTheSource() {
        val old = MangaSource.parse(bika().rules.put("compatibilityNote", "此源需要专用图片解密器，原配置已保存，需适配后启用").put("enabled", false))
        assertFalse(old.enabled)
        assertTrue(old.compatibilityNote.contains("已适配"))
        assertTrue(old.compatibilityNote.contains("仍停用"))
    }

    @Test fun malformedCatalogAddressDoesNotAbortOtherSourceImports() {
        val bad = sample(MangaDecoder.Kind.BIKA_AES).put("bookSourceUrl", "https://bad.example/[invalid")
        val result = LegadoConverter.convert(bad)
        assertNull(result.source)
        assertTrue(result.skipReason.contains("地址格式无效"))
        val (sources, skipped) = LegadoConverter.convertAll(JSONArray().put(bad).put(sample(MangaDecoder.Kind.BIKA_AES)))
        assertEquals(1, sources.size)
        assertEquals(1, skipped.size)
    }

    @Test fun aesUsesUtf8LiteralKeysAndHandlesBinaryPayloads() {
        val payload = ByteArray(257) { (it * 71).toByte() }
        for ((kind, pair) in listOf(
            MangaDecoder.Kind.MANWA_AES to ("my2ecret782ecret" to "my2ecret782ecret"),
            MangaDecoder.Kind.BIKA_AES to ("f5d965df75336270" to "97b60394abc2fbe1"),
        )) {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pair.first.toByteArray(), "AES"), IvParameterSpec(pair.second.toByteArray()))
            assertArrayEquals(payload, MangaDecoder.decrypt(spec(kind), cipher.doFinal(payload)))
        }
    }

    @Test fun corruptCiphertextFailsWithActionableMessage() {
        for (bytes in listOf(ByteArray(0), ByteArray(17), ByteArray(32))) {
            val failure = runCatching { MangaDecoder.decrypt(spec(MangaDecoder.Kind.BIKA_AES), bytes) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("图片"))
        }
    }

    @Test fun jmThresholdAndAsciiDigestMatchPublishedAlgorithm() {
        val decoder = spec(MangaDecoder.Kind.JM_ROWS)
        for ((path, count) in listOf("268849/1" to null, "268850/1" to 10, "421925/23" to 8, "421926/23" to 12, "800001/003" to 10))
            assertEquals(path, count, MangaDecoder.stripCount(decoder, "https://images.example/photos/$path.jpg"))
        assertNull(MangaDecoder.stripCount(decoder, "https://images.example/photos/421926/23.gif"))
        assertNull(MangaDecoder.stripCount(decoder, "https://images.example/cover/421926.jpg"))
    }

    @Test fun guoguoUsesOnlyTheNumericImageFilename() {
        val decoder = spec(MangaDecoder.Kind.GUOGUO_ROWS)
        assertEquals(76, MangaDecoder.stripCount(decoder, "https://images.example/1234/003.png?ts=999"))
        assertEquals(72, MangaDecoder.stripCount(decoder, "https://images.example/12345.jpg"))
        assertNull(MangaDecoder.stripCount(decoder, "https://images.example/banner.png"))
    }

    @Test fun rowsMoveLastRemainderStripToTopWithEveryPixelPreserved() {
        val original = IntArray(11) { it }
        val restored = IntArray(11) { -1 }
        MangaDecoder.rowSlices(11, 3).forEach { slice -> original.copyInto(restored, slice.target, slice.source, slice.source + slice.length) }
        assertArrayEquals(intArrayOf(6, 7, 8, 9, 10, 3, 4, 5, 0, 1, 2), restored)
        for (height in listOf(9, 10, 93, 1024)) for (count in 1..minOf(height, 80)) {
            val source = IntArray(height)
            val target = IntArray(height)
            MangaDecoder.rowSlices(height, count).forEach { s ->
                for (p in 0 until s.length) { source[s.source + p]++; target[s.target + p]++ }
            }
            assertTrue(source.all { it == 1 } && target.all { it == 1 })
        }
    }

    @Test fun columnsDoNotLosePixelsWhenWidthIsNotDivisible() {
        val restored = IntArray(11) { -1 }
        MangaDecoder.columnSlices(11, 3).forEach { s -> for (i in 0 until s.length) restored[s.target + i] = s.source + i }
        assertArrayEquals(intArrayOf(7, 8, 9, 10, 3, 4, 5, 6, 0, 1, 2), restored)
        assertEquals((0..10).toSet(), restored.toSet())
    }

    @Test fun invalidStripCountsAndHugeUrlValuesAreRejected() {
        for (count in listOf(0, -1, 101, 513)) assertTrue(runCatching { MangaDecoder.rowSlices(100, count) }.isFailure)
        assertTrue(runCatching { MangaDecoder.stripCount(spec(MangaDecoder.Kind.XIANG_COLUMNS), "https://i.example/img?w=999999") }.isFailure)
    }

    @Test fun baoziRetainsOriginalWithinToleranceAndUsesFragmentDimensions() {
        assertNull(MangaDecoder.targetSize("https://i.example/a.jpg", 800, 1200))
        assertNull(MangaDecoder.targetSize("https://i.example/a.jpg#1x1", 800, 1200))
        assertNull(MangaDecoder.targetSize("https://i.example/a.jpg#400x600", 800, 1200))
        assertEquals(MangaDecoder.Size(400, 1200), MangaDecoder.targetSize("https://i.example/a.jpg#400x1200", 800, 1000))
        assertTrue(runCatching { MangaDecoder.targetSize("https://i.example/a.jpg#0x100", 800, 1000) }.isFailure)
    }

    @Test fun prefixHeadersPreserveBikaImageScriptAndReferer() {
        val source = bika()
        var imageHeaders = emptyMap<String, String>()
        val engine = LegacyMangaEngine({ _, _, _, _ ->
            """<script>var imgs=["https:\u002F\u002Fpic.drleao.cn\u002Fsynthetic.bin?auth_key=fixture\u0026x=1","https://pic.drleao.cn/synthetic.bin?auth_key=fixture&x=1"];</script>"""
        }, { _, _, h -> imageHeaders = h })
        assertEquals(listOf("https://pic.drleao.cn/synthetic.bin?auth_key=fixture&x=1"), engine.pages(source, MangaChapter("原创测试", "https://bikahub.net/read/fixture")))
        assertEquals("https://bikahub.net/", imageHeaders["Referer"])
    }

    @Test fun headerParserHandlesEscapesBracesCaseAndBothPositions() {
        val header = """{"Referer":"https://e.example/}x","Accept":"a\\\"}b"}"""
        val parts = splitLegacyHeaders("@header:$header<js>result;</js>@Header:{\"User-Agent\":\"test\"}")
        assertEquals("<js>result;</js>", parts.rule)
        assertEquals(2, parts.objects.size)
        assertEquals("https://e.example/}x", JSONObject(parts.objects.first()).getString("Referer"))
        assertTrue(runCatching { splitLegacyHeaders("img@src@Header:{\"a\":\"incomplete") }.isFailure)
    }

    @Test fun runtimeKeepsSelectorAfterPrefixHeader() {
        val html = """<img src="synthetic.png">"""
        assertEquals(listOf("synthetic.png"), LegacyRuleRuntime().evaluate("@Header:{\"Referer\":\"https://e.example\"}img@src", html, "https://e.example", true))
    }

    @Test fun headerTemplateQuotesDoNotTruncateTheObject() {
        val header = """{"Accept":"{{var x = "}"; x + "ok"}}","Referer":thisPage}"""
        val parts = splitLegacyHeaders("@Header:$header img@src")
        assertEquals("img@src", parts.rule)
        assertEquals(listOf(header), parts.objects)
    }

    @Test fun prefixHeaderAndPostBodyCanCoexist() {
        assertEquals("/search@Header:{\"Referer\":\"https://e.example/\"}" to "q=test",
            legacySearchPost("@Header:{\"Referer\":\"https://e.example/\"}/search@q=test"))
    }

    @Test fun decodedCacheDoesNotReuseOldPuzzleOrCoverAndSurvivesSourceReload() {
        val root = Files.createTempDirectory("manga-decoder-cache").toFile()
        try {
            val store = MangaStore(root)
            val source = bika()
            val url = "https://images.example/synthetic.bin"
            store.saveSources(listOf(source))
            val old = File(store.imageDir, mangaHash("${source.id}|$url")).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val page = store.cachedImage(source.id, url)
            val cover = store.cachedImage(source.id, url, cover = true)
            assertNotEquals(old, page); assertNotEquals(page, cover)
            page.writeBytes(byteArrayOf(4, 5, 6))
            store.protectImage(source.id, url)
            val protected = store.cachedImage(source.id, url)
            assertEquals(store.downloadDir, protected.parentFile)
            assertEquals(protected, MangaStore(root).cachedImage(source.id, url))
            assertEquals(protected, MangaStore(root).also { it.saveSources(listOf(source.copy(enabled = false))) }.cachedImage(source.id, url))
            store.saveSources(emptyList())
            assertEquals(protected, MangaStore(root).cachedImage(source.id, url))
            assertTrue(old.isFile)
            assertEquals(mangaHash("plain|$url"), store.cachedImage("plain", url).name)
        } finally { root.deleteRecursively() }
    }

    @Test fun deletingDownloadsAlsoCleansOldDecoderCacheAndRetainsSharedPages() {
        val root = Files.createTempDirectory("manga-decoder-delete").toFile()
        try {
            val store = MangaStore(root); val source = bika(); store.saveSources(listOf(source))
            val url = "https://images.example/test.bin"
            val old = File(store.downloadDir, mangaHash("${source.id}|$url")).apply { writeBytes(byteArrayOf(1)) }
            val decoded = store.cachedImage(source.id, url).apply { writeBytes(byteArrayOf(2)) }
            store.protectImage(source.id, url)
            val chapter = MangaChapter("测试", "https://e.example/chapter", listOf(url))
            val first = MangaBook("decoder-delete-first", "测试一", source.id, chapters = listOf(chapter), cachedChapters = setOf(chapter.url))
            val second = first.copy(id = "decoder-delete-second", title = "测试二")
            store.upsert(first); store.upsert(second)
            assertEquals(0L, store.deleteDownloads(first.id))
            assertTrue(old.isFile && decoded.isFile)
            store.remove(first.id)
            store.saveSources(emptyList())
            assertEquals(3L, store.deleteDownloads(second.id))
            assertFalse(old.isFile || decoded.isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun fullCatalogAuditWhenRequestedIdentifiesEveryImageDecoder() {
        val path = System.getProperty("manga.decoder.catalog") ?: return
        val all = JSONArray(File(path).readText())
        // 目录包含同名同域的重复投稿；逐项转换核对，避免 ID 去重影响算法覆盖统计。
        val converted = (0 until all.length()).map { LegadoConverter.convert(all.getJSONObject(it)) }
        val sources = converted.mapNotNull { it.source }
        val withDecoder = sources.filter { MangaDecoder.rule(it.legacy).isNotBlank() }
        assertEquals(25, withDecoder.size)
        // H-rule support now covers the previously disabled encrypted decoder source too.
        // All 25 originals in this snapshot explicitly enable their source; keep that intent.
        assertEquals(25, withDecoder.count { it.enabled })
        withDecoder.forEach { source ->
            assertEquals("启停状态应与原始配置一致：${source.name}", source.legacy!!.optBoolean("enable", true), source.enabled)
            assertNotNull("必须识别实际解码脚本：${source.name}", MangaDecoder.identify(MangaDecoder.rule(source.legacy)))
        }
        println("DECODER_CATALOG catalog=${all.length()} converted=${sources.size} invalid=${converted.count { it.source == null }} enabled=${sources.count { it.enabled }} decoderSources=${withDecoder.size} decoderEnabled=${withDecoder.count { it.enabled }}")
        converted.filter { it.source == null }.forEach { println("DECODER_CATALOG_INVALID name=${it.name} reason=${it.skipReason}") }
    }
}
