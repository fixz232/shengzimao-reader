package com.shengzimao.reader.mobile.data

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Retain the original rules: Legado expressions are evaluated, never translated to SoNovel CSS. */
object NovelLegadoRules {
    fun recognizes(json: JSONObject): Boolean = json.has("bookSourceUrl") || json.has("ruleSearchUrl")

    fun normalize(input: JSONObject): JSONObject {
        val raw = JSONObject(input.toString())
        val name = raw.optString("bookSourceName").trim()
        require(name.isNotBlank()) { "缺少阅读书源名称 bookSourceName" }
        require(raw.optInt("bookSourceType", 0) == 0) { "此入口只支持文字小说书源，请将其他类型源导入对应功能" }
        // Legado uses ##labels (including spaces/emoji) to distinguish rules on one host.
        // Keep that identity without sending the label as part of a network address.
        val originalAddress = raw.getString("bookSourceUrl").trim()
        val uri = URI(originalAddress.substringBefore("##"))
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "阅读书源地址必须是有效的 HTTP(S) 地址"
        }
        val modern = raw.has("ruleToc") || raw.has("ruleContent")
        fun required(section: String, field: String, old: String) {
            require((if (modern) raw.optJSONObject(section)?.optString(field) else raw.optString(old)).orEmpty().isNotBlank()) {
                "阅读书源缺少 ${if (modern) "$section.$field" else old}"
            }
        }
        required("ruleToc", "chapterList", "ruleChapterList")
        required("ruleToc", "chapterName", "ruleChapterName")
        required("ruleContent", "content", "ruleBookContent")
        val identity = uri.toASCIIString() + if (originalAddress.contains("##"))
            "#" + URLEncoder.encode(originalAddress.substringAfter("##"), "UTF-8").replace("+", "%20") else ""
        return JSONObject().put("name", name.take(250)).put("url", identity)
            .put("disabled", !raw.optBoolean("enabled", true)).put("legado", raw)
    }

    fun searchable(rule: JSONObject): Boolean {
        val raw = rule.optJSONObject("legado") ?: return false
        val search = raw.optJSONObject("ruleSearch")
        return !rule.optBoolean("disabled") && (if (search != null)
            raw.optString("searchUrl").isNotBlank() && search.optString("bookList").isNotBlank() &&
                search.optString("name").isNotBlank() && search.optString("bookUrl").isNotBlank()
        else raw.optString("ruleSearchUrl").isNotBlank() && raw.optString("ruleSearchList").isNotBlank() &&
            raw.optString("ruleSearchName").isNotBlank() && raw.optString("ruleSearchNoteUrl").isNotBlank())
    }
}
