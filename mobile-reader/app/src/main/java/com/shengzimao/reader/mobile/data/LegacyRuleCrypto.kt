package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 已核对的 H / S007 格式解封；只返回文本，绝不执行解出的脚本。 */
internal object LegacyRuleCrypto {
    private const val MAX_ENVELOPE = 200_000
    private const val MAX_PLAIN = 128_000
    private val marker = Regex("^:_([HRST][0-9]+)_", RegexOption.IGNORE_CASE)
    private val privateMarker = Regex(":_[HRST][0-9]+_|:\\s*\\$[A-Za-z][A-Za-z0-9]*_", RegexOption.IGNORE_CASE)
    // 原应用的格式版本常量，不是用户密码；H002 无目录样本，仅做合成向量测试。
    private val formats = mapOf(
        "H001" to "F41QW5WFH3E88FFGEE22FBE34WGHF9WF",
        "H002" to "P0FWIswsFGnqny5h75TMpS2pcp4Lpc1k",
        "H003" to "clnd8CDIs6oqq3bJG2wI6N55B8HFJyl2",
        "H004" to "UFbrrNHE5Sv8HqVFhtssJjR0CCBPZ7DP",
        "H005" to "ScoyV09Veuam8YPMFvA9e9pSJzTJLNOm",
        "H006" to "t7UNxE8HiRWWOY1Ob9YUSJ4sfQlcU4x8",
        "S007" to "nrP2wkM6mpqQggW1q7EQ2oD6vjcw3Oiq",
    )
    private data class Binding(val urls: Set<String>, val original: String, val fingerprints: Set<String>)
    // 目录中六条配置改过域名，但两个密文字段与原地址配置逐字一致。
    // 同时限定当前地址和密文 SHA-256，不对任意源猜域名或尝试不相关密钥。
    private val bindings = listOf(
        Binding(setOf("https://www.copymanga.site"), "https://www.copymanga.tv", setOf(
            "16c625fe34cf532eb4e5d2a55284e2adcba98cb15d1078f001a32091d1b8ca20",
            "5dee20e0243917f7929ad07004df4d5eed7f6cc9c4ac5dec28964e45a8ecb62a")),
        Binding(setOf("http://www.kuman5.com", "http://kuman5.com"), "http://www.kuman57.com", setOf(
            "bf74664cf37835549cd854fa78a8e17f8b11bb803031482107229b65c8eb8e31",
            "e2bc36b3db25d04b641dc18ba015ee10f1036fea4a6cc2f74539011a19f7db14")),
        Binding(setOf("http://zerobywtxt.com", "http://www.zerobywtx.com"), "http://www.zerobywtxt.com", setOf(
            "f553ab75f31c0c00e2b3c13bba04f77a737efce027f725dfdd98c061a9da64ca",
            "ecc1ba05567227eea0a69b8efd8bb2a33f2d4665eb046f67036e72b049c77909")),
        Binding(setOf("http://m.qiman58.com"), "http://m.qiman6.com", setOf(
            "da6332937cc20a13f15d687ab7a8bc20bcd2cbe8b3e3dfea3a193efdae069c3b",
            "85e0e55a727879247229af096ff6fafa1d8b765b9f14b5dfa34a02dc3bc32740")),
    )
    private val cache = object : LinkedHashMap<String, String>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 48
    }
    data class Inspection(val fields: Int, val versions: Set<String>, val bindingUrl: String?, val failure: String?)

    fun hasEnvelope(raw: String) = privateMarker.containsMatchIn(raw)

    fun rawRule(source: JSONObject, name: String): String = when (val value = source.opt(name)) {
        is String -> value.trim()
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }.joinToString("||").trim()
        is JSONObject -> value.optString("rule").trim()
        else -> ""
    }

    fun readRule(source: JSONObject, name: String): String {
        val raw = rawRule(source, name)
        return if (hasEnvelope(raw)) decodeBound(raw, source.optString("bookSourceUrl")).first else raw
    }

    fun inspect(source: JSONObject): Inspection {
        var count = 0
        val versions = linkedSetOf<String>()
        var bindingUrl: String? = null
        for (key in source.keys().asSequence().filter { it.startsWith("rule") }) {
            val raw = rawRule(source, key)
            if (!hasEnvelope(raw)) continue
            count++
            val result = runCatching { decodeBound(raw, source.optString("bookSourceUrl")) }
            if (result.isFailure) return Inspection(count, versions, bindingUrl, "$key：${result.exceptionOrNull()?.message}")
            versions += marker.find(raw)!!.groupValues[1].uppercase()
            result.getOrThrow().second?.let { bindingUrl = it }
        }
        return Inspection(count, versions, bindingUrl, null)
    }

    private fun compact(raw: String) = raw.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

    private fun decodeBound(raw: String, sourceUrl: String): Pair<String, String?> {
        require(raw.length <= MAX_ENVELOPE) { "加密规则超过长度上限" }
        val direct = runCatching { decode(raw, sourceUrl) }
        if (direct.isSuccess) return direct.getOrThrow() to null
        val fingerprint = mangaHash(compact(raw))
        val binding = bindings.firstOrNull { sourceUrl in it.urls && fingerprint in it.fingerprints }
            ?: throw direct.exceptionOrNull()!!
        return decode(raw, binding.original) to binding.original
    }

    /** 严格按提供的加密地址派生，不使用目录域名兼容表；用于原版交叉验证。 */
    fun decode(raw: String, encryptionUrl: String): String {
        require(raw.length <= MAX_ENVELOPE) { "加密规则超过长度上限" }
        val text = raw.trim()
        val match = marker.find(text) ?: throw IllegalArgumentException("无法识别的私有加密规则格式")
        val version = match.groupValues[1].uppercase()
        val constant = formats[version] ?: throw IllegalArgumentException("尚未适配加密规则 $version，原配置已保留")
        require(version.startsWith("S") || (encryptionUrl.isNotBlank() && encryptionUrl.length <= 4096)) { "缺少有效的原加密地址" }
        val payload = compact(text.substring(match.range.last + 1))
        val keyId = mangaHash(encryptionUrl + "\u0000" + version + "\u0000" + payload)
        synchronized(cache) { cache[keyId]?.let { return it } }
        val bytes = try { Base64.getDecoder().decode(payload) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("加密规则 Base64 格式损坏", failure) }
        require(bytes.isNotEmpty() && bytes.size % 16 == 0) { "加密规则密文长度无效" }
        val key = if (version.startsWith("S")) constant.toByteArray(Charsets.US_ASCII) else
            MessageDigest.getInstance("MD5").digest((hostMaterial(encryptionUrl) + constant).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        val plain = try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec("1234567890123456".toByteArray(Charsets.US_ASCII)))
            }.doFinal(bytes)
        } catch (failure: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("加密规则无法解开：源地址与原加密地址不一致，或密文已损坏", failure)
        }
        require(plain.size <= MAX_PLAIN) { "解出的规则超过长度上限" }
        val decoded = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("解出的规则不是有效 UTF-8 文本", failure)
        }
        require(decoded.isNotBlank() && !decoded.contains('\u0000') && !hasEnvelope(decoded)) { "解出的规则为空或仍为加密内容" }
        synchronized(cache) { cache[keyId] = decoded }
        return decoded
    }

    /** 保留原版将地址当作十六进制并做七位解包的行为，包括非 hex 字符和奇数长度。 */
    internal fun hostMaterial(url: String): String {
        val text = if (url.length % 2 == 0) url else "0$url"
        val bytes = ByteArray(text.length / 2) { i ->
            ((Character.digit(text[i * 2], 16) shl 4) + Character.digit(text[i * 2 + 1], 16)).toByte()
        }
        var index = 0
        var offset = 0
        fun nextSeven(): Int? {
            if (index >= bytes.size) return null
            val value = (((bytes[index].toInt() and 0xff) and (255 ushr offset)) shl offset) ushr 1
            offset += 7
            if (offset < 8) return value
            offset -= 8
            index++
            return if (index >= bytes.size) value else value or (((bytes[index].toInt() and (65280 ushr offset)) and 255) ushr (8 - offset))
        }
        val escapes = intArrayOf(0, 10, 13, 34, 38, 92)
        val out = ByteArrayOutputStream()
        while (true) {
            val value = nextSeven() ?: break
            val escape = escapes.indexOf(value)
            if (escape < 0) { out.write(value); continue }
            val following = nextSeven()
            val head = if (following == null) 0xde else ((escape and 7) shl 2) or 0xc2
            val tail = following ?: value
            out.write(head or if (tail and 64 != 0) 1 else 0)
            out.write((tail and 63) or 128)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
