package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.SelectorSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选择器拆分与归一化的单元回归。 */
class SelectorSplitterTest {

    @Test
    fun plainCssKeepsWholeSelectorAsPath() {
        assertEquals(".title" to "", SelectorSplitter.split(".title"))
        assertEquals("div.a .b" to "", SelectorSplitter.split("div.a .b"))
    }

    @Test
    fun trailingAttributeIsSplitOff() {
        assertEquals("a" to "href", SelectorSplitter.split("a@href"))
        assertEquals(".cover img" to "src", SelectorSplitter.split(".cover img@src"))
    }

    /**
     * 关键回归：XPath 谓词里的 `@` 不能被当成属性分隔符。
     * 旧实现按最后一个 `@` 切分，会把 `//div[@class=x]` 切成一团乱码并抛
     * `Could not parse query '//div['`。
     */
    @Test
    fun xpathPredicateAtSignIsNotTreatedAsAttribute() {
        val (path, attr) = SelectorSplitter.split("//div[@class=comic-item]")
        assertEquals("//div[@class=comic-item]", path)
        assertEquals("", attr)
    }

    @Test
    fun xpathWithTrailingAttributeStillSplitsCorrectly() {
        // //meta[@property=og:image]/@content → 路径 + content
        val (path, attr) = SelectorSplitter.split("//meta[@property='og:image']/@content")
        assertEquals("//meta[@property='og:image']", path)
        assertEquals("content", attr)
        val doc = org.jsoup.Jsoup.parse("<meta property='og:image' content='cover.png'>")
        assertEquals("cover.png", doc.selectXpath(path).first()!!.attr(attr))
    }

    @Test
    fun nestedPredicatesKeepDepthBalance() {
        val (path, attr) = SelectorSplitter.split("//div[@a=1][@b=2]/span@title")
        assertEquals("//div[@a=1][@b=2]/span", path)
        assertEquals("title", attr)
    }

    @Test
    fun isXPathRecognisesCommonForms() {
        assertTrue(SelectorSplitter.isXPath("//div"))
        assertTrue(SelectorSplitter.isXPath("/html/body"))
        assertTrue(SelectorSplitter.isXPath("div[@class]"))
        assertTrue(!SelectorSplitter.isXPath(".title"))
        assertTrue(!SelectorSplitter.isXPath("#list li"))
    }

    @Test
    fun normalizeAddsQuotesAroundUnquotedAttributeValues() {
        assertEquals("//div[@class='comic-item']", SelectorSplitter.normalizeXPath("//div[@class=comic-item]"))
        assertEquals("//ul[@id='list']/li", SelectorSplitter.normalizeXPath("//ul[@id=list]/li"))
    }

    @Test
    fun normalizeLeavesAlreadyQuotedValuesAlone() {
        assertEquals("//div[@class='a b']", SelectorSplitter.normalizeXPath("//div[@class='a b']"))
    }

    @Test
    fun normalizeDropsZeroIndex() {
        // jsoup 的 XPath 下标从 1 开始，Legado 常见的 [0] 会取不到东西。
        assertEquals("//p", SelectorSplitter.normalizeXPath("//p[0]"))
    }
}
