package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫画源导入的回归用例。
 *
 * 现场故障：源地址 `https://eo.com/yiciyuan/tuyuans/json/id/66.json` 返回 301 跳到
 * `x.com`，跟随后得到的是非 JSON 内容；旧实现直接把响应体丢给 `JSONObject(text)`，
 * 抛出未翻译的 `Value ... of type java.lang.String cannot be converted to JSONObject`，
 * 用户只看到一串英文栈信息。
 *
 * 这里锁定修复后必须满足的规约：任何非 JSON 响应都要转成可读中文提示，且能提示跳转。
 */
class MangaSourceImportTest {

    @Test
    fun singleSourceObjectIsAccepted() {
        val text = """{"schemaVersion":1,"id":"a","name":"源A","baseUrl":"https://e.com",
            "search":{"url":"/s?q={query}","items":".i","title":".t","link":"a@href"},
            "detail":{"chapters":".c a"},
            "reader":{"pages":".r img"}}"""
        val sources = MangaSource.importText(text)
        assertEquals(1, sources.size)
        assertEquals("源A", sources[0].name)
    }

    @Test
    fun topLevelArrayIsAccepted() {
        val text = """[{"schemaVersion":1,"id":"a","name":"源A","baseUrl":"https://e.com",
            "search":{"url":"/s","items":".i","title":".t","link":"a@href"},
            "detail":{"chapters":".c a"},"reader":{"pages":".r img"}},
            {"schemaVersion":1,"id":"b","name":"源B","baseUrl":"https://e2.com",
            "search":{"url":"/s","items":".i","title":".t","link":"a@href"},
            "detail":{"chapters":".c a"},"reader":{"pages":".r img"}}]"""
        val sources = MangaSource.importText(text)
        assertEquals(2, sources.size)
    }

    @Test
    fun sourcesWrapperIsAccepted() {
        val text = """{"sources":[{"schemaVersion":1,"id":"a","name":"源A","baseUrl":"https://e.com",
            "search":{"url":"/s","items":".i","title":".t","link":"a@href"},
            "detail":{"chapters":".c a"},"reader":{"pages":".r img"}}]}"""
        assertEquals(1, MangaSource.importText(text).size)
    }

    /**
     * 关键回归：HTML 响应体不能被当成 JSON 解析，
     * 必须给出中文提示，而不是 org.json 的英文类型转换异常。
     */
    @Test
    fun htmlResponseIsReportedInChineseNotAsJsonTypeError() {
        val html = "<!DOCTYPE html><html><head><title>x.com</title></head><body>redirect</body></html>"
        val message = runCatching { MangaSource.importText(html) }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue("应给出可读的中文提示，实际为：$message", message.contains("JSON"))
        assertTrue("不应泄漏 org.json 的英文类型异常，实际为：$message", !message.contains("cannot be converted"))
    }

    @Test
    fun emptyResponseIsReportedInChinese() {
        val message = runCatching { MangaSource.importText("   ") }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue("空响应应给出中文提示，实际为：$message", message.contains("JSON"))
        assertTrue(!message.contains("cannot be converted"))
    }

    @Test
    fun mismatchBetweenIdAndObjectStillBreaksParseCleanly() {
        // schemaVersion 类型错误同样应受控，不应抛出英文栈信息。
        val bad = """{"schemaVersion":"x","id":"a","name":"源A","baseUrl":"https://e.com"}"""
        val message = runCatching { MangaSource.importText(bad) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.isNotBlank())
        assertTrue(!message.contains("cannot be converted"))
    }

    /**
     * 现场故障复现：链接跟随跳转后返回的不是 JSON。
     * 修复前这里抛 English 类型异常；修复后必须给中文提示并指出应改用直连 JSON。
     */
    @Test
    fun stringArrayIsRejectedWithIndexAndGuidance() {
        val text = """["https://e.com/66.json","https://e.com/67.json"]"""
        val message = runCatching { MangaSource.importText(text) }.exceptionOrNull()?.message.orEmpty()
        assertTrue("应指明第几项有问题：$message", message.contains("第 1 项"))
        assertTrue("不应泄漏英文类型异常：$message", !message.contains("cannot be converted"))
    }

    @Test
    fun invalidSourceEntryNamesItsIndex() {
        val text = """[{"schemaVersion":1,"id":"a","name":"源A","baseUrl":"https://e.com",
            "search":{"url":"/s","items":".i","title":".t","link":"a@href"},
            "detail":{"chapters":".c a"},"reader":{"pages":".r img"}},
            {"schemaVersion":1,"name":"坏源","baseUrl":"https://e2.com"}]"""
        val message = runCatching { MangaSource.importText(text) }.exceptionOrNull()?.message.orEmpty()
        assertTrue("应指明是第 2 个源出错：$message", message.contains("第 2 个"))
    }

    @Test
    fun htmlTellsUserItIsNotJson() {
        val html = "<!DOCTYPE html><html><body>moved</body></html>"
        val message = runCatching { MangaSource.importText(html) }.exceptionOrNull()?.message.orEmpty()
        assertTrue("应明确说明返回的是网页：$message", message.contains("网页"))
        assertTrue("应提示改用粘贴导入：$message", message.contains("粘贴"))
    }

    @Test
    fun blankInputExplainsLikelyCause() {
        val message = runCatching { MangaSource.importText("") }.exceptionOrNull()?.message.orEmpty()
        assertTrue("空内容应提示链接可能跳转：$message", message.contains("链接"))
    }
}
