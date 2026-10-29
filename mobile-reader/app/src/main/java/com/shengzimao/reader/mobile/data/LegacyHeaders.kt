package com.shengzimao.reader.mobile.data

import org.json.JSONObject

/** @Header 可放在选择器/脚本之前或之后；只消费完整对象，不能吞掉其后的脚本。 */
internal data class LegacyHeaders(val rule: String, val objects: List<String>)

internal fun splitLegacyHeaders(raw: String): LegacyHeaders {
    val rule = StringBuilder()
    val headers = mutableListOf<String>()
    var position = 0
    var quote = '\u0000'
    var escaped = false
    val scriptStart = Regex("@js\\s*:|<js>|<javascript>", RegexOption.IGNORE_CASE).find(raw)?.range?.first ?: -1
    while (position < raw.length) {
        val c = raw[position]
        if (quote != '\u0000') {
            rule.append(c); position++
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = '\u0000'
            continue
        }
        if (raw.startsWith("{{", position)) {
            val end = raw.indexOf("}}", position + 2)
            require(end >= 0) { "请求头动态表达式不完整" }
            rule.append(raw.substring(position, end + 2)); position = end + 2
            continue
        }
        if (scriptStart >= 0 && position > scriptStart && c == '/') {
            var end = position
            when {
                raw.startsWith("//", position) -> end = raw.indexOf('\n', position + 2).takeIf { it >= 0 } ?: raw.length
                raw.startsWith("/*", position) -> end = raw.indexOf("*/", position + 2).takeIf { it >= 0 }?.plus(2) ?: raw.length
                else -> {
                    var previous = position - 1
                    while (previous >= 0 && raw[previous].isWhitespace()) previous--
                    val prefix = raw.substring(maxOf(scriptStart, previous - 7), previous + 1)
                    if (previous < 0 || raw[previous] in "(=[:,!&|?{;" || Regex("\\b(return|throw|case)$").containsMatchIn(prefix)) {
                        // Quotes and @Header text in a JS regexp are not string/header boundaries.
                        end = position + 1
                        var characterClass = false
                        var regexEscape = false
                        while (end < raw.length) {
                            val next = raw[end++]
                            if (regexEscape) regexEscape = false
                            else when (next) {
                                '\\' -> regexEscape = true
                                '[' -> characterClass = true
                                ']' -> characterClass = false
                                '/' -> if (!characterClass) break
                            }
                        }
                    }
                }
            }
            if (end > position) { rule.append(raw.substring(position, end)); position = end; continue }
        }
        if (c == '\'' || c == '"' || c == '`') {
            quote = c; rule.append(c); position++; continue
        }
        if (!raw.regionMatches(position, "@Header:", 0, 8, true)) {
            rule.append(c); position++; continue
        }
        var start = position + 8
        while (start < raw.length && raw[start].isWhitespace()) start++
        require(start < raw.length && raw[start] == '{') { "请求头声明必须是 JSON 对象" }
        var depth = 0
        var objectQuote = '\u0000'
        var escaped = false
        var end = start
        while (end < raw.length) {
            // 旧式请求头可在 JSON 字符串里放 {{ JS }}，表达式内部的引号不属于 JSON。
            if (raw.startsWith("{{", end)) {
                val close = raw.indexOf("}}", end + 2)
                require(close >= 0) { "请求头动态表达式不完整" }
                end = close + 2
                continue
            }
            val c = raw[end++]
            if (objectQuote != '\u0000') {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == objectQuote) objectQuote = '\u0000'
            } else when (c) {
                '"', '\'' -> objectQuote = c
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
        }
        require(depth == 0 && objectQuote == '\u0000') { "请求头 JSON 对象不完整" }
        headers += raw.substring(start, end)
        position = end
    }
    return LegacyHeaders(rule.toString().trim(), headers)
}

/** Accept the old {Cookie=articlevisited=1} map syntax as data, never as JavaScript. */
internal fun legacyHeaderObject(raw: String): Map<String, String> {
    val objectText = raw.trim()
    require(objectText.startsWith('{') && objectText.endsWith('}')) { "请求头声明必须是对象" }
    val json = runCatching { JSONObject(objectText) }.getOrNull()
    if (json != null) return json.keys().asSequence().associateWith { key ->
        val value = json.get(key)
        require(value !is JSONObject && value !is org.json.JSONArray && value != JSONObject.NULL) { "请求头值必须是文本" }
        value.toString()
    }
    return SelectorSplitter.splitRules(objectText.substring(1, objectText.length - 1), ",").associate { field ->
        val match = Regex("^\\s*([A-Za-z0-9_-]+)\\s*[:=]\\s*([\\s\\S]*)$").matchEntire(field)
            ?: error("旧式请求头映射格式无效")
        val value = match.groupValues[2].trim()
        require(value.none { it in "{}\r\n" }) { "旧式请求头值无效" }
        match.groupValues[1] to value.removeSurrounding("\"").removeSurrounding("'")
    }
}
