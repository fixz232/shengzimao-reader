package com.shengzimao.reader.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布前的结构自检。
 *
 * 起因：第一版 APK 里的在线搜书入口曾因构建流程没跑到而缺失，
 * 而构建本身是成功的。这里用"类是否被编译进 release 产物"来做最低限度的兜底。
 */
class ReleaseStructureTest {

    @Test
    fun onlineSearchClassesAreCompiledIn() {
        val required = listOf(
            "com.shengzimao.reader.mobile.ui.OnlineScreenKt",
            "com.shengzimao.reader.mobile.ui.OnlineController",
            "com.shengzimao.reader.mobile.data.SonovelEngine",
            "com.shengzimao.reader.mobile.data.TomatoEngine",
            "com.shengzimao.reader.mobile.data.TomatoParser",
            "com.shengzimao.reader.mobile.data.Updates",
            "com.shengzimao.reader.mobile.data.Backup",
        )
        val missing = required.filter { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader) }.isFailure
        }
        assertTrue("这些类没有被编译进 APK：$missing", missing.isEmpty())
    }

    @Test
    fun mobileConsentRequiresCurrentRevision() {
        // 漫画源与在线使用规则更新后，旧版声明确认不能复用。
        assertEquals("2026-10-24-v2", com.shengzimao.reader.mobile.data.Consent.VERSION)
    }

    @Test
    fun trackingIsRejectedWhenManifestAndLocalDisagree() {
        val book = com.shengzimao.reader.mobile.data.Book(
            title = "书",
            file = "abcdefgh.txt",
            chapters = listOf(
                com.shengzimao.reader.mobile.data.Chapter("第一章", 0),
                com.shengzimao.reader.mobile.data.Chapter("第二章", 10),
                com.shengzimao.reader.mobile.data.Chapter("第三章", 20),
            ),
            onlineManifest = com.shengzimao.reader.mobile.data.OnlineChapterCodec.encode(
                listOf(
                    com.shengzimao.reader.mobile.data.OnlineChapter("第一章", "https://e.com/1"),
                    com.shengzimao.reader.mobile.data.OnlineChapter("第二章", "https://e.com/2"),
                ),
            ),
        )
        val details = com.shengzimao.reader.mobile.data.Updates.Details(
            book = com.shengzimao.reader.mobile.data.OnlineResult(bookName = "书"),
            toc = listOf(
                com.shengzimao.reader.mobile.data.OnlineChapter("第一章", "https://e.com/1"),
                com.shengzimao.reader.mobile.data.OnlineChapter("第二章", "https://e.com/2"),
                com.shengzimao.reader.mobile.data.OnlineChapter("第三章", "https://e.com/3"),
            ),
        )
        val outcome = runCatching {
            com.shengzimao.reader.mobile.data.Updates.planUpdate(book, book.chapters, details)
        }
        assertTrue("清单与本地章节数不一致时必须拒绝追加", outcome.isFailure)
    }
}
