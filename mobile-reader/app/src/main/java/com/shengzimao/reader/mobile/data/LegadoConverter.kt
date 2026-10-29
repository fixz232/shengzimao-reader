package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** 只转换可声明的旧式漫画源；关键 URL、解密及分页脚本不能删掉后冒充可用源。 */
object LegadoConverter {
    data class Result(val source: MangaSource?, val name: String, val skipReason: String = "")
    private val script = Regex("@js\\s*:|<js>|<javascript>", RegexOption.IGNORE_CASE)

    fun looksLikeLegado(json: JSONObject): Boolean = json.has("bookSourceUrl") || json.has("ruleSearchUrl") ||
        json.has("bookSourceName") && json.has("ruleSearchList")

    fun convertAll(array: JSONArray): Pair<List<MangaSource>, List<String>> {
        val sources = ArrayList<MangaSource>()
        val skipped = ArrayList<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item == null) { skipped.add("第 ${i + 1} 项：不是源对象"); continue }
            val result = if (looksLikeLegado(item)) convert(item) else runCatching {
                Result(MangaSource.parse(item), item.optString("name", "第 ${i + 1} 项"))
            }.getOrElse { Result(null, item.optString("name", "第 ${i + 1} 项"), it.message.orEmpty()) }
            if (result.source == null) skipped.add("${result.name}：${result.skipReason}")
            else if (sources.any { it.id == result.source.id }) skipped.add("${result.name}：源 id 重复")
            else sources.add(result.source)
        }
        return sources to skipped
    }

    fun convert(json: JSONObject): Result {
        val name = json.optString("bookSourceName").trim().ifBlank { "未命名漫画源" }.take(80)
        return try { Result(convertRules(json, name), name) }
        catch (failure: IllegalArgumentException) {
            runCatching { Result(legacySource(json, name, failure.message.orEmpty()), name) }
                .getOrElse { Result(null, name, it.message.orEmpty()) }
        }
        catch (failure: org.json.JSONException) { Result(null, name, "规则格式不完整：${failure.message}") }
    }

    private fun legacySource(original: JSONObject, name: String, previousReason: String): MangaSource {
        val base = mangaHttpUrl(original.optString("bookSourceUrl"))
        val crypto = LegacyRuleCrypto.inspect(original)
        val unavailable = when {
            crypto.failure != null -> "私有加密规则未就绪：${crypto.failure}；原配置已保存，需修复后启用"
            MangaDecoder.rule(original).isNotBlank() && MangaDecoder.identify(MangaDecoder.rule(original)) == null -> "此源的图片解密器尚未适配，原配置已保存，需适配后启用"
            listOf("ruleSearchUrl", "ruleSearchList", "ruleChapterList", "ruleBookContent").any { rule(original, it).isBlank() } -> "源的搜索、目录或图片规则不完整，原配置已保存，请编辑补全"
            else -> "旧式规则兼容模式" + (if (crypto.fields > 0) " · 已解封 ${crypto.versions.joinToString("/")} 加密规则" else "") +
                (if (crypto.bindingUrl != null) " · 已核对原加密地址（网络仍使用当前地址）" else "") +
                (MangaDecoder.identify(MangaDecoder.rule(original))?.let { " · 已适配${it.label}" } ?: "") + " · 请先测试搜索、目录和图片"
        }
        val enabled = unavailable.startsWith("旧式") && original.optBoolean("enable", true)
        val j = JSONObject().put("id", "legado-" + mangaHash(base + "|" + name).take(20)).put("schemaVersion", 1)
            .put("name", name).put("baseUrl", base).put("enabled", enabled).put("legacy", original)
            .put("compatibilityNote", unavailable).put("conversionNote", previousReason)
            .put("search", JSONObject().put("url", "/").put("items", "legacy").put("title", "legacy").put("link", "legacy"))
            .put("detail", JSONObject().put("chapters", "legacy"))
            .put("reader", JSONObject().put("pages", "legacy"))
        crypto.bindingUrl?.let { j.put("legacyEncryptionBaseUrl", it) }
        original.optString("httpUserAgent").trim().takeIf { it.isNotEmpty() }?.let {
            j.put("headers", JSONObject().put("User-Agent", it))
        }
        return MangaSource.parse(j)
    }

    private fun convertRules(json: JSONObject, name: String): MangaSource {
        val base = mangaHttpUrl(json.optString("bookSourceUrl"))
        val fields = listOf("ruleSearchUrl", "ruleSearchList", "ruleSearchName", "ruleSearchNoteUrl", "ruleChapterUrl",
            "ruleChapterList", "ruleContentUrl", "ruleBookContent", "ruleContentUrlNext", "ruleChapterUrlNext")
        fields.forEach { require(!LegacyRuleCrypto.hasEnvelope(rule(json, it))) { "$it 使用加密规则，需保留原始配置" } }
        require(MangaDecoder.rule(json).isBlank()) { "图片解码器需保留旧式源规则" }
        require(rule(json, "ruleChapterUrlNext").isBlank()) { "暂不支持多页目录规则，请使用本应用格式" }
        val required = listOf("ruleSearchUrl", "ruleSearchList", "ruleSearchName", "ruleSearchNoteUrl", "ruleChapterList", "ruleContentUrl", "ruleBookContent")
        required.forEach { require(rule(json, it).isNotBlank()) { "缺少 $it，无法确定完整的搜索/下载链接" } }
        // 文本尾部脚本可省去；链接、列表、图片和分页的脚本必须保留语义，无法安全转换则跳过。
        fields.filter { it != "ruleSearchName" }.forEach {
            require(!script.containsMatchIn(rule(json, it))) { "$it 依赖脚本，本应用不执行漫画源脚本" }
            require(!rule(json, it).contains("{{")) { "$it 包含动态表达式，无法安全转换" }
        }
        var url = rule(json, "ruleSearchUrl").replace("searchKey", "{query}").replace("searchPage", "{page}")
        require(legacySearchPost(url).second == null) { "搜索规则使用表单 POST，需保留旧式请求语义" }
        require(!url.contains("{{") && !url.contains("$.") && !url.contains(",{")) { "搜索 URL 使用表达式或非 GET 请求" }
        require(listOf('+', '*', '/', '-').none { url.contains("{page}$it") }) { "搜索分页需要计算表达式" }
        // 不给没有分页的站点擅自追加 page 参数。
        mangaHttpUrl(java.net.URI(base).resolve(url.replace("{query}", "q").replace("{page}", "1")).toString())
        val headers = JSONObject()
        json.optString("httpUserAgent").trim().takeIf { it.isNotEmpty() }?.let { headers.put("User-Agent", it) }
        val contentParts = splitLegacyHeaders(rule(json, "ruleBookContent"))
        var content = contentParts.rule
        for (rawHeaders in contentParts.objects) {
            val h = runCatching { JSONObject(rawHeaders) }.getOrElse { throw IllegalArgumentException("图片请求头格式无法转换") }
            h.keys().forEach { key ->
                var v = h.getString(key)
                if (v == "host") v = base
                require(!v.contains("{{") && !v.contains("thisPage") && !v.contains("java.")) { "图片请求头依赖动态脚本" }
                headers.put(key, v)
            }
        }
        val replacements = JSONArray()
        // 旧式单个字面替换，例如 #https#http；不执行正则或代码替换。
        if (content.contains('#')) {
            val pieces = content.split('#')
            require(pieces.size == 3 && pieces[1] in listOf("https", "http", "https://", "http://") &&
                pieces[2] in listOf("https", "http", "https://", "http://")) { "图片规则含暂不支持的替换表达式" }
            replacements.put(JSONObject().put("from", pieces[1]).put("to", pieces[2]))
            content = pieces[0]
        }
        val searchType = typeOf(rule(json, "ruleSearchList"))
        val chapterRaw = rule(json, "ruleChapterList")
        val detailType = typeOf(chapterRaw.removePrefix("-"))
        val readerType = typeOf(content)
        val rules = JSONObject().put("schemaVersion", 1).put("id", "legado-" + mangaHash(base + "|" + name).take(20))
            .put("name", name).put("baseUrl", base).put("headers", headers)
        val search = JSONObject().put("type", searchType).put("url", url)
            .put("items", normalize(rule(json, "ruleSearchList"), searchType))
            .put("title", normalize(textRule(rule(json, "ruleSearchName")), searchType))
            .put("link", normalize(rule(json, "ruleSearchNoteUrl"), searchType))
        optional(json, search, "ruleSearchAuthor", "author", searchType)
        optional(json, search, "ruleSearchCoverUrl", "cover", searchType)
        rules.put("search", search)
        val detail = JSONObject().put("type", detailType)
            .put("chapters", normalize(chapterRaw.removePrefix("-"), detailType))
            .put("chapterTitle", normalize(textRule(rule(json, "ruleChapterName")), detailType))
            .put("chapterUrl", normalize(rule(json, "ruleContentUrl"), detailType))
            .put("reverse", chapterRaw.startsWith("-"))
        rule(json, "ruleChapterUrl").takeIf { it.isNotBlank() }?.let { detail.put("tocUrl", normalize(it, "html")) }
        optional(json, detail, "ruleIntroduce", "intro", detailType)
        optional(json, detail, "ruleCoverUrl", "cover", detailType)
        rules.put("detail", detail)
        val reader = JSONObject().put("type", readerType).put("pages", normalize(content, readerType))
            .put("image", if (readerType == "json") "" else "@src")
        // pages 中的 @src / @data-src 在引擎中按每条备用规则读取，不丢掉属性。
        if (replacements.length() > 0) reader.put("imageReplace", replacements)
        rule(json, "ruleContentUrlNext").takeIf { it.isNotBlank() }?.let {
            reader.put("nextPage", normalize(it, readerType)).put("maxPages", 50)
        }
        rules.put("reader", reader)
        return MangaSource.parse(rules)
    }

    private fun optional(from: JSONObject, to: JSONObject, field: String, key: String, type: String) {
        val raw = rule(from, field)
        if (raw.isNotBlank() && !script.containsMatchIn(raw) && !LegacyRuleCrypto.hasEnvelope(raw))
            to.put(key, normalize(raw, type))
    }

    private fun rule(json: JSONObject, key: String): String {
        return LegacyRuleCrypto.rawRule(json, key)
    }

    private fun textRule(raw: String): String = script.find(raw)?.let { raw.substring(0, it.range.first).trim() } ?: raw
    private fun typeOf(raw: String): String = if (raw.trim().startsWith("$") || raw.trim().startsWith("@json:", true)) "json" else "html"

    fun normalize(raw: String, type: String = "html"): String {
        val alternatives = SelectorSplitter.splitRules(raw.trim(), "||").flatMap { SelectorSplitter.splitRules(it, "|") }
        return alternatives.joinToString("||") { alternative ->
            SelectorSplitter.splitRules(alternative, "&").joinToString("&&") { single -> normalizeSingle(single, type) }
        }
    }

    private fun normalizeSingle(raw: String, type: String): String {
        var r = raw.trim().removePrefix("@css:").removePrefix("@XPath:").removePrefix("@xpath:")
        if (r.startsWith("@json:", true)) r = r.substring(6).trim()
        if (type == "json" || r.startsWith("$")) {
            return r
        }
        require(!r.contains('!') && !r.contains("##")) { "选择器包含暂不支持的排除或正则替换：${r.take(80)}" }
        if (SelectorSplitter.isXPath(r)) {
            r = r.replace(Regex("/@([\\w:-]+)$"), "@$1").replace(Regex("/text\\(\\)\\s*$"), "")
            return SelectorSplitter.normalizeXPath(r)
        }
        val tokens = r.split('@').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return ""
        val terminal = tokens.last()
        val attr = when {
            terminal in listOf("text", "textNodes", "html", "all") -> { tokens.removeAt(tokens.lastIndex); "" }
            terminal in listOf("href", "src", "value", "content", "title", "alt", "class", "id", "style") || terminal.startsWith("data-") -> {
                tokens.removeAt(tokens.lastIndex); "@$terminal"
            }
            else -> ""
        }
        val css = tokens.joinToString(" ") { token ->
            Regex("\\b(id|class|tag)\\.([\\w-]+)(?:\\.(\\d+))?").replace(token) { m ->
                val prefix = when (m.groupValues[1]) { "id" -> "#"; "class" -> "."; else -> "" }
                prefix + m.groupValues[2] + m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ":eq($it)" }.orEmpty()
            }
        }
        return css + attr
    }
}
