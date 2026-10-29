package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.ResponseDecoder
import com.shengzimao.reader.mobile.data.MangaSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

/**
 * 端到端回归：模拟 yckceo.com 链接的完整响应处理链路。
 *
 * 现场：链接 302 跳到 gcore.jsdelivr.net，返回 200 + application/json，
 * 但正文是 base64(zlib(97 个阅读书源的 JSON 数组))。
 * 旧实现直接把它当明文 JSON，报“返回的内容不是有效的 JSON 对象或数组”。
 */
class MangaImportEndToEndTest {

    private fun zlib(data: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(data); d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    private fun sampleJson(): String =
        javaClass.classLoader!!.getResourceAsStream("legado-sample.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun compressedLegadoPayloadImportsSuccessfully() {
        // 构造与现场完全一致的响应体形态。
        val compressed = Base64.getEncoder().encodeToString(zlib(sampleJson().toByteArray()))
        assertTrue("现场响应不以 { 开头", !compressed.trimStart().startsWith("{"))

        // importText 内部先做 ResponseDecoder.decode，再识别 Legado 并转换。
        val sources = MangaSource.importText(compressed)
        assertTrue("应导入出至少一个可用源，实际 ${sources.size}", sources.isNotEmpty())
        println("导入 ${sources.size} 个源，跳过 ${MangaSource.lastSkipped.size} 个")
        sources.take(5).forEach { println("  ✔ ${it.name} (${it.baseUrl})") }

        // 每个导入的源都必须能再次解析（可持久化）。
        sources.forEach { s ->
            val again = MangaSource.parse(org.json.JSONObject(s.definition))
            assertTrue(again.name.isNotBlank())
        }
    }

    @Test
    fun skippedListIsPopulatedForScriptSources() {
        val compressed = Base64.getEncoder().encodeToString(zlib(sampleJson().toByteArray()))
        MangaSource.importText(compressed)
        val skipped = MangaSource.lastSkipped
        assertTrue("有效源不能因旧式脚本被丢弃", skipped.isEmpty())
        val sources = MangaSource.importText(compressed)
        assertTrue(sources.size == 97)
        assertTrue(sources.any { it.compatibilityNote.contains("已解封") })
        println("跳过 ${skipped.size} 个，示例：")
        skipped.take(3).forEach { println("  ✘ $it") }
        assertTrue("跳过原因应说明字段", skipped.all { it.contains("：") })
    }

    @Test
    fun plainLegadoArrayWithoutCompressionAlsoWorks() {
        // 有些镜像直接返回明文 JSON 数组。
        val sources = MangaSource.importText(sampleJson())
        assertTrue(sources.isNotEmpty())
    }

    @Test
    fun nativeFormatStillImportsUnaffected() {
        // 本应用自身格式不应被 Legado 分支干扰。
        val native = """[{"schemaVersion":1,"id":"mine","name":"我的源","baseUrl":"https://e.com",
            "search":{"url":"/s?q={query}&page={page}","items":".i","title":".t","link":"a@href"},
            "detail":{"chapters":".c a"},
            "reader":{"pages":".r img"}}]"""
        val sources = MangaSource.importText(native)
        assertTrue(sources.size == 1)
        assertTrue(sources.first().id == "mine")
    }
}
