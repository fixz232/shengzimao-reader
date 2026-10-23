package com.shengzimao.reader.mobile.data

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 只访问番茄公开页面；与桌面版 tomato.cjs 使用相同接口与字段约定。 */
class TomatoEngine(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {

    suspend fun search(query: String): List<OnlineResult> {
        val id = TomatoParser.bookId(query)
        if (id.isNotEmpty()) {
            val book = TomatoParser.bookHtml(fetch(TomatoParser.bookUrl(id)), id)
            check(book.bookName.isNotBlank()) { "番茄暂未返回书籍信息，请稍后重试或打开官网核对。" }
            return listOf(book)
        }
        require(!query.contains("://")) { "请输入番茄书名、书籍 ID 或番茄官网书籍链接。" }
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("device_platform", "android")
            .addQueryParameter("parent_enterfrom", "novel_channel_search.tab.")
            .addQueryParameter("offset", "0").addQueryParameter("aid", "1967")
            .addQueryParameter("q", query).build().toString()
        return OnlineMatcher.filter(query, TomatoParser.searchResponse(json(fetch(url))))
    }

    suspend fun details(url: String): Updates.Details {
        val id = TomatoParser.bookId(url)
        require(id.isNotEmpty()) { "番茄书籍链接无效。" }
        val book = TomatoParser.bookHtml(fetch(TomatoParser.bookUrl(id)), id)
        check(book.bookName.isNotBlank()) { "番茄详情暂不可用，请稍后重试或打开官网核对。" }
        val toc = TomatoParser.directory(json(fetch("https://fanqienovel.com/api/reader/directory/detail?bookId=$id")))
        check(toc.isNotEmpty() && toc.size <= 30000) { "番茄没有返回有效目录，或目录超过 30000 章上限。" }
        return Updates.Details(book.copy(chapterCount = toc.size), toc)
    }

    suspend fun chapters(chapters: List<OnlineChapter>, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val text = StringBuilder()
        for ((index, chapter) in chapters.withIndex()) {
            coroutineContext.ensureActive()
            if (index > 0) delay(200)
            val body = TomatoParser.chapterHtml(fetch(chapter.url))
            check(body.isNotBlank()) { "番茄章节「${chapter.title}」没有可读取的公开正文，已停止。可在官网阅读。" }
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(chapter.title).append("\n\n").append(body)
            check(text.length <= 64 * 1024 * 1024) { "正文超过入库上限，已停止。" }
            progress(index + 1, chapters.size)
        }
        return text.toString()
    }

    suspend fun probe(): String {
        val response = json(fetch(SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("device_platform", "android").addQueryParameter("aid", "1967")
            .addQueryParameter("parent_enterfrom", "novel_channel_search.tab.")
            .addQueryParameter("offset", "0").addQueryParameter("q", "小说").build().toString()))
        check(response.has("data")) { "番茄服务没有返回搜索数据，请稍后重试。" }
        return "番茄公开搜索服务可连接；详情、目录和正文仍取决于官网开放情况。"
    }

    private fun json(body: String): JSONObject {
        val value = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalStateException("番茄服务暂不可用或需要网页验证，请稍后重试或打开官网。")
        check(!value.has("code") || value.optInt("code", -1) == 0) { "番茄请求未成功，请稍后重试或打开官网核对。" }
        return value
    }

    private suspend fun fetch(url: String, redirects: Int = 0): String {
        require(TomatoParser.allowedUrl(url, api = true)) { "番茄请求地址不在允许的公开域名内。" }
        coroutineContext.ensureActive()
        val response = suspendCancellableCoroutine<FetchResponse> { continuation ->
            val call = client.newCall(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; ShengzimaoReader/1.1)")
                .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("番茄网络请求失败，请检查网络后重试。", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { result ->
                        try {
                            if (result.code in 300..399) {
                                val location = result.header("Location")?.let { result.request.url.resolve(it)?.toString() }
                                    ?: throw IllegalStateException("番茄返回了无效跳转地址。")
                                if (continuation.isActive) continuation.resume(FetchResponse(redirect = location))
                                return
                            }
                            check(result.isSuccessful) { "番茄请求失败（HTTP ${result.code}），请稍后重试或打开官网。" }
                            check(result.body.contentLength() <= MAX_RESPONSE) { "番茄返回的数据超过大小上限。" }
                            val bytes = result.body.byteStream().use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (continuation.isActive) {
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    check(out.size() + size <= MAX_RESPONSE) { "番茄返回的数据超过大小上限。" }
                                    out.write(buffer, 0, size)
                                }
                                out.toByteArray()
                            }
                            val body = bytes.toString(Charsets.UTF_8)
                            check(body.isNotBlank()) { "番茄暂未返回数据，请稍后重试或打开官网核对。" }
                            if (continuation.isActive) continuation.resume(FetchResponse(text = body))
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
        if (response.redirect != null) {
            check(redirects < 3) { "番茄跳转次数过多，请打开官网核对。" }
            return fetch(response.redirect, redirects + 1)
        }
        return response.text
    }

    private data class FetchResponse(val text: String = "", val redirect: String? = null)

    companion object {
        const val SEARCH_URL = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/"
        private const val MAX_RESPONSE = 16 * 1024 * 1024
    }
}

/** 纯解析函数：不执行网页 JS、不读取令牌、不尝试绕过登录或付费限制。 */
object TomatoParser {
    const val SOURCE_ID = -2
    private val pageHosts = setOf("fanqienovel.com", "www.fanqienovel.com", "fqnovel.com", "www.fqnovel.com")
    private val numericId = Regex("\\d{3,32}")

    fun allowedUrl(value: String, api: Boolean = false): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (uri.host?.lowercase() in pageHosts || api && uri.host == "novel.snssdk.com")
    }.getOrDefault(false)

    fun bookId(value: String): String {
        val text = value.trim()
        if (numericId.matches(text)) return text
        if (!allowedUrl(text)) return ""
        return Regex("(?:/page/|/book/|[?&]bookId=|[?&]book_id=)(\\d{3,32})(?=[/?&#]|$)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1).orEmpty()
    }

    fun bookUrl(id: String): String {
        require(numericId.matches(id)) { "番茄书籍 ID 无效。" }
        return "https://fanqienovel.com/page/$id"
    }

    fun searchResponse(json: JSONObject): List<OnlineResult> {
        val seen = HashSet<String>()
        return objects(json).mapNotNull { item ->
            val id = first(item, "book_id", "bookId", "bookIdStr").ifBlank { bookId(first(item, "url", "book_url")) }
            val title = clean(first(item, "title", "book_name", "bookName"))
            if (!numericId.matches(id) || title.isBlank() || first(item, "genre") in setOf("4", "905") || !seen.add(id)) null
            else OnlineResult(
                bookName = title.take(250), author = clean(first(item, "author", "author_name", "authorName")).take(120),
                url = bookUrl(id), sourceId = SOURCE_ID, sourceName = "番茄小说",
                category = categories(first(item, "category", "category_name", "categoryName")),
                status = status(first(item, "creation_status", "creationStatus", "status", "serial_status")),
                wordCount = first(item, "word_count", "wordCount", "wordNumber", "words"),
                intro = clean(first(item, "intro", "abstract", "description")).take(10000),
            )
        }.take(500).toList()
    }

    fun bookHtml(html: String, id: String): OnlineResult {
        val scripts = Regex("<script\\b[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(html)
        val values = scripts.mapNotNull { match ->
            val script = match.groupValues[1].trim()
            val json = if (Regex("id=[\"'](?:__INITIAL_STATE__|__NEXT_DATA__)[\"']", RegexOption.IGNORE_CASE).containsMatchIn(match.value)) script
            else initialJson(script)
            json?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
        val item = values.flatMap { objects(it) }.firstOrNull {
            first(it, "bookName", "book_name", "title").isNotBlank() &&
                first(it, "author", "author_name", "authorName", "chapterCount", "chapterTotal").isNotBlank()
        } ?: JSONObject()
        val title = clean(first(item, "bookName", "book_name", "title")).ifBlank {
            Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        }
        return OnlineResult(
            bookName = title.take(250), author = clean(first(item, "author", "author_name", "authorName")).take(120),
            url = bookUrl(id), sourceId = SOURCE_ID, sourceName = "番茄小说",
            category = categories(first(item, "category", "categoryV2", "category_name")),
            status = status(first(item, "creation_status", "creationStatus", "status", "serial_status")),
            wordCount = first(item, "wordCount", "wordNumber", "word_count", "words"),
            lastUpdateTime = updateTime(first(item, "lastUpdateTime", "lastPublishTime", "last_update_time", "updateTime")),
            latestChapter = clean(first(item, "latestChapter", "lastChapterTitle", "lastChapterName", "last_chapter_name")),
            intro = clean(first(item, "intro", "abstract", "book_desc", "description")).take(10000),
            chapterCount = first(item, "chapterCount", "chapter_count", "chapterTotal").toIntOrNull() ?: 0,
        )
    }

    private fun initialJson(script: String): String? {
        val assignment = Regex("(?:window\\.)?__INITIAL_STATE__\\s*=\\s*").find(script) ?: return null
        val start = assignment.range.last + 1
        if (script.getOrNull(start) != '{') return null
        var depth = 0; var quoted = false; var escaped = false
        for (index in start until script.length) {
            val char = script[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return script.substring(start, index + 1)
            }
        }
        return null
    }

    fun directory(json: JSONObject): List<OnlineChapter> {
        val seen = HashSet<String>()
        return objects(json).mapNotNull { item ->
            val id = first(item, "item_id", "itemId", "chapter_id", "chapterId", "id")
            val title = clean(first(item, "title", "chapter_title", "chapterTitle", "chapter_name", "name"))
            if (!Regex("\\d{1,32}").matches(id) || title.isBlank() || !seen.add(id)) null
            else OnlineChapter(title.take(250), "https://fanqienovel.com/reader/$id")
        }.take(30001).toList()
    }

    fun chapterHtml(html: String): String {
        val body = Regex("<article\\b[^>]*>([\\s\\S]*?)</article>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("<(div|section)[^>]*(?:class|id)=[\"'][^\"']*(?:muye-reader-content|reader-content|chapter-content|content)[^\"']*[\"'][^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(2) ?: return ""
        val text = clean(body)
        if (text.any { it in '\uE000'..'\uF8FF' } || Regex("登录后阅读|购买本章|验证后阅读|付费解锁").containsMatchIn(text)) return ""
        return text
    }

    fun clean(value: String): String = value
        .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<br\\s*/?>|</p\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x")) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }
        .replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun objects(value: Any?, depth: Int = 0): Sequence<JSONObject> = sequence {
        if (depth > 32) return@sequence
        when (value) {
            is JSONObject -> {
                yield(value)
                for (key in value.keys()) yieldAll(objects(value.opt(key), depth + 1))
            }
            is JSONArray -> for (index in 0 until value.length()) yieldAll(objects(value.opt(index), depth + 1))
        }
    }

    private fun first(item: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        item.opt(key)?.takeUnless { it == JSONObject.NULL || it is Double || it is Float }?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }.orEmpty()

    private fun status(value: String): String = when (value) { "0" -> "已完结"; "1" -> "连载中"; else -> value }
    private fun categories(value: String): String = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { first(it, "Name", "name") }.filter { it.isNotBlank() }.joinToString(" / ")
    }.getOrDefault(value)

    private fun updateTime(value: String): String = runCatching {
        if (!Regex("\\d{10}|\\d{13}").matches(value)) value else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(value.toLong() * if (value.length == 10) 1000 else 1))
    }.getOrDefault(value)
}
