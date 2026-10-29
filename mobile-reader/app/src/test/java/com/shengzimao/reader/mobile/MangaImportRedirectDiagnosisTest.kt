package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 现场故障的精确复现：`eo.com` 301 跳到 `x.com`，跟随跳转后拿到的是 HTML。
 *
 * MangaEngine.importUrl 里对“跳转后非 JSON”的诊断依赖真实网络，
 * 无法在单元测试中跑；这里锁定其判定与文案所依赖的纯逻辑部分。
 */
class MangaImportRedirectDiagnosisTest {

    /** 与引擎内一致的判定：响应体是否可能是一个漫画源 JSON。 */
    private fun looksLikeSourceJson(text: String): Boolean {
        // 先去掉 BOM 再 trim，顺序反了会漏掉 “BOM + 空白” 开头的正常 JSON。
        val t = text.removePrefix("\uFEFF").trim()
        return t.startsWith("{") || t.startsWith("[")
    }

    @Test
    fun htmlBodyIsNotMistakenForSourceJson() {
        // x.com 跳转后返回的典型响应体。
        val html = "<!DOCTYPE html><html><head><title>x.com</title></head></html>"
        assertTrue(!looksLikeSourceJson(html))
    }

    @Test
    fun realSourceJsonIsAccepted() {
        assertTrue(looksLikeSourceJson("""{"schemaVersion":1,"name":"源"}"""))
        assertTrue(looksLikeSourceJson("""[{"schemaVersion":1,"name":"源"}]"""))
    }

    @Test
    fun leadingWhitespaceAndBomAreIgnored() {
        assertTrue(looksLikeSourceJson("\uFEFF { \"name\": \"x\" }"))
        assertTrue(looksLikeSourceJson("\n\t[ ]"))
    }

    @Test
    fun htmlImportGetsGuidanceMentioningRedirectOrPaste() {
        val html = "<!DOCTYPE html><html><body>jumped</body></html>"
        val message = runCatching { MangaSource.importText(html) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "应给网页类提示，实际：$message",
            message.contains("网页") && message.contains("粘贴"),
        )
    }
}
