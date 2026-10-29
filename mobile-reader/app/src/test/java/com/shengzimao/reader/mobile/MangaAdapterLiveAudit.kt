package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit manual command. Never runs as a JUnit test and never enters any APK. */
object MangaAdapterLiveAudit {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size >= 3) { "catalog.json output-directory python.exe [start] [end]" }
        val catalogFile = File(args[0]).absoluteFile
        val output = File(args[1]).absoluteFile.apply { mkdirs() }
        val catalog = JSONArray(catalogFile.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val start = args.getOrNull(3)?.toInt() ?: 0
        val end = minOf(args.getOrNull(4)?.toInt() ?: catalog.length(), catalog.length())
        val results = File(output, "results.jsonl")
        val gate = Semaphore(4)
        val lock = Any()
        val adapterFiles = File(output, "adapter-packs").listFiles()?.filter { it.extension == "json" }.orEmpty()
        val packs = adapterFiles.map { MangaAdapterPack.parse(it.readText(Charsets.UTF_8)) }
        println("AUDIT_START count=${end-start} decoder=Pillow androidRenderingVerified=false")
        (start until end).map { index -> async(Dispatchers.IO) { gate.withPermit {
            val row = inspect(index, catalog.getJSONObject(index), output, args[2], packs)
            synchronized(lock) { results.appendText(row.toString() + "\n", Charsets.UTF_8) }
            println("SOURCE_DONE index=$index status=${row.optString("status")} stage=${row.optString("stage")} images=${row.optInt("decodedImages")}")
        } } }.awaitAll()
        println("AUDIT_FINISHED count=${end-start}")
    }

    private suspend fun inspect(index: Int, raw: JSONObject, output: File, python: String, packs: List<MangaAdapterPack>): JSONObject {
        val started = System.nanoTime()
        val row = JSONObject().put("index", index).put("name", raw.optString("name", raw.optString("bookSourceName")))
            .put("baseUrl", raw.optString("baseUrl", raw.optString("bookSourceUrl")))
            .put("testedAt", java.time.Instant.now().toString()).put("androidRenderingVerified", false)
            .put("decoder", "Pillow").put("configurationSha256", mangaHash(raw.toString())).put("decodedImages", 0)
        var stage = "configuration"
        val folder = File(output, "cases/$index").apply { mkdirs() }
        val deadline = System.nanoTime() + 75_000_000_000L
        val trace = JSONArray()
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) call.cancel() else call.timeout().timeout(minOf(left, 9_000), TimeUnit.MILLISECONDS)
                }
            }).addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val event = JSONObject().put("stage", stage).put("host", response.request.url.host)
                    .put("path", response.request.url.encodedPath.take(180)).put("http", response.code)
                if (stage == "pages" && response.request.url.host in setOf("m.ac.qq.com", "ac.qq.com") && response.isSuccessful) {
                    val html = response.peekBody(2_000_000).string()
                    val expressions = Regex("""\(\+eval\("([^"\n]{1,120})"\)\)\.toString""")
                        .findAll(html).take(20).map { it.groupValues[1] }.toList()
                    event.put("nonceExpressions", JSONArray(expressions)).put("userAgent", response.request.header("User-Agent"))
                }
                synchronized(trace) { trace.put(event) }
                response
            }.build()
        try {
            val parsed = if (raw.has("baseUrl")) MangaSource.parse(raw) else MangaSource.importText(raw.toString()).single()
            val source = parsed.copy(enabled = true)
            val store = MangaStore(folder); store.saveSources(listOf(source))
            packs.forEach { store.adapters.install(it, store.sources()) }
            row.put("sourceId", source.id).put("ruleVersion", store.adapters.effective(source).ruleVersion)
            val crypto = store.adapters.effective(source).legacy?.let(LegacyRuleCrypto::inspect)
            require(crypto?.failure == null) { "规则暂不支持：${crypto?.failure}" }
            val runtime = DesktopMangaImages(python, File(".tools/adapter-audit/decode_image.py").absoluteFile)
            val engine = MangaEngine(store, client, runtime)
            stage = "search"
            val queries = when {
                source.name.contains("YYDS", true) -> listOf("道诡异仙", "一")
                source.name.contains("包子") -> listOf("一人之下", "道诡异仙")
                Regex("manga|webtoon|comicextra", RegexOption.IGNORE_CASE).containsMatchIn(source.name) -> listOf("love", "one")
                else -> listOf("一", "斗破苍穹")
            }
            var books = emptyList<MangaBook>()
            val usedQueries = JSONArray()
            for (query in queries) {
                usedQueries.put(query)
                books = engine.search(source, query, 1)
                if (books.isNotEmpty()) break
            }
            row.put("queries", usedQueries).put("searchResults", books.size)
            if (books.isEmpty()) return row.put("status", "NO_SAMPLE").put("message", "两次搜索无样本，不能判定图片可读")
            stage = "directory"
            var selected: MangaBook? = null
            val attempts = JSONArray()
            for (candidate in books.take(3)) {
                try {
                    val detailed = engine.details(source, candidate)
                    if (detailed.chapters.isNotEmpty()) { selected = detailed; break }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { attempts.put(JSONObject().put("book", candidate.title).put("message", failure.message.orEmpty().take(250))) }
                if (System.nanoTime() >= deadline) break
            }
            row.put("directoryAttempts", attempts)
            val book = selected ?: error("前三个候选均没有可读目录：${attempts.optJSONObject(0)?.optString("message").orEmpty()}")
            row.put("book", book.title).put("bookUrl", book.url).put("chapters", book.chapters.size)
            require(book.chapters.isNotEmpty()) { "没有有效目录" }
            val samples = JSONArray()
            row.put("imageSamples", samples)
            var failures = 0
            val digests = hashSetOf<String>()
            for (chapterIndex in listOf(0, book.chapters.size / 2, book.chapters.lastIndex).distinct()) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) { failures++; break }
                stage = "pages"
                val chapter = book.chapters[chapterIndex]
                val refs = try {
                    engine.pages(source, chapter).also { require(it.isNotEmpty()) { "章节图片列表为空" } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    failures++
                    samples.put(JSONObject().put("chapter", chapterIndex).put("error", failure.message.orEmpty().take(300))
                        .put("exception", failure.javaClass.simpleName))
                    continue
                }
                row.put("pages", row.optInt("pages") + refs.size)
                for (page in listOf(0, refs.lastIndex).distinct()) {
                    val sample = JSONObject().put("chapter", chapterIndex).put("page", page)
                    if (System.nanoTime() >= deadline) { sample.put("error", "检测时间预算已到"); samples.put(sample); failures++; break }
                    stage = "image"
                    try {
                        val image = engine.imageFile(refs[page], source.id, force = true,
                            context = MangaImageContext(book.url, chapter.url, page, engine.ruleVersion(source)),
                            priority = MangaImagePriority.PREFETCH, headers = engine.imageHeaders(source, chapter, refs[page]))
                        val decoded = runtime.inspect(image)
                        sample.put("width", decoded.getInt("width")).put("height", decoded.getInt("height"))
                            .put("bytes", image.length()).put("sha256", decoded.getString("sha256"))
                        require(decoded.getInt("width") > 100 && decoded.getInt("height") > 100 && image.length() > 2000) { "样本过小，尚不能确认为正文图" }
                        digests += decoded.getString("sha256")
                        row.put("decodedImages", row.optInt("decodedImages") + 1)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failures++; sample.put("error", failure.message.orEmpty().take(300)).put("exception", failure.javaClass.simpleName) }
                    samples.put(sample)
                }
            }
            row.put("imageSamples", samples).put("distinctImages", digests.size)
            row.put("status", when {
                row.optInt("decodedImages") > 0 && failures == 0 && (samples.length() < 2 || digests.size >= 2) -> "DESKTOP_DECODE_SAMPLED"
                row.optInt("decodedImages") > 0 -> "PARTIAL_IMAGE_CHECK"
                else -> "IMAGE_CHECK_FAILED"
            })
            row.put("message", "生产请求链路；桌面 Pillow 实际解码 ${row.optInt("decodedImages")} 张，失败 $failures；不代表 Android 显示通过")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val message = failure.message.orEmpty()
            val status = when {
                failure is java.net.UnknownHostException -> "DNS_FAILED"
                failure is javax.net.ssl.SSLException -> "TLS_FAILED"
                Regex("HTTP (401|403|429|451)|登录|验证码").containsMatchIn(message) -> "ACCESS_LIMITED"
                Regex("不支持|尚未适配|未就绪|解密器").containsMatchIn(message) -> "UNSUPPORTED_CONFIG"
                failure is java.net.SocketTimeoutException || failure is java.io.InterruptedIOException -> "TIMEOUT"
                Regex("HTTP [45][0-9][0-9]").containsMatchIn(message) -> "HTTP_FAILED"
                failure is java.io.IOException -> "NETWORK_FAILED"
                else -> "RULE_OR_CONTENT_FAILED"
            }
            row.put("status", status).put("message", message.take(600)).put("exception", failure.javaClass.simpleName)
        } finally {
            row.put("stage", stage).put("elapsedMs", (System.nanoTime() - started) / 1_000_000).put("httpTrace", trace)
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
        return row
    }
}

internal class DesktopMangaImages(private val python: String, private val helper: File) : MangaImageRuntime {
    fun inspect(file: File, transform: JSONObject? = null): JSONObject {
        val process = ProcessBuilder(python, "-X", "utf8", helper.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write((transform ?: JSONObject()).put("path", file.absolutePath).toString())
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("桌面图片解码超时")
        }
        val result = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = JSONObject(result)
        require(process.exitValue() == 0 && parsed.optBoolean("ok")) { "桌面解码失败：${parsed.optString("error")}" }
        return parsed
    }
    override fun checkCached(file: File) { inspect(file) }
    override fun verify(file: File) { inspect(file) }
    override fun decode(file: File, reference: String, spec: MangaDecoder.Spec, cover: Boolean) {
        if (spec.kind in setOf(MangaDecoder.Kind.MANWA_AES, MangaDecoder.Kind.BIKA_AES)) {
            if (runCatching { inspect(file) }.isSuccess) return
            file.writeBytes(MangaDecoder.decrypt(spec, file.readBytes())); inspect(file); return
        }
        if (cover) return
        val bounds = inspect(file)
        val width = bounds.getInt("width"); val height = bounds.getInt("height")
        val target = if (spec.kind == MangaDecoder.Kind.BAOZI_SIZE) MangaDecoder.targetSize(reference, width, height) else null
        val count = MangaDecoder.stripCount(spec, reference)
        if (target == null && (count == null || count == 1)) return
        val transform = JSONObject().put("action", "transform")
        if (target != null) transform.put("target", JSONArray(listOf(target.width, target.height))) else {
            val columns = spec.kind == MangaDecoder.Kind.XIANG_COLUMNS
            val slices = if (columns) MangaDecoder.columnSlices(width, requireNotNull(count)) else MangaDecoder.rowSlices(height, requireNotNull(count))
            transform.put("columns", columns).put("slices", JSONArray().also { a -> slices.forEach { a.put(JSONArray(listOf(it.source, it.target, it.length))) } })
        }
        inspect(file, transform)
    }
}
