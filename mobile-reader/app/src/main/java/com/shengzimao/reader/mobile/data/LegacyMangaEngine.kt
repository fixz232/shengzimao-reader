package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Legacy pagination scripts explicitly return 0/false when no next page exists. */
private fun hasLegacyNext(value: String) = value.trim().let { it.isNotEmpty() && it !in setOf("0", "false", "null", "undefined") }

/** URL@key=value is form POST; URL@{...} is JSON POST. */
internal fun legacySearchPost(raw: String): Pair<String, String?> {
    val parts = splitLegacyHeaders(raw)
    val address = parts.rule
    // 完整 URL 脚本仍交给原运行时执行，不把脚本里的字符串误识别为表单。
    if (address.contains("{{") || Regex("@js\\s*:|<js>|<javascript>", RegexOption.IGNORE_CASE).containsMatchIn(address)) return raw to null
    val marker = Regex("@(?=\\{|[A-Za-z0-9_.%\\[\\]-]+=)").find(address)?.range?.first ?: return raw to null
    return (address.substring(0, marker) + parts.objects.joinToString("") { "@Header:$it" }) to address.substring(marker + 1)
}

/** 旧式源逐段执行，变量按作品/章节隔离，避免多本漫画互相覆盖 java.put 的数据。 */
class LegacyMangaEngine(
    private val fetch: (String, MangaSource, String?, Map<String, String>) -> String,
    private val imageHeaders: (String, String, Map<String, String>) -> Unit,
) {
    private fun runtime(source: MangaSource, state: String = "") = LegacyRuleRuntime(
        runCatching { JSONObject(state) }.getOrDefault(JSONObject()).let { j -> j.keys().asSequence().associateWith { j.optString(it) }.toMutableMap() },
    ) { url, body, headers -> fetch(url, source, body, headers) }
    private fun rule(source: MangaSource, name: String): String = source.legacy?.let { LegacyRuleCrypto.readRule(it, name) }.orEmpty()
    private fun state(runtime: LegacyRuleRuntime) = JSONObject(runtime.variables as Map<*, *>).toString()

    private fun address(raw: String, input: String, base: String, runtime: LegacyRuleRuntime, expand: Boolean = true): Pair<String, Map<String, String>> {
        val parts = splitLegacyHeaders(raw)
        val expandedParts = splitLegacyHeaders(if (expand) runtime.interpolate(parts.rule, input, base) else parts.rule)
        val expanded = expandedParts.rule
        val url = if (expand && (expanded.contains("@js", true) || expanded.startsWith("<js>", true))) runtime.evaluate(expanded, input, base).firstOrNull().orEmpty()
            else expanded.removePrefix("thisPage").let { if (expanded == "thisPage") base else it }
        val result = splitLegacyHeaders(url)
        val target = base.toHttpUrl().resolve(result.rule.trim()) ?: error("旧式源返回了无效地址")
        return mangaHttpUrl(target.toString()) to headers(parts.objects + expandedParts.objects + result.objects, input, target.toString(), runtime)
    }

    private fun headers(objects: List<String>, input: String, base: String, runtime: LegacyRuleRuntime): Map<String, String> =
        objects.flatMap { raw ->
            val text = runtime.interpolate(raw, input, base)
            legacyHeaderObject(text).map { (key, value) -> key to when (value) {
                "host" -> URI(base).let { "${it.scheme}://${it.rawAuthority}" }
                "thisPage" -> base
                else -> value
            } }.toList()
        }.toMap()

    fun search(source: MangaSource, keyword: String, page: Int): List<MangaBook> {
        val runtime = runtime(source)
        runtime.searchKeyword = keyword
        runtime.searchPage = page
        val originalParts = splitLegacyHeaders(rule(source, "ruleSearchUrl"))
        var template = runtime.interpolate(originalParts.rule, "", source.baseUrl)
        if (template.startsWith("@js", true) || template.startsWith("<js>", true))
            template = runtime.evaluate(template, "", source.baseUrl).firstOrNull().orEmpty()
        val templateHeaders = splitLegacyHeaders(template)
        template = templateHeaders.rule
        val charset = Regex("\\|(?:char|charset)=([A-Za-z0-9_-]+)$", RegexOption.IGNORE_CASE).find(template)
        val charsetName = charset?.groupValues?.get(1) ?: "UTF-8"
        require(java.nio.charset.Charset.isSupported(charsetName)) { "搜索规则字符集不受支持：$charsetName" }
        if (charset != null) template = template.substring(0, charset.range.first)
        template += (originalParts.objects + templateHeaders.objects).joinToString("") { "@Header:$it" }
        val encoded = URLEncoder.encode(keyword, charsetName).replace("+", "%20")
        val (rawAddress, rawForm) = legacySearchPost(template)
        // Apply page variants to the URL only; braces in a GraphQL body are data.
        fun pages(value: String) = value.replace("searchPage-1", (page - 1).toString())
            .replace("searchPage+1", (page + 1).toString()).replace("searchPage", page.toString())
        val addressRule = pages(Regex("\\{([^{}\"':]*,[^{}\"':]*)\\}").replace(rawAddress) {
            it.groupValues[1].split(',').let { choices -> choices[(page - 1).coerceIn(0, choices.lastIndex)].trim() }
        })
        val form = rawForm?.let(::pages)?.let { value ->
            // Some Yiciyuan sources wrap a URL-encoded form in braces; it is not JSON.
            if (value.startsWith('{') && value.endsWith('}') &&
                Regex("^\\{[A-Za-z0-9_.%\\[\\]-]+=").containsMatchIn(value)) value.substring(1, value.length - 1) else value
        }
        val (url, headers) = address(addressRule.replace("searchKey", encoded), "", source.baseUrl, runtime, expand = false)
        val jsonBody = form?.trimStart()?.startsWith('{') == true
        val body = form?.replace("searchKey", if (jsonBody) JSONObject.quote(keyword).let { it.substring(1, it.length - 1) } else encoded)
        if (jsonBody) JSONObject(body!!)
        val requestHeaders = if (body != null && headers.keys.none { it.equals("Content-Type", true) })
            headers + ("Content-Type" to if (jsonBody) "application/json; charset=utf-8" else "application/x-www-form-urlencoded; charset=$charsetName") else headers
        val text = fetch(url, source, body, requestHeaders)
        val items = runtime.evaluate(rule(source, "ruleSearchList"), text, url, true)
        return items.take(300).mapNotNull { item ->
            val each = runtime(source, state(runtime))
            val title = each.evaluate(rule(source, "ruleSearchName"), item, url, objectInput = true).firstOrNull().orEmpty()
            val link = each.evaluate(rule(source, "ruleSearchNoteUrl"), item, url, objectInput = true).firstOrNull().orEmpty()
            if (title.isBlank() || link.isBlank()) null else {
                val (target, _) = address(link, item, url, each)
                val cover = each.evaluate(rule(source, "ruleSearchCoverUrl"), item, url).firstOrNull().orEmpty().takeIf { rule(source, "ruleSearchCoverUrl").isNotBlank() }.orEmpty()
                MangaBook(mangaHash("${source.id}|$target").take(32), title.take(250), source.id, source.name, target,
                    cover = cover.takeIf { it.isNotBlank() }?.let { URI(url).resolve(it).toString() }.orEmpty(),
                    author = rule(source, "ruleSearchAuthor").takeIf { it.isNotBlank() }?.let { each.evaluate(it, item, url).firstOrNull() }.orEmpty(), ruleState = state(each))
            }
        }.distinctBy { it.url }
    }

    fun details(source: MangaSource, book: MangaBook): MangaBook {
        val runtime = runtime(source, book.ruleState)
        val (detailUrl, headers) = address(book.url, "", book.url, runtime)
        val text = fetch(detailUrl, source, null, headers)
        val tocRule = rule(source, "ruleChapterUrl")
        val tocAddress = if (tocRule.isBlank() || tocRule == "thisPage") detailUrl to headers else {
            val parts = splitLegacyHeaders(tocRule)
            val link = runtime.evaluate(parts.rule, text, detailUrl).firstOrNull().orEmpty()
            val target = address(link, text, detailUrl, runtime)
            target.first to (target.second + headers(parts.objects, text, target.first, runtime))
        }
        val chapters = ArrayList<MangaChapter>()
        var url = tocAddress.first
        val visited = HashSet<String>()
        var tocText = if (url == detailUrl) text else fetch(url, source, null, tocAddress.second)
        repeat(50) {
            require(visited.add(url)) { "旧式源目录分页出现循环" }
            runtime.evaluate(rule(source, "ruleChapterList").removePrefix("-"), tocText, url, true).forEach { item ->
                val title = runtime.evaluate(rule(source, "ruleChapterName"), item, url, objectInput = true).firstOrNull().orEmpty()
                val contentRule = splitLegacyHeaders(rule(source, "ruleContentUrl"))
                val raw = runtime.evaluate(contentRule.rule, item, url, objectInput = true).firstOrNull().orEmpty()
                if (raw.isNotBlank()) {
                    val target = address(raw, item, url, runtime)
                    chapters += MangaChapter(title.ifBlank { "未命名章节" }.take(250), target.first,
                        ruleState = state(runtime), requestHeaders = headers(contentRule.objects, item, target.first, runtime) + target.second)
                }
            }
            require(chapters.size <= 30000) { "漫画目录超过 30000 章上限" }
            val nextRule = rule(source, "ruleChapterUrlNext")
            val next = if (nextRule.isBlank()) "" else runtime.evaluate(nextRule, tocText, url).firstOrNull().orEmpty()
            if (!hasLegacyNext(next)) return finishDetails(source, book, text, runtime, chapters)
            val nextAddress = address(next, tocText, url, runtime)
            if (nextAddress.first in visited) return finishDetails(source, book, text, runtime, chapters)
            url = nextAddress.first; tocText = fetch(url, source, null, nextAddress.second)
        }
        error("目录分页超过上限，未保存不完整目录")
    }

    private fun finishDetails(source: MangaSource, book: MangaBook, text: String, runtime: LegacyRuleRuntime, chapters: List<MangaChapter>): MangaBook {
        require(chapters.isNotEmpty()) { BaoziReader.emptyDirectoryMessage(text, book.url)
            ?: BaoziReader.publicDetail(source.baseUrl, book.url)?.let { publicUrl ->
                BaoziReader.emptyDirectoryMessage(fetch(publicUrl, source, null, emptyMap()), publicUrl)
            }
            ?: "旧式源未返回有效目录，请运行源测试查看规则状态" }
        val unique = chapters.distinctBy { it.url }.let { if (rule(source, "ruleChapterList").startsWith("-")) it.reversed() else it }
        fun field(name: String) = rule(source, name).takeIf { it.isNotBlank() }?.let { runtime.evaluate(it, text, book.url).firstOrNull() }.orEmpty()
        return book.copy(chapters = unique, intro = field("ruleIntroduce").take(8000), author = field("ruleBookAuthor").ifBlank { book.author }, ruleState = state(runtime))
    }

    fun pages(source: MangaSource, chapter: MangaChapter): List<String> {
        val runtime = runtime(source, chapter.ruleState)
        var url = chapter.url
        var requestHeaders = chapter.requestHeaders.ifEmpty {
            headers(splitLegacyHeaders(rule(source, "ruleContentUrl")).objects, "", chapter.url, runtime)
        }
        val result = LinkedHashSet<String>(); val visited = HashSet<String>()
        repeat(50) {
            require(visited.add(url)) { "图片分页出现循环，未标记下载完成" }
            val text = fetch(url, source, null, requestHeaders)
            val parts = splitLegacyHeaders(rule(source, "ruleBookContent"))
            val header = headers(parts.objects, text, url, runtime)
            runtime.evaluate(parts.rule, text, url, true).forEach { image ->
                if (image.isNotBlank() && !image.startsWith("data:") && !mangaPlaceholder(image)) {
                    // Scripts may return per-image @Header declarations. Keep them as request
                    // context instead of treating the suffix as part of the image address.
                    val imageParts = splitLegacyHeaders(image)
                    val target = url.toHttpUrl().resolve(imageParts.rule.trim())?.toString()
                        ?: error("旧式源返回了无效图片地址")
                    if (!mangaPlaceholder(target)) {
                        result += mangaHttpUrl(target)
                        imageHeaders(source.id, target, header + headers(imageParts.objects, text, url, runtime))
                    }
                }
            }
            require(result.size <= 5000) { "单章图片超过 5000 张上限" }
            val nextRule = splitLegacyHeaders(rule(source, "ruleContentUrlNext"))
            val next = if (nextRule.rule.isBlank()) "" else runtime.evaluate(nextRule.rule, text, url).firstOrNull().orEmpty()
            if (!hasLegacyNext(next) || URI(url).resolve(next).toString() in visited) {
                require(result.isNotEmpty()) { "旧式源没有返回图片：可能规则失效或需要站点专用适配" }
                return result.toList()
            }
            val target = address(next, text, url, runtime)
            requestHeaders = headers(nextRule.objects, text, target.first, runtime) + target.second
            url = target.first
        }
        error("图片分页超过上限，未标记下载完成")
    }
}
