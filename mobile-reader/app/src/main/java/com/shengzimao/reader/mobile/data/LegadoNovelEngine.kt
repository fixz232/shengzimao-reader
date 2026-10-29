package com.shengzimao.reader.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.net.URLEncoder

/** Modern and old Legado novel rules run through the same bounded rule sandbox as legacy manga. */
class LegadoNovelEngine(
    private val source: JSONObject,
    private val root: File,
    private val fetch: (NovelRequest) -> NovelPage,
) {
    private val raw = source.getJSONObject("legado")
    private val base = source.getString("url").toHttpUrl().newBuilder().fragment(null).build().toString()
    private val sourceKey = source.getString("url")
    private val sourceName = source.getString("name")
    private val modern = raw.has("ruleToc") || raw.has("ruleContent")

    private fun rule(section: String, field: String, old: String = ""): String =
        if (modern) raw.optJSONObject(section)?.optString(field).orEmpty() else LegacyRuleCrypto.readRule(raw, old)

    private fun stateFile(url: String) = File(root, "state/${mangaHash(sourceKey + "|" + url)}.json")
    private fun runtime(bookUrl: String = "", book: JSONObject = JSONObject(), chapter: OnlineChapter? = null): LegacyRuleRuntime {
        val stored = if (bookUrl.isBlank()) JSONObject() else runCatching {
            val file = stateFile(bookUrl)
            if (file.isFile && file.length() <= 2_000_000) JSONObject(file.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
        val vars = stored.optJSONObject("variables") ?: JSONObject()
        val runtime = LegacyRuleRuntime(vars.keys().asSequence().associateWith { vars.optString(it) }.toMutableMap()) { url, body, headers ->
            // Script requests use the same URL/options, cookies and charset handling as rule requests.
            val defaults = raw.optString("header").trim().takeIf { it.startsWith('{') }?.let(::jsonHeaders).orEmpty()
            val request = NovelRequest.parse(url, base, body = body, headers = defaults + headers, sourceKey = sourceKey)
            fetch(request).text
        }
        chapter?.ruleState?.takeIf { it.isNotBlank() }?.let { text ->
            require(text.length <= 64000) { "章节规则变量过大" }
            val state = JSONObject(text)
            state.keys().forEach { runtime.variables[it] = state.getString(it) }
        }
        runtime.novelMode = true
        runtime.scriptLibrary = raw.optString("jsLib")
        runtime.scriptObjects = mapOf("source" to raw.toString(), "book" to
            (if (book.length() > 0) book else stored.optJSONObject("book") ?: JSONObject()).toString(),
            "chapter" to JSONObject().put("title", chapter?.title.orEmpty()).put("url", chapter?.url.orEmpty()).toString())
        return runtime
    }

    private fun saveState(url: String, runtime: LegacyRuleRuntime, book: OnlineResult) {
        val vars = JSONObject(runtime.variables as Map<*, *>)
        val json = JSONObject().put("variables", vars).put("book", book.toJson()).toString()
        require(json.length <= 2_000_000) { "书源保存的作品变量过大" }
        atomicJson(stateFile(url), json)
    }

    private data class LoadedPage(val url: String, val text: String, val request: NovelRequest)

    private fun load(template: String, input: String, url: String, runtime: LegacyRuleRuntime): LoadedPage =
        load(request(template, input, url, runtime))

    private fun load(request: NovelRequest): LoadedPage {
        checkInterrupted()
        val page = fetch(request)
        return LoadedPage(page.url, page.text, request)
    }

    private fun request(template: String, input: String, url: String, runtime: LegacyRuleRuntime): NovelRequest {
        checkInterrupted()
        var expanded = expandRequest(template, input, url, runtime)
        if (expanded.startsWith("@js:", true) || expanded.startsWith("<js>", true))
            expanded = runtime.evaluate(expanded, input, url).joinToString("")
        val headerRule = raw.optString("header")
        val headerText = when {
            headerRule.isBlank() -> "{}"
            headerRule.startsWith("@js:", true) || headerRule.startsWith("<js>", true) -> runtime.evaluate(headerRule, input, url).joinToString("")
            else -> runtime.interpolate(headerRule, input, url)
        }
        var headers = jsonHeaders(headerText)
        var body: String? = null
        if (!modern) {
            val parts = splitLegacyHeaders(expanded)
            for (obj in parts.objects) headers = headers + jsonHeaders(runtime.interpolate(obj, input, url))
            val post = legacySearchPost(parts.rule)
            expanded = post.first; body = post.second
        }
        return NovelRequest.parse(expanded, url, body = body, headers = headers, sourceKey = sourceKey)
    }

    private fun expandRequest(template: String, input: String, url: String, runtime: LegacyRuleRuntime): String {
        if (template.trimStart().startsWith("@js:", true) || template.trimStart().startsWith("<js>", true))
            return runtime.interpolate(template, input, url)
        val marker = Regex(",\\s*(?=\\{)").find(template)
        val options = marker?.let { JSONObject(template.substring(it.range.last + 1)) }
        val charset = options?.optString("charset")?.takeIf { it.isNotBlank() } ?: "UTF-8"
        val encodedKey = URLEncoder.encode(runtime.searchKeyword.orEmpty(), charset).replace("+", "%20")
        // Android's regex engine requires both closing braces to be escaped.
        val keyToken = Regex("\\{\\{\\s*key\\s*\\}\\}")
        fun expand(value: Any): Any = when (value) {
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> out.put(key, expand(value.get(key))) } }
            is JSONArray -> JSONArray((0 until value.length()).map { expand(value.get(it)) })
            is String -> runtime.interpolate(value, input, url)
            else -> value
        }
        val address = runtime.interpolate(keyToken.replace(if (marker == null) template else template.substring(0, marker.range.first)) { encodedKey }, input, url)
        if (options == null) return address
        val body = options.opt("body")
        val expandedOptions = expand(options) as JSONObject
        if (body is String) {
            val json = body.trimStart().startsWith('{') || body.trimStart().startsWith('[')
            val value = if (json) runCatching {
                if (body.trimStart().startsWith('{')) expand(JSONObject(body)).toString() else expand(JSONArray(body)).toString()
            }.getOrElse { runtime.interpolate(body, input, url) }
            else runtime.interpolate(keyToken.replace(body) { encodedKey }, input, url)
            expandedOptions.put("body", value)
        }
        return "$address,$expandedOptions"
    }

    private fun values(runtime: LegacyRuleRuntime, expression: String, input: String, url: String, list: Boolean = false): List<String> =
        if (expression.isBlank()) emptyList() else runtime.evaluate(expression, input, url, list,
            objectInput = input.trimStart().let { it.startsWith('{') || it.startsWith('[') })

    private fun field(runtime: LegacyRuleRuntime, expression: String, input: String, url: String): String =
        values(runtime, expression, input, url).joinToString("\n").trim()

    private fun link(value: String, url: String): String = NovelRequest.parse(value, url, sourceKey = sourceKey).reference

    fun search(keyword: String, page: Int = 1): List<OnlineResult> {
        require(NovelLegadoRules.searchable(source)) { "此阅读书源没有可用的搜索规则" }
        val runtime = runtime().apply { searchKeyword = keyword; searchPage = page }
        var template = if (modern) raw.getString("searchUrl") else raw.getString("ruleSearchUrl")
        if (!modern) template = template.replace("searchKey", URLEncoder.encode(keyword, "UTF-8"))
            .replace("searchPage", page.toString())
        val response = load(template, "", base, runtime)
        val items = values(runtime, rule("ruleSearch", "bookList", "ruleSearchList"), response.text, response.url, true)
        return items.take(500).mapNotNull { item ->
            checkInterrupted()
            val each = runtime().apply {
                variables.putAll(runtime.variables); searchKeyword = keyword; searchPage = page
            }
            val title = field(each, rule("ruleSearch", "name", "ruleSearchName"), item, response.url)
            val address = field(each, rule("ruleSearch", "bookUrl", "ruleSearchNoteUrl"), item, response.url)
            if (title.isBlank() || address.isBlank()) return@mapNotNull null
            val result = OnlineResult(bookName = title.take(250),
                author = field(each, rule("ruleSearch", "author", "ruleSearchAuthor"), item, response.url).take(250),
                url = link(address, response.url), sourceName = sourceName, sourceKey = sourceKey,
                sourceId = (sourceKey.hashCode() and 0x3fffffff) + 1_000_000,
                intro = field(each, rule("ruleSearch", "intro", "ruleIntroduce"), item, response.url).take(8000),
                latestChapter = field(each, rule("ruleSearch", "lastChapter", "ruleSearchLastChapter"), item, response.url).take(250),
                category = field(each, rule("ruleSearch", "kind", "ruleSearchKind"), item, response.url).take(250))
            saveState(result.url, each, result)
            result
        }.distinctBy { it.url }
    }

    fun details(bookUrl: String): Updates.Details {
        val runtime = runtime(bookUrl)
        val detail = load(bookUrl, "", base, runtime)
        val info = raw.optJSONObject("ruleBookInfo")
        val init = info?.optString("init").orEmpty()
        val infoText = if (init.isBlank()) detail.text else field(runtime, init, detail.text, detail.url)
        fun bookField(key: String, old: String) = field(runtime, rule("ruleBookInfo", key, old), infoText, detail.url)
        val remembered = runCatching { JSONObject(runtime.scriptObjects.getValue("book")) }.getOrDefault(JSONObject())
        val book = OnlineResult(bookName = bookField("name", "ruleBookName").ifBlank { remembered.optString("bookName") },
            author = bookField("author", "ruleBookAuthor").ifBlank { remembered.optString("author") },
            intro = bookField("intro", "ruleIntroduce").take(8000), url = bookUrl,
            sourceKey = sourceKey, sourceName = sourceName,
            latestChapter = bookField("lastChapter", "ruleBookLastChapter").take(250))
        runtime.scriptObjects = runtime.scriptObjects + ("book" to book.toJson().put("bookUrl", bookUrl).put("name", book.bookName).toString())
        val tocLink = field(runtime, rule("ruleBookInfo", "tocUrl", "ruleChapterUrl"), infoText, detail.url)
        var current = if (tocLink.isBlank()) detail else load(tocLink, detail.text, detail.url, runtime)
        val chapters = ArrayList<OnlineChapter>()
        val visited = HashSet<String>()
        val pendingPages = ArrayDeque<NovelRequest>()
        val listRule = rule("ruleToc", "chapterList", "ruleChapterList")
        repeat(100) {
            val responseIdentity = current.request.copy(url = current.url).referenceIdentity
            require(visited.add(current.request.referenceIdentity) &&
                (responseIdentity == current.request.referenceIdentity || visited.add(responseIdentity))) { "目录分页出现循环，请检查书源规则" }
            val items = values(runtime, listRule.removePrefix("-").removePrefix("+"), current.text, current.url, true)
            for (item in items) {
                checkInterrupted()
                val before = runtime.variables.toMap()
                val title = field(runtime, rule("ruleToc", "chapterName", "ruleChapterName"), item, current.url)
                val address = field(runtime, rule("ruleToc", "chapterUrl", "ruleContentUrl"), item, current.url).ifBlank {
                    if (items.size == 1 && rule("ruleToc", "chapterUrl", "ruleContentUrl").isBlank()) current.url else ""
                }
                val delta = runtime.variables.filter { (key, value) -> before[key] != value }
                val state = if (delta.isEmpty()) "" else JSONObject(delta as Map<*, *>).toString()
                require(state.length <= 64000) { "章节规则变量过大" }
                if (title.isNotBlank() && address.isNotBlank()) chapters += OnlineChapter(title.take(250), link(address, current.url), state)
                runtime.variables.clear(); runtime.variables.putAll(before)
            }
            require(chapters.size <= 30000) { "目录超过 30000 章，已停止读取" }
            val links = values(runtime, rule("ruleToc", "nextTocUrl", "ruleChapterUrlNext"), current.text, current.url)
                .filter { it.isNotBlank() && it !in setOf("false", "null", "0", "undefined") }
                .map { NovelRequest.parse(it, current.url, sourceKey = sourceKey) }.distinctBy { it.referenceIdentity }
            links.filter { candidate -> candidate.referenceIdentity !in visited && pendingPages.none { it.referenceIdentity == candidate.referenceIdentity } }
                .forEach(pendingPages::addLast)
            require(pendingPages.size <= 100) { "目录分页超过 100 页，未保存不完整目录" }
            while (pendingPages.firstOrNull()?.referenceIdentity in visited) pendingPages.removeFirst()
            val next = pendingPages.removeFirstOrNull()
            if (next == null) {
                val toc = chapters.distinctBy { it.url }.let { if (listRule.startsWith('-')) it.reversed() else it }
                require(toc.isNotEmpty()) { "书源没有返回可读目录；可能需要登录、验证或更新规则" }
                saveState(bookUrl, runtime, book)
                val linked = toc.mapIndexed { index, chapter -> chapter.copy(nextUrl = toc.getOrNull(index + 1)?.url.orEmpty(), previousUrl = toc.getOrNull(index - 1)?.url.orEmpty()) }
                require(OnlineChapterCodec.encode(linked).length <= 16_000_000) { "目录及规则变量合计过大" }
                return Updates.Details(book.copy(chapterCount = linked.size), linked)
            }
            current = load(next.reference, current.text, current.url, runtime)
        }
        error("目录分页超过 100 页，未保存不完整目录")
    }

    fun chapter(bookUrl: String, chapter: OnlineChapter): String {
        val runtime = runtime(bookUrl, chapter = chapter)
        var current = load(chapter.url, "", base, runtime)
        val visited = HashSet<String>()
        val pendingPages = ArrayDeque<NovelRequest>()
        val text = StringBuilder()
        val directory = NovelRequest.parse(bookUrl, base, sourceKey = sourceKey).referenceIdentity
        val adjacent = listOf(chapter.nextUrl, chapter.previousUrl).filter { it.isNotBlank() }
            .map { NovelRequest.parse(it, base, sourceKey = sourceKey).referenceIdentity }.toSet() + directory
        repeat(50) {
            val responseIdentity = current.request.copy(url = current.url).referenceIdentity
            require(visited.add(current.request.referenceIdentity) &&
                (responseIdentity == current.request.referenceIdentity || visited.add(responseIdentity))) { "正文分页出现循环" }
            val contentRule = rule("ruleContent", "content", "ruleBookContent")
            val extracted = values(runtime, contentRule, current.text, current.url).joinToString("\n")
            val part = cleanText(extracted)
            val candidates = values(runtime, rule("ruleContent", "nextContentUrl", "ruleContentUrlNext"), current.text, current.url)
                .filter { it.isNotBlank() && it !in setOf("false", "null", "0", "undefined") }
                .map { NovelRequest.parse(it, current.url, sourceKey = sourceKey) }
            candidates.distinctBy { it.referenceIdentity }.filter { candidate -> candidate.referenceIdentity !in visited &&
                candidate.referenceIdentity !in adjacent && pendingPages.none { it.referenceIdentity == candidate.referenceIdentity } }
                .forEach(pendingPages::addLast)
            require(pendingPages.size <= 50) { "正文分页超过 50 页，未返回不完整章节" }
            while (pendingPages.firstOrNull()?.referenceIdentity in visited) pendingPages.removeFirst()
            val next = pendingPages.removeFirstOrNull()
            // Some sites expose an empty final page whose next link explicitly returns to the
            // directory. Only accept that recognizable terminal template after real content.
            val terminal = part.isBlank() && text.isNotBlank() && next == null &&
                candidates.any { it.referenceIdentity == directory } && emptyContainer(contentRule, current.text)
            require(part.isNotBlank() || terminal) { "正文为空；请检查规则、原站登录或验证状态" }
            if (part.isNotBlank()) {
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(part)
                require(text.length <= 2_000_000) { "单章正文超过大小上限" }
            }
            if (next == null) {
                var answer = text.toString()
                val replace = rule("ruleContent", "replaceRegex", "ruleBookContentReplace")
                if (replace.isNotBlank()) {
                    // A bare replacement rule acts on the extracted text, not on the HTML document.
                    answer = if (replace.startsWith("@js:", true)) runtime.evaluate(replace, answer, current.url).joinToString("\n")
                    else runtime.evaluate(if (replace.startsWith("##")) replace else "##$replace", answer, current.url).joinToString("\n")
                }
                require(answer.isNotBlank()) { "正文净化后为空，请检查书源替换规则" }
                return chapter.title + "\n\n" + answer.trim() + "\n\n"
            }
            current = load(next.reference, current.text, current.url, runtime)
        }
        error("正文分页超过 50 页，未返回不完整章节")
    }

    suspend fun chapters(bookUrl: String, chapters: List<OnlineChapter>, cache: ChapterDownloadCache,
        concurrency: Int, progress: (Int, Int, String) -> Unit): String = coroutineScope {
        require(chapters.isNotEmpty()) { "没有可下载的章节" }
        val semaphore = Semaphore(concurrency.coerceIn(1, 8))
        val done = java.util.concurrent.atomic.AtomicInteger()
        val size = java.util.concurrent.atomic.AtomicLong()
        // Bounded workers avoid creating tens of thousands of pending download coroutines.
        val result = arrayOfNulls<String>(chapters.size)
        val cursor = java.util.concurrent.atomic.AtomicInteger()
        List(minOf(concurrency.coerceIn(1, 8), chapters.size)) {
            async(Dispatchers.IO) {
                while (true) {
                    val index = cursor.getAndIncrement()
                    if (index >= chapters.size) break
                    val chapter = chapters[index]
                    val body = semaphore.withPermit { runInterruptible {
                        cache.read(chapter) ?: this@LegadoNovelEngine.chapter(bookUrl, chapter).also { cache.save(chapter, it) }
                    } }
                    require(size.addAndGet(body.length.toLong()) <= 128L * 1024 * 1024) { "下载正文超过 128 MB 上限" }
                    result[index] = body
                    val count = done.incrementAndGet()
                    progress(count, chapters.size, "已保存 $count / ${chapters.size} 章")
                }
            }
        }.awaitAll()
        require(result.sumOf { it?.length?.toLong() ?: 0L } <= 128L * 1024 * 1024) { "下载正文超过 128 MB 上限" }
        result.joinToString("") { requireNotNull(it) }
    }

    companion object {
        internal fun jsonHeaders(text: String): Map<String, String> = JSONObject(text).let { obj ->
            require(obj.length() <= 32) { "书源请求头超过 32 项" }
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }

        internal fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("书源操作已取消")
        }

        internal fun cleanText(input: String): String {
            if (!Regex("</?[A-Za-z][^>]*>").containsMatchIn(input)) return Jsoup.parse(input.replace("\n", "<br>")).wholeText().trim()
            val document = Jsoup.parse(input)
            document.select("script,style,noscript").remove()
            document.select("br").forEach { it.before("\n"); it.remove() }
            document.select("p,div,section,li,h1,h2,h3").forEach { it.before("\n"); it.after("\n") }
            return document.wholeText().replace('\u00a0', ' ').replace(Regex("[\\t ]*\\n[\\t ]*"), "\n")
                .replace(Regex("\n{3,}"), "\n\n").trim()
        }

        private fun emptyContainer(expression: String, html: String): Boolean {
            return runCatching {
                if (expression.contains("@js:", true) || expression.contains("<js>", true) || expression.contains("##")) return false
                val (path, _) = SelectorSplitter.split(LegadoConverter.normalize(expression))
                if (path.isBlank()) return false
                val doc = Jsoup.parse(html)
                val nodes = if (SelectorSplitter.isXPath(path)) doc.selectXpath(SelectorSplitter.normalizeXPath(path)) else doc.select(path)
                nodes.isNotEmpty() && nodes.all { node ->
                    val copy = node.clone(); copy.select("script,style,noscript").remove()
                    copy.wholeText().replace('\u00a0', ' ').isBlank()
                }
            }.getOrDefault(false)
        }
    }
}
