package com.shengzimao.reader.mobile.ui

import android.content.Context
import com.shengzimao.reader.mobile.data.BatchItem
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.ChapterParser
import com.shengzimao.reader.mobile.data.Library
import com.shengzimao.reader.mobile.data.LibraryStore
import com.shengzimao.reader.mobile.data.OnlineBatch
import com.shengzimao.reader.mobile.data.OnlineChapter
import com.shengzimao.reader.mobile.data.onlineChapters
import com.shengzimao.reader.mobile.data.OnlineChapterCodec
import com.shengzimao.reader.mobile.data.OnlineMatcher
import com.shengzimao.reader.mobile.data.OnlineResult
import com.shengzimao.reader.mobile.data.OnlineUiState
import com.shengzimao.reader.mobile.data.NovelSourceGroups
import com.shengzimao.reader.mobile.data.NovelSearchFailure
import com.shengzimao.reader.mobile.data.LegadoNovelEngine
import com.shengzimao.reader.mobile.data.NovelHttpTransport
import com.shengzimao.reader.mobile.data.Updates
import com.shengzimao.reader.mobile.data.SonovelEngine
import com.shengzimao.reader.mobile.data.TomatoEngine
import com.shengzimao.reader.mobile.data.TomatoParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线搜书、下载入库与追更。
 *
 * 与桌面版一致的行为约定：
 * - 搜索、书源管理与后台下载串行；阅读使用独立会话，不等待整本下载；
 * - 批量下载最多 30 本、追更最多 200 本，严格串行；
 * - 搜索结果的相关性过滤与聚合排序在本地完成（引擎只负责抓源站原始结果）；
 * - 入库后记录 onlineUrl 与国家目录清单，为安全追更提供核对依据。
 */
class OnlineController(
    private val context: Context,
    private val store: LibraryStore,
    private val readLibrary: () -> Library,
    private val writeLibrary: (Library) -> Unit,
    /** 把下载到的正文写入书库；返回落库后的书 + 是否新增。 */
    private val saveDownloaded: (OnlineResult, text: String, chapters: List<OnlineChapter>) -> Boolean,
    private val notify: (String, Boolean) -> Unit,
    private val tomatoPage: Boolean = false,
    /** 在线下载的并发章节数（来自用户设置）。 */
    private val concurrencyProvider: () -> Int = { TomatoEngine.DEFAULT_CONCURRENCY },
) {
    private val _state = MutableStateFlow(OnlineUiState())
    val state: StateFlow<OnlineUiState> = _state.asStateFlow()

    private var engine: SonovelEngine? = null
    private var engineGroup: Int = -1
    private var readingEngine: SonovelEngine? = null
    private var readingEngineGroup = -1
    private val readingLock = kotlinx.coroutines.sync.Mutex()
    private val sourceGroups by lazy {
        NovelSourceGroups(java.io.File(context.filesDir, "novel-source-groups")) {
            context.assets.open("engine/rules/main.json").bufferedReader().use { it.readText() }
        }
    }
    private val tomato by lazy { TomatoEngine() }
    private val novelHttp by lazy { NovelHttpTransport() }
    private var cachedRulesGroup = -1
    private var cachedRules = emptyMap<String, JSONObject>()

    @Synchronized
    private fun groupRules(group: Int): Map<String, JSONObject> {
        if (cachedRulesGroup != group) {
            val array = sourceGroups.rules(group)
            cachedRules = (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("url") }
            cachedRulesGroup = group
        }
        return cachedRules
    }

    private fun legadoFor(group: Int, sourceKey: String): LegadoNovelEngine? {
        if (sourceKey.isBlank()) return null
        val rule = groupRules(group)[sourceKey]
            ?: error("此书原来的书源已不存在，请重新导入到分组 $group 或换源")
        require(rule.has("legado")) { "此书源的规则格式已变化，请重新搜索并换源" }
        return LegadoNovelEngine(rule, java.io.File(context.filesDir, "legado-novels/group-$group"), novelHttp::fetch)
    }

    /**
     * 引擎有全局可变状态（工作目录、AppConfig、HttpClientContext），
     * 而 ReaderViewModel 的各个入口是彼此独立的协程，仅靠 UI 上的 running 标记无法阻止并发。
     * 管理与下载任务在这把锁内执行；阅读有自己的锁、引擎类空间和工作目录。
     */
    private val taskLock = kotlinx.coroutines.sync.Mutex()

    private fun downloadConcurrency(): Int =
        runCatching { concurrencyProvider() }.getOrDefault(TomatoEngine.DEFAULT_CONCURRENCY).coerceIn(1, 8)

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!taskLock.tryLock()) throw IllegalStateException("已有在线任务正在运行，请等待完成。")
        try {
            return block()
        } finally {
            if (currentCoroutineContext().isActive.not()) {
                val wasSearching = _state.value.searching
                _state.value = _state.value.copy(running = false, searching = false, message = if (wasSearching) "搜索已停止，已返回的结果保留。" else "在线任务已停止，可重试。", error = "")
            }
            taskLock.unlock()
        }
    }

    private fun engineFor(group: Int): SonovelEngine {
        NovelSourceGroups.requireGroup(group)
        val existing = engine
        if (existing != null && existing.available && engineGroup == group) return existing
        if (group != 1) require(sourceGroups.entries(group).isNotEmpty()) { "分组 $group 尚未导入书源，请先导入。" }
        // Retain only one dex class loader; reading uses the book's saved group,
        // independently of the group currently selected in the search page.
        engine = null
        val created = SonovelEngine(context, if (group == 1) null else sourceGroups.sonovelRulesFile(group))
        engine = created
        engineGroup = group
        if (!created.available) {
            _state.value = _state.value.copy(engineReady = false)
            throw IllegalStateException("内置在线引擎未能加载：${created.loadError ?: "未知初始化错误"}")
        }
        _state.value = _state.value.copy(engineReady = true)
        return created
    }

    /** A download reconfigures its engine's global work directory; never share that instance with reading. */
    private fun readingEngineFor(group: Int): SonovelEngine {
        NovelSourceGroups.requireGroup(group)
        readingEngine?.takeIf { it.available && readingEngineGroup == group }?.let { return it }
        val created = SonovelEngine(context, if (group == 1) null else sourceGroups.sonovelRulesFile(group), readingSession = true)
        check(created.available) { "在线阅读引擎未能加载：${created.loadError ?: "未知初始化错误"}" }
        readingEngine = created
        readingEngineGroup = group
        return created
    }

    suspend fun loadSourceGroups() = taskLock.withLock { withContext(Dispatchers.IO) {
        refreshSourceGroups(sourceGroups.selected())
    } }

    private fun refreshSourceGroups(group: Int) {
        val entries = (1..5).map { sourceGroups.entries(it) }
        _state.value = _state.value.copy(sourceGroup = group, sourceCounts = entries.map { it.size },
            sourceEntries = entries[group - 1], selectedSourceUrls = sourceGroups.selectedSources(group), sourcesReady = true)
    }

    suspend fun selectSearchSources(urls: Set<String>?) = exclusive {
        _state.value = _state.value.copy(sourcesReady = false)
        try { withContext(Dispatchers.IO) {
            val group = _state.value.sourceGroup
            sourceGroups.selectSources(group, urls)
            refreshSourceGroups(group)
            _state.value = _state.value.copy(results = emptyList(), details = null, detailsToc = emptyList(), detailsIndex = -1,
                searchFailures = emptyList(), searchCompleted = 0, searchTotal = 0,
                revision = _state.value.revision + 1, error = "", message = "搜索范围已更新，下次搜索使用所选书源。")
        } } finally { _state.value = _state.value.copy(sourcesReady = true) }
    }

    suspend fun selectSourceGroup(group: Int) = exclusive {
        NovelSourceGroups.requireGroup(group)
        if (_state.value.sourceGroup == group && _state.value.sourcesReady) return@exclusive
        _state.value = _state.value.copy(sourcesReady = false)
        try { withContext(Dispatchers.IO) {
            sourceGroups.select(group)
            refreshSourceGroups(group)
            _state.value = _state.value.copy(results = emptyList(), details = null, detailsToc = emptyList(),
                searchFailures = emptyList(), searchCompleted = 0, searchTotal = 0,
                detailsIndex = -1, batch = null, error = "", elapsedMs = 0, revision = _state.value.revision + 1,
                message = if (group == 1) "分组 1 使用内置书源。" else "已切换到分组 $group，仅搜索此组导入的书源。")
        } } finally { _state.value = _state.value.copy(sourcesReady = true) }
    }

    suspend fun importSources(group: Int, read: () -> String) = exclusive { withContext(Dispatchers.IO) {
        require(!tomatoPage && group in 2..5) { "请在聚合搜书的分组 2–5 导入书源。" }
        _state.value = _state.value.copy(importingSources = true, error = "")
        try {
            val text = runInterruptible { read() }
            currentCoroutineContext().ensureActive()
            val result = sourceGroups.import(group, text)
            synchronized(this@OnlineController) {
                if (cachedRulesGroup == group) { cachedRulesGroup = -1; cachedRules = emptyMap() }
            }
            readingLock.withLock {
                if (readingEngineGroup == group) { readingEngine = null; readingEngineGroup = -1 }
            }
            if (engineGroup == group) { engine = null; engineGroup = -1 }
            refreshSourceGroups(_state.value.sourceGroup)
            _state.value = _state.value.copy(results = emptyList(), details = null, detailsToc = emptyList(),
                searchFailures = emptyList(), searchCompleted = 0, searchTotal = 0,
                detailsIndex = -1, batch = null, elapsedMs = 0, revision = _state.value.revision + 1)
            report(result.message(group))
        } finally {
            _state.value = _state.value.copy(importingSources = false)
        }
    } }

    fun report(text: String, error: Boolean = false) {
        _state.value = _state.value.copy(message = text, error = if (error) text else "")
        notify(text, error)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = "")
    }

    // ---------------- 搜索 ----------------

    suspend fun search(keyword: String, history: List<String>) = exclusive { searchLocked(keyword, history) }

    suspend fun retryFailedSources() = exclusive {
        val snapshot = _state.value
        require(!tomatoPage && snapshot.searchFailures.isNotEmpty()) { "没有需要重试的失败书源。" }
        searchLocked(snapshot.keyword, snapshot.searchHistory, snapshot.searchFailures.map { it.sourceUrl }.toSet())
    }

    private suspend fun searchLocked(keyword: String, history: List<String>, retryUrls: Set<String>? = null) = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        if (query.isEmpty() || query.length > 120) throw IllegalArgumentException("请输入 1–120 个字符的书名或作者。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        if (!tomatoPage && !_state.value.sourcesReady) refreshSourceGroups(sourceGroups.selected())
        val group = _state.value.sourceGroup
        if (!tomatoPage) require(sourceGroups.entries(group).any { it.searchable && (_state.value.selectedSourceUrls == null || it.url in _state.value.selectedSourceUrls.orEmpty()) }) {
            if (_state.value.sourceCounts[group - 1] == 0) "分组 $group 还没有书源，请先导入书源，再搜索。"
            else "分组 $group 没有选中的可搜索书源，请打开搜索范围选择书源。"
        }
        val started = System.currentTimeMillis()
        val retained = if (retryUrls == null) emptyList() else _state.value.results
        val previousFailures = if (retryUrls == null) emptyList() else _state.value.searchFailures
        _state.value = _state.value.copy(
            running = true,
            searching = true,
            keyword = query,
            results = retained,
            searchFailures = previousFailures, searchCompleted = 0, searchTotal = 0,
            message = "正在搜索，请稍候…",
            error = "",
            batch = null,
            details = null,
            detailsToc = emptyList(),
            detailsIndex = -1,
            elapsedMs = 0,
            revision = _state.value.revision + 1,
            searchHistory = (listOf(query) + history.filter { it != query }).take(12),
        )
        try {
            val searchContext = currentCoroutineContext()
            val revision = _state.value.revision
            val streamed = com.shengzimao.reader.mobile.data.StreamingNovelResults(query)
            streamed.append(retained)
            val raw = if (tomatoPage) tomato.search(query) else runInterruptible {
                searchSources(group, query, retryUrls ?: _state.value.selectedSourceUrls, previousFailures) { books, completed, total, failures ->
                    searchContext.ensureActive()
                    if (_state.value.revision == revision) {
                        val visible = streamed.append(books)
                        _state.value = _state.value.copy(results = visible,
                            searchFailures = failures, searchCompleted = completed, searchTotal = total,
                            message = "已搜索 $completed/$total 个书源 · ${visible.size} 条结果" +
                                (if (failures.isEmpty()) "" else " · ${failures.size} 个失败") + "，其他书源继续搜索…",
                            elapsedMs = System.currentTimeMillis() - started)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val filtered = (if (tomatoPage) raw else streamed.append(raw)).take(500)
            _state.value = _state.value.copy(
                running = false,
                searching = false,
                results = filtered,
                message = if (_state.value.searchFailures.isNotEmpty()) {
                    "${if (retryUrls == null) "搜索结束" else "重试结束"}，找到 ${filtered.size} 条相关结果；${_state.value.searchFailures.size} 个书源失败，可查看原因并重试。"
                } else if (retryUrls != null) {
                    "失败书源重试完成，当前共 ${filtered.size} 条相关结果。"
                } else if (filtered.isEmpty()) {
                    "未找到与书名或作者匹配的结果，请缩短书名、检查作者，或更换关键词。"
                } else {
                    "搜索完成，找到 ${filtered.size} 条相关结果（已过滤 ${raw.size - filtered.size} 条无关或无效结果）。"
                },
                elapsedMs = System.currentTimeMillis() - started,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // 把引擎自检信息一并带出，避免用户只看到一句无上下文的类名。
            val hint = engine?.takeIf { it.available }?.diagnostics().orEmpty()
            val detail = error.message ?: "搜索失败"
            _state.value = _state.value.copy(
                running = false,
                searching = false,
                error = if (hint.isBlank()) detail else "$detail（$hint）",
                message = "任务未完成。",
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }

    /** Publish each source immediately; slow sources cannot hide the completed ones. */
    private fun searchSources(group: Int, query: String, selection: Set<String>?, previousFailures: List<NovelSearchFailure>,
        publish: (List<OnlineResult>, Int, Int, List<NovelSearchFailure>) -> Unit): List<OnlineResult> {
        val entries = sourceGroups.searchEntries(group, selection)
        val selectedUrls = entries.map { it.url }.toSet()
        val native = groupRules(group).values.filter {
            it.has("legado") && com.shengzimao.reader.mobile.data.NovelLegadoRules.searchable(it) &&
                it.optString("url") in selectedUrls
        }
        val sonovel = entries.filter { it.searchable && it.format == "SoNovel" }
        val sonovelCount = sonovel.size
        val total = native.size + sonovelCount
        val completed = java.util.concurrent.atomic.AtomicInteger()
        val results = ArrayList<OnlineResult>()
        val failures = LinkedHashMap(previousFailures.associateBy { it.sourceUrl })
        val finished = HashSet<String>()
        val guard = Any()
        fun complete(url: String, name: String, error: String?) {
            synchronized(guard) {
                finished.add(url)
                if (error != null) failures[url] = NovelSearchFailure(url, name.ifBlank { url }, error.ifBlank { "书源请求失败" }.take(500)) else failures.remove(url)
            }
        }
        fun accept(books: List<OnlineResult>, count: Int = 1) = synchronized(guard) {
            val marked = books.map { it.copy(sourceGroup = group) }
            results.addAll(marked.take((5000 - results.size).coerceAtLeast(0)))
            publish(marked, completed.addAndGet(count), total, failures.values.toList())
        }
        // The outer runInterruptible owns cancellation. Cancel every queued/running native request on exit.
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = ArrayList<java.util.concurrent.Callable<Unit>>()
            if (sonovelCount > 0) tasks += java.util.concurrent.Callable<Unit> {
                var previous = 0
                try {
                    engineFor(group).search(query, sourceUrls = selectedUrls, onSourceComplete = ::complete) { items, done, _ ->
                        val advance = (done - previous).coerceAtLeast(0); previous = done
                        accept(OnlineResult.listFrom(items), advance)
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                    val remaining = synchronized(guard) { sonovel.filter { it.url !in finished } }
                    remaining.forEach { complete(it.url, it.name, error.message ?: "引擎请求失败") }
                    accept(emptyList(), remaining.size)
                }
            }
            val cursor = java.util.concurrent.atomic.AtomicInteger()
            repeat(minOf(native.size, if (sonovelCount > 0) 3 else 4)) {
                tasks += java.util.concurrent.Callable<Unit> {
                    while (true) {
                        val index = cursor.getAndIncrement()
                        if (index >= native.size) break
                        val rule = native[index]
                        try {
                            val books = LegadoNovelEngine(rule, java.io.File(context.filesDir, "legado-novels/group-$group"), novelHttp::fetch).search(query)
                            complete(rule.getString("url"), rule.optString("name"), null)
                            accept(books)
                        } catch (error: Exception) {
                            if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                            complete(rule.getString("url"), rule.optString("name"), error.message ?: "书源请求失败")
                            accept(emptyList())
                        }
                    }
                }
            }
            workers.invokeAll(tasks).forEach { it.get() }
            return results.toList()
        } finally { workers.shutdownNow() }
    }

    // ---------------- 详情 ----------------

    suspend fun details(index: Int) = exclusive { detailsLocked(index) }

    private suspend fun detailsLocked(index: Int) = withContext(Dispatchers.IO) {
        val book = _state.value.results.getOrNull(index) ?: throw IllegalArgumentException("请从当前结果选择书籍。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        _state.value = _state.value.copy(running = true, message = "正在读取书籍详情和目录…", error = "", details = null, detailsToc = emptyList(), detailsIndex = -1)
        try {
            val details = detailsFor(book.url, book.sourceGroup, book.sourceKey)
            val merged = backfill(book, details.book)
            _state.value = _state.value.copy(
                running = false,
                results = _state.value.results.toMutableList().also { it[index] = merged },
                details = merged.copy(chapterCount = details.chapterCount),
                detailsToc = details.toc,
                detailsIndex = index,
                message = "详情与目录已读取。",
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.value = _state.value.copy(running = false, error = error.message ?: "读取详情失败", message = "任务未完成。")
        }
    }

    fun closeDetails() {
        _state.value = _state.value.copy(details = null, detailsToc = emptyList(), detailsIndex = -1)
    }

    private fun backfill(previous: OnlineResult, fresh: OnlineResult): OnlineResult = OnlineResult(
        bookName = fresh.bookName.ifBlank { previous.bookName },
        author = fresh.author.ifBlank { previous.author },
        url = fresh.url.ifBlank { previous.url },
        sourceId = if (fresh.sourceId >= 0) fresh.sourceId else previous.sourceId,
        sourceName = fresh.sourceName.ifBlank { previous.sourceName },
        category = fresh.category.ifBlank { previous.category },
        intro = fresh.intro.ifBlank { previous.intro },
        latestChapter = fresh.latestChapter.ifBlank { previous.latestChapter },
        lastUpdateTime = fresh.lastUpdateTime.ifBlank { previous.lastUpdateTime },
        status = fresh.status.ifBlank { previous.status },
        wordCount = fresh.wordCount.ifBlank { previous.wordCount },
        matchScore = previous.matchScore,
        matchType = previous.matchType,
        chapterCount = fresh.chapterCount.takeIf { it > 0 } ?: previous.chapterCount,
        sourceGroup = previous.sourceGroup,
        sourceKey = previous.sourceKey,
    )

    // ---------------- 下载入库 ----------------

    suspend fun download(index: Int, format: String) = exclusive { downloadLocked(index, format) }

    private suspend fun downloadLocked(index: Int, format: String) = withContext(Dispatchers.IO) {
        val book = _state.value.results.getOrNull(index) ?: throw IllegalArgumentException("请从当前结果选择书籍。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        _state.value = _state.value.copy(running = true, message = "正在下载《${book.bookName}》，完成后自动导入。", error = "")
        try {
            val added = importDownloaded(book, format)
            _state.value = _state.value.copy(
                running = false,
                message = if (added) "《${book.bookName}》已下载并加入书库。" else "《${book.bookName}》已在书库中，已跳过。",
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.value = _state.value.copy(running = false, error = error.message ?: "下载失败", message = "任务未完成。")
        }
    }

    suspend fun downloadMany(indexes: List<Int>, format: String) = exclusive { downloadManyLocked(indexes, format) }

    private suspend fun downloadManyLocked(indexes: List<Int>, format: String) = withContext(Dispatchers.IO) {
        if (indexes.isEmpty()) throw IllegalArgumentException("请先勾选要下载的书籍。")
        if (indexes.size > 30) throw IllegalArgumentException("批量下载一次最多 30 本。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        // 先固定这一批的结果快照：期间用户重新搜索会让 index 指向别的书。
        val snapshot = indexes.mapNotNull { index -> _state.value.results.getOrNull(index)?.let { index to it } }
        if (snapshot.isEmpty()) throw IllegalStateException("请先勾选要下载的书籍。")
        val items = snapshot.map { (index, book) ->
            BatchItem(index = index, bookName = book.bookName, sourceName = book.sourceName)
        }
        var batch = OnlineBatch(kind = "download", format = format, items = items)
        _state.value = _state.value.copy(running = true, batch = batch, message = "正在下载 ${items.size} 本（批量），完成后自动导入。", error = "")
        val updated = items.toMutableList()
        for ((position, item) in items.withIndex()) {
            updated[position] = item.copy(state = "downloading")
            _state.value = _state.value.copy(batch = batch.copy(items = updated.toList()))
            val book = snapshot.firstOrNull { it.first == item.index }?.second
            updated[position] = try {
                if (book == null) throw IllegalStateException("搜索结果已变化。")
                val added = importDownloaded(book, format)
                item.copy(state = if (added) "done" else "skipped", message = if (added) "已加入书库" else "已在书库")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                item.copy(state = "failed", message = error.message ?: "下载失败")
            }
            batch = batch.copy(items = updated.toList())
            _state.value = _state.value.copy(batch = batch)
        }
        _state.value = _state.value.copy(
            running = false,
            message = "批量下载结束：成功 ${batch.done} 本，跳过 ${batch.items.count { it.state == "skipped" }} 本，失败 ${batch.failed} 本。",
        )
    }

    private suspend fun detailsFor(url: String, group: Int = 1, sourceKey: String = "", reading: Boolean = false): Updates.Details =
        if (sourceKey.isBlank() && TomatoParser.bookId(url).isNotEmpty()) tomato.details(url)
        else if (sourceKey.isNotBlank()) runInterruptible { requireNotNull(legadoFor(group, sourceKey)).details(url) }.let {
            it.copy(book = it.book.copy(sourceGroup = group, sourceKey = sourceKey))
        }
        else runInterruptible { Updates.validateDetails((if (reading) readingEngineFor(group) else engineFor(group)).details(url)) }.let {
            it.copy(book = it.book.copy(sourceGroup = group))
        }

    suspend fun readingDetails(url: String, group: Int = 1, sourceKey: String = ""): Updates.Details = readingLock.withLock { withContext(Dispatchers.IO) { detailsFor(url, group, sourceKey, reading = true) } }

    suspend fun readChapter(bookUrl: String, chapter: OnlineChapter, group: Int = 1, sourceKey: String = ""): String = readingLock.withLock {
        withContext(Dispatchers.IO) {
            if (sourceKey.isBlank() && TomatoParser.bookId(bookUrl).isNotEmpty()) tomato.chapters(listOf(chapter), 1)
            else if (sourceKey.isNotBlank()) runInterruptible { requireNotNull(legadoFor(group, sourceKey)).chapter(bookUrl, chapter) }
            else runInterruptible { readingEngineFor(group).readChapter(bookUrl, chapter) }
        }
    }

    suspend fun downloadQueued(book: OnlineResult, format: String, progress: (Int, Int, String) -> Unit): Boolean =
        taskLock.withLock { withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(running = true, queuedDownload = true, message = "正在下载《${book.bookName}》，进度见下载管理。", error = "")
            try {
                importDownloaded(book, format, progress).also {
                    _state.value = _state.value.copy(message = if (it) "《${book.bookName}》已加入书库。" else "《${book.bookName}》已在书库中。")
                }
            } finally { _state.value = _state.value.copy(running = false, queuedDownload = false) }
        } }

    /**
     * 按选定的章节列表下载番茄正文。
     *
     * 章节选择下载时只读用户勾选的部分，但入库仍以**完整在线目录**为准：
     * 否则本地章节数会少于源站目录，后续追更无法安全核对。
     */
    private suspend fun downloadTomatoChapters(
        book: OnlineResult,
        details: Updates.Details,
        selected: List<OnlineChapter>,
        progress: (Int, Int, String) -> Unit,
    ): Boolean {
        require(selected.isNotEmpty()) { "没有选择任何章节。" }
        val started = System.currentTimeMillis()
        var lastReport = 0L
        val checkpoint = com.shengzimao.reader.mobile.data.ChapterDownloadCache(java.io.File(context.filesDir,
            "downloads/tomato-parts/" + com.shengzimao.reader.mobile.data.mangaHash(book.url)))
        val text = tomato.chapters(selected, downloadConcurrency(), checkpoint) { done, total ->
            val now = System.currentTimeMillis()
            if (done == total || now - lastReport >= 200) {
                lastReport = now
                _state.value = _state.value.copy(message = "正在读取公开正文：$done / $total 章…")
                val elapsed = (now - started).coerceAtLeast(1)
                val rate = done * 1000.0 / elapsed
                progress(done, total, "已读取 $done / $total 章 · %.1f 章/秒".format(rate))
            }
        }
        currentCoroutineContext().ensureActive()
        return saveDownloaded(backfill(book, details.book), text, details.toc).also { if (it) checkpoint.clear() }
    }

    /** 按章节列表下载并入库。仅番茄支持按章选择；其他源退化为整本下载。 */
    suspend fun downloadChapters(book: OnlineResult, chapters: List<OnlineChapter>, format: String) =
        taskLock.withLock { withContext(Dispatchers.IO) {
            require(chapters.isNotEmpty()) { "请至少选择一个章节。" }
            _state.value = _state.value.copy(
                running = true, queuedDownload = true,
                message = "正在下载《${book.bookName}》的 ${chapters.size} 章，进度见下载管理。", error = "",
            )
            try {
                val added = if (book.sourceKey.isBlank() && book.sourceId == TomatoParser.SOURCE_ID) {
                    // 已入库的同书直接跳过，避免重复下载。
                    if (readLibrary().books.any { it.onlineUrl == book.url && store.exists(it) }) false
                    else {
                        val details = tomato.details(book.url)
                        val valid = chapters.filter { wanted -> details.toc.any { it.url == wanted.url } }
                        require(valid.isNotEmpty()) { "所选章节已不在源站目录中，请重新读取详情。" }
                        downloadTomatoChapters(book, details, valid) { _, _, _ -> }
                    }
                } else {
                    // 非番茄源没有稳定的按章接口，按整本处理并说明。
                    importDownloaded(book, format) { _, _, _ -> }
                }
                _state.value = _state.value.copy(message = if (added) "《${book.bookName}》已加入书库。" else "《${book.bookName}》已在书库中。")
                added
            } finally {
                _state.value = _state.value.copy(running = false, queuedDownload = false)
            }
        } }

    private suspend fun importDownloaded(book: OnlineResult, format: String, progress: (Int, Int, String) -> Unit = { _, _, _ -> }): Boolean {
        if (readLibrary().books.any { it.onlineUrl == book.url && it.onlineSourceGroup == book.sourceGroup && it.onlineSourceKey == book.sourceKey && store.exists(it) }) return false
        if (book.sourceKey.isBlank() && book.sourceId == TomatoParser.SOURCE_ID) {
            val details = tomato.details(book.url)
            return downloadTomatoChapters(book, details, details.toc, progress)
        }
        legadoFor(book.sourceGroup, book.sourceKey)?.let { native ->
            val details = runInterruptible { native.details(book.url) }
            val checkpoint = com.shengzimao.reader.mobile.data.ChapterDownloadCache(java.io.File(context.filesDir,
                "downloads/legado-parts/" + com.shengzimao.reader.mobile.data.mangaHash(NovelSourceGroups.identity(book.url, book.sourceGroup, book.sourceKey))))
            val text = native.chapters(book.url, details.toc, checkpoint, downloadConcurrency(), progress)
            currentCoroutineContext().ensureActive()
            return saveDownloaded(backfill(book, details.book), text, details.toc).also { if (it) checkpoint.clear() }
        }
        val engine = engineFor(book.sourceGroup)
        val result = coroutineScope {
            val monitor = launch {
                while (isActive) {
                    delay(400)
                    val counts = engine.downloadProgress()
                    if (counts.size == 2 && counts[1] > 0) progress(counts[0], counts[1], "已保存 ${counts[0]} / ${counts[1]} 章")
                }
            }
            try { runInterruptible { engine.download(book.url, format, downloadConcurrency()) } } finally { monitor.cancel() }
        }
        try {
            // 书源规则不可信：确认引擎返回的路径确实落在本次任务目录内。
            val jobRoot = result.job.canonicalPath
            val actual = result.text.canonicalPath
            if (!actual.startsWith(jobRoot + java.io.File.separator)) throw IllegalStateException("引擎返回了不允许的文件路径。")
            if (result.text.length() > 128L * 1024 * 1024) throw IllegalStateException("文件超过 128 MB 上限，已跳过导入。")
            val bytes = result.text.readBytes()
            val text = if (com.shengzimao.reader.mobile.data.EpubParser.isEpub(bytes)) {
                com.shengzimao.reader.mobile.data.EpubParser.parse(bytes, book.bookName).text
            } else bytes.toString(Charsets.UTF_8)
            var details: Updates.Details? = null
            result.metadata?.let { file ->
                if (file.length() <= 16L * 1024 * 1024) {
                    details = runCatching { Updates.validateDetails(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
                }
            }
            val chapters = details?.toc ?: emptyList()
            val merged = book.copy(
                intro = details?.book?.intro ?: book.intro,
                category = details?.book?.category ?: book.category,
                latestChapter = details?.book?.latestChapter ?: book.latestChapter,
                lastUpdateTime = details?.book?.lastUpdateTime ?: book.lastUpdateTime,
                status = details?.book?.status ?: book.status,
                chapterCount = chapters.size,
            )
            val enriched = if (merged.author.isBlank()) merged.copy(author = details?.book?.author.orEmpty()) else merged
            return saveDownloaded(enriched, text, chapters)
        } finally {
            result.cleanup()
        }
    }

    // ---------------- 追更 ----------------

    suspend fun updateBooks(ids: List<String>) = exclusive { updateBooksLocked(ids) }

    private suspend fun updateBooksLocked(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) throw IllegalArgumentException("没有需要检查更新的书籍。")
        if (ids.size > 200) throw IllegalArgumentException("一次最多检查 200 本。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        val library = readLibrary()
        val targets = ids.mapNotNull { id -> library.books.firstOrNull { it.id == id } }
        if (targets.isEmpty()) throw IllegalArgumentException("没有找到要追更的书籍。")
        if (targets.any { it.onlineUrl.isEmpty() }) throw IllegalArgumentException("本地书籍没有原站链接，无法追更。")
        val items = targets.map { BatchItem(id = it.id, bookName = it.title) }
        var batch = OnlineBatch(kind = "update", items = items)
        _state.value = _state.value.copy(running = true, batch = batch, message = "正在检查追更书籍…", error = "")
        val updated = items.toMutableList()
        for ((position, item) in items.withIndex()) {
            updated[position] = item.copy(state = "downloading", message = "检查目录")
            _state.value = _state.value.copy(batch = batch.copy(items = updated.toList()))
            updated[position] = try {
                val added = updateOne(item.id)
                item.copy(state = "done", message = if (added > 0) "新增 $added 章" else "已是最新")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                item.copy(state = "failed", message = error.message ?: "检查失败")
            }
            batch = batch.copy(items = updated.toList())
            _state.value = _state.value.copy(batch = batch)
        }
        _state.value = _state.value.copy(running = false, message = "追更结束：完成 ${batch.done} 本，失败 ${batch.failed} 本。")
    }

    /** 单本追更：拉目录 → 核对 → 下载新增章 → 拼接替换正文（任何失败都保持原文）。返回新增章节数。 */
    private suspend fun updateOne(bookId: String): Int {
        val library = readLibrary()
        val book = library.books.firstOrNull { it.id == bookId } ?: throw IllegalStateException("书籍不存在。")
        if (book.onlineOnly) {
            val details = detailsFor(book.onlineUrl, book.onlineSourceGroup, book.onlineSourceKey)
            require(details.toc.isNotEmpty()) { "源站目录为空，已保留旧目录" }
            val known = book.onlineChapters.map { it.url }.toSet()
            val added = details.toc.count { it.url !in known }
            val anchor = com.shengzimao.reader.mobile.data.ChapterAnchor.match(book.onlineChapters, details.toc, book.position)
            val current = readLibrary()
            writeLibrary(current.copy(books = current.books.map { item -> if (item.id != bookId) item else item.copy(
                onlineManifest = OnlineChapterCodec.encode(details.toc), chapters = details.toc.mapIndexed { i, c -> com.shengzimao.reader.mobile.data.Chapter(c.title, i) },
                length = details.toc.size, position = anchor.index, lastUpdateCheckAt = System.currentTimeMillis(), newChapterCount = maxOf(item.newChapterCount, added),
                latestChapter = details.book.latestChapter) }))
            return added
        }
        if (!store.exists(book)) throw IllegalStateException("正文缺失，请先重新关联或恢复备份。")
        val details = detailsFor(book.onlineUrl, book.onlineSourceGroup, book.onlineSourceKey)
        val localChapters = book.chapters.ifEmpty { ChapterParser.find(store.readText(book)) }
        val plan = Updates.planUpdate(book, localChapters, details)
        if (plan.chapters.isEmpty()) {
            writeLibrary(
                library.copy(
                    books = library.books.map {
                        if (it.id != bookId) it else it.copy(
                            onlineManifest = OnlineChapterCodec.encode(details.toc),
                            lastUpdateCheckAt = System.currentTimeMillis(),
                            newChapterCount = 0,
                        )
                    },
                ),
            )
            return 0
        }
        val array = JSONArray().also { target ->
            plan.chapters.forEach { target.put(JSONObject().put("title", it.title).put("url", it.url)) }
        }
        val addition = if (book.onlineSourceKey.isBlank() && TomatoParser.bookId(book.onlineUrl).isNotEmpty()) {
            tomato.chapters(plan.chapters)
        } else if (book.onlineSourceKey.isNotBlank()) {
            requireNotNull(legadoFor(book.onlineSourceGroup, book.onlineSourceKey)).chapters(book.onlineUrl, plan.chapters,
                com.shengzimao.reader.mobile.data.ChapterDownloadCache(java.io.File(context.filesDir,
                    "downloads/legado-parts/" + com.shengzimao.reader.mobile.data.mangaHash(NovelSourceGroups.identity(book.onlineUrl, book.onlineSourceGroup, book.onlineSourceKey)))),
                downloadConcurrency()) { _, _, _ -> }
        } else {
            val data = runInterruptible { engineFor(book.onlineSourceGroup).update(book.onlineUrl, plan.start, array) }
            if (data.optInt("count") != plan.chapters.size) throw IllegalStateException("新增章节未完整返回，已保留原正文。")
            val additionFile = java.io.File(data.optString("file"))
            if (!additionFile.isFile) throw IllegalStateException("新增正文文件缺失，已保留原正文。")
            try { additionFile.readText(Charsets.UTF_8) } finally { additionFile.delete() }
        }
        currentCoroutineContext().ensureActive()
        val original = store.readText(book)
        if (book.digest.isNotEmpty() && LibraryStore.digest(original) != book.digest) {
            throw IllegalStateException("本地正文已被修改，请重新关联后再更新。")
        }
        val merged = Updates.merge(original, addition, details.toc)
        // 用新文件名写入：覆盖同名的旧文件会让正在阅读的页面读到半写状态。
        val previousFile = book.file
        val fileName = store.writeText(java.util.UUID.randomUUID().toString(), merged.text)
        val current = readLibrary()
        // 网络请求期间这本书可能已被删除或换过文件，此时放弃写入并清理刚生成的文件。
        val survivor = current.books.firstOrNull { it.id == bookId }
        if (survivor == null || survivor.file != previousFile) {
            runCatching { java.io.File(store.booksDir, fileName).delete() }
            throw IllegalStateException("书籍已在追更期间被修改，本次更新已放弃。")
        }
        val retired = (survivor.updateBackupFiles + previousFile).distinct()
        writeLibrary(
            current.copy(
                books = current.books.map {
                    if (it.id != bookId) it else it.copy(
                        file = fileName,
                        length = merged.text.length,
                        digest = LibraryStore.digest(merged.text),
                        chapters = merged.chapters,
                        onlineManifest = OnlineChapterCodec.encode(details.toc),
                        latestChapter = details.book.latestChapter,
                        lastUpdateTime = details.book.lastUpdateTime,
                        status = details.book.status,
                        lastUpdateCheckAt = System.currentTimeMillis(),
                        lastUpdatedAt = System.currentTimeMillis(),
                        newChapterCount = plan.chapters.size,
                        updateBackupFiles = retired.take(2),
                    )
                },
            ),
        )
        // 清理被挤出的历史正文（仍被其它书或被当前书引用的一律保留）。
        val latest = readLibrary()
        val referenced = HashSet<String>()
        latest.books.forEach { item ->
            if (item.file.isNotEmpty()) referenced.add(item.file)
            item.updateBackupFiles.forEach { referenced.add(it) }
        }
        retired.filter { it !in referenced }.forEach { stale ->
            runCatching { java.io.File(store.booksDir, stale).delete() }
        }
        return plan.chapters.size
    }

    fun toggleTracking(bookId: String, enabled: Boolean) {
        val library = readLibrary()
        val book = library.books.firstOrNull { it.id == bookId } ?: throw IllegalStateException("书籍不存在。")
        if (book.onlineUrl.isEmpty()) throw IllegalStateException("此书没有原站链接。")
        writeLibrary(library.copy(books = library.books.map { if (it.id == bookId) it.copy(tracking = enabled) else it }))
    }

    /** 需要自动检查的追更书籍。 */
    fun trackedBooks(): List<Book> = readLibrary().books.filter { it.tracking && it.onlineUrl.isNotEmpty() }

    suspend fun probe(): String = exclusive { withContext(Dispatchers.IO) {
        if (tomatoPage) return@withContext tomato.probe()
        if (!_state.value.sourcesReady) refreshSourceGroups(sourceGroups.selected())
        val group = _state.value.sourceGroup
        val native = sourceGroups.entries(group).count { it.format == "阅读/Legado" }
        if (native > 0) return@withContext "分组 $group 已加载 $native 条阅读/Legado 规则及 ${sourceGroups.entries(group).size - native} 条 SoNovel 规则。导入成功不代表原站可用，请搜索后验证目录和正文。"
        val engine = engineFor(group)
        val info = runInterruptible { engine.probe() }
        val warning = info.optString("warning").ifBlank { engine.diagnostics() }
        buildString {
            append("分组 $group 引擎 ${info.optString("version")} 可用，加载 ${info.optInt("sources")} 条书源规则。")
            if (warning.isNotBlank()) append(" 配置自检：" + warning)
        }
    } }
}
