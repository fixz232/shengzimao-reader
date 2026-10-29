package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.*
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/** 旧式源的纯规则运行时。脚本不能访问 JVM、文件、Android 或任意 Java 类。 */
class LegacyRuleRuntime(
    val variables: MutableMap<String, String> = mutableMapOf(),
    private val fetch: (String, String?, Map<String, String>) -> String = { _, _, _ -> error("此处不能发起网络请求") },
) {
    private var content = ""
    private var base = ""
    private var requestCount = 0
    private var requestLimit = 12
    internal var searchKeyword: String? = null
    internal var searchPage: Int = 1
    internal var novelMode = false
    internal var scriptLibrary = ""
    internal var scriptObjects: Map<String, String> = emptyMap()

    private fun extractValue(rule: String, input: String, url: String, list: Boolean) =
        extract(rule, input, url, list, preserveText = novelMode)

    fun evaluate(rule: String, input: String, baseUrl: String, list: Boolean = false, objectInput: Boolean = false): List<String> {
        content = input; base = baseUrl
        if (novelMode) Regex("^@get:\\{([A-Za-z0-9_-]{1,100})\\}$").matchEntire(rule.trim())?.let {
            return listOf(variables[it.groupValues[1]].orEmpty())
        }
        if (novelMode) Regex("^@get:\\{([A-Za-z0-9_-]{1,100})\\}([\\s\\S]+)$").matchEntire(rule.trim())?.let {
            if (it.groupValues[2].startsWith("@js:") || it.groupValues[2].startsWith("<js>"))
                return evaluate(it.groupValues[2], variables[it.groupValues[1]].orEmpty(), baseUrl, list, objectInput)
        }
        if (novelMode && Regex("[0-9]+").matches(rule)) return listOf(rule)
        val adapted = LegacyPrivateScripts.adapt(rule)
        if (adapted != rule) return evaluate(adapted, input, baseUrl, list, objectInput)
        if (rule.contains("@Header:", true)) {
            val parts = splitLegacyHeaders(rule)
            if (parts.objects.isNotEmpty()) return evaluate(parts.rule, input, baseUrl, list, objectInput)
        }
        // Old Yiciyuan readers declare a callback instead of an @js expression.
        // Execute it in the same bounded sandbox, with the raw chapter available as html/result.
        val callback = rule.trimStart()
        if (callback.startsWith("\$function")) {
            // Some callbacks fetch one small address response per page. Keep a finite
            // chapter budget without applying the ordinary field-script limit to them.
            val previousLimit = requestLimit
            requestLimit = 128
            return try {
                evaluate("@js:var html=result;\n" + callback.removePrefix("\$") + "\ngetImgList();",
                    input, baseUrl, list, objectInput)
            } finally { requestLimit = previousLimit }
        }
        val expanded = interpolateValues(rule, input, baseUrl)
        if (expanded != rule) return evaluate(expanded, input, baseUrl, list, objectInput)
        if (rule.isBlank()) return listOf(input)
        val match = Regex("@js\\s*:|<js>|<javascript>", RegexOption.IGNORE_CASE).find(rule)
        if (match != null) {
            val before = rule.substring(0, match.range.first).trim()
            val initial = if (before.isBlank()) input else extractValue(before, input, baseUrl, list).let { values ->
                if (list) JSONArray(values.map { value ->
                    when { value.trim().startsWith("{") -> JSONObject(value); value.trim().startsWith("[") -> JSONArray(value); else -> value }
                }).toString() else if (SelectorSplitter.splitRules(before, "&").size > 1) values.joinToString(" ") else values.firstOrNull().orEmpty()
            }
            return script(rule.substring(match.range.last + 1).replace("</js>", "").replace("</javascript>", ""), initial, baseUrl,
                before.isNotBlank() && list, objectInput && before.isBlank())
        }
        return extractValue(rule, input, baseUrl, list)
    }

    fun interpolate(template: String, input: String, baseUrl: String): String =
        interpolateValues(Regex("\\{\\{([\\s\\S]*?)\\}\\}").replace(template) { match ->
            script(match.groupValues[1], input, baseUrl).firstOrNull().orEmpty()
        }, input, baseUrl)

    private fun interpolateValues(raw: String, input: String, baseUrl: String): String {
        val stored = Regex("@put:\\{((?:\\{\\{[^{}]*\\}\\}|[^{}])+)\\}").replace(raw) { match ->
            SelectorSplitter.splitRules(match.groupValues[1], ",").forEach { binding ->
                val key = binding.substringBefore(':').trim()
                require(Regex("[A-Za-z0-9_-]{1,100}").matches(key) && (key in variables || variables.size < 100)) { "旧式源变量名称或数量无效" }
                val expression = binding.substringAfter(':').trim().let {
                    if (novelMode) it.removeSurrounding("\"").removeSurrounding("'") else it
                }
                variables[key] = (if (expression.startsWith("{{") && expression.endsWith("}}"))
                    script(expression.substring(2, expression.length - 2), input, baseUrl,
                        objectInput = input.trimStart().let { it.startsWith('{') || it.startsWith('[') })
                    else evaluate(expression, input, baseUrl)).firstOrNull().orEmpty().take(1_000_000)
            }
            ""
        }
        val values = Regex("@get:\\{([A-Za-z0-9_-]{1,100})\\}").replace(stored) { variables[it.groupValues[1]].orEmpty() }
        return Regex("(?<!\\{)\\{(\\$[^{}]+)\\}(?!\\})").replace(values) { match ->
            extractValue(match.groupValues[1], input, baseUrl, false).firstOrNull().orEmpty()
        }
    }

    fun script(code: String, input: String, baseUrl: String, listInput: Boolean = false, objectInput: Boolean = false): List<String> {
        require(code.length + scriptLibrary.length <= 128_000 && input.length <= 8_000_000) { "源脚本或正文超过规则运行上限" }
        var deadline = System.nanoTime() + 3_000_000_000L
        var instructions = 0
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 5000
                setClassShutter { false }
                maximumInterpreterStackDepth = 128
            }
            override fun observeInstructionCount(cx: Context, count: Int) {
                instructions += count
                if (Thread.currentThread().isInterrupted || instructions > 4_000_000 || System.nanoTime() > deadline)
                    throw EvaluatorException("源脚本运行超时，已停止")
            }
        }
        return try { factory.call(ContextAction { cx ->
            val scope = cx.initSafeStandardObjects()
            listOf("Packages", "java", "javax", "org", "com", "JavaAdapter", "JavaImporter", "getClass", "load", "loadClass", "importClass", "importPackage").forEach { scope.delete(it) }
            fun set(name: String, value: Any) = ScriptableObject.putProperty(scope, name, value)
            fun function(target: Scriptable, name: String, action: (Array<out Any?>) -> Any?) {
                ScriptableObject.putProperty(target, name, object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                    override fun call(context: Context, parent: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? = try {
                        action(args)
                    } catch (failure: IOException) {
                        // Rhino 1.7 cannot rethrow a checked exception through a native callback.
                        throw EvaluatorException(failure.message ?: "源请求失败").apply { initCause(failure) }
                    }
                })
            }
            fun arg(args: Array<out Any?>, index: Int) = args.getOrNull(index)?.let(Context::toString).orEmpty()
            fun array(values: List<String>) = cx.newArray(scope, values.take(5000).map { it as Any }.toTypedArray()).also { valuesArray ->
                function(valuesArray, "toArray") { valuesArray }
            }
            val elementValues = java.util.IdentityHashMap<Scriptable, List<Element>>()
            lateinit var elements: (List<Element>) -> Scriptable
            elements = { nodes ->
                val result = cx.newObject(scope)
                elementValues[result] = nodes
                function(result, "select") { args -> elements(nodes.flatMap { it.select(arg(args, 0)) }.take(5000)) }
                function(result, "selectFirst") { args -> nodes.firstNotNullOfOrNull { it.selectFirst(arg(args, 0)) }?.let { elements(listOf(it)) } }
                function(result, "selectXpath") { args -> elements(nodes.flatMap { it.selectXpath(SelectorSplitter.normalizeXPath(arg(args, 0))) }.take(5000)) }
                function(result, "attr") { args -> nodes.firstOrNull()?.attr(arg(args, 0)).orEmpty() }
                function(result, "text") { nodes.joinToString(" ") { it.text() }.take(2_000_000) }
                function(result, "html") { nodes.joinToString("") { it.html() }.take(8_000_000) }
                function(result, "outerHtml") { nodes.joinToString("") { it.outerHtml() }.take(8_000_000) }
                function(result, "data") { nodes.joinToString("") { it.data() }.take(8_000_000) }
                function(result, "toString") { nodes.joinToString("") { it.outerHtml() }.take(8_000_000) }
                function(result, "toArray") { cx.newArray(scope, nodes.take(5000).map { elements(listOf(it)) as Any }.toTypedArray()) }
                function(result, "size") { nodes.size }
                function(result, "get") { args -> elements(listOfNotNull(nodes.getOrNull(arg(args, 0).toIntOrNull() ?: 0))) }
                function(result, "first") { elements(nodes.take(1)) }
                result
            }
            val java = cx.newObject(scope)
            function(java, "get") { args -> variables[arg(args, 0)].orEmpty() }
            function(java, "put") { args -> arg(args, 1).take(1_000_000).also { variables[arg(args, 0).take(100)] = it } }
            function(java, "getString") { args -> extractValue(arg(args, 0), content, baseUrl, false).joinToString(if (novelMode) "\n" else "") }
            function(java, "getStringList") { args -> array(extractValue(arg(args, 0), content, baseUrl, true)) }
            if (novelMode) {
                fun selected(args: Array<out Any?>) = extractValue(arg(args, 0), content, baseUrl, true)
                    .flatMap { Jsoup.parse(it, baseUrl).body().children().toList() }.take(5000)
                function(java, "getElement") { args -> elements(selected(args).take(1)) }
                function(java, "getElements") { args -> elements(selected(args)) }
            }
            function(java, "setContent") { args -> content = arg(args, if (args.size > 1) 1 else 0); "" }
            fun base64(args: Array<out Any?>): String {
                val raw = arg(args, 0)
                require(raw.length <= 2_000_000) { "Base64 内容过大" }
                return Base64.getDecoder().decode(raw.filterNot { it in " \t\r\n" }).toString(Charsets.UTF_8)
            }
            function(java, "base64Decoder", ::base64)
            function(java, "atob", ::base64)
            function(java, "base64Decode", ::base64)
            function(java, "base64Encode") { args ->
                val value = arg(args, 0)
                require(value.length <= 1_000_000) { "Base64 内容过大" }
                Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            }
            function(java, "base64UrlDecoder") { args ->
                val raw = arg(args, 0)
                require(raw.length <= 2_000_000) { "Base64 内容过大" }
                Base64.getUrlDecoder().decode(raw.filterNot { it.isWhitespace() }).toString(Charsets.UTF_8)
            }
            function(java, "md5") { args ->
                val value = arg(args, 0)
                require(value.length <= 1_000_000) { "摘要输入过大" }
                MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }
            ScriptableObject.putProperty(java, "md5Encode", ScriptableObject.getProperty(java, "md5"))
            val ycy = cx.newObject(scope)
            fun bytes(value: Any?): ByteArray {
                require(value is NativeArray && value.length <= 2_000_000) { "字节数组无效或过大" }
                return ByteArray(value.length.toInt()) { index ->
                    val number = Context.toNumber(value.get(index, value))
                    require(number.isFinite() && number % 1 == 0.0 && number in 0.0..255.0) { "字节值无效" }
                    number.toInt().toByte()
                }
            }
            fun byteArray(bytes: ByteArray) = cx.newArray(scope, bytes.map { (it.toInt() and 255) as Any }.toTypedArray())
            function(ycy, "toBytes") { args ->
                val raw = arg(args, 0)
                require(raw.length <= 2_000_000) { "字节转换内容过大" }
                val bytes = raw.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 2_000_000) { "字节转换内容过大" }
                byteArray(bytes)
            }
            function(ycy, "atob") { args ->
                val value = args.getOrNull(0)
                val raw = if (value is NativeArray) {
                    bytes(value).toString(Charsets.UTF_8)
                } else arg(args, 0)
                base64(arrayOf(raw))
            }
            function(ycy, "atobToBytes") { args ->
                val value = args.getOrNull(0)
                val raw = if (value is NativeArray) bytes(value).toString(Charsets.UTF_8) else arg(args, 0)
                require(raw.length <= 2_000_000) { "Base64 内容过大" }
                byteArray(Base64.getDecoder().decode(raw.filterNot { it.isWhitespace() }))
            }
            function(ycy, "decrypt") { args ->
                require(arg(args, 0) in setOf("AES/CBC/PKCS7Padding", "AES/CBC/PKCS5Padding")) { "暂不支持该图片地址处理算法" }
                LegacyPrivateScripts.decryptAesBytes(bytes(args.getOrNull(1)), bytes(args.getOrNull(2)), bytes(args.getOrNull(3)))
            }
            function(ycy, "toast") { "" }
            set("ycy", ycy)
            function(java, "decryptAesCbc") { args -> LegacyPrivateScripts.decryptAes(arg(args, 0), arg(args, 1), arg(args, 2)) }
            function(java, "log") { "" }
            function(java, "toast") { "" }
            fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
                require(++requestCount <= requestLimit) { "单次源操作请求次数超过上限（$requestLimit）" }
                require(body == null || body.length <= 1_000_000) { "请求正文过大" }
                val target = URI(baseUrl).resolve(url).toString()
                // Network calls have their own timeouts. Waiting for bytes is not script execution.
                val started = System.nanoTime()
                return try { fetch(target, body, headers).also { require(it.length <= 8_000_000) { "响应过大" } } }
                finally { deadline += System.nanoTime() - started }
            }
            fun requestHeaders(value: Any?): Map<String, String> = when (value) {
                null, Undefined.instance -> emptyMap()
                is Scriptable -> {
                    require(value.ids.size <= 32) { "请求头数量过多" }
                    value.ids.associate { key -> key.toString().let { it to Context.toString(ScriptableObject.getProperty(value, it)) } }
                }
                else -> legacyHeaderObject(Context.toString(value))
            }
            function(java, "aJax") { args -> request(arg(args, 0)) }
            function(java, "ajax") { args -> request(arg(args, 0)) }
            function(java, "encodeURI") { args -> URLEncoder.encode(arg(args, 0), arg(args, 1).ifBlank { "UTF-8" }).replace("+", "%20") }
            function(java, "postForm") { args -> request(arg(args, 0), arg(args, 1),
                mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8") + requestHeaders(args.getOrNull(2))) }
            function(java, "post") { args ->
                val text = request(arg(args, 0), arg(args, 1),
                    mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8") + requestHeaders(args.getOrNull(2)))
                cx.newObject(scope).also { response -> function(response, "body") { text } }
            }
            function(java, "postJson") { args ->
                val value = args.getOrNull(1)
                val json = if (value is Scriptable) { set("__payload", value); Context.toString(cx.evaluateString(scope, "JSON.stringify(__payload)", "post-json", 1, null)) } else arg(args, 1)
                request(arg(args, 0), json, mapOf("Content-Type" to "application/json") + requestHeaders(args.getOrNull(2)))
            }
            function(java, "fetch") { args ->
                val options = args.getOrNull(1) as? Scriptable
                fun option(key: String) = options?.let { ScriptableObject.getProperty(it, key) }?.takeIf { it != Scriptable.NOT_FOUND }
                val header = option("headers") as? Scriptable
                val headers = header?.ids?.mapNotNull { key -> val name = key.toString(); ScriptableObject.getProperty(header, name).takeIf { it != Scriptable.NOT_FOUND }?.let { name to Context.toString(it) } }?.toMap().orEmpty()
                val method = option("method")?.let(Context::toString)?.uppercase() ?: "GET"
                require(method in setOf("GET", "POST")) { "源脚本仅支持 GET / POST 请求" }
                val text = request(arg(args, 0), if (method == "POST") option("body")?.let(Context::toString).orEmpty() else null, headers); val response = cx.newObject(scope)
                val body = cx.newObject(scope); function(body, "string") { text }
                function(response, "body") { body }; response
            }
            set("java", java)
            val jsEngine = cx.newObject(scope)
            for ((name, original) in listOf("aJax" to "aJax", "atob" to "atob")) {
                ScriptableObject.putProperty(jsEngine, name, ScriptableObject.getProperty(java, original))
            }
            set("JsEngine", jsEngine)
            val jsoup = cx.newObject(scope)
            function(jsoup, "parse") { args -> elements(listOf(Jsoup.parse(arg(args, 0), baseUrl))) }
            function(jsoup, "connect") { args ->
                val url = arg(args, 0)
                var body = ""
                var method = "GET"
                val headers = linkedMapOf<String, String>()
                val connection = cx.newObject(scope)
                function(connection, "requestBody") { bodyArgs -> body = arg(bodyArgs, 0); require(body.length <= 1_000_000) { "请求正文过大" }; connection }
                function(connection, "followRedirects") { option ->
                    require(option.firstOrNull()?.let(Context::toBoolean) == true) { "旧式请求暂不支持禁止重定向的响应读取" }; connection
                }
                function(connection, "method") { methodArgs ->
                    method = arg(methodArgs, 0).uppercase()
                    require(method in setOf("GET", "POST")) { "源脚本仅支持 GET / POST 请求" }; connection
                }
                function(connection, "header") { headerArgs ->
                    val name = arg(headerArgs, 0); val value = arg(headerArgs, 1)
                    MangaRequestHeaders.validate(name, value)
                    require(headers.size < 32 || headers.containsKey(name)) { "请求头数量过多" }
                    headers[name] = value
                    connection
                }
                function(connection, "get") { elements(listOf(Jsoup.parse(request(url, headers = headers), URI(baseUrl).resolve(url).toString()))) }
                function(connection, "post") { elements(listOf(Jsoup.parse(request(url, body, mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8") + headers), URI(baseUrl).resolve(url).toString()))) }
                function(connection, "execute") {
                    val text = request(url, if (method == "POST") body else null,
                        (if (method == "POST") mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8") else emptyMap()) + headers)
                    cx.newObject(scope).also { response ->
                        function(response, "body") { text }
                        function(response, "parse") { elements(listOf(Jsoup.parse(text, URI(baseUrl).resolve(url).toString()))) }
                    }
                }
                connection
            }
            val ns = cx.newObject(scope); val pkg = cx.newObject(scope)
            ScriptableObject.putProperty(pkg, "Jsoup", jsoup)
            val connectionType = cx.newObject(scope); val methods = cx.newObject(scope)
            ScriptableObject.putProperty(methods, "GET", "GET"); ScriptableObject.putProperty(methods, "POST", "POST")
            ScriptableObject.putProperty(connectionType, "Method", methods); ScriptableObject.putProperty(pkg, "Connection", connectionType)
            ScriptableObject.putProperty(ns, "jsoup", pkg); set("org", ns)
            val structured = objectInput && (input.trim().startsWith("{") || input.trim().startsWith("["))
            val resultValue = if (listInput || structured) org.mozilla.javascript.json.JsonParser(cx, scope).parseValue(input) else input
            // Per-item objects must support result.title/url; preserve JSON.parse(result) rules too.
            if (structured && resultValue is ScriptableObject) {
                function(resultValue, "toString") { input }
                resultValue.setAttributes("toString", ScriptableObject.DONTENUM)
            }
            set("result", resultValue); set("baseUrl", baseUrl); set("thisPage", baseUrl)
            if (listInput && resultValue is NativeArray) {
                // Old rule chains use both result.map(...) and result.match(...) on selected lists.
                cx.evaluateString(scope, """
                    ['match','replace'].forEach(function(key) {
                        Object.defineProperty(result, key, {enumerable:false, value:function() {
                            return String.prototype[key].apply(String(this), arguments);
                        }});
                    });
                """.trimIndent(), "selected-list-compatibility", 1, null)
            }
            set("host", URI(baseUrl).let { "${it.scheme}://${it.rawAuthority}" })
            searchKeyword?.let { set("searchKey", it); set("searchPage", searchPage) }
            if (novelMode) {
                set("key", searchKeyword.orEmpty()); set("page", searchPage)
                for ((name, json) in scriptObjects) {
                    require(name in setOf("book", "chapter", "source"))
                    val value = org.mozilla.javascript.json.JsonParser(cx, scope).parseValue(json)
                    if (value is Scriptable && name in setOf("source", "book")) {
                        function(value, "getVariable") { variables["__${name}Variable"].orEmpty() }
                        function(value, "setVariable") { args -> arg(args, 0).take(1_000_000).also { variables["__${name}Variable"] = it } }
                        if (name == "source") {
                            function(value, "getKey") { JSONObject(json).optString("bookSourceUrl") }
                            function(value, "getName") { JSONObject(json).optString("bookSourceName") }
                        }
                    }
                    set(name, value)
                }
                if (scriptLibrary.isNotBlank()) cx.evaluateString(scope, scriptLibrary, "novel-source-library", 1, null)
            }
            // 只提供原始 JS 值，绝不把 Java 对象包装进脚本作用域。
            val answer = cx.evaluateString(scope, code, "manga-source", 1, null)
            val values = if (answer is NativeArray) (0 until minOf(answer.length, 5000).toInt()).map { answer.get(it, answer) } else listOf(answer)
            values.filterNot { it == null || it == Undefined.instance || it == Scriptable.NOT_FOUND }.flatMap { value ->
                val nodes = (value as? Scriptable)?.let { elementValues[it] }
                if (nodes != null) nodes.map { it.outerHtml() }
                else listOf(if (value is NativeObject) Context.toString(NativeJSON.stringify(cx, scope, value, null, null)) else Context.toString(value))
            }.also { result ->
                require(result.size <= 5000 && result.all { it.length <= 2_000_000 } && result.sumOf { it.length.toLong() } <= 8_000_000) { "规则结果过大" }
            }
        }) } catch (failure: EvaluatorException) {
            (failure.cause as? IOException)?.let { throw it }
            throw failure
        }
    }

    companion object {
        fun extract(raw: String, input: String, baseUrl: String, list: Boolean, preserveText: Boolean = false): List<String> {
            if (raw == "thisPage" || raw == "baseUrl") return listOf(baseUrl)
            if (raw.startsWith("http://") || raw.startsWith("https://")) return listOf(raw)
            // Split selector fallbacks before their trailing replacement, leaving regex pipes intact.
            val replacement = (if (preserveText) raw.indexOf("##") else raw.indexOf('#')).takeIf { it >= 0 } ?: raw.length
            val alternatives = SelectorSplitter.splitRules(raw.substring(0, replacement), "||")
                .flatMap { SelectorSplitter.splitRules(it, "|") }.toMutableList()
            if (preserveText && replacement == 0 && alternatives.isEmpty()) alternatives.add("")
            if (replacement < raw.length && alternatives.isNotEmpty()) alternatives[alternatives.lastIndex] += raw.substring(replacement)
            for (alternative in alternatives) {
                val all = ArrayList<String>()
                for (part in SelectorSplitter.splitRules(alternative, "&")) {
                    val pieces = if (part.contains("##")) part.split("##", limit = 3) else if (preserveText) listOf(part) else part.split('#', limit = 3)
                    val json = input.trim().startsWith("{") || input.trim().startsWith("[")
                    val selector = pieces[0].trim().let { value ->
                        if (!preserveText) value else {
                            val token = if (value in setOf("text", "textNodes", "html", "all", "href", "src", "content") || value.startsWith("data-")) "@$value" else value
                            Regex("(^|@)class\\.([\\w-]+(?:[ \\t]+[\\w-]+)+)(?=@|$)").replace(token) {
                                it.groupValues[1] + "." + it.groupValues[2].split(Regex("[ \\t]+")).joinToString(".")
                            }
                        }
                    }
                    if (!json && (selector.startsWith("$") || selector.startsWith("@json:", true))) continue
                    val exclude = !json && Regex("!-?\\d+").containsMatchIn(selector)
                    val indexed = !json && Regex("(?:id|class|tag)\\.[\\w-]+\\.-\\d+(?=@|$)").containsMatchIn(selector)
                    val modernIndexed = preserveText && Regex("(^|@)(?:(?:id|class|tag)\\.)?(?:[A-Za-z][\\w-]*|\\.[\\w-]+)\\.-?\\d+(?=@|$)").containsMatchIn(selector)
                    val chainSelection = exclude || indexed || modernIndexed
                    val normalized = if (chainSelection) selector else LegadoConverter.normalize(selector, if (json) "json" else "html")
                    val values: List<String> = if (preserveText && pieces[0].isBlank()) listOf(input)
                    else if (normalized.startsWith("$") || input.trim().startsWith("{") || input.trim().startsWith("[")) {
                        val root: Any = if (input.trim().startsWith("[")) JSONArray(input) else JSONObject(input)
                        LegacyJsonPath.select(root, normalized).flatMap { node ->
                            if (list && node is JSONArray && !normalized.contains("..")) (0 until node.length()).map { node.get(it).toString() }
                            else listOf(node.toString())
                        }
                    } else {
                        val doc = Jsoup.parse(input, baseUrl)
                        if (preserveText) doc.outputSettings().prettyPrint(false)
                        val (path, attr) = if (chainSelection) "" to selector.substringAfterLast('@', "").takeIf {
                            it in setOf("text", "textNodes", "html", "all", "href", "src", "title", "alt", "class", "id", "style", "value", "content") || it.startsWith("data-")
                        }.orEmpty()
                            else SelectorSplitter.split(normalized)
                        val nodes = if (chainSelection) {
                            val chain = if (attr.isBlank()) selector else selector.substringBeforeLast('@')
                            var selected = listOf<Element>(doc)
                            for (token in SelectorSplitter.splitRules(chain, "@")) {
                                val filter = Regex("^(.+?)!(-?\\d+(?::-?\\d+)*)$").matchEntire(token)
                                val rawToken = filter?.groupValues?.get(1) ?: token
                                val position = Regex("^(id|class|tag)\\.([\\w-]+)\\.(-?\\d+)$").matchEntire(rawToken)
                                val genericPosition = if (preserveText) Regex("^([A-Za-z][\\w-]*|\\.[\\w-]+)\\.(-?\\d+)$").matchEntire(rawToken) else null
                                val css = LegadoConverter.normalize(position?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } ?: genericPosition?.groupValues?.get(1) ?: rawToken)
                                selected = selected.flatMap { parent ->
                                    val found = parent.select(css).toList()
                                    val excluded = filter?.groupValues?.get(2)?.split(':')?.map { it.toInt().let { n -> if (n < 0) found.size + n else n } }.orEmpty()
                                    val filtered = found.filterIndexed { index, _ -> index !in excluded }
                                    val itemIndex = position?.groupValues?.get(3) ?: genericPosition?.groupValues?.get(2)
                                    if (itemIndex == null) filtered else itemIndex.toInt().let { n ->
                                        listOfNotNull(filtered.getOrNull(if (n < 0) filtered.size + n else n))
                                    }
                                }.distinct().take(5000)
                            }
                            selected
                        } else if (path.isBlank()) doc.body().children().toList().ifEmpty { listOf(doc) }
                            else if (SelectorSplitter.isXPath(path)) doc.selectXpath(SelectorSplitter.normalizeXPath(path)).toList() else doc.select(path).toList()
                        val textOutput = pieces[0].trim().endsWith("@text") || pieces[0].trim().endsWith("/text()")
                        val terminal = selector.substringAfterLast('@', "")
                        nodes.take(5000).map { node ->
                            if (preserveText && terminal == "textNodes") node.textNodes().joinToString("\n") { it.text() }
                            else if (preserveText && terminal in setOf("html", "all")) node.html()
                            else if (attr == "text") node.text()
                            else if (attr == "html") node.html()
                            else if (attr.isNotBlank()) node.attr(attr)
                            else if (list && !textOutput) node.outerHtml()
                            else if (preserveText) node.wholeText()
                            else node.text()
                        }
                    }
                    all += values.map { value -> if (pieces.size >= 2) Regex(pieces[1]).replace(value, pieces.getOrElse(2) { "" }) else value }
                }
                if (all.any { it.isNotBlank() }) return all
            }
            return emptyList()
        }
    }
}
