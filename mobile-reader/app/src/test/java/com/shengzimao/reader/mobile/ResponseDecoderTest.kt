package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.ResponseDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * 响应解码：yckceo.com 的书源链接跳到 jsdelivr 后返回 base64(zlib(json))，
 * 旧实现直接当明文 JSON 解析因而失败。
 */
class ResponseDecoderTest {

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun plainJsonIsReturnedUnchanged() {
        val json = """[{"bookSourceName":"源"}]"""
        assertEquals(json, ResponseDecoder.decode(json))
    }

    @Test
    fun base64ZlibJsonIsDecoded() {
        val json = """[{"bookSourceName":"压缩源","bookSourceUrl":"https://e.com"}]"""
        val payload = Base64.getEncoder().encodeToString(zlib(json.toByteArray()))
        // 模拟现场：编码后的串不以 { 或 [ 开头。
        assertTrue(!payload.trim().startsWith("{"))
        val decoded = ResponseDecoder.decode(payload)
        assertEquals(json, decoded)
    }

    @Test
    fun base64GzipJsonIsDecoded() {
        val json = """{"a":1}"""
        val gz = java.io.ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(json.toByteArray()) }
        val payload = Base64.getEncoder().encodeToString(gz.toByteArray())
        assertEquals(json, ResponseDecoder.decode(payload))
    }

    @Test
    fun plainBase64JsonIsDecoded() {
        val json = """[1,2,3]"""
        val payload = Base64.getEncoder().encodeToString(json.toByteArray())
        assertEquals(json, ResponseDecoder.decode(payload))
    }

    @Test
    fun nonJsonIsLeftAlone() {
        val html = "<!DOCTYPE html><html></html>"
        assertEquals(html, ResponseDecoder.decode(html))
        val garbage = "eNrsvfd3G0eSOP6v4GT7PdIQMchB+kFC"
        // 无法解压时原样返回，交由上层给出可读错误。
        assertEquals(garbage, ResponseDecoder.decode(garbage))
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals("", ResponseDecoder.decode(""))
        assertEquals("", ResponseDecoder.decode("   "))
    }

    /** 真实现场数据：解压后应是一个 JSON 数组且含有 Legado 字段。 */
    @Test
    fun realCapturedPayloadDecodesToLegadoArray() {
        val stream = javaClass.classLoader!!.getResourceAsStream("legado-sample.json")
        assertTrue("样本资源应存在", stream != null)
        val json = stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        // 重新压缩成现场形态，验证解码往返。
        val payload = Base64.getEncoder().encodeToString(zlib(json.toByteArray()))
        val decoded = ResponseDecoder.decode(payload)
        assertTrue(decoded.trimStart().startsWith("["))
        assertTrue(decoded.contains("bookSourceUrl"))
    }
}
