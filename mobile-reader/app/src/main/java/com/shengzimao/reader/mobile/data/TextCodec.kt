package com.shengzimao.reader.mobile.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class DecodedFile(
    val text: String,
    val encoding: String,
    val hadBom: Boolean,
    val bomLength: Int,
)

/**
 * 文本解码：BOM 优先，其次按 Unicode 代理对比例猜测 UTF-16，
 * 最后用 UTF-8 严格解码失败率判断是否回退到 GB18030（兼容国内 TXT 常见编码）。
 */
object TextCodec {
    const val MAX_TEXT_BYTES = 128 * 1024 * 1024
    private const val SAMPLE_BYTES = 1 shl 20

    val failFast: Charset = Charset.forName("UTF-8")

    /** 返回 BOM 字节数；没有 BOM 返回 0。 */
    fun bomLength(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        // UTF-32 的 BOM 前两个字节与 UTF-16 相同，必须先判 4 字节。
        if (length >= 4 && bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0xFE.toByte() &&
            bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 0.toByte()
        ) return 4
        if (length >= 3 && bytes[offset] == 0xEF.toByte() && bytes[offset + 1] == 0xBB.toByte() && bytes[offset + 2] == 0xBF.toByte()) return 3
        if (length >= 2 && bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0xFE.toByte()) return 2
        if (length >= 2 && bytes[offset] == 0xFE.toByte() && bytes[offset + 1] == 0xFF.toByte()) return 2
        return 0
    }

    private fun utf16Charset(bytes: ByteArray): Charset? {
        if (bytes.size < 4) return null
        val sample = minOf(bytes.size, SAMPLE_BYTES)
        var evenZero = 0
        var oddZero = 0
        var pairs = 0
        var index = 0
        while (index + 1 < sample) {
            if (bytes[index] == 0.toByte()) evenZero++
            if (bytes[index + 1] == 0.toByte()) oddZero++
            pairs++
            index += 2
        }
        if (pairs == 0) return null
        val even = evenZero.toDouble() / pairs
        val odd = oddZero.toDouble() / pairs
        return when {
            odd > 0.4 && even < 0.05 -> Charset.forName("UTF-16LE")
            even > 0.4 && odd < 0.05 -> Charset.forName("UTF-16BE")
            else -> null
        }
    }

    /** 用 head + tail 采样严格解码：只看开头遇到长英文段落会把 GBK 误判成 UTF-8。 */
    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        fun attempt(start: Int, length: Int): Boolean {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, start, length))
            return true
        }
        val half = SAMPLE_BYTES / 2
        val head = minOf(bytes.size, half)
        val tailStart = maxOf(head, bytes.size - half)
        val tail = bytes.size - tailStart
        if (attempt(0, head) && (tail <= 0 || attempt(tailStart, tail))) "" else null
    } catch (_: Exception) {
        null
    }

    fun decode(bytes: ByteArray, hint: String? = null): DecodedFile {
        val bom = bomLength(bytes)
        if (bom == 4) {
            // UTF-32LE：Java 没有内置解码器，按 4 字节小端手工拼成 UTF-16。
            val body = bytes.copyOfRange(4, bytes.size)
            val units = java.lang.StringBuilder(body.size / 4)
            var i = 0
            while (i + 3 < body.size) {
                val codePoint = (body[i].toInt() and 0xFF) or
                    ((body[i + 1].toInt() and 0xFF) shl 8) or
                    ((body[i + 2].toInt() and 0xFF) shl 16) or
                    ((body[i + 3].toInt() and 0xFF) shl 24)
                if (codePoint in 0..0x10FFFF) units.appendCodePoint(codePoint)
                i += 4
            }
            return DecodedFile(units.toString(), "UTF-32LE", true, 4)
        }
        if (bom == 2 || bom == 3) {
            // BOM 非 UTF-8 的 UTF-16/32 只处理 UTF-16 两种，保持与桌面版 detectBom 前四项一致。
            val charset = when {
                bom == 3 -> Charset.forName("UTF-8")
                bytes[0] == 0xFF.toByte() -> Charset.forName("UTF-16LE")
                else -> Charset.forName("UTF-16BE")
            }
            val body = bytes.copyOfRange(bom, bytes.size)
            return DecodedFile(String(body, charset), charset.name(), true, bom)
        }
        if (!hint.isNullOrBlank() && runCatching { Charset.forName(hint) }.isSuccess) {
            val charset = Charset.forName(hint)
            return DecodedFile(String(bytes, charset), charset.name(), false, 0)
        }
        utf16Charset(bytes)?.let { charset ->
            return DecodedFile(String(bytes, charset), charset.name(), false, 0)
        }
        decodeStrict(bytes, java.nio.charset.StandardCharsets.UTF_8)?.let { sample ->
            return DecodedFile(String(bytes, java.nio.charset.StandardCharsets.UTF_8), "UTF-8", false, 0)
        }
        val gb = Charset.forName("GB18030")
        return DecodedFile(String(bytes, gb), "GB18030", false, 0)
    }

    fun decodeFile(file: File, hint: String? = null): DecodedFile {
        if (file.length() > MAX_TEXT_BYTES) throw IllegalStateException("文件超过 128 MB 上限，请拆分后再导入。")
        return decode(file.readBytes(), hint)
    }

    /**
     * 流式解码用于超大文本；返回合并后的字符串。
     * 这是给本地快照写入使用的落底方案，正常路径直接 readBytes。
     */
    fun decodeStream(stream: java.io.InputStream, hint: String? = null): DecodedFile {
        val pushback = PushbackInputStream(stream, 3)
        val head = ByteArray(3)
        var read = pushback.read(head)
        if (read < 0) read = 0
        val bom = bomLength(head, 0, read)
        if (bom > 0) pushback.unread(head, bom, read - bom)
        val sample = pushback.readNBytes(SAMPLE_BYTES)
        pushback.unread(sample)
        val probe = decode(sample, hint)
        val reader = InputStreamReader(pushback, Charset.forName(probe.encoding))
        val builder = StringBuilder(sample.size * 3)
        val buffer = CharArray(1 shl 16)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            builder.append(buffer, 0, n)
        }
        return DecodedFile(builder.toString(), probe.encoding, probe.hadBom, probe.bomLength)
    }

    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")

    fun fromBytes(text: String): ByteArrayInputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
}
