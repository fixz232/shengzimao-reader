package com.shengzimao.reader.mobile.data

/**
 * 选择器拆分：把 `路径@属性` 拆成路径与属性名。
 *
 * 单独抽出来是因为它是 XPath 支持的关键：
 * XPath 的谓词里也用 `@`（`//div[@class=comic-item]`），
 * 按最后一个 `@` 直接切分会把查询切成 `//div[`，导致 jsoup 抛
 * `SelectorParseException: Could not parse query '//div['`。
 */
object SelectorSplitter {

    private val ATTR_NAME = Regex("[\\w:-]+")

    /** @return 路径与属性名；没有属性时第二项为空串。 */
    fun split(selector: String): Pair<String, String> {
        val raw = selector.trim()
        if (raw.isEmpty()) return "" to ""
        var depth = 0
        var index = -1
        for (i in raw.indices) {
            when (raw[i]) {
                '[' -> depth++
                ']' -> if (depth > 0) depth--
                '@' -> if (depth == 0) index = i
            }
        }
        if (index < 0) return raw to ""
        val attr = raw.substring(index + 1)
        // 属性名只允许字母数字与 -_:；否则说明这不是属性写法（例如 XPath 谓词残留）。
        if (!ATTR_NAME.matches(attr)) return raw to ""
        val path = raw.substring(0, index)
        return (if (isXPath(path)) path.removeSuffix("/") else path) to attr
    }

    /** 选择器是否按 XPath 解释。 */
    fun isXPath(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty()) return false
        return p.startsWith("/") || p.startsWith("./") || p.startsWith("../") || p.contains("[@") || p.endsWith("/text()")
    }

    /** 只拆顶层运算符，保留 XPath 谓词、CSS 属性和引号内的分隔符。 */
    fun splitRules(raw: String, operator: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var start = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' || c == '(' || c == '{' -> depth++
                c == ']' || c == ')' || c == '}' -> depth--
                depth == 0 && raw.startsWith(operator, i) -> {
                    parts.add(raw.substring(start, i).trim())
                    i += operator.length
                    start = i
                    continue
                }
            }
            i++
        }
        parts.add(raw.substring(start).trim())
        return parts.filter { it.isNotEmpty() }
    }

    private val UNQUOTED_ATTR = Regex("@([\\w:-]+)\\s*=\\s*([^'\"\\s\\]|]+)")

    /**
     * 把 Legado 常见的 XPath 写法改成 jsoup 能解析的形式。
     *
     * jsoup 要求属性值带引号，`//div[@class=comic-item]` 直接传进去会返回 0 个元素
     * （不报错，静默匹配失败），所以必须补引号。
     */
    fun normalizeXPath(path: String): String {
        var p = UNQUOTED_ATTR.replace(path) { m ->
            "@${m.groupValues[1]}='${m.groupValues[2]}'"
        }
        // jsoup 的 XPath 下标从 1 开始，去掉 Legado 里常见的 [0] 取首个写法。
        p = p.replace(Regex("\\[0\\]"), "")
        return p
    }
}
