package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.mangaLookupQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaLookupTest {
    @Test fun siteEditionSuffixDoesNotHideOtherEditions() {
        assertEquals("一人之下", mangaLookupQuery("一人之下（《异人之下》原著漫画"))
        assertEquals("原创漫画", mangaLookupQuery(" 原创漫画 (新版) "))
        assertEquals("（原创漫画）", mangaLookupQuery("（原创漫画）"))
        assertEquals("道诡异仙", mangaLookupQuery("道诡异仙"))
    }
    @Test fun lookupPreservesLimitsAndEmptyInput() {
        assertEquals("", mangaLookupQuery("  "))
        assertEquals(120, mangaLookupQuery("漫".repeat(200)).length)
    }
}
