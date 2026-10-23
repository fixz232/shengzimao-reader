package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.ChapterParser
import com.shengzimao.reader.mobile.data.TextCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {
    @Test
    fun findsCommonChapterHeadings() {
        val text = buildString {
            appendLine("书名：测试")
            appendLine()
            appendLine("第一章 开始")
            appendLine("正文第一段。")
            appendLine()
            appendLine("第 2 章 继续")
            appendLine("正文第二段。")
            appendLine("第十二回 大结局")
        }
        val chapters = ChapterParser.find(text)
        assertEquals(3, chapters.size)
        assertEquals("第一章 开始", chapters[0].title)
        assertEquals("第 2 章 继续", chapters[1].title)
        assertEquals("第十二回 大结局", chapters[2].title)
        assertTrue(chapters[1].position > chapters[0].position)
    }

    @Test
    fun ignoresInlineMentions() {
        val text = "他说：第一章 开始 的故事很长，这里需要继续写下去才能读到结尾。"
        assertTrue(ChapterParser.find(text).isEmpty())
    }

    @Test
    fun englishAndVolumeHeadings() {
        val text = "Chapter 1 Start\nhello\n第三卷 风起\nmore\nChapter 12 End\n"
        val titles = ChapterParser.find(text).map { it.title }
        assertEquals(listOf("Chapter 1 Start", "第三卷 风起", "Chapter 12 End"), titles)
    }

    @Test
    fun titleFromFileName() {
        assertEquals("斗破苍穹", ChapterParser.titleFromFileName("斗破苍穹.txt"))
        assertEquals("无扩展名", ChapterParser.titleFromFileName("无扩展名"))
    }
}

class OnlineMatcherTest {
    private fun result(name: String, author: String) = com.shengzimao.reader.mobile.data.OnlineResult(bookName = name, author = author, url = "https://example.com/b")

    @Test
    fun exactTitleRanksFirst() {
        val filtered = com.shengzimao.reader.mobile.data.OnlineMatcher.filter(
            "三体",
            listOf(result("三体全集", "刘慈欣"), result("三体", "刘慈欣"), result("漫威", "某某")),
        )
        assertEquals("三体", filtered.first().bookName)
        assertEquals("书名完全匹配", filtered.first().matchType)
        assertTrue(filtered.none { it.bookName == "漫威" })
    }

    @Test
    fun compoundQueryNeedsEveryWord() {
        val filtered = com.shengzimao.reader.mobile.data.OnlineMatcher.filter(
            "三体 刘慈欣",
            listOf(result("三体", "刘慈欣"), result("三体", "别人")),
        )
        assertEquals(1, filtered.size)
        assertEquals("刘慈欣", filtered.first().author)
        assertEquals("多关键词匹配", filtered.first().matchType)
    }

    @Test
    fun normalizationIgnoresPunctuationAndCase() {
        assertEquals("三体", com.shengzimao.reader.mobile.data.OnlineMatcher.normalize("《三体》"))
        assertEquals("harrypotter", com.shengzimao.reader.mobile.data.OnlineMatcher.normalize("Harry-Potter"))
    }
}

class UpdatesTest {
    private fun chapter(title: String) = com.shengzimao.reader.mobile.data.OnlineChapter(title, "https://example.com/c")

    @Test
    fun titleKeyNormalizesSpacingAndNumbering() {
        val key = com.shengzimao.reader.mobile.data.Updates.titleKey("  第 1 章   开端 ")
        assertEquals(key, com.shengzimao.reader.mobile.data.Updates.titleKey("第1章开端"))
    }

    @Test
    fun planAppendsOnlyNewChapters() {
        val text = "第一章\n甲\n第二章\n乙\n"
        val book = com.shengzimao.reader.mobile.data.Book(
            title = "书",
            file = "abcdefgh.txt",
            digest = "",
            chapters = com.shengzimao.reader.mobile.data.Updates.mapOnlineChapters(
                text,
                listOf(chapter("第一章"), chapter("第二章")),
            ),
        )
        val details = com.shengzimao.reader.mobile.data.Updates.Details(
            book = com.shengzimao.reader.mobile.data.OnlineResult(bookName = "书"),
            toc = listOf(chapter("第一章"), chapter("第二章"), chapter("第三章")),
        )
        val plan = com.shengzimao.reader.mobile.data.Updates.planUpdate(book, book.chapters, details)
        assertEquals(2, plan.start)
        assertEquals(1, plan.chapters.size)
        assertEquals("第三章", plan.chapters.first().title)
    }

    @Test(expected = IllegalStateException::class)
    fun shrunkenTocIsRejected() {
        val book = com.shengzimao.reader.mobile.data.Book(
            title = "书",
            file = "abcdefgh.txt",
            chapters = listOf(
                com.shengzimao.reader.mobile.data.Chapter("第一章", 0),
                com.shengzimao.reader.mobile.data.Chapter("第二章", 10),
            ),
        )
        val details = com.shengzimao.reader.mobile.data.Updates.Details(
            book = com.shengzimao.reader.mobile.data.OnlineResult(bookName = "书"),
            toc = listOf(chapter("第一章")),
        )
        com.shengzimao.reader.mobile.data.Updates.planUpdate(book, book.chapters, details)
    }
}

class TextCodecTest {
    @Test
    fun decodesUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好，世界".toByteArray(Charsets.UTF_8)
        val decoded = TextCodec.decode(bytes)
        assertEquals("你好，世界", decoded.text)
        assertTrue(decoded.hadBom)
    }

    @Test
    fun decodesGbkWithoutBom() {
        val text = "第1章 测试中文编码，这里是一段足够长的正文内容，用来验证自动识别。"
        val bytes = text.toByteArray(charset("GB18030"))
        val decoded = TextCodec.decode(bytes)
        assertEquals(text, decoded.text)
        assertEquals("GB18030", decoded.encoding)
    }

    @Test
    fun decodesUtf16Le() {
        val text = "第一章 十六位编码的正文内容。"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, TextCodec.decode(bytes).text)
    }

    @Test
    fun normalizesLineEndings() {
        assertEquals("a\nb\nc", TextCodec.normalize("a\r\nb\rc"))
    }
}
