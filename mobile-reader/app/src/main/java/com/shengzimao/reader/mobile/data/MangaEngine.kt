package com.shengzimao.reader.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.TlsVersion
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 声明式 HTML/CSS、JSON 路径源；不执行远端脚本，不提供登录或验证绕过。 */
class MangaEngine internal constructor(private val store: MangaStore, private val client: OkHttpClient =
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
    private val imageRuntime: MangaImageRuntime = AndroidMangaImageRuntime) {
    // 链接导入的协议故障或连接重置：用新连接回退一次，保留系统证书和原代理配置。
    internal val importCompatibleClient by lazy {
        client.newBuilder().connectionPool(okhttp3.ConnectionPool()).protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2).build(), ConnectionSpec.CLEARTEXT))
            .connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    }
    private val importClient = client.newBuilder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()
    private val importRetryClient by lazy { importCompatibleClient.newBuilder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build() }
    // 固定条带锁不会在仍有等待者时移除，避免同一 .part 文件被两个任务同时写入。
    private val imageLocks = Array(64) { Mutex() }

    /**
     * 图片下载的全局并发闸门。
     * 之前 3 路并发只写在下载路径的 chunked(3) 里，阅读时的预取不受限制；
     * 把限制下沉到引擎，任何调用方都遵守同一上限。
     */
    private val imageScheduler = MangaImageScheduler(3)
    private val loadingTimes = MangaLoadingTimes()
    fun reading(active: Boolean) = imageScheduler.reading(active)
    fun imageLoadMillis(sourceId: String): Long = loadingTimes.estimate(sourceId)
    fun ruleVersion(source: MangaSource): String = store.adapters.effective(source).ruleVersion

    /** 源定义解析结果缓存：避免每张图都重新解析一次全部源 JSON。 */
    private val sourceCache = ConcurrentHashMap<String, MangaSource>()
    private val legacyHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val imageRoutes = ConcurrentHashMap<String, String>()
    private val imageClient = client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).build()
    private val compatibleOrigins = ConcurrentHashMap.newKeySet<String>()
    private val imageCompatibleClient by lazy { importCompatibleClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build() }
    private class CompatibilityFailure(host: String, cause: java.io.IOException) : java.io.IOException(
        "漫画站点 $host 的连接中断，安全兼容重试也未成功，可重试或更换漫画源", cause)
    private val legacyEngine = LegacyMangaEngine({ url, source, body, headers ->
        request(url, source, extraHeaders = headers, postBody = body).use { response ->
            require(response.isSuccessful) { "漫画源请求失败（HTTP ${response.code}）" }
            val content = response.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 8 * 1024 * 1024) { "漫画源响应过大" }; out.write(buffer, 0, n)
                }; out.toByteArray()
            }
            ResponseDecoder.decode(content.toString(response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                .also { require(it.isNotBlank()) { "漫画站点返回空响应，请稍后重试或更换漫画源" } }
        }
    }) { source, url, headers -> legacyHeaders["$source|$url"] = headers }

    fun invalidateSources(sources: List<MangaSource> = emptyList()) {
        sourceCache.clear()
        legacyHeaders.clear()
        sources.filter { it.enabled }.forEach { sourceCache[it.id] = it }
    }

    private fun resolveSource(sourceId: String): MangaSource = store.adapters.effective(
        sourceCache[sourceId] ?: store.sources().firstOrNull { it.id == sourceId && it.enabled }
            ?.also { sourceCache[sourceId] = it }
            ?: error("漫画源已停用或删除，当前图片未缓存"))

    suspend fun adapterPackUrl(url: String): MangaAdapterPack = withContext(Dispatchers.IO) {
        val address = mangaHttpUrl(url)
        require(address.toHttpUrl().isHttps) { "适配包链接必须使用 HTTPS，也可以从本地文件导入" }
        val trail = mutableListOf<String>()
        val text = fetchTracked(address, trail, MangaAdapterPack.MAX_BYTES, importClient)
        require(trail.all { it.toHttpUrl().isHttps }) { "适配包下载不能降级为 HTTP" }
        MangaAdapterPack.parse(text)
    }

    /**
     * 从链接导入漫画源。
     *
     * 会记录跳转链：源地址被 301 到别的站点时（例如 eo.com → x.com），
     * 返回的往往不是 JSON。此时直接告诉用户跳到了哪里，而不是抛 JSON 解析异常。
     */
    suspend fun importUrl(url: String, onFallback: (String) -> Unit = {}, onProgress: (String) -> Unit = {}): List<MangaSource> = withContext(Dispatchers.IO) {
        val trail = mutableListOf<String>()
        val address = mangaHttpUrl(url)
        onProgress("正在连接漫画源并读取 JSON…")
        suspend fun readLink(link: String): String {
            trail.clear()
            try { return fetchTracked(link, trail, 8 * 1024 * 1024, importClient) }
            catch (failure: java.io.IOException) {
                if (!MangaImportTls.canRetry(failure)) throw MangaImportTls.diagnose(trail.lastOrNull() ?: link, failure, false)
                currentCoroutineContext().ensureActive()
                onProgress("连接中断，正在使用新连接兼容重试（1/1）…")
                delay(250)
                // 入口已成功跳转时直接重试落点，避免又被入口网络故障拦住。
                val retryUrl = trail.lastOrNull() ?: link
                trail.clear(); trail += retryUrl
                try { return fetchTracked(retryUrl, trail, 8 * 1024 * 1024, importRetryClient) }
                catch (compatibleFailure: java.io.IOException) {
                    compatibleFailure.addSuppressed(failure)
                    throw MangaImportTls.diagnose(trail.lastOrNull() ?: retryUrl, compatibleFailure, true)
                }
            }
        }
        fun recoverable(failure: java.io.IOException) = !MangaImportTls.certificateFailure(failure) &&
            (MangaImportTls.canRetry(failure) || failure is java.net.UnknownHostException)
        val text = try { readLink(address) } catch (failure: java.io.IOException) {
            if (!recoverable(failure)) throw failure
            var recovered: String? = null
            var last = failure
            val routes = MangaImportRoutes.candidates(address)
            for ((index, direct) in routes.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress("分享入口连接失败，正在读取已核验的公开 JSON 直链（${index + 1}/${routes.size}）…")
                try { recovered = readLink(direct); onFallback(MangaImportRoutes.NOTICE); break }
                catch (next: java.io.IOException) {
                    next.addSuppressed(last); last = next
                    if (!recoverable(next)) throw next
                }
            }
            recovered ?: throw last
        }
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        MangaImportFormat.textError(trimmed)?.let { throw IllegalArgumentException(it) }
        val looksHtml = trimmed.startsWith("<")
        if (looksHtml || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            val origin = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            val landed = trail.lastOrNull()?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()
            val jumped = landed.isNotBlank() && origin.isNotBlank() && !landed.equals(origin, true)
            val where = if (jumped) "该地址被跳转到了 ${landed}（原域名 ${origin}）。" else ""
            val what = if (looksHtml) "返回的是网页而不是 JSON。" else "返回的内容不是有效的 JSON 对象或数组。"
            throw IllegalArgumentException(
                "${what}${where}这通常说明链接指向的是网页、跳转入口或需要登录的页面。" +
                    "请改用直接返回漫画源 JSON 的地址；如果浏览器里能看到 JSON，可复制内容后用「粘贴」导入。",
            )
        }
        onProgress("已读取内容，正在解析并核对漫画源…")
        MangaSource.importText(trimmed)
    }

    private suspend fun fetchTracked(url: String, trail: MutableList<String>, max: Int,
        httpClient: OkHttpClient = client): String = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        runInterruptible {
            request(url, null, 0, trail, httpClient = httpClient).use { response ->
                require(response.isSuccessful) { "漫画源请求失败（HTTP ${response.code}），请检查站点或源规则" }
                require(response.body.contentLength() <= max) { "漫画源响应超过大小上限" }
                val bytes = java.io.ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        jobContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(bytes.size() + n <= max) { "漫画源响应超过大小上限" }
                        bytes.write(buffer, 0, n)
                    }
                }
                val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                ResponseDecoder.decode(bytes.toByteArray().toString(charset))
            }
        }
    }

    suspend fun search(source: MangaSource, keyword: String, page: Int): List<MangaBook> =
        searchEffective(store.adapters.effective(source), keyword, page)

    private suspend fun searchEffective(source: MangaSource, keyword: String, page: Int): List<MangaBook> = withContext(Dispatchers.IO) {
        if (source.legacy != null) return@withContext runInterruptible { legacyEngine.search(source, keyword, page) }
        val rule = source.rules.getJSONObject("search")
        val path = rule.getString("url")
            .replace("{query}", URLEncoder.encode(keyword, "UTF-8").replace("+", "%20"))
            .replace("{page}", page.toString())
        val url = resolve(source.baseUrl, path)
        val items = items(fetch(url, source), rule, "items", url)
        items.take(300).mapNotNull { item ->
            val title = value(item, rule.optString("title")).trim()
            val link = value(item, rule.optString("link"))
            if (title.isBlank() || link.isBlank()) null else {
                val detailUrl = resolve(url, link)
                val cover = optionalValue(item, rule, "cover")
                MangaBook(id = mangaHash(source.id + "|" + detailUrl).take(32), title = title.take(250),
                    sourceId = source.id, sourceName = source.name, url = detailUrl,
                    cover = if (cover.isBlank()) "" else resolve(url, cover), author = optionalValue(item, rule, "author").take(150))
            }
        }.distinctBy { it.url }
    }

    /**
     * 浏览源站的一个「发现」条目（排行榜 / 分类）。
     *
     * 复用 search 段的选择器语法与解析器：discover 只提供 URL 与 items/title/link 规则，
     * 因此不引入第二套解析逻辑，也不会影响已有源的搜索结果。
     * 源未配置 discover 时调用方应走 [MangaDiscover.FALLBACK_KEYWORDS] 的搜索降级方案。
     */
    suspend fun browse(
        source: MangaSource,
        discover: MangaDiscover,
        entry: MangaDiscoverEntry,
        page: Int = 1,
    ): List<MangaBook> = browseEffective(store.adapters.effective(source), discover, entry, page)

    private suspend fun browseEffective(
        source: MangaSource,
        discover: MangaDiscover,
        entry: MangaDiscoverEntry,
        page: Int,
    ): List<MangaBook> = withContext(Dispatchers.IO) {
        val path = entry.url.replace("{page}", page.toString())
        val url = resolve(source.baseUrl, path)
        // 翻页后缀单独拼接，保持 baseUrl 解析在同一个 host 上。
        val pageSuffix = if (entry.page.isNotBlank() && page > 1) {
            entry.page.replace("{page}", page.toString())
        } else ""
        val target = if (pageSuffix.isBlank()) url else resolve(url, url.trimEnd('/') + "/" + pageSuffix.trimStart('/'))
        // 统一用 items/title/link 三键构造一个临时规则对象，交给 items() 复用。
        val rule = JSONObject().apply {
            put("items", discover.items)
            put("title", discover.title)
            put("link", discover.link)
            if (discover.cover.isNotBlank()) put("cover", discover.cover)
            if (discover.author.isNotBlank()) put("author", discover.author)
        }
        val items = items(fetch(target, source), rule, "items", target)
        items.take(120).mapNotNull { item ->
            val title = value(item, rule.optString("title")).trim()
            val link = value(item, rule.optString("link"))
            if (title.isBlank() || link.isBlank()) null else {
                val detailUrl = resolve(target, link)
                val cover = optionalValue(item, rule, "cover")
                MangaBook(
                    id = mangaHash(source.id + "|" + detailUrl).take(32),
                    title = title.take(250),
                    sourceId = source.id,
                    sourceName = source.name,
                    url = detailUrl,
                    cover = if (cover.isBlank()) "" else resolve(target, cover),
                    author = optionalValue(item, rule, "author").take(150),
                )
            }
        }.distinctBy { it.url }
    }

    suspend fun details(source: MangaSource, book: MangaBook): MangaBook = detailsEffective(store.adapters.effective(source), book)

    private suspend fun detailsEffective(source: MangaSource, book: MangaBook): MangaBook = withContext(Dispatchers.IO) {
        if (source.legacy != null) return@withContext runInterruptible { legacyEngine.details(source, book) }
        val rule = source.rules.getJSONObject("detail")
        val text = fetch(book.url, source)
        val root = parseRoot(text, rule, book.url)
        val tocLink = optionalValue(root, rule, "tocUrl")
        val tocUrl = if (tocLink.isBlank()) book.url else resolve(book.url, tocLink)
        val tocRoot = if (tocUrl == book.url) root else parseRoot(fetch(tocUrl, source), rule, tocUrl)
        val chapterItems = itemsFrom(tocRoot, rule.getString("chapters"))
        var chapters = chapterItems.take(30001).mapNotNull { item ->
            val title = value(item, rule.optString("chapterTitle")).trim()
            val link = value(item, rule.optString("chapterUrl", "@href")).trim()
            if (link.isBlank()) null else MangaChapter(title.ifBlank { "未命名章节" }.take(250), resolve(tocUrl, link))
        }.distinctBy { it.url }
        require(chapters.isNotEmpty()) { BaoziReader.emptyDirectoryMessage(text, book.url)
            ?: "漫画源没有返回有效目录，请检查 detail.chapters / chapterUrl 规则" }
        require(chapters.size <= 30000) { "漫画目录超过 30000 章上限" }
        if (rule.optBoolean("reverse")) chapters = chapters.reversed()
        val cover = optionalValue(root, rule, "cover")
        book.copy(title = optionalValue(root, rule, "title").ifBlank { book.title }.take(250),
            author = optionalValue(root, rule, "author").ifBlank { book.author }.take(150),
            intro = optionalValue(root, rule, "intro").take(8000),
            cover = if (cover.isBlank()) book.cover else resolve(book.url, cover), chapters = chapters)
    }

    /** Image requests retain chapter context, including after the process and reader reopen. */
    suspend fun pages(source: MangaSource, chapter: MangaChapter): List<String> = withContext(Dispatchers.IO) {
        loadPages(source, chapter).also { images ->
            val defaults = defaultImageHeaders(source, chapter.url)
            images.forEach { image -> legacyHeaders.compute("${source.id}|$image") { _, explicit ->
                if (explicit.orEmpty().keys.any { it.equals("Referer", true) }) explicit else defaults + explicit.orEmpty()
            } }
        }
    }

    private fun defaultImageHeaders(source: MangaSource, chapterUrl: String): Map<String, String> =
        if (store.adapters.effective(source).headers.keys.any { it.equals("Referer", true) }) emptyMap() else mapOf("Referer" to chapterUrl)

    internal fun captureImageHeaders(source: MangaSource, chapter: MangaChapter, images: List<String>): Map<String, Map<String, String>> {
        val defaults = defaultImageHeaders(source, chapter.url)
        return images.mapNotNull { image -> legacyHeaders["${source.id}|$image"]?.takeIf { it != defaults && it.isNotEmpty() }?.let { image to it } }.toMap()
    }

    internal fun restoreImageHeaders(source: MangaSource, chapter: MangaChapter) {
        chapter.pages.filterNot { it.startsWith("file:") }.forEach { image ->
            legacyHeaders["${source.id}|$image"] = chapter.imageHeaders[image] ?: defaultImageHeaders(source, chapter.url)
        }
    }

    internal fun imageHeaders(source: MangaSource, chapter: MangaChapter, reference: String): Map<String, String> =
        (chapter.imageHeaders[reference] ?: legacyHeaders["${source.id}|$reference"] ?: defaultImageHeaders(source, chapter.url)) +
            store.adapters.imageHeaders(source, chapter.url, reference)

    private suspend fun loadPages(source: MangaSource, chapter: MangaChapter): List<String> =
        loadPagesEffective(store.adapters.effective(source), chapter)

    private suspend fun loadPagesEffective(source: MangaSource, chapter: MangaChapter): List<String> = withContext(Dispatchers.IO) {
        MangaSiteAdapters.find(source, chapter)?.takeUnless { store.adapters.overridesReader(source) }?.let { adapter ->
            return@withContext adapter.pages(source, chapter,
                fetch = { url, headers -> fetch(url, source, extraHeaders = headers) },
                rememberHeaders = { image, headers -> legacyHeaders["${source.id}|$image"] = headers })
        }
        if (source.legacy != null) return@withContext runInterruptible { legacyEngine.pages(source, chapter) }
        val rule = source.rules.getJSONObject("reader")
        val result = LinkedHashSet<String>()
        val visited = HashSet<String>()
        var url = chapter.url
        repeat(rule.optInt("maxPages", if (rule.has("nextPage")) 50 else 1).coerceIn(1, 50)) {
            require(visited.add(url)) { "章节分页出现循环链接，已停止，未标记完整缓存" }
            val text = fetch(url, source)
            val root = parseRoot(text, rule, url)
            if (root is Element && !store.adapters.overridesReader(source)) BoodoReader.chapter(root)?.let { lazy ->
                val pages = BoodoReader.pages(lazy, url) { endpoint, body ->
                    fetch(endpoint, source, postBody = body, extraHeaders = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded", "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest"))
                }
                pages.forEach { legacyHeaders["${source.id}|$it"] = mapOf("Referer" to url) }
                return@withContext pages
            }
            for (alternative in SelectorSplitter.splitRules(rule.getString("pages"), "||")) {
                val images = LinkedHashSet<String>()
                for (selector in SelectorSplitter.splitRules(alternative, "&&")) {
                    val (_, attr) = SelectorSplitter.split(selector)
                    for (item in itemsFrom(root, selector)) {
                        var image = value(item, if (attr.isNotBlank() && item is Element) "@$attr" else rule.optString("image", "@src"))
                        if ((image.isBlank() || image.startsWith("data:")) && item is Element)
                            image = listOf("data-src", "data-original", "data-lazy-src", "src").firstNotNullOfOrNull { a ->
                                item.attr(a).takeIf { it.isNotBlank() && !it.startsWith("data:") }
                            }.orEmpty()
                        val replacements = rule.optJSONArray("imageReplace")
                        if (replacements != null) for (i in 0 until replacements.length()) {
                            val replacement = replacements.getJSONObject(i)
                            val from = replacement.getString("from")
                            require(from.isNotEmpty()) { "图片替换规则不能为空" }
                            image = image.replace(from, replacement.getString("to"))
                        }
                        if (image.isNotBlank() && !image.startsWith("data:") && !mangaPlaceholder(image)) images.add(resolve(url, image))
                    }
                }
                result.addAll(images)
                require(result.size <= 5000) { "单章图片超过 5000 张上限" }
                if (images.isNotEmpty()) break
            }
            val next = optionalValue(root, rule, "nextPage")
            if (next.isBlank()) return@withContext result.toList().also { require(it.isNotEmpty()) { "该章节没有图片，请检查 reader.pages / image 规则；不支持需要网页脚本、登录或验证的章节" } }
            url = resolve(url, next)
        }
        error("章节分页超过上限，已停止，未标记完整缓存；请检查 nextPage 规则")
    }

    private val backgroundImages = kotlinx.coroutines.sync.Semaphore(1)
    suspend fun prefetchImage(reference: String, sourceId: String, context: MangaImageContext? = null): File =
        backgroundImages.withPermit { imageFile(reference, sourceId, context = context, priority = MangaImagePriority.PREFETCH) }

    suspend fun imageFile(reference: String, sourceId: String, force: Boolean = false, cover: Boolean = false,
        context: MangaImageContext? = null, priority: MangaImagePriority = MangaImagePriority.VISIBLE,
        headers: Map<String, String>? = null,
        onStage: (String) -> Unit = {}): File = try {
        onStage("正在等待图片读取…")
        withTimeout(30_000) {
            if (cover) backgroundImages.withPermit { loadImageFile(reference, sourceId, force, cover, context, MangaImagePriority.COVER, headers, onStage) }
            else loadImageFile(reference, sourceId, force, cover, context, priority, headers, onStage)
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw java.io.IOException("图片加载超时，可重试图片或更换漫画源", timeout)
    }

    private suspend fun loadImageFile(reference: String, sourceId: String, force: Boolean, cover: Boolean,
        context: MangaImageContext?, priority: MangaImagePriority,
        headers: Map<String, String>?,
        onStage: (String) -> Unit): File = withContext(Dispatchers.IO) {
        if (reference.startsWith("file:")) return@withContext store.localImage(reference)
        require(!mangaPlaceholder(reference)) { "当前地址是加载占位图，请重新加载章节" }
        val file = store.cachedImage(sourceId, mangaHttpUrl(reference), cover, context)
        fun usableCache() = file.isFile && file.length() > 0 && runCatching {
            imageRuntime.checkCached(file); BaoziReader.checkImageNotice(file)
        }.isSuccess
        if (!force && usableCache()) { onStage("正在读取已验证的图片缓存…"); store.touchImage(file); return@withContext file }
        val source = resolveSource(sourceId)
        val decoderRule = MangaDecoder.rule(source.legacy)
        val decoder = MangaDecoder.identify(decoderRule)
        require(decoderRule.isBlank() || decoder != null) { "此源的图片解密器尚未适配，请更新漫画源或切换其他源" }
        val jobContext = currentCoroutineContext()
        val lock = imageLocks[(file.name.hashCode() and Int.MAX_VALUE) % imageLocks.size]
        lock.withLock {
            if (force || !usableCache()) {
                val temp = File(file.parentFile, file.name + ".part")
                imageScheduler.withPermit(priority, runCatching { URI(mangaHttpUrl(reference)).host.orEmpty() }.getOrDefault(sourceId)) {
                    val startedAt = imageRuntime.elapsedMillis()
                    for (attempt in 0..1) {
                    try {
                        runInterruptible {
                                onStage("正在连接图片服务器…")
                                requestImage(reference, source, headers ?: legacyHeaders["$sourceId|$reference"].orEmpty()).use { response ->
                                    if (!response.isSuccessful) throw MangaImageAddressException("图片请求失败（HTTP ${response.code}）", response.code)
                                    require(!mangaPlaceholder(response.request.url.toString())) {
                                        "图片服务器返回了站点提示图，请刷新章节或更换漫画源"
                                    }
                                    val type = response.body.contentType()?.toString().orEmpty()
                                    if (type.contains("text/html", true) || type.contains("application/json", true))
                                        throw MangaImageAddressException("图片地址返回了网页或接口错误，请检查源的图片规则和 Referer 请求头")
                                    require(response.body.contentLength() <= 24L * 1024 * 1024) { "漫画图片超过 24 MB" }
                                    response.body.byteStream().use { input -> temp.outputStream().use { output ->
                                        val buffer = ByteArray(8192); var size = 0L
                                        val total = response.body.contentLength()
                                        var reportedAt = 0L
                                        while (true) {
                                            jobContext.ensureActive()
                                            val n = input.read(buffer); if (n < 0) break
                                            size += n; require(size <= 24L * 1024 * 1024) { "漫画图片超过 24 MB" }
                                            output.write(buffer, 0, n)
                                            val now = imageRuntime.elapsedMillis()
                                            if (now - reportedAt >= 150 || size == total) {
                                                reportedAt = now
                                                onStage(if (total > 0) "正在下载图片 · ${(size * 100 / total).coerceIn(0, 100)}%"
                                                    else "正在下载图片 · ${size / 1024} KB")
                                            }
                                        }
                                    } }
                                }
                                BaoziReader.checkImageNotice(temp)
                                MangaImagePayload.normalize(temp)
                                decoder?.let { onStage("正在处理源配置的图片解码规则…"); imageRuntime.decode(temp, reference, it, cover) }
                                onStage("正在验证图片文件…")
                                imageRuntime.verify(temp)
                                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        loadingTimes.record(sourceId, imageRuntime.elapsedMillis() - startedAt)
                        break
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (attempt == 1 || !retriable(failure)) throw failure
                        delay(400)
                    } finally { temp.delete() }
                    }
                }
            }
        }
        store.touchImage(file)
        file
    }

    /** 源可用性检查：依次跑搜索 / 详情 / 图片三段规则，逐段报告结果。 */
    suspend fun test(source: MangaSource, keyword: String): MangaSourceTest {
        val started = System.currentTimeMillis()
        var results = 0; var chapters = 0; var images = 0; var decoded = 0; var sampled = 0
        var stage = MangaReadability.UNTESTED
        fun outcome(ok: Boolean, message: String, status: MangaReadability = stage) = MangaSourceTest(ok, message, results, chapters, images,
            readability = status, sampledImages = decoded, sampledChapters = sampled,
            elapsedMs = System.currentTimeMillis() - started, ruleVersion = ruleVersion(source))
        fun failureStatus(failure: Exception): MangaReadability {
            val message = failure.message.orEmpty()
            return when {
                (failure is MangaImageAddressException && failure.status in setOf(401, 403)) ||
                    Regex("HTTP (401|403)|登录|验证码|访问受限").containsMatchIn(message) -> MangaReadability.VERIFICATION
                Regex("尚未适配|不支持|解密器|未就绪").containsMatchIn(message) -> MangaReadability.UNSUPPORTED
                failure is java.net.UnknownHostException || failure is javax.net.ssl.SSLException ||
                    failure is java.net.ConnectException -> MangaReadability.UNREACHABLE
                else -> stage.takeUnless { it == MangaReadability.UNTESTED } ?: MangaReadability.FAILED
            }
        }
        try {
            val found = search(source, keyword.trim().ifBlank { source.name }, 1)
            results = found.size
            if (found.isEmpty()) return outcome(false, "搜索无样本，请换一个关键词；不据此判定整源失效", MangaReadability.SEARCHABLE)
            stage = MangaReadability.SEARCHABLE
            var selected: MangaBook? = null
            var firstDirectoryFailure: Exception? = null
            for (candidate in found.take(3)) {
                try {
                    val candidateBook = details(source, candidate)
                    if (candidateBook.chapters.isNotEmpty()) { selected = candidateBook; break }
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { if (firstDirectoryFailure == null) firstDirectoryFailure = failure }
            }
            val book = selected ?: throw (firstDirectoryFailure ?: IllegalStateException("前三个搜索候选均未提供可读目录"))
            chapters = book.chapters.size
            if (chapters == 0) return outcome(false, "搜索正常，但没有可读目录")
            stage = MangaReadability.DIRECTORY
            val failures = mutableListOf<String>()
            val failureStates = mutableListOf<MangaReadability>()
            for (index in listOf(0, chapters / 2, chapters - 1).distinct()) {
                try {
                    val chapter = book.chapters[index]
                    val refs = pages(source, chapter)
                    require(refs.isNotEmpty()) { "章节没有图片地址" }
                    images += refs.size; sampled++
                    for (page in listOf(0, refs.lastIndex).distinct()) {
                        try {
                            val image = imageFile(refs[page], source.id, force = true, context = MangaImageContext(book.url, chapter.url, page, ruleVersion(source)), priority = MangaImagePriority.PREFETCH,
                                headers = imageHeaders(source, chapter, refs[page]))
                            withContext(Dispatchers.IO) { imageRuntime.verify(image) }
                            decoded++
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { failures += "第 ${index + 1} 章第 ${page + 1} 页：${failure.message}"; failureStates += failureStatus(failure) }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { failures += "第 ${index + 1} 章：${failure.message}"; failureStates += failureStatus(failure) }
            }
            if (decoded > 0) return outcome(failures.isEmpty(), "抽样 $sampled 章，实际下载解码 $decoded 张图片" +
                failures.firstOrNull()?.let { "；部分失败：$it" }.orEmpty(), if (failures.isEmpty()) MangaReadability.READABLE else MangaReadability.PARTIAL)
            val status = listOf(MangaReadability.VERIFICATION, MangaReadability.UNSUPPORTED, MangaReadability.UNREACHABLE)
                .firstOrNull { it in failureStates } ?: MangaReadability.DIRECTORY
            return outcome(false, failures.firstOrNull() ?: "目录正常，但图片不可读", status)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val message = failure.message ?: "未知错误"
            return outcome(false, message, failureStatus(failure))
        }
    }

    /**
     * 带一次退避重试的请求。源站偶发 5xx / 连接被重置时直接报错体验很差，
     * 只对可安全重放的 GET 请求重试。
     */
    private suspend fun fetch(url: String, source: MangaSource?, max: Int = 8 * 1024 * 1024,
        postBody: String? = null, extraHeaders: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0..1) {
            try {
                val jobContext = currentCoroutineContext()
                return@withContext runInterruptible {
                    request(url, source, postBody = postBody, extraHeaders = extraHeaders).use { response ->
                        require(response.isSuccessful) { "漫画源请求失败（HTTP ${response.code}），请检查站点或源规则" }
                        require(response.body.contentLength() <= max) { "漫画源响应超过大小上限" }
                        val bytes = java.io.ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                jobContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                require(bytes.size() + n <= max) { "漫画源响应超过大小上限" }
                                bytes.write(buffer, 0, n)
                            }
                        }
                        val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        // 部分镜像站返回 base64(zlib(json))，统一在这里解码，上层只看到明文。
                        ResponseDecoder.decode(bytes.toByteArray().toString(charset))
                            .also { require(it.isNotBlank()) { "漫画站点返回空响应，请稍后重试或更换漫画源" } }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                last = failure
                // 4xx 是配置问题，重试无意义；只对网络异常与 5xx 退避重试一次。
                if (!retriable(failure) || attempt == 1) throw failure
                delay(400)
            }
        }
        throw last ?: IllegalStateException("漫画源请求失败")
    }

    private fun retriable(failure: Exception) = (failure is java.io.IOException && failure !is MangaImageAddressException && failure !is CompatibilityFailure && !MangaImportTls.certificateFailure(failure)) ||
        Regex("HTTP 5\\d\\d").containsMatchIn(failure.message.orEmpty())

    private fun requestImage(reference: String, source: MangaSource, headers: Map<String, String>): okhttp3.Response {
        val candidates = (store.adapters.imageCandidates(source, reference) + MangaSiteAdapters.imageCandidates(reference)).distinct()
        val chapterContext = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: source.baseUrl
        val adaptedHeaders = headers + store.adapters.imageHeaders(source, chapterContext, reference)
        if (candidates.size == 1) return request(reference, source, extraHeaders = adaptedHeaders, httpClient = imageClient)
        val original = URI(reference)
        val key = original.host.substringBefore('-').substringBefore('.')
        val preferred = imageRoutes[key]
        val ordered = candidates.sortedBy { if (URI(it).host == preferred) 0 else 1 }
        var last: Exception? = null
        for (candidate in ordered) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("图片请求已取消")
            try {
                val scoped = MangaRequestHeaders.scoped(adaptedHeaders, MangaRequestHeaders.sameOrigin(reference.toHttpUrl(), candidate.toHttpUrl()))
                val response = request(candidate, source, extraHeaders = scoped, httpClient = imageClient)
                val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                if (response.isSuccessful && !mangaPlaceholder(response.request.url.toString()) &&
                    (type.isEmpty() || type.startsWith("image/", true) ||
                    type.equals("application/octet-stream", true))) {
                    imageRoutes[key] = URI(candidate).host
                    return response
                }
                val code = response.code; response.close()
                if (code in setOf(401, 403)) throw MangaImageAddressException("图片访问受限（HTTP $code），请在原站确认访问权限", code)
                last = MangaImageAddressException(if (code in 200..299) "漫画图片线路返回的不是图片，请刷新章节链接"
                    else "漫画图片线路暂不可用（HTTP $code）", if (code in 200..299) 0 else code)
            } catch (failure: java.io.IOException) {
                if (Thread.currentThread().isInterrupted) throw failure
                // Preserve access/signature errors so the chapter refresh path can run immediately.
                // Trying every CDN first can exhaust the page deadline and hide the original 403.
                if (failure is MangaImageAddressException && failure.status in setOf(401, 403)) throw failure
                imageRoutes.remove(key, URI(candidate).host)
                last = failure
            }
        }
        throw last ?: java.io.IOException("漫画图片线路暂不可用，请稍后重试")
    }

    private fun request(url: String, source: MangaSource?, redirects: Int = 0, trail: MutableList<String>? = null,
        extraHeaders: Map<String, String> = emptyMap(), postBody: String? = null, httpClient: OkHttpClient = client,
        allowCredentials: Boolean = true): okhttp3.Response {
        mangaHttpUrl(url)
        val target = source?.let { store.adapters.requestUrl(it, url) } ?: url
        val credentials = allowCredentials && MangaRequestHeaders.sameOrigin(url.toHttpUrl(), target.toHttpUrl())
        val request = Request.Builder().url(target).header("User-Agent", "ShengZiMaoReader/1.4 Android")
        val sourceHeaders = source?.headers.orEmpty()
        source?.let {
            MangaRequestHeaders.scoped(sourceHeaders, credentials && MangaRequestHeaders.sameOrigin(it.baseUrl.toHttpUrl(), target.toHttpUrl()))
                .forEach { (key, value) -> request.header(key, value) }
        }
        MangaRequestHeaders.scoped(extraHeaders, credentials).forEach { (key, value) -> request.header(key, value) }
        if (postBody != null) request.post(postBody.toRequestBody())
        if (source != null && (sourceHeaders.keys + extraHeaders.keys).none { it.equals("Referer", true) }) request.header("Referer", source.baseUrl)
        val built = request.build()
        val response = executeRequest(built, source, httpClient)
        if (response.code in 300..399) {
            val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
            response.close()
            require(redirects < 4 && next != null) { "漫画源跳转无效或次数过多" }
            trail?.add(next)
            return request(next, source, redirects + 1, trail, extraHeaders, if (response.code in listOf(307, 308)) postBody else null, httpClient,
                credentials && MangaRequestHeaders.sameOrigin(target.toHttpUrl(), next.toHttpUrl()))
        }
        return response
    }

    /** Read-only source requests share import's verified TLS fallback; never replay a source POST. */
    private fun executeRequest(request: okhttp3.Request, source: MangaSource?, primary: OkHttpClient): okhttp3.Response {
        if (source == null || request.method != "GET") return primary.newCall(request).execute()
        val origin = "${request.url.scheme}://${request.url.host}:${request.url.port}"
        val compatible = if (primary === imageClient) imageCompatibleClient else importCompatibleClient
        if (origin in compatibleOrigins) return compatible.newCall(request).execute()
        try { return primary.newCall(request).execute() }
        catch (failure: java.io.IOException) {
            if (Thread.currentThread().isInterrupted || !MangaImportTls.canRetry(failure)) throw failure
            compatibleOrigins.add(origin)
            try { return compatible.newCall(request).execute() }
            catch (retry: java.io.IOException) {
                retry.addSuppressed(failure)
                throw CompatibilityFailure(request.url.host, retry)
            }
        }
    }

    private fun parseRoot(text: String, rule: JSONObject, url: String): Any {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") || rule.optString("type", "html") == "json" -> JSONObject(trimmed)
            else -> Jsoup.parse(text, url)
        }
    }

    private fun items(text: String, rule: JSONObject, key: String, url: String): List<Any> =
        itemsFrom(parseRoot(text, rule, url), rule.getString(key))

    private fun itemsFrom(root: Any, selector: String): List<Any> {
        for (alternative in SelectorSplitter.splitRules(selector, "||").ifEmpty { listOf("") }) {
            val items = SelectorSplitter.splitRules(alternative, "&&").ifEmpty { listOf("") }.flatMap { single ->
                when (root) {
                    is Element -> if (single.startsWith("$")) emptyList() else selectElements(root, single)
                    else -> when (val value = jsonPath(root, single)) {
                        is JSONArray -> (0 until value.length()).map { value.get(it) }
                        null, JSONObject.NULL -> emptyList()
                        else -> listOf(value)
                    }
                }
            }
            if (items.isNotEmpty()) return items.distinct()
        }
        return emptyList()
    }

    /**
     * 在 HTML 文档内按选择器取元素。
     *
     * 兼容 CSS 与 XPath：Legado 转换来的源大量使用 XPath。
     * jsoup 的 XPath 实现有缺口（`@class=foo` 不带引号取不到），
     * 这里先规整再交给 jsoup，属性取值统一放到 [value] 里处理。
     */
    private fun selectElements(root: Element, selector: String): List<Element> {
        val raw = selector.trim()
        if (raw.isEmpty()) return listOf(root)
        val (pathPart, attrPart) = SelectorSplitter.split(raw)
        val path = if (attrPart.isEmpty()) raw else pathPart.trim()
        if (path.isEmpty()) return listOf(root)
        if (!SelectorSplitter.isXPath(path)) {
            return runCatching { root.select(path) }.getOrElse { emptyList() }.toList()
        }
        val normalized = SelectorSplitter.normalizeXPath(path).let {
            if (root !is org.jsoup.nodes.Document && it.startsWith("//")) ".$it" else it
        }
        return runCatching {
            root.selectXpath(normalized).toList()
        }.getOrElse {
            runCatching { root.select(path) }.getOrElse { emptyList() }.toList()
        }
    }

    private fun value(item: Any, selector: String): String {
        for (alternative in SelectorSplitter.splitRules(selector, "||").ifEmpty { listOf("") }) {
            val result = valueSingle(item, alternative)
            if (result.isNotBlank()) return result
        }
        return ""
    }

    private fun valueSingle(item: Any, selector: String): String {
        if (item is Element) {
            if (selector.startsWith("$")) return ""
            val (css, attr) = SelectorSplitter.split(selector)
            val path = css.trim()
            if (path.isEmpty()) {
                // 只写了属性：取当前元素自身的属性。
                return if (attr.isBlank()) item.text() else item.attr(attr)
            }
            val element = selectElements(item, path).firstOrNull() ?: return ""
            return if (attr.isBlank()) element.text() else element.attr(attr)
        }
        if (selector.isBlank()) return if (item is String || item is Number) item.toString() else ""
        val value = jsonPath(item, selector)
        return if (value == null || value == JSONObject.NULL || value is JSONObject || value is JSONArray) "" else value.toString()
    }

    private fun optionalValue(item: Any, rule: JSONObject, key: String): String =
        rule.optString(key).takeIf { it.isNotBlank() }?.let { value(item, it) }.orEmpty()

    private fun jsonPath(root: Any, selector: String): Any? {
        val path = selector.removePrefix("$").removePrefix(".").replace("[", ".").replace("]", "")
        if (path.isBlank()) return root
        val parts = path.split('.')
        require(parts.size <= 32) { "JSON 路径超过 32 层上限" }
        var nodes = listOf(root)
        var projected = false
        for (part in parts) {
            if (part == "*") projected = true
            nodes = nodes.flatMap { current ->
                when {
                    part == "*" && current is JSONArray -> (0 until current.length()).map { current.get(it) }
                    current is JSONObject -> listOfNotNull(current.opt(part)).filter { it != JSONObject.NULL }
                    current is JSONArray && part.toIntOrNull() != null -> listOfNotNull(current.opt(part.toInt()))
                    else -> emptyList()
                }
            }
        }
        return if (projected) JSONArray(nodes) else nodes.firstOrNull()
    }
    private fun resolve(base: String, relative: String) = mangaHttpUrl(URI(base).resolve(relative.trim()).toString())
}

/** 源自检结果。 */
data class MangaSourceTest(
    val ok: Boolean,
    val message: String,
    val results: Int,
    val chapters: Int,
    val images: Int,
    val testedAt: Long = System.currentTimeMillis(),
    val readability: MangaReadability = if (ok) MangaReadability.READABLE else MangaReadability.FAILED,
    val sampledImages: Int = 0,
    val sampledChapters: Int = 0,
    val elapsedMs: Long = 0,
    val ruleVersion: String = "",
)
