package com.shengzimao.reader.mobile.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Audited legacy bridges use narrow data APIs; arbitrary Java imports remain disabled. */
internal object LegacyPrivateScripts {
    private val audited = setOf(
        "5f0ff3628b23411653db20b424ec06dbc3c55d168b7e5caae098e710d78e6591",
        "e783ad950acf547d2e3b5d1f9d66a80fd31e3e267cf3b346ea1165a035d8ce18",
    )

    fun adapt(rule: String): String {
        val normalized = rule.trim().replace("\r\n", "\n")
        val hash = mangaHash(normalized)
        if (hash == "13c1e9d7e048aeae0be0e4f825d5e82124a2daa79850294608bd2ae50fa17feb") {
            val start = normalized.indexOf("function b64d(s)")
            val tail = normalized.indexOf("(function(){")
            check(start >= 0 && tail > start)
            return normalized.substring(0, start) + "function b64d(value){return java.base64UrlDecoder(value);}" + normalized.substring(tail)
        }
        if (hash == "11c5707df635a77183d5acbc44e1bc7f51adadaa9ed0f58dde5a63b2ae018abc") {
            return "@js:var html=result;var JsEngine={atob:function(value){return java.atob(value);}};\n" +
                normalized.removePrefix("$") + "\ngetImgList();"
        }
        if (hash == "bb72635343b45a671c411c821d492d9c26aeb5b9d7161f891950082c684fba33") {
            val tail = normalized.indexOf("eval(")
            check(tail > 0)
            return "@js:function strToMd5(value){return java.md5(String(value));}\n" + normalized.substring(tail)
        }
        if (!rule.contains("JavaImporter") || hash !in audited) return rule
        val start = normalized.indexOf("var Magua = new JavaImporter();")
        val end = normalized.indexOf("function jsDeByAES")
        check(start >= 0 && end > start)
        return normalized.substring(0, start) +
            "function javaDeByAES(data, key, iv) { return java.decryptAesCbc(data, key, iv); }\n" + normalized.substring(end)
    }

    fun decryptAes(encoded: String, key: String, iv: String): String {
        require(encoded.length <= 2_000_000) { "AES 内容过大" }
        return decryptAesBytes(Base64.getDecoder().decode(encoded.filterNot { it in " \t\r\n" }), key.toByteArray(Charsets.UTF_8), iv.toByteArray(Charsets.UTF_8))
    }

    fun decryptAesBytes(encoded: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): String {
        require(encoded.size <= 2_000_000) { "AES 内容过大" }
        require(keyBytes.size in setOf(16, 24, 32) && ivBytes.size == 16) { "AES key / IV 长度无效" }
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val bytes = cipher.doFinal(encoded)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: Exception) {
            throw IllegalArgumentException("页面 AES 数据无法解密，请检查源站规则", failure)
        }
    }
}
