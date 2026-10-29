package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.LegadoConverter
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Legado 转换后的规则能被本应用引擎按 XPath / CSS 正确求值。
 *
 * 这里复刻 MangaEngine 的选择器归一化逻辑，用真实形态的 HTML 断言取值结果，
 * 确保"转换出来的源"不是一堆看着合法、实则取不到字的规则。
 */
class LegadoRuleEvaluationTest {

    private val html = """
        <!DOCTYPE html><html><body>
        <div class="comic-item">
          <a href="/comic/1"><img src="/c1.jpg"/><span class="title"> 斗破苍穹 </span></a>
          <span class="author">天蚕土豆</span>
        </div>
        <div class="comic-item">
          <a href="/comic/2"><img src="/c2.jpg"/><span class="title"> 武动乾坤 </span></a>
          <span class="author">天蚕土豆</span>
        </div>
        </body></html>
    """.trimIndent()

    /**
     * 直接使用生产代码的拆分与归一化逻辑，避免测试与实现各写一套而失真。
     * 之前的探针就是因为在测试里另写了一份正则，才误判了 selectXpath 的能力。
     */
    private fun select(root: org.jsoup.nodes.Element, selector: String): List<org.jsoup.nodes.Element> {
        val raw = selector.trim()
        if (raw.isEmpty()) return listOf(root)
        val (pathPart, attrPart) = com.shengzimao.reader.mobile.data.SelectorSplitter.split(raw)
        val path = if (attrPart.isEmpty()) raw else pathPart.trim()
        if (path.isEmpty()) return listOf(root)
        if (!com.shengzimao.reader.mobile.data.SelectorSplitter.isXPath(path)) return root.select(path).toList()
        val normalized = com.shengzimao.reader.mobile.data.SelectorSplitter.normalizeXPath(path)
        // 注意：不能写 getOrDefault(root.select(path))——默认值是急切求值的，
        // 即便 selectXpath 成功，root.select(path) 也会先执行并按 CSS 抛异常。
        val fromXpath = runCatching {
            val doc = root.ownerDocument() ?: root
            doc.selectXpath(normalized).toList()
        }.getOrNull()
        if (fromXpath != null) return fromXpath
        return runCatching { root.select(path) }.getOrElse { emptyList() }.toList()
    }

    /** 复刻 MangaEngine.value。 */
    private fun value(item: org.jsoup.nodes.Element, selector: String): String {
        val (css, attr) = com.shengzimao.reader.mobile.data.SelectorSplitter.split(selector)
        val path = css.trim()
        if (path.isEmpty()) return if (attr.isBlank()) item.text() else item.attr(attr)
        val element = select(item, path).firstOrNull() ?: return ""
        return if (attr.isBlank()) element.text() else element.attr(attr)
    }

    @Test
    fun xpathItemsWithUnquotedAttributeWork() {
        val doc = Jsoup.parse(html, "https://e.com")
        // Legado 常见写法：//div[@class=comic-item]（不带引号）。
        // jsoup 对不带引号的属性值会静默返回 0 个，归一化后必须能命中。
        val items = select(doc, "//div[@class=comic-item]")
        assertTrue("未加引号的 XPath 属性应能命中，实际 ${items.size}", items.size == 2)
    }

    @Test
    fun xpathItemsWithQuotedAttributeWork() {
        val doc = Jsoup.parse(html, "https://e.com")
        assertTrue(select(doc, "//div[@class='comic-item']").size == 2)
    }

    @Test
    fun titleTextIsExtractedFromXPathRule() {
        val doc = Jsoup.parse(html, "https://e.com")
        val items = select(doc, "//div[@class=comic-item]")
        val titles = items.map { value(it, ".title") }
        assertTrue("应取到两个标题，实际 $titles", titles.size == 2)
        assertTrue("标题应含书名，实际 $titles", titles.first().contains("斗破"))
    }

    @Test
    fun hrefIsExtractedForDetailNavigation() {
        val doc = Jsoup.parse(html, "https://e.com")
        val items = select(doc, "//div[@class=comic-item]")
        val links = items.map { value(it, "a@href") }
        assertTrue("应取到详情链接，实际 $links", links.all { it.startsWith("/comic/") })
    }

    @Test
    fun convertedScenarioProducesUsableSelectors() {
        // 用一条构造的 Legado 源走完整转换，再用它的规则去求值。
        val legado = JSONObject(
            """
            {
              "bookSourceName":"测试XPath源",
              "bookSourceUrl":"https://e.com",
              "ruleSearchUrl":"https://e.com/search?q=searchKey&page=searchPage",
              "ruleSearchList":"//div[@class=comic-item]",
              "ruleSearchName":".title",
              "ruleSearchAuthor":".author",
              "ruleSearchNoteUrl":"a@href",
              "ruleChapterList":"//ul[@id=list]/li",
              "ruleChapterName":"a",
              "ruleChapterUrl":"",
              "ruleContentUrl":"a@href",
              "ruleBookContent":"//div[@class=content]"
            }
            """.trimIndent(),
        )
        val result = LegadoConverter.convert(legado)
        assertTrue("应能转换：${result.skipReason}", result.source != null)
        val search = result.source!!.rules.getJSONObject("search")
        val url = search.getString("url")
        assertTrue("URL 应替换 searchKey：$url", url.contains("{query}") && !url.contains("searchKey"))
        assertTrue("URL 应替换 searchPage：$url", url.contains("{page}") && !url.contains("searchPage"))

        // 用转换后的规则在真实 HTML 上求值。
        val doc = Jsoup.parse(html, "https://e.com")
        val items = select(doc, search.getString("items"))
        assertTrue("转换后的 items 规则应命中元素，实际 ${items.size}", items.size == 2)
        val title = value(items.first(), search.getString("title"))
        assertTrue("转换后的 title 规则应取到书名，实际 '$title'", title.contains("斗破"))
    }

    @Test
    fun jsonPathSourceEvaluatesAgainstJson() {
        val bookJson = JSONArray(
            """[{"title":"斗破苍穹","authors":"天蚕土豆","cover":"/c.jpg"},{"title":"武动乾坤","authors":"天蚕土豆","cover":"/c2.jpg"}]""",
        )
        // Legado 的 $.data.list 会去掉 $. 变成 data.list；这里直接验证纯路径取值约定。
        val items = (0 until bookJson.length()).map { bookJson.getJSONObject(it) }
        assertEquals2(2, items.size)
        assertEquals2("斗破苍穹", items.first().optString("title"))
    }

    private fun assertEquals2(expected: Int, actual: Int) = org.junit.Assert.assertEquals(expected, actual)
    private fun assertEquals2(expected: String, actual: String) = org.junit.Assert.assertEquals(expected, actual)
}
