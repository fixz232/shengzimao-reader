package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URI

data class NovelSourceEntry(val name: String, val url: String, val searchable: Boolean, val format: String = "SoNovel")

data class NovelSourceImport(val added: Int, val updated: Int, val skipped: Int, val total: Int, val reasons: List<String> = emptyList()) {
    fun message(group: Int): String = "分组 $group：新增 $added 条，更新 $updated 条，跳过 $skipped 条；共 $total 条书源。" +
        if (reasons.isEmpty()) "" else " 跳过原因：" + reasons.take(3).joinToString("；")
}

/** Built-in rules never share a writable file with a user's imported rules. */
class NovelSourceGroups(private val directory: File, private val builtin: () -> String) {
    private val selectionFile get() = File(directory, "selection.json")

    fun selected(): Int = if (selectionFile.isFile) {
        JSONObject(selectionFile.readText(Charsets.UTF_8)).optInt("group", 1).takeIf { it in 1..5 } ?: 1
    } else 1

    fun select(group: Int) {
        requireGroup(group)
        atomicJson(selectionFile, JSONObject().put("group", group).toString())
    }

    /** Search scope is independent of the imported rules. null means all; empty means none. */
    fun selectedSources(group: Int): Set<String>? {
        requireGroup(group)
        val file = File(directory, "scope-$group.json")
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optBoolean("all", true)) return null
        val urls = json.optJSONArray("urls") ?: JSONArray()
        val selectedKeys = (0 until urls.length()).mapNotNull { index -> runCatching { key(urls.getString(index)) }.getOrNull() }.toSet()
        return entries(group).filter { key(it.url) in selectedKeys }.map { it.url }.toSet()
    }

    fun selectSources(group: Int, urls: Set<String>?) {
        requireGroup(group)
        val known = entries(group).map { it.url }.toSet()
        require(urls == null || urls.all { it in known }) { "搜索范围包含此分组中不存在的书源。" }
        atomicJson(File(directory, "scope-$group.json"), JSONObject()
            .put("all", urls == null).put("urls", JSONArray(urls?.toList().orEmpty())).toString())
    }

    fun searchEntries(group: Int, urls: Set<String>? = selectedSources(group)): List<NovelSourceEntry> =
        entries(group).filter { it.searchable && (urls == null || it.url in urls) }

    fun rulesFile(group: Int): File {
        require(group in 2..5) { "分组 1 使用内置书源，不接受用户导入。" }
        return File(directory, "group-$group.json")
    }

    /** The dex engine must never be handed a mixed Legado/SoNovel file. */
    fun sonovelRulesFile(group: Int): File {
        val rules = rules(group)
        val filtered = JSONArray((0 until rules.length()).map { rules.getJSONObject(it) }.filterNot { it.has("legado") })
        require(filtered.length() > 0) { "此组没有 SoNovel 规则" }
        return File(directory, "group-$group-sonovel.json").also { atomicJson(it, filtered.toString()) }
    }

    fun entries(group: Int): List<NovelSourceEntry> = rules(group).let { array ->
        (0 until array.length()).map { index ->
            val rule = array.getJSONObject(index)
            val search = rule.optJSONObject("search")
            NovelSourceEntry(rule.optString("name"), rule.getString("url"),
                if (rule.has("legado")) NovelLegadoRules.searchable(rule) else
                    !rule.optBoolean("disabled") && search != null && !search.optBoolean("disabled") && search.optString("url").isNotBlank(),
                if (rule.has("legado")) "阅读/Legado" else "SoNovel")
        }
    }

    fun rules(group: Int): JSONArray {
        requireGroup(group)
        if (group == 1) return JSONArray(builtin())
        val file = rulesFile(group)
        if (!file.exists()) return JSONArray()
        require(file.length() <= MAX_BYTES) { "分组 $group 的规则文件过大，请先导出检查。" }
        return JSONArray(file.readText(Charsets.UTF_8))
    }

    /** Validate before writing; an invalid import cannot erase an existing group. */
    fun import(group: Int, text: String): NovelSourceImport {
        val target = rulesFile(group)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "书源文件不能超过 32 MB。" }
        val input = text.trim().removePrefix("\uFEFF").trim()
        require(!input.startsWith('<')) { "链接返回的是网页，请使用页面里的书源 JSON 下载链接或 JSON 文件。" }
        val tokener = JSONTokener(input)
        val root = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "JSON 末尾有额外内容。" }
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("rules") ?: JSONArray().put(root)
            else -> throw IllegalArgumentException("请导入 SoNovel 或阅读/Legado 书源 JSON 对象或数组。")
        }
        require(array.length() in 1..MAX_SOURCES) { "每次请导入 1–$MAX_SOURCES 条书源。" }
        val imported = LinkedHashMap<String, JSONObject>()
        var skipped = 0
        var firstError = ""
        val reasons = ArrayList<String>()
        for (index in 0 until array.length()) {
            try {
                val rule = validate(array.getJSONObject(index))
                val key = key(rule.getString("url"))
                if (imported.put(key, rule) != null) skipped++
            } catch (error: Exception) {
                skipped++
                if (firstError.isBlank()) firstError = "第 ${index + 1} 条：${error.message}"
                if (reasons.size < 10) reasons.add("第 ${index + 1} 条：${error.message}")
            }
        }
        require(imported.isNotEmpty()) { "未找到可导入的小说书源。$firstError" }
        val existing = rules(group)
        val merged = LinkedHashMap<String, JSONObject>()
        for (index in 0 until existing.length()) {
            val rule = existing.getJSONObject(index)
            merged[key(rule.getString("url"))] = rule
        }
        val updated = imported.keys.count { it in merged }
        merged.putAll(imported)
        require(merged.size <= MAX_SOURCES) { "每组最多保存 $MAX_SOURCES 条书源。" }
        val output = JSONArray(merged.values.toList()).toString()
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "该组全部规则合计不能超过 32 MB。" }
        atomicJson(target, output)
        return NovelSourceImport(imported.size - updated, updated, skipped, merged.size, reasons)
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_SOURCES = 5000

        fun requireGroup(group: Int) = require(group in 1..5) { "请选择分组 1–5。" }

        /** Preserve existing group-1 cache keys and isolate matching URLs in other groups. */
        fun identity(url: String, group: Int, sourceKey: String = ""): String {
            requireGroup(group)
            val key = if (group == 1) url else "novel-group-$group|$url"
            return if (sourceKey.isBlank()) key else "$key|source=${mangaHash(sourceKey)}"
        }

        fun readImport(input: java.io.InputStream): String {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "书源文件不能超过 32 MB。" }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }

        private fun key(url: String): String {
            val uri = URI(url)
            return "${uri.scheme.lowercase()}://${uri.rawAuthority.lowercase()}${uri.rawPath.orEmpty().trimEnd('/')}" +
                uri.rawQuery?.let { "?$it" }.orEmpty() + uri.rawFragment?.let { "#$it" }.orEmpty()
        }

        private fun validate(raw: JSONObject): JSONObject {
            if (raw.has("legado")) return NovelLegadoRules.normalize(raw.getJSONObject("legado"))
            if (NovelLegadoRules.recognizes(raw)) return NovelLegadoRules.normalize(raw)
            val rule = JSONObject(raw.toString())
            val uri = URI(rule.getString("url").trim())
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "站点地址必须是有效的 HTTP(S) 地址" }
            rule.put("url", uri.toASCIIString())
            require(rule.optString("name").isNotBlank()) { "缺少书源名称 name" }
            require(rule.optJSONObject("toc")?.optString("item")?.isNotBlank() == true) { "缺少目录规则 toc.item" }
            require(rule.optJSONObject("chapter")?.optString("content")?.isNotBlank() == true) { "缺少正文规则 chapter.content" }
            val chapter = rule.getJSONObject("chapter")
            // SoNovel passes these fields directly into String.replaceAll/split.
            // Missing optional fields must not turn a valid import into a read-time NPE.
            if (chapter.isNull("filterTxt")) chapter.put("filterTxt", "")
            if (chapter.isNull("filterTag")) chapter.put("filterTag", "")
            if (!chapter.optBoolean("paragraphTagClosed") && chapter.optString("paragraphTag").isBlank())
                chapter.put("paragraphTag", "(?i)<br\\s*/?>|\\r?\\n")
            Regex(chapter.getString("filterTxt"))
            if (!chapter.optBoolean("paragraphTagClosed")) Regex(chapter.getString("paragraphTag"))
            if (!rule.has("book")) rule.put("book", JSONObject())
            require(rule.optJSONObject("book") != null) { "book 必须是对象" }
            if (rule.has("search")) {
                val search = rule.getJSONObject("search")
                if (!search.optBoolean("disabled")) {
                    require(search.optString("url").isNotBlank() && search.optString("result").isNotBlank() && search.optString("bookName").isNotBlank()) { "搜索规则缺少 url、result 或 bookName" }
                    require(search.optString("method").uppercase() in setOf("GET", "POST")) { "search.method 必须是 GET 或 POST" }
                }
            }
            return rule
        }
    }
}
