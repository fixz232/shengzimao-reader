package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.TextCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamTextRegressionTest {
    @Test fun utf8StreamPreservesFirstBytesAndLargeSample() {
        val text = "第一章 原创测试\n" + "正文完整保留。".repeat(200_000)
        assertEquals(text, TextCodec.decodeStream(text.toByteArray().inputStream()).text)
        assertEquals(text, TextCodec.decode(text.toByteArray()).text)
    }
    @Test fun unicodeBomIsRemovedOnceAndReported() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bom = when (charset) { Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()); Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()); else -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) }
            val decoded = TextCodec.decodeStream((bom + "完整中文正文".toByteArray(charset)).inputStream())
            assertEquals("完整中文正文", decoded.text); assertTrue(decoded.hadBom)
        }
    }
    @Test fun shortReadsAndEmptyFilesAreSafe() {
        val bytes = "开头不会被吃掉".toByteArray()
        val short = object : ByteArrayInputStream(bytes) { override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len)) }
        assertEquals("开头不会被吃掉", TextCodec.decodeStream(short).text)
        assertEquals("", TextCodec.decodeStream(byteArrayOf().inputStream()).text)
    }
}
