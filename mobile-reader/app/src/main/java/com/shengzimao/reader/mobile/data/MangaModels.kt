package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class MangaChapter(val title: String, val url: String, val pages: List<String> = emptyList(), val ruleState: String = "",
    val imageHeaders: Map<String, Map<String, String>> = emptyMap(),
    val requestHeaders: Map<String, String> = emptyMap(), val imageRuleVersion: String = "", val cacheIdentity: String = "") {
    fun json() = JSONObject().put("title", title).put("url", url).put("pages", JSONArray(pages)).put("ruleState", ruleState)
        .put("imageHeaders", JSONObject().also { contexts -> imageHeaders.forEach { (image, headers) -> contexts.put(image, JSONObject(headers)) } })
        .put("requestHeaders", JSONObject(requestHeaders)).put("imageRuleVersion", imageRuleVersion).put("cacheIdentity", cacheIdentity)
    companion object {
        fun parse(j: JSONObject): MangaChapter {
            val pages = j.optJSONArray("pages").strings()
            val contexts = j.optJSONObject("imageHeaders")
            val headers = pages.take(5000).mapNotNull { image ->
                val raw = contexts?.optJSONObject(image) ?: return@mapNotNull null
                val values = raw.keys().asSequence().take(16).associateWith { raw.getString(it) }
                values.forEach { (key, value) -> MangaRequestHeaders.validate(key, value) }
                image to values
            }.toMap()
            val requestHeaders = j.optJSONObject("requestHeaders")?.let { raw ->
                raw.keys().asSequence().take(16).associateWith { raw.getString(it) }.also { values ->
                    values.forEach { (key, value) -> MangaRequestHeaders.validate(key, value) }
                }
            }.orEmpty()
            return MangaChapter(j.getString("title"), j.getString("url"), pages, j.optString("ruleState"), headers, requestHeaders, j.optString("imageRuleVersion"), j.optString("cacheIdentity"))
        }
    }
}

data class MangaBook(
    val id: String,
    val title: String,
    val sourceId: String = "",
    val sourceName: String = "本地漫画",
    val url: String = "",
    val cover: String = "",
    val author: String = "",
    val intro: String = "",
    val chapters: List<MangaChapter> = emptyList(),
    val favorite: Boolean = false,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val lastReadAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val cachedChapters: Set<String> = emptySet(),
    val onlineOnly: Boolean = false,
    val pageFraction: Float = 0f,
    val pageReference: String = "",
    val bookmarks: List<MangaBookmark> = emptyList(),
    val readerPreferences: String = "",
    val ruleState: String = "",
    val updatedAt: Long = 0,
    val newChapters: Int = 0,
    val tracking: Boolean = false,
    val unreadChapterUrls: Set<String> = emptySet(),
    val updateError: String = "",
    val lastUpdateCheck: Long = 0,
    val pageOverrides: String = "",
) {
    val local get() = sourceId.isEmpty()
    fun json() = JSONObject().put("id", id).put("title", title).put("sourceId", sourceId).put("sourceName", sourceName)
        .put("url", url).put("cover", cover).put("author", author).put("intro", intro)
        .put("chapters", JSONArray().also { a -> chapters.forEach { a.put(it.json()) } }).put("favorite", favorite).put("onlineOnly", onlineOnly)
        .put("chapterIndex", chapterIndex).put("pageIndex", pageIndex).put("lastReadAt", lastReadAt)
        .put("createdAt", createdAt).put("cachedChapters", JSONArray(cachedChapters.toList()))
        .put("pageFraction", pageFraction.toDouble()).put("pageReference", pageReference)
        .put("bookmarks", JSONArray().also { a -> bookmarks.forEach { a.put(it.json()) } })
        .put("readerPreferences", readerPreferences)
        .put("ruleState", ruleState)
        .put("updatedAt", updatedAt).put("newChapters", newChapters).put("tracking", tracking)
        .put("unreadChapterUrls", JSONArray(unreadChapterUrls.toList())).put("updateError", updateError)
        .put("lastUpdateCheck", lastUpdateCheck).put("pageOverrides", pageOverrides)
    companion object {
        fun parse(j: JSONObject): MangaBook {
            val a = j.optJSONArray("chapters") ?: JSONArray()
            return MangaBook(
                id = j.getString("id"), title = j.getString("title"), sourceId = j.optString("sourceId"), sourceName = j.optString("sourceName", "本地漫画"),
                url = j.optString("url"), cover = j.optString("cover"), author = j.optString("author"), intro = j.optString("intro"),
                chapters = (0 until minOf(a.length(), 30000)).map { MangaChapter.parse(a.getJSONObject(it)) },
                favorite = j.optBoolean("favorite"), chapterIndex = j.optInt("chapterIndex"), pageIndex = j.optInt("pageIndex"),
                lastReadAt = j.optLong("lastReadAt"), createdAt = j.optLong("createdAt"), cachedChapters = j.optJSONArray("cachedChapters").strings().toSet(),
                onlineOnly = j.optBoolean("onlineOnly", false),
                pageFraction = j.optDouble("pageFraction", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                pageReference = j.optString("pageReference"), readerPreferences = j.optString("readerPreferences"),
                ruleState = j.optString("ruleState"),
                updatedAt = j.optLong("updatedAt"), newChapters = j.optInt("newChapters").coerceAtLeast(0),
                tracking = j.optBoolean("tracking"),
            unreadChapterUrls = j.optJSONArray("unreadChapterUrls").strings().toSet(),
            updateError = j.optString("updateError"), lastUpdateCheck = j.optLong("lastUpdateCheck"),
            pageOverrides = j.optString("pageOverrides"),
                bookmarks = j.optJSONArray("bookmarks")?.let { a -> (0 until minOf(a.length(), 2000)).mapNotNull { i ->
                    runCatching { MangaBookmark.parse(a.getJSONObject(i)) }.getOrNull()
                } }.orEmpty(),
            )
        }
    }
}

data class MangaBookmark(val chapterUrl: String, val page: Int, val reference: String = "", val fraction: Float = 0f,
    val label: String = "", val createdAt: Long = System.currentTimeMillis()) {
    fun json() = JSONObject().put("chapterUrl", chapterUrl).put("page", page).put("reference", reference)
        .put("fraction", fraction.toDouble()).put("label", label).put("createdAt", createdAt)
    companion object {
        fun parse(j: JSONObject) = MangaBookmark(j.getString("chapterUrl"), j.optInt("page").coerceAtLeast(0),
            j.optString("reference"), j.optDouble("fraction", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            j.optString("label"), j.optLong("createdAt"))
    }
}

data class MangaSource(val id: String, val name: String, val baseUrl: String, val definition: String, val enabled: Boolean = true) {
    val ruleVersion: String by lazy { mangaHash(definition) }
    val compatibilityNote: String get() {
        val note = rules.optString("compatibilityNote")
        if (note == "此源使用私有加密规则，原配置已保存，需适配后启用") {
            legacy?.let { original ->
                val crypto = LegacyRuleCrypto.inspect(original)
                if (crypto.failure != null) return "私有加密规则未就绪：${crypto.failure}；原配置已保存"
                if (crypto.fields > 0) return "已解封 ${crypto.versions.joinToString("/")} 加密规则" +
                    (if (crypto.bindingUrl != null) " · 已核对原加密地址" else "") +
                    if (enabled) " · 请测试搜索、目录和图片" else " · 当前源仍停用，可在源管理启用或重新导入原始配置"
            }
        }
        // 老版本曾一律停用图片解密源。更新提示但不擅自改变用户保存的启停状态。
        if (note == "此源需要专用图片解密器，原配置已保存，需适配后启用" ||
            note == "此源的图片解密器尚未适配，原配置已保存，需适配后启用") {
            MangaDecoder.identify(MangaDecoder.rule(legacy))?.let {
                return "已适配${it.label}" + if (enabled) " · 请测试搜索、目录和图片" else " · 当前源仍停用，可在源管理启用或重新导入原始配置"
            }
        }
        return note
    }
    val legacy: JSONObject? get() = rules.optJSONObject("legacy")
    val rules get() = JSONObject(definition)
    val headers: Map<String, String> get() {
        val h = rules.optJSONObject("headers") ?: return emptyMap()
        return h.keys().asSequence().associateWith { h.getString(it) }
    }
    fun json() = rules.put("id", id).put("enabled", enabled)
    companion object {
        fun parse(j: JSONObject): MangaSource {
            require(j.optInt("schemaVersion", 1) == 1) { "不支持的漫画源格式版本" }
            val name = j.optString("name").trim()
            require(name.isNotBlank() && name.length <= 80) { "漫画源必须提供 1–80 字的 name" }
            val base = mangaHttpUrl(j.optString("baseUrl"))
            val id = j.optString("id").ifBlank { mangaHash(base).take(20) }
            require(Regex("[A-Za-z0-9._-]{1,80}").matches(id)) { "漫画源 id 仅允许字母、数字、点、下划线和连字符" }
            val search = j.optJSONObject("search") ?: error("缺少 search 搜索规则")
            val detail = j.optJSONObject("detail") ?: error("缺少 detail 详情规则")
            val reader = j.optJSONObject("reader") ?: error("缺少 reader 图片规则")
            for ((rule, keys) in listOf(search to listOf("url", "items", "title", "link"), detail to listOf("chapters"), reader to listOf("pages"))) {
                keys.forEach { require(rule.optString(it).isNotBlank()) { "漫画源缺少规则 $it" } }
                require(rule.optString("type", "html") in listOf("html", "json")) { "仅支持 html / json 漫画源，不执行脚本" }
            }
            val headers = j.optJSONObject("headers")
            headers?.keys()?.forEach { key ->
                MangaRequestHeaders.validate(key, headers.getString(key))
            }
            return MangaSource(id, name, base, j.toString(), j.optBoolean("enabled", true))
        }

        /**
         * 解析导入文本。
         *
         * 现场问题：把源地址填成会 301 跳到别的站点（如 eo.com → x.com）的链接时，
         * 跟随跳转后拿到的是 HTML，旧实现把它直接交给 org.json，在真机上抛出
         * “Value ... of type java.lang.String cannot be converted to JSONObject”
         * 这类英文异常，用户无法判断是链接错了、站点改版还是格式不对。
         * 这里统一翻译成可操作的中文提示。
         */
        fun importText(text: String): List<MangaSource> {
            require(text.toByteArray().size <= 8 * 1024 * 1024) { "源文件超过 8 MB" }
            // 阅读格式的分享链接常返回 base64(zlib(json))，先解码再判断格式。
            val decoded = ResponseDecoder.decode(text)
            val trimmed = decoded.trim().removePrefix("\uFEFF").trim()
            MangaImportFormat.textError(trimmed)?.let { throw IllegalArgumentException(it) }
            require(trimmed.isNotEmpty()) { "没有读到任何内容。若来源是链接，请确认它直接返回 JSON，而不是跳转到其他站点或返回网页。" }
            val looksHtml = trimmed.startsWith("<")
            require(!looksHtml) {
                "返回的是网页（HTML）而不是漫画源 JSON。常见原因是链接发生了跳转（例如跳到别的域名）、需要登录，或该地址只是一个展示页。请改用直接返回 JSON 的地址，或把 JSON 内容复制后用「粘贴」导入。"
            }
            require(trimmed.startsWith("{") || trimmed.startsWith("[")) {
                "内容不是有效的 JSON 对象或数组。请导入本应用格式或支持转换的旧式漫画源；不支持 Mihon / Tachiyomi APK 扩展。"
            }
            val raw = runCatching {
                if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).let { obj -> obj.optJSONArray("sources") ?: JSONArray().put(obj) }
            }.getOrElse { failure ->
                throw IllegalArgumentException("JSON 格式有误：${failure.message?.take(160) ?: "无法解析"}")
            }
            require(raw.length() in 1..500) { "一次导入 1–500 个漫画源，当前为 ${raw.length()} 个" }
            MangaImportFormat.jsonError(raw)?.let { throw IllegalArgumentException(it) }

            // 旧式格式保留原配置，支持的加密规则在执行前解封，失败则明确停用。
            val legadoCount = (0 until raw.length()).count { raw.optJSONObject(it)?.let(LegadoConverter::looksLikeLegado) == true }
            if (legadoCount > 0) return importLegado(raw, legadoCount)

            val result = (0 until raw.length()).map { index ->
                val element = raw.opt(index)
                require(element is JSONObject) {
                    "第 ${index + 1} 项不是 JSON 对象（实际是 ${element?.javaClass?.simpleName ?: "null"}）。若链接返回的是字符串数组或纯文本地址列表，请改用数组形式且每项为完整源对象。"
                }
                runCatching { parse(element) }.getOrElse { failure ->
                    throw IllegalArgumentException("第 ${index + 1} 个漫画源无法解析：${failure.message ?: "格式不符合要求"}")
                }
            }
            require(result.map { it.id }.distinct().size == result.size) { "同一文件中漫画源 id 重复" }
            lastSkipped = emptyList()
            return result
        }

        /** Legado 批量转换的结果说明（被跳过的源及原因），供界面提示。 */
        var lastSkipped: List<String> = emptyList()
            private set

        private fun importLegado(raw: JSONArray, legadoCount: Int): List<MangaSource> {
            val (converted, skipped) = LegadoConverter.convertAll(raw)
            lastSkipped = skipped
            require(converted.isNotEmpty()) {
                "这批源无法转换（其中旧式源 $legadoCount 个），请检查地址和必填规则。" +
                    skipped.take(3).joinToString("；").take(500)
            }
            require(converted.map { it.id }.distinct().size == converted.size) { "转换后漫画源 id 重复" }
            return converted
        }

        val template = """
            {
              "schemaVersion": 1,
              "id": "my-manga-source",
              "name": "我的漫画源",
              "baseUrl": "https://example.invalid",
              "headers": {"Referer": "https://example.invalid/"},
              "search": {"type": "html", "url": "/search?q={query}&page={page}", "items": ".manga-item", "title": ".title", "link": "a@href", "cover": "img@data-src", "author": ".author"},
              "detail": {"type": "html", "title": "h1", "cover": ".cover img@src", "intro": ".intro", "chapters": ".chapters a", "chapterTitle": "", "chapterUrl": "@href", "reverse": false},
              "reader": {"type": "html", "pages": ".reader img", "image": "@data-src"}
            }
        """.trimIndent()
    }
}

internal fun mangaHttpUrl(raw: String): String {
    val uri = try { URI(raw.trim()) } catch (failure: java.net.URISyntaxException) {
        throw IllegalArgumentException("漫画地址格式无效，请检查空格、括号及 URL 参数编码", failure)
    }
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "只接受不含账号密码的 HTTP / HTTPS 地址" }
    return uri.toASCIIString()
}

internal fun mangaHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
