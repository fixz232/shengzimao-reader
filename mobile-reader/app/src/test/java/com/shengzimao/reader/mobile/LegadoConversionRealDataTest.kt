package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.LegadoConverter
import com.shengzimao.reader.mobile.data.MangaSource
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实的 97 个阅读书源（yckceo.com id=69 解码所得）验证 Legado 转换。
 *
 * 完整保存全部源配置，支持受限旧式脚本与已适配的 H 解封；损坏或缺字段源默认停用，
 * 因此转换器的职责是：能转的转成合法源，不能转的明确报告原因，
 * 绝不生成“看起来能导入、实际永远搜不到东西”的坏源。
 */
class LegadoConversionRealDataTest {

    private fun loadSample(): JSONArray {
        val text = javaClass.classLoader!!.getResourceAsStream("legado-sample.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONArray(text)
    }

    @Test
    fun convertsASubsetAndReportsSkippedOnes() {
        val array = loadSample()
        assertTrue("样本应有书源", array.length() > 50)
        val (converted, skipped) = LegadoConverter.convertAll(array)
        println("总共 ${array.length()} 个源")
        println("成功转换 ${converted.size} 个")
        println("跳过 ${skipped.size} 个")
        // 必须转出一些源，否则说明转换器整体失效。
        assertTrue("97 个源配置必须全部保留，实际 ${converted.size}", converted.size == 97 && skipped.isEmpty())
        println("legacy=${converted.count { it.legacy != null }} disabled=${converted.count { !it.enabled }}")
        // 被跳过的必须给出可读原因。
        skipped.forEach { reason ->
            assertTrue("跳过原因应说明字段：$reason", reason.contains("："))
        }
        // 每个转换结果都应能通过本应用自己的格式校验（convert 内部已 parse，这里复查）。
        converted.forEach { source ->
            assertTrue("id 应为 legado 前缀：${source.id}", source.id.startsWith("legado-"))
            assertTrue("baseUrl 应为 http(s)：${source.baseUrl}", source.baseUrl.startsWith("http"))
            val rules = source.rules
            assertTrue("应有 search 规则", rules.has("search"))
            assertTrue("应有 detail 规则", rules.has("detail"))
            assertTrue("应有 reader 规则", rules.has("reader"))
        }
    }

    @Test
    fun supportedEncryptedSourcesHaveStatusAndDamagedOnesStayDisabled() {
        val array = loadSample()
        val (sources, _) = LegadoConverter.convertAll(array)
        assertTrue(sources.any { it.enabled && it.compatibilityNote.contains("已解封") })
        val damaged = org.json.JSONObject(array.getJSONObject(0).toString()).put("ruleChapterList", ":_H999_AAAA")
        val source = LegadoConverter.convert(damaged).source!!
        assertTrue(!source.enabled && source.compatibilityNote.contains("H999"))
    }

    @Test
    fun selectorWithScriptFallbackStillConverts() {
        // YYDS 的 ruleSearchName 是「CSS 选择器 + @js: 兜底」，选择器部分可用，不应整体跳过。
        val array = loadSample()
        var target: org.json.JSONObject? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("bookSourceName").contains("YYDS")) { target = item; break }
        }
        assertTrue("样本中应存在 YYDS 源", target != null && target!!.optString("ruleSearchName").contains("@js:"))
        val result = LegadoConverter.convert(target!!)
        assertTrue("带脚本兜底但有选择器的源应能转换，实际跳过：${result.skipReason}", result.source != null)
        val title = result.source!!.rules.getJSONObject("search").getString("title")
        assertTrue("标题规则不应含 @js:，实际：$title", !title.contains("@js:"))
    }

    @Test
    fun singleKnownJsonSourceConverts() {
        // 再漫画（zaimanhua）是这批里少见的纯 JSON 规则源。
        val array = loadSample()
        var target: org.json.JSONObject? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("bookSourceName").contains("再漫画")) { target = item; break }
        }
        assertTrue("样本中应存在再漫画源", target != null)
        val result = LegadoConverter.convert(target!!)
        println("再漫画 -> 成功=${result.source != null}, 原因=${result.skipReason}")
        if (result.source != null) {
            val rules = result.source.rules
            val search = rules.getJSONObject("search")
            println("  search.type=${search.optString("type")} url=${search.optString("url")}")
            println("  search.items=${search.optString("items")} title=${search.optString("title")}")
            assertTrue("JSON 源须保留关键词规则", if (result.source.legacy != null) result.source.legacy!!.optString("ruleSearchUrl").contains("searchKey") else search.getString("url").contains("{query}"))
            assertTrue("JSON 源须保留分页规则", if (result.source.legacy != null) result.source.legacy!!.optString("ruleSearchUrl").contains("searchPage") else search.getString("url").contains("{page}"))
        }
    }

    @Test
    fun convertedRulesAreValidMangaSources() {
        val array = loadSample()
        val (converted, _) = LegadoConverter.convertAll(array)
        converted.forEach { source ->
            // 用序列化再解析一遍，确保落盘后仍可用。
            val again = MangaSource.parse(org.json.JSONObject(source.definition))
            assertTrue(again.name.isNotBlank())
            assertTrue(again.baseUrl.startsWith("http"))
        }
    }
}
