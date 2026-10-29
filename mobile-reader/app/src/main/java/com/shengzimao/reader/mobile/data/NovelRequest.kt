package com.shengzimao.reader.mobile.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class NovelPage(val url: String, val text: String)

/** Legado keeps request options after a comma in book/chapter URLs; retain them in saved links. */
data class NovelRequest(val url: String, val method: String = "GET", val body: String? = null,
    val headers: Map<String, String> = emptyMap(), val charset: String = "", val sourceKey: String = "",
    val options: JSONObject? = null) {
    val reference: String get() = url + options?.let { ",$it" }.orEmpty()

    /** Pagination must distinguish POST bodies and header options on the same endpoint. */
    val identity: String by lazy { mangaHash(org.json.JSONArray().put(url).put(method).put(body)
        .put(charset.uppercase(java.util.Locale.ROOT)).put(org.json.JSONArray(headers.entries
            .sortedBy { it.key.lowercase(java.util.Locale.ROOT) }
            .map { org.json.JSONArray().put(it.key.lowercase(java.util.Locale.ROOT)).put(it.value) })).toString()) }

    /** Compare saved navigation targets without evaluating a source's dynamic default headers. */
    val referenceIdentity: String by lazy {
        val explicit = options?.optJSONObject("headers") ?: options?.optJSONObject("header")
        copy(headers = explicit?.keys()?.asSequence()?.associateWith { explicit.getString(it) }.orEmpty()).identity
    }

    companion object {
        fun parse(raw: String, base: String, body: String? = null, headers: Map<String, String> = emptyMap(), sourceKey: String = ""): NovelRequest {
            require(raw.isNotBlank() && raw.length <= 16000) { "书源返回空地址或地址过长" }
            val marker = Regex(",\\s*(?=\\{)").find(raw)
            val address = if (marker == null) raw.trim() else raw.substring(0, marker.range.first).trim()
            val options = marker?.let { JSONObject(raw.substring(it.range.last + 1)) }
            require(options?.optBoolean("webView") != true && options?.optString("webJs").isNullOrBlank() && options?.optString("js").isNullOrBlank()) {
                "此书源请求需要浏览器验证或 WebView 脚本，当前小说兼容引擎暂不支持"
            }
            val baseUrl = base.substringBefore(",{").toHttpUrl()
            val target = baseUrl.resolve(address)?.takeIf { it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty() }
                ?: error("书源返回无效的 HTTP(S) 地址")
            val method = options?.optString("method")?.takeIf { it.isNotBlank() }?.uppercase()
                ?: if (body != null) "POST" else "GET"
            require(method in setOf("GET", "POST")) { "书源暂不支持 $method 请求" }
            val optionHeaders = options?.optJSONObject("headers") ?: options?.optJSONObject("header")
            val merged = headers.toMutableMap().apply {
                optionHeaders?.keys()?.forEach { key -> keys.firstOrNull { it.equals(key, true) }?.let(::remove); put(key, optionHeaders.getString(key)) }
            }
            require(merged.size <= 32) { "书源请求头过多" }
            merged.forEach { (name, value) ->
                require(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name) &&
                    name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding") &&
                    value.length <= 8192 && value.none { it == '\r' || it == '\n' || it.code == 0 }) { "书源请求头无效：$name" }
            }
            val charset = options?.optString("charset").orEmpty()
            require(charset.isBlank() || Charset.isSupported(charset)) { "书源字符集不受支持：$charset" }
            val data = if (options?.has("body") == true && !options.isNull("body")) options.get("body").toString() else body
            return NovelRequest(target.toString(), method, if (method == "POST") data.orEmpty() else null,
                merged, charset, sourceKey, options)
        }
    }
}

/** Separate source sessions, reuse connection pools, and preserve normal TLS verification. */
class NovelHttpTransport {
    private val sessions = ConcurrentHashMap<String, OkHttpClient>()
    private val common = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    fun fetch(request: NovelRequest): NovelPage {
        LegadoNovelEngine.checkInterrupted()
        val client = sessions.computeIfAbsent(request.sourceKey) {
            val savedCookies = mutableListOf<Cookie>()
            common.newBuilder().cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(savedCookies) {
                    savedCookies.removeAll { old -> old.expiresAt < System.currentTimeMillis() || cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                    savedCookies.addAll(cookies); while (savedCookies.size > 500) savedCookies.removeAt(0)
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(savedCookies) {
                    savedCookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                }
            }).build()
        }
        val builder = Request.Builder().url(request.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        if (request.method == "POST") {
            val type = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?: "application/x-www-form-urlencoded; charset=${request.charset.ifBlank { "UTF-8" }}"
            val encoding = Charset.forName(request.charset.ifBlank { "UTF-8" })
            builder.post(request.body.orEmpty().toByteArray(encoding).toRequestBody(type.toMediaType()))
        }
        client.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "书源请求失败（HTTP ${response.code}），请检查原站状态或更新规则" }
            require(response.body.contentLength() <= 8_000_000) { "书源响应超过 8 MB 上限" }
            val output = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    LegadoNovelEngine.checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8_000_000) { "书源响应超过 8 MB 上限" }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            val declared = request.charset.takeIf { it.isNotBlank() }?.let(Charset::forName)
                ?: response.body.contentType()?.charset()
            val htmlCharset = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
                .find(bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1))?.groupValues?.get(1)
            val charset = declared ?: htmlCharset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val text = bytes.toString(charset).removePrefix("\uFEFF")
            require(text.isNotBlank()) { "书源返回空响应" }
            return NovelPage(response.request.url.toString(), text)
        }
    }
}
