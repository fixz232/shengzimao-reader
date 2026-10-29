package com.shengzimao.reader.mobile.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * 响应体编码探测与解码。
 *
 * 现场问题：yckceo.com 的书源链接会跳到 gcore.jsdelivr.net，
 * 返回 200 + application/json，但内容其实是 **base64(zlib(json))**：
 *
 *     eNrsvfd3G0eSOP6v4GT7PdIQMchB+kFCIEAQiUQkIOj0BsAAGISZwQQkW/foLNuyJO/KWU7ruGvJksN6ZQX7f7klQPKze2+/f8K3ewIwAAaDIFLrvdu9s0j2dFVXd1dXdVdXV5175kSOJKserIZ24ngdO3HqxImTfFEMJ0o1LF6mMbQASnvXvpQ+kBydx3w0yVGg/L/fua3pP/hm//r9kc9hlMcFv6bTntjIt3iHgt8UoBJ0DXwosyx1CkFarZau0ykw9bIuT9ZBPYxAczUAydIcdpKvlWAw2lnCCBZAhcguXquhiEWn16wEcYJrn9Y4iQJN4gWNQX9aE1jVOCmqhqWwXABnEYvJpjNZNSuBjXgoeFJTw6uYxoflq+Sqxl2myTqGGAxWnR7+nyZE
 *
 * 旧实现把它当明文 JSON 解析，于是报 “返回的内容不是有效的 JSON 对象或数组”。
 * 这里统一做一次解码，让上层拿到的永远是明文。
 */
object ResponseDecoder {
    private const val MAX_DECODED = 8 * 1024 * 1024

    /**
     * 尝试把响应体解成明文。识别顺序：
     * 1. 已经是明文 JSON（{ 或 [ 开头）→ 原样返回；
     * 2. base64 → zlib(deflate) → 明文；
     * 3. base64 → gzip → 明文；
     * 4. 纯 base64 → 明文 JSON。
     * 任何一步不成立都返回原文，由调用方决定如何报错。
     */
    fun decode(text: String): String {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_DECODED) { "响应内容超过 8 MB" }
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<")) return trimmed
        if (trimmed.isEmpty()) return trimmed

        val compact = trimmed.filterNot { it.isWhitespace() }
        if (!Regex("[A-Za-z0-9+/=_-]+").matches(compact)) return trimmed
        val base64 = runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull() ?: return trimmed
        if (base64.isEmpty()) return text

        inflate(base64)?.let { return it }
        gunzip(base64)?.let { return it }

        // 有些源直接返回 base64(明文json)，没有压缩。
        val plain = runCatching { base64.toString(Charsets.UTF_8) }.getOrNull()
        if (plain != null && (plain.trimStart().startsWith("{") || plain.trimStart().startsWith("["))) return plain
        return text
    }

    /** zlib 流：头 2 字节是 0x78 开头。 */
    private fun inflate(bytes: ByteArray): String? {
        if (bytes.size < 3) return null
        val head = bytes[0].toInt() and 0xFF
        if (head != 0x78) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(minOf(bytes.size, 65536))
            val buffer = ByteArray(16384)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    return null
                } else {
                    require(out.size() + n <= MAX_DECODED) { "解压后的漫画源超过 8 MB" }
                    out.write(buffer, 0, n)
                }
            }
            out.toByteArray().toString(Charsets.UTF_8)
                .takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
        } catch (_: java.util.zip.DataFormatException) { null } finally { inflater.end() }
    }

    /** gzip 流：头 2 字节 0x1F 0x8B。 */
    private fun gunzip(bytes: ByteArray): String? {
        if (bytes.size < 3) return null
        if ((bytes[0].toInt() and 0xFF) != 0x1F || (bytes[1].toInt() and 0xFF) != 0x8B) return null
        return try {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= MAX_DECODED) { "解压后的漫画源超过 8 MB" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
        } catch (_: java.io.IOException) { null }
    }
}
