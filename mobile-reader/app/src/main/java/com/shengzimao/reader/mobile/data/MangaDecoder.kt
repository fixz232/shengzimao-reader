package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 只识别审计过的图片算法，不把源的 JavaImporter/Packages 脚本交给 JVM 执行。 */
internal object MangaDecoder {
    enum class Kind { MANWA_AES, BIKA_AES, JM_ROWS, BAOZI_SIZE, XIANG_COLUMNS, GUOGUO_ROWS }
    data class Spec(val kind: Kind, val label: String, val fingerprint: String)
    data class Slice(val source: Int, val target: Int, val length: Int)
    data class Size(val width: Int, val height: Int)

    private val known = mapOf(
        "14f214d7deb3c4cc3e53d940c8f70e41a9d2fe582f1ab5c9f867fb27a5c5e48f" to (Kind.MANWA_AES to "漫蛙 AES-CBC"),
        "66686c331d1ea58e04d0190fcb7f2a3eb1bc4f6ea7e344d84f560723388a7e67" to (Kind.BIKA_AES to "哔咔 AES-CBC"),
        "c8c4163e0c1e492a1ac0a5a7042d1895de36d952320acfd48e9d6c3cd056e855" to (Kind.JM_ROWS to "JM 行条带还原"),
        "f43728f5c1310c6417069ae30e7b245ca28e3358d8987ab0fef3021b4a9cf50f" to (Kind.JM_ROWS to "JM 行条带还原"),
        "605125b5a614f4744cfb26397243fda7c058af62f30c7910308c096d2e2ff22f" to (Kind.BAOZI_SIZE to "包子尺寸还原"),
        "199394d329e68c96e648859754da75490a44527f3772adbb07db639ff74f5d12" to (Kind.XIANG_COLUMNS to "列条带还原"),
        "28e6edbb3522f807b0b5cadda6baf6fcc724811e7539bf675546a0369b31665b" to (Kind.GUOGUO_ROWS to "果果行条带还原"),
    )
    val supported: List<Spec> get() = known.map { (hash, entry) -> Spec(entry.first, entry.second, hash) }

    fun rule(legacy: JSONObject?): String = when (val value = legacy?.opt("ruleBookContentDecoder")) {
        is String -> value.trim()
        is JSONObject -> value.optString("rule").trim()
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }.joinToString("||").trim()
        else -> ""
    }

    fun identify(raw: String): Spec? {
        val hash = mangaHash(raw.trim().replace("\r\n", "\n"))
        val match = known[hash] ?: return null
        return Spec(match.first, match.second, hash)
    }

    fun decrypt(spec: Spec, bytes: ByteArray): ByteArray {
        val (key, iv) = when (spec.kind) {
            Kind.MANWA_AES -> "my2ecret782ecret" to "my2ecret782ecret"
            Kind.BIKA_AES -> "f5d965df75336270" to "97b60394abc2fbe1"
            else -> return bytes
        }
        require(bytes.isNotEmpty() && bytes.size % 16 == 0) { "加密图片长度无效，请重新加载章节" }
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
            }.doFinal(bytes)
        } catch (failure: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("图片 AES 解密失败：源的密钥或图片地址可能已更新", failure)
        }
    }

    fun stripCount(spec: Spec, url: String): Int? {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        fun ascii(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.last().code
        return when (spec.kind) {
            Kind.JM_ROWS -> {
                val match = Regex("/photos/(\\d+)/(\\d+)").find(path) ?: return null
                val id = match.groupValues[1].toLongOrNull() ?: return null
                if (path.contains("gif", true) || id < 268850) null
                else (ascii(match.groupValues[1] + match.groupValues[2]) % if (id > 421925) 8 else 10) * 2 + 2
            }
            Kind.GUOGUO_ROWS -> Regex("(\\d+)\\.(?:jpg|png|gif|webp)$", RegexOption.IGNORE_CASE).find(path)?.let {
                (ascii(it.groupValues[1] + "0") % 10 + 11) * 4
            }
            Kind.XIANG_COLUMNS -> if (!url.contains("w=")) null else
                Regex("\\d+").findAll(url).lastOrNull()?.value?.toIntOrNull()?.also { require(it in 1..512) { "图片条带数量超出支持范围" } }
            else -> null
        }
    }

    /** 原脚本把余数放在最后一条源切片，移动到目标顶部。每个像素恰好覆盖一次。 */
    fun rowSlices(height: Int, count: Int): List<Slice> {
        require(height > 0 && count in 1..512 && count <= height) { "图片尺寸小于条带数量或条带数量无效" }
        val unit = height / count
        val remainder = height % count
        return (1..count).map { i ->
            val extra = if (i == count) remainder else 0
            Slice(unit * (i - 1), height - unit * i - extra, unit + extra)
        }
    }

    fun columnSlices(width: Int, count: Int): List<Slice> {
        require(width > 0 && count in 1..512 && count <= width) { "图片尺寸小于条带数量或条带数量无效" }
        // 用整数边界分配余数，避免原脚本浮点裁切在不能整除时留下透明列。
        return (0 until count).map { i ->
            val left = (width.toLong() * i / count).toInt()
            val right = (width.toLong() * (i + 1) / count).toInt()
            Slice(left, width - right, right - left)
        }
    }

    fun targetSize(url: String, width: Int, height: Int): Size? {
        val match = Regex("#(\\d+)x(\\d+)").find(url) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: error("图片目标尺寸无效")
        val h = match.groupValues[2].toIntOrNull() ?: error("图片目标尺寸无效")
        if (w == 1 && h == 1) return null
        require(w > 0 && h > 0) { "图片目标尺寸无效" }
        if (kotlin.math.abs(width.toDouble() / w * h - height) < 200) return null
        return Size(w, h)
    }

    fun cacheName(sourceId: String, url: String, spec: Spec?, cover: Boolean = false, context: MangaImageContext? = null): String =
        mangaHash((if (context == null || cover) "$sourceId|$url" else "$sourceId|page-v2|${context.key()}") +
            if (spec == null) "" else "|decoder-v1:${spec.fingerprint}:${if (cover) "cover" else "page"}")
}
