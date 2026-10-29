package com.shengzimao.reader.mobile.ui

import android.content.Context
import android.net.Uri
import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 聚合搜索的单个源结果。 */
data class MangaSourceHit(val sourceId: String, val sourceName: String, val results: List<MangaBook>, val error: String = "")

data class MangaUiState(
    val loaded: Boolean = false,
    val books: List<MangaBook> = emptyList(),
    val sources: List<MangaSource> = emptyList(),
    /** “全部”聚合模式的标记；否则为具体源 id。 */
    val selectedSource: String = "",
    val selectedSourceIds: Set<String> = emptySet(),
    val query: String = "",
    val results: List<MangaBook> = emptyList(),
    /** 聚合模式下各源的命中与失败情况，用于按源筛选与失败提示。 */
    val hits: List<MangaSourceHit> = emptyList(),
    val page: Int = 0,
    val searching: Boolean = false,
    val searchedSources: Int = 0,
    val totalSearchSources: Int = 0,
    val importing: Boolean = false,
    val sourceImportStage: String = "",
    val sourceImportNotice: String = "",
    val detailLoading: Boolean = false,
    val detail: MangaBook? = null,
    val pendingSources: List<MangaSource> = emptyList(),
    /** 导入时被跳过的源及原因（阅读格式里依赖脚本或加密的源）。 */
    val skippedSources: List<String> = emptyList(),
    /** 正在编辑的源 id；非空时源管理进入编辑态。 */
    val editingSourceId: String = "",
    val testingSourceId: String = "",
    val tests: Map<String, MangaSourceTest> = emptyMap(),
    val adapterPacks: List<MangaAdapterInstallation> = emptyList(),
    val pendingAdapter: MangaAdapterPack? = null,
    val pendingAdapterOrigin: String = "",
    val adapterBusy: Boolean = false,
    val adapterError: String = "",
    val adapterMessage: String = "",
    val testingSourceIds: Set<String> = emptySet(),
    val batchTesting: Boolean = false,
    val batchTestDone: Int = 0,
    val batchTestTotal: Int = 0,
    val cache: MangaCacheUsage = MangaCacheUsage(0, 0, 0),
    val saving: Boolean = false,
    val updatingIds: Set<String> = emptySet(),
    val switchSearching: Boolean = false,
    val switching: Boolean = false,
    val switchResults: List<MangaBook> = emptyList(),
    val history: List<String> = emptyList(),
    val message: String = "",
    /** 搜索页错误，与详情页/源管理的错误分开，避免串页。 */
    val searchError: String = "",
    val detailError: String = "",
    val detailAlternatives: List<MangaBook> = emptyList(),
    val detailFinding: Boolean = false,
    val detailFindStarted: Boolean = false,
    val detailFindCompleted: Int = 0,
    val detailFindTotal: Int = 0,
    val sourceError: String = "",
    /** 「漫源详情 / 浏览」页：当前查看的源 id，空表示未打开。 */
    val browseSourceId: String = "",
    /** 该源声明了真实 discover 规则（排行榜/分类），否则走关键词搜索降级。 */
    val browseHasDiscover: Boolean = false,
    val browseIsLegacy: Boolean = false,
    val browseGroups: List<com.shengzimao.reader.mobile.data.MangaDiscoverGroup> = emptyList(),
    /** 当前选中的条目名；空表示未选择。 */
    val browseEntry: String = "",
    val browseEntryUrl: String = "",
    val browseResults: List<MangaBook> = emptyList(),
    val browseLoading: Boolean = false,
    val browseError: String = "",
    val browsePage: Int = 1,
)

/** 聚合搜索的并发上限，避免同时打太多站点。 */
private const val AGGREGATE_CONCURRENCY = 4

class MangaController(private val context: Context, private val scope: CoroutineScope) {
    val store = MangaStore(File(context.filesDir, "comics"))
    val engine = MangaEngine(store)
    private val mutable = MutableStateFlow(MangaUiState())
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var detailFindJob: Job? = null
    private var importJob: Job? = null
    private var sourceImportJob: Job? = null
    private var alternativeJob: Job? = null
    private var browseJob: Job? = null
    @Volatile private var searchRevision = 0
    private var detailRevision = 0
    @Volatile private var detailFindRevision = 0
    private var browseRevision = 0
    private data class BrowseKey(val source: MangaSource, val entry: MangaDiscoverEntry, val page: Int)
    private val browseCache = object : LinkedHashMap<BrowseKey, List<MangaBook>>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrowseKey, List<MangaBook>>?): Boolean = size > 24
    }
    private val detailsCache = object : LinkedHashMap<Pair<MangaSource, String>, Pair<Long, MangaBook>>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<MangaSource, String>, Pair<Long, MangaBook>>?): Boolean = size > 24
    }

    private suspend fun loadDetails(book: MangaBook, refresh: Boolean = false): MangaBook {
        val source = source(book.sourceId)
        val key = source to book.url
        if (!refresh) synchronized(detailsCache) { detailsCache[key] }?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.let { return it.second }
        val fresh = try { withTimeout(20_000) { engine.details(source, book) } }
            catch (timeout: TimeoutCancellationException) { throw java.io.IOException("漫画详情读取超时，可重试或更换漫画源", timeout) }
        currentCoroutineContext().ensureActive()
        synchronized(detailsCache) { detailsCache[key] = System.currentTimeMillis() to fresh }
        return fresh
    }
    private val refreshLock = Mutex()
    private val progressFlusher = ProgressFlusher(store, scope)
    private val pageRefreshLocks = Array(32) { Mutex() }
    private var trackedUpdateJob: Job? = null
    private var batchTestJob: Job? = null
    private var adapterJob: Job? = null
    private val lastReadable = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastAddressRefresh = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        safe { try { refresh(); loadMeta() } finally { mutable.update { it.copy(loaded = true) } } }
    }

    private suspend fun loadMeta() {
        val usage = withContext(Dispatchers.IO) { store.cacheUsage() }
        val history = withContext(Dispatchers.IO) { readHistory() }
        val tests = withContext(Dispatchers.IO) { readTests() }
        mutable.update { it.copy(cache = usage, history = history, tests = tests,
            adapterPacks = store.adapters.entries(), adapterError = store.adapters.loadError) }
    }

    private fun safe(block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(sourceError = failure.message ?: "漫画操作未完成") } }
    }

    private suspend fun refresh() = refreshLock.withLock {
        val books = withContext(Dispatchers.IO) { store.books() }
        val sources = withContext(Dispatchers.IO) { store.sources() }
        engine.invalidateSources(sources)
        mutable.update { state ->
            val selectedIds = state.selectedSourceIds.intersect(sources.filter { it.enabled }.map { it.id }.toSet())
            state.copy(books = books, sources = sources, selectedSourceIds = selectedIds,
                selectedSource = when {
                    state.selectedSource == ALL_SOURCES -> ALL_SOURCES
                    selectedIds.size > 1 -> SELECTED_SOURCES
                    selectedIds.size == 1 -> selectedIds.first()
                    else -> state.selectedSource.takeIf { id -> sources.any { it.id == id && it.enabled } }
                        ?: sources.firstOrNull { it.enabled }?.id.orEmpty()
                })
        }
    }

    /** 章节与书架变更不重载全部漫源，避免读一章就清空取图路径的源缓存。 */
    private suspend fun refreshBooks() {
        val books = withContext(Dispatchers.IO) { store.books() }
        mutable.update { it.copy(books = books) }
    }

    fun reloadLibrary() = safe { refresh(); loadMeta() }

    fun dismissMessage() { mutable.update { it.copy(message = "", searchError = "", detailError = "", sourceError = "") } }
    fun selectSource(id: String) {
        searchRevision++; searchJob?.cancel()
        mutable.update { it.copy(selectedSource = id, selectedSourceIds = if (id == ALL_SOURCES || id.isBlank()) emptySet() else setOf(id),
            results = emptyList(), hits = emptyList(), searching = false, searchedSources = 0, totalSearchSources = 0, page = 0, searchError = "") }
    }

    fun selectSources(ids: Set<String>) {
        val selected = ids.intersect(state.value.sources.filter { it.enabled }.map { it.id }.toSet())
        searchRevision++; searchJob?.cancel()
        mutable.update { it.copy(selectedSource = when (selected.size) { 0 -> ""; 1 -> selected.first(); else -> SELECTED_SOURCES },
            selectedSourceIds = selected, results = emptyList(), hits = emptyList(), searching = false,
            searchedSources = 0, totalSearchSources = 0, page = 0, searchError = "") }
    }

    /**
     * 打开某个漫源的详情/浏览页。
     *
     * 有 discover 规则时展示该源声明的排行榜与分类；没有时退回常用分类关键词
     * （界面会明确标注这是「搜索分类」，不是源站官方榜单）。
     */
    fun openBrowse(sourceId: String) {
        val source = state.value.sources.firstOrNull { it.id == sourceId }?.let(store.adapters::effective) ?: return
        val revision = ++browseRevision
        browseJob?.cancel()
        mutable.update { it.copy(browseSourceId = sourceId, browseHasDiscover = false, browseIsLegacy = false,
            browseGroups = emptyList(), browseEntry = "", browseEntryUrl = "", browseResults = emptyList(),
            browseError = "", browseLoading = true, browsePage = 1) }
        browseJob = scope.launch {
            try {
                val (discover, legacy) = withContext(Dispatchers.Default) { MangaDiscover.from(source) to (source.legacy != null) }
                currentCoroutineContext().ensureActive()
                if (revision != browseRevision) return@launch
                val groups = discover?.groups ?: listOf(
                    MangaDiscoverGroup(name = "分类关键词", entries = MangaDiscover.FALLBACK_KEYWORDS.map {
                        MangaDiscoverEntry(name = it.second, url = it.first)
                    }),
                )
                mutable.update {
                    it.copy(browseSourceId = sourceId, browseHasDiscover = discover != null,
                        browseIsLegacy = legacy, browseGroups = groups, browseEntry = "",
                        browseResults = emptyList(), browseError = "", browseLoading = false, browsePage = 1)
                }
                if (source.enabled && discover != null) groups.firstOrNull()?.entries?.firstOrNull()?.let {
                    browse(it.name, entryUrl = it.url)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (revision == browseRevision) mutable.update { it.copy(browseLoading = false, browseError = failure.message ?: "漫源详情读取失败") }
            }
        }
    }

    fun closeBrowse() {
        browseRevision++
        browseJob?.cancel()
        mutable.update {
            it.copy(browseSourceId = "", browseGroups = emptyList(), browseEntry = "", browseEntryUrl = "", browseResults = emptyList(), browseError = "", browseLoading = false)
        }
    }

    /** 载入某个榜单/分类的结果；[more] 为 true 时追加下一页（仅 discover 规则支持）。 */
    fun browse(entryName: String, more: Boolean = false, entryUrl: String = state.value.browseEntryUrl, force: Boolean = false) {
        val current = state.value
        val source = current.sources.firstOrNull { it.id == current.browseSourceId }?.let(store.adapters::effective) ?: return
        val entry = current.browseGroups.flatMap { it.entries }.firstOrNull { it.name == entryName && (entryUrl.isBlank() || it.url == entryUrl) }
            ?: current.browseGroups.flatMap { it.entries }.firstOrNull { it.name == entryName } ?: return
        val revision = ++browseRevision
        browseJob?.cancel()
        browseJob = scope.launch {
            mutable.update {
                it.copy(
                    browseEntry = entryName,
                    browseEntryUrl = entry.url,
                    browseLoading = true,
                    browseError = "",
                    browseResults = if (more) it.browseResults else emptyList(),
                    browsePage = if (more) it.browsePage else 1,
                )
            }
            val page = if (more) current.browsePage + 1 else 1
            runCatching {
                val key = BrowseKey(source, entry, page)
                val books = if (!force && browseCache.containsKey(key)) browseCache.getValue(key) else withTimeout(20_000) {
                    val discover = withContext(Dispatchers.Default) { MangaDiscover.from(source) }
                    if (discover != null) engine.browse(source, discover, entry, page) else {
                        // 降级：把分类名当关键词搜索，只取第 1 页。
                        engine.search(source, entry.url, 1)
                    }
                }
                currentCoroutineContext().ensureActive()
                books.distinctBy { it.id }.take(MAX_RESULTS).also { browseCache[key] = it }
            }.fold(
                onSuccess = { books ->
                    if (revision != browseRevision) return@fold
                    mutable.update {
                        it.copy(
                            browseResults = if (more) (it.browseResults + books).distinctBy { b -> b.id }.take(MAX_RESULTS) else books,
                            browseLoading = false,
                            browsePage = page,
                        )
                    }
                },
                onFailure = { failure ->
                    currentCoroutineContext().ensureActive()
                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                    if (revision != browseRevision) return@fold
                    mutable.update {
                        it.copy(browseLoading = false, browseError = if (failure is TimeoutCancellationException) "读取超时，已停止本次加载，可重试或更换分类" else failure.message ?: "读取失败，请检查源规则或网络")
                    }
                },
            )
        }
    }

    /**
     * 搜索。[more] 为 true 时追加下一页。
     * 选中 [ALL_SOURCES] 时并发查询所有启用源，合并后用同一套相关度规则过滤排序。
     */
    fun search(query: String, more: Boolean = false) {
        val revision = ++searchRevision
        searchJob?.cancel()
        val ready = state.value.sources.filter { it.enabled }.sortedByDescending { source -> sourceReadabilityScore(source.id) }
        if (ready.isEmpty()) return
        val aggregate = state.value.selectedSource == ALL_SOURCES
        val targets = if (aggregate) ready else ready.filter { it.id == state.value.selectedSource || it.id in state.value.selectedSourceIds }
        if (targets.isEmpty()) return
        searchJob = scope.launch(Dispatchers.Default) {
            try {
                val keyword = query.trim()
                require(keyword.length in 1..120) { "请输入 1–120 字的漫画名或作者" }
                val page = if (more) state.value.page + 1 else 1
                val previous = if (more) state.value.results else emptyList()
                val previousHits = if (more) state.value.hits else emptyList()
                mutable.update { it.copy(searching = true, query = keyword, searchError = "", results = previous,
                    hits = previousHits, page = page, searchedSources = 0, totalSearchSources = targets.size, message = "正在搜索，结果会陆续显示…") }
                try {
                    val permits = Semaphore(AGGREGATE_CONCURRENCY)
                    val publishLock = Mutex()
                    val hits = ArrayList<MangaSourceHit>()
                    coroutineScope {
                        targets.map { source -> async {
                            permits.withPermit {
                                val hit = try {
                                    MangaSourceHit(source.id, source.name, withTimeout(20_000) { engine.search(source, keyword, page) })
                                } catch (timeout: TimeoutCancellationException) {
                                    MangaSourceHit(source.id, source.name, emptyList(), "搜索超时，可单独重试")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { MangaSourceHit(source.id, source.name, emptyList(), failure.message ?: "搜索失败") }
                                currentCoroutineContext().ensureActive()
                                publishLock.withLock {
                                    if (revision != searchRevision) return@withLock
                                    hits.add(hit)
                                    val filtered = filterByRelevance(keyword, hits.flatMap { it.results })
                                    val failed = hits.count { it.error.isNotBlank() }
                                    mutable.update { current -> current.copy(
                                        results = (previous + filtered).distinctBy { it.id }.take(MAX_RESULTS),
                                        hits = (previousHits + hits).groupBy { it.sourceId }.values.map { pages ->
                                            pages.last().copy(results = pages.flatMap { it.results }.distinctBy { it.id }.take(MAX_RESULTS))
                                        },
                                        searchedSources = hits.size,
                                        message = "已搜索 ${hits.size}/${targets.size} 个源 · ${filtered.size} 条结果" + if (failed > 0) " · $failed 个源失败" else "",
                                    ) }
                                }
                            }
                        } }.awaitAll()
                    }
                    if (revision == searchRevision) mutable.update { current ->
                        current.copy(message = when {
                            current.results.isNotEmpty() -> "已读取第 $page 页 · ${current.results.size} 条 · ${hits.size}/${targets.size} 个源完成" +
                                hits.count { it.error.isNotBlank() }.takeIf { it > 0 }?.let { " · $it 个源失败" }.orEmpty()
                            hits.all { it.error.isNotBlank() } -> "搜索失败：" + hits.take(3).joinToString("；") { "${it.sourceName} ${it.error}" }
                            else -> "没有更多结果，可尝试更换关键词或漫画源"
                        })
                    }
                } finally {
                    if (revision == searchRevision) mutable.update { it.copy(searching = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == searchRevision) mutable.update { it.copy(searchError = failure.message ?: "搜索失败", searching = false) }
            }
        }
    }

    /**
     * 相关度过滤。复用小说在线搜书的同一套规则（OnlineMatcher）：
     * 有严格命中就只保留严格命中，否则保留相似度 ≥0.7 的近似结果。
     */
    private fun filterByRelevance(keyword: String, books: List<MangaBook>): List<MangaBook> {
        if (books.isEmpty()) return emptyList()
        val mapped = books.map { book -> book to OnlineResult(bookName = book.title, author = book.author) }
        val scored = OnlineMatcher.filter(keyword, mapped.map { it.second })
        val order = scored.associateBy { it.bookName + "\u0000" + it.author }
        return books.sortedWith(
            compareByDescending<MangaBook> { book -> order[book.title + "\u0000" + book.author]?.matchScore ?: 0 }
                .thenBy { it.title },
        ).filter { book ->
            val hit = order[book.title + "\u0000" + book.author]
            hit == null || hit.matchScore > 0 || scored.isEmpty()
        }
    }

    fun cancelSearch() { searchRevision++; searchJob?.cancel(); mutable.update { it.copy(searching = false) } }
    fun openDetails(book: MangaBook, refresh: Boolean = false) {
        val revision = ++detailRevision
        detailJob?.cancel()
        stopDetailAlternatives()
        detailJob = scope.launch {
            mutable.update { it.copy(detail = book, detailLoading = true, detailError = "",
                detailAlternatives = emptyList(), detailFindStarted = false, detailFindCompleted = 0, detailFindTotal = 0) }
            try {
                val fresh = loadDetails(book, refresh)
                currentCoroutineContext().ensureActive()
                if (revision == detailRevision) mutable.update { it.copy(detail = fresh) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (revision == detailRevision) mutable.update { it.copy(detailError = failure.message ?: "读取详情失败") }
            } finally {
                if (revision == detailRevision) {
                    mutable.update { it.copy(detailLoading = false) }
                    if (state.value.detail?.chapters.isNullOrEmpty() && state.value.detailError.isNotBlank()) {
                        findDetailAlternatives(sameSourceOnly = true)
                    }
                }
            }
        }
    }
    fun closeDetails() {
        detailRevision++; detailJob?.cancel(); stopDetailAlternatives()
        mutable.update { it.copy(detail = null, detailLoading = false, detailError = "",
            detailAlternatives = emptyList(), detailFindStarted = false) }
    }

    /** A failed detail is not on the shelf yet. Include other editions in the same source. */
    fun findDetailAlternatives(sameSourceOnly: Boolean = false) {
        val book = state.value.detail ?: return
        val query = mangaLookupQuery(book.title)
        if (query.isBlank()) return
        stopDetailAlternatives()
        val revision = detailFindRevision
        val targets = state.value.sources.filter { it.enabled && (!sameSourceOnly || it.id == book.sourceId) }
            .sortedBy { if (it.id == book.sourceId) 0 else 1 }
        mutable.update { it.copy(detailFinding = true, detailFindStarted = true, detailAlternatives = emptyList(),
            detailFindCompleted = 0, detailFindTotal = targets.size) }
        detailFindJob = scope.launch(Dispatchers.Default) {
            try {
                val permits = Semaphore(AGGREGATE_CONCURRENCY)
                coroutineScope { targets.map { source -> async {
                    permits.withPermit {
                        val hits = try { withTimeout(12_000) { engine.search(source, query, 1) } }
                            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; emptyList() }
                            catch (_: Exception) { emptyList() }
                        currentCoroutineContext().ensureActive()
                        val matches = filterByRelevance(query, hits).filterNot { it.sourceId == book.sourceId && it.url == book.url }
                        // 自动恢复只检查同源前四个版本，逐个确认目录后立即展示。
                        // 不静默改写原条目的作者、URL 或阅读进度。
                        if (sameSourceOnly) {
                            val checks = Semaphore(2)
                            coroutineScope { matches.take(4).map { candidate -> async {
                                checks.withPermit {
                                    val ready = try { withTimeoutOrNull(12_000) { loadDetails(candidate) } }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null }
                                    currentCoroutineContext().ensureActive()
                                    if (ready != null && ready.chapters.isNotEmpty()) mutable.update { current ->
                                        if (revision != detailFindRevision || current.detail?.id != book.id) current else current.copy(
                                            detailAlternatives = (current.detailAlternatives + ready).distinctBy { it.id }.take(200))
                                    }
                                }
                            } }.awaitAll() }
                        }
                        mutable.update { current -> if (revision != detailFindRevision || current.detail?.id != book.id) current else current.copy(
                            detailAlternatives = if (sameSourceOnly) current.detailAlternatives else (current.detailAlternatives + matches).distinctBy { it.id }.take(200),
                            detailFindCompleted = current.detailFindCompleted + 1,
                        ) }
                    }
                } }.awaitAll() }
            } finally {
                mutable.update { if (revision == detailFindRevision) it.copy(detailFinding = false) else it }
            }
        }
    }

    fun stopDetailAlternatives() {
        detailFindRevision++; detailFindJob?.cancel()
        mutable.update { it.copy(detailFinding = false) }
    }

    /** 加入漫库。带重入保护：详情页连点“加入漫库”不会并发写库。 */
    fun keep(book: MangaBook, openFailureDetails: Boolean = false, onSaved: (String) -> Unit = {}): Job? {
        if (state.value.saving) return null
        mutable.update { it.copy(saving = true) }
        return scope.launch {
            try {
                val ready = if (book.chapters.isEmpty()) loadDetails(book) else book
                require(ready.chapters.isNotEmpty()) { "漫画源没有返回可阅读的目录" }
                withContext(Dispatchers.IO) {
                    val previous = store.books().firstOrNull { entry -> entry.id == book.id }
                    val chapters = ready.chapters.map { chapter -> previous?.chapters?.firstOrNull { it.url == chapter.url }?.let { chapter.copy(pages = it.pages, imageHeaders = it.imageHeaders) } ?: chapter }
                    val position = previous?.chapters?.getOrNull(previous.chapterIndex)?.url
                    val nextIndex = chapters.indexOfFirst { it.url == position }.takeIf { it >= 0 } ?: 0
                    val saved = if (previous == null) ready.copy(onlineOnly = !ready.local) else previous.copy(
                        title = ready.title, author = ready.author, cover = ready.cover, intro = ready.intro,
                        chapters = chapters, chapterIndex = nextIndex,
                        pageIndex = if (chapters.getOrNull(nextIndex)?.url == position) previous.pageIndex else 0,
                        cachedChapters = previous.cachedChapters.intersect(chapters.map { it.url }.toSet()),
                    )
                    store.upsert(saved)
                }
                refreshBooks()
                mutable.update { it.copy(message = "已加入书架；在线漫画阅读需联网，下载导入后可离线阅读") }
                onSaved(book.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = failure.message ?: "加入漫库失败"
                if (openFailureDetails) {
                    detailRevision++; detailJob?.cancel(); stopDetailAlternatives()
                    mutable.update { it.copy(detail = book, detailLoading = false, detailError = message, message = "",
                        detailAlternatives = emptyList(), detailFindStarted = false, detailFindCompleted = 0, detailFindTotal = 0) }
                    findDetailAlternatives(sameSourceOnly = true)
                } else mutable.update { it.copy(detailError = message, message = message) }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun favorite(id: String) = safe { withContext(Dispatchers.IO) { store.patch(id) { it.copy(favorite = !it.favorite) } }; refresh() }
    fun rename(id: String, title: String) = safe { require(title.isNotBlank()); withContext(Dispatchers.IO) { store.patch(id) { it.copy(title = title.trim().take(250)) } }; refresh() }
    fun remove(id: String) = safe { withContext(Dispatchers.IO) { store.remove(id) }; refresh(); refreshCache() }
    fun importLocal(uris: List<Uri>) {
        if (importJob?.isActive == true) return
        importJob = safe {
            mutable.update { it.copy(importing = true, searchError = "", message = "正在导入漫画…") }
            try {
                val books = withContext(Dispatchers.IO) { store.import(context, uris) }
                mutable.update { it.copy(message = "已导入 ${books.size} 部漫画，多张图片按文件名自然顺序合并为一册") }
            } finally { refresh(); refreshCache(); mutable.update { it.copy(importing = false) } }
        }
    }

    // ---------------- 搜索历史 ----------------

    private fun historyFile() = File(context.filesDir, "comics/search-history.json")
    private fun readHistory(): List<String> = runCatching {
        val file = historyFile()
        if (!file.isFile) emptyList() else (0 until org.json.JSONArray(file.readText()).length()).map { org.json.JSONArray(file.readText()).getString(it) }
    }.getOrDefault(emptyList())

    fun rememberQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val next = (listOf(trimmed) + state.value.history.filter { it != trimmed }).take(12)
        mutable.update { it.copy(history = next) }
        scope.launch(Dispatchers.IO) {
            runCatching { atomicJson(historyFile(), org.json.JSONArray(next).toString()) }
        }
    }
    fun clearHistory() {
        mutable.update { it.copy(history = emptyList()) }
        scope.launch(Dispatchers.IO) { runCatching { historyFile().takeIf { it.isFile }?.delete() } }
    }

    // ---------------- 缓存管理 ----------------

    fun refreshCache() = scope.launch {
        val usage = withContext(Dispatchers.IO) { store.cacheUsage() }
        mutable.update { it.copy(cache = usage) }
    }

    fun clearCache() = scope.launch {
        val freed = withContext(Dispatchers.IO) { store.clearImageCache() }
        refresh()
        val usage = withContext(Dispatchers.IO) { store.cacheUsage() }
        mutable.update { it.copy(cache = usage, message = "已清理在线漫画缓存 ${usage.readable(freed)}") }
    }

    /** 按容量上限淘汰最久未使用的在线图片。 */
    fun trimCache(maxBytes: Long) = scope.launch {
        val freed = withContext(Dispatchers.IO) { store.trimImageCache(maxBytes) }
        refresh()
        val usage = withContext(Dispatchers.IO) { store.cacheUsage() }
        mutable.update { it.copy(cache = usage, message = if (freed > 0) "已释放 ${usage.readable(freed)}" else "缓存未超过上限") }
    }

    // ---------------- 源管理 ----------------

    private fun adapterAction(block: suspend () -> Unit) {
        if (adapterJob?.isActive == true) return
        adapterJob = scope.launch {
            mutable.update { it.copy(adapterBusy = true, adapterError = "", adapterMessage = "") }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(adapterError = failure.message ?: "适配包操作失败") } }
            finally { mutable.update { it.copy(adapterBusy = false, adapterPacks = store.adapters.entries()) } }
        }
    }

    fun previewAdapter(text: String) = adapterAction {
        val pack = withContext(Dispatchers.Default) { MangaAdapterPack.parse(text) }
        mutable.update { it.copy(pendingAdapter = pack, pendingAdapterOrigin = "粘贴") }
    }

    fun previewBundledAdapter() = adapterAction {
        val pack = withContext(Dispatchers.IO) {
            context.assets.open("manga-adapters/public-compatibility.json").bufferedReader(Charsets.UTF_8).use {
                MangaAdapterPack.parse(it.readText())
            }
        }
        mutable.update { it.copy(pendingAdapter = pack, pendingAdapterOrigin = "应用内置兼容包") }
    }

    fun importAdapterFile(uri: Uri) = adapterAction {
        val pack = withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= MangaAdapterPack.MAX_BYTES) { "适配包文件超过 2 MB" }
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            } ?: error("无法读取适配包文件")
            MangaAdapterPack.parse(text)
        }
        mutable.update { it.copy(pendingAdapter = pack, pendingAdapterOrigin = "本地文件") }
    }

    fun importAdapterUrl(url: String, expectedId: String = "") = adapterAction {
        val address = url.trim()
        val pack = engine.adapterPackUrl(address)
        require(expectedId.isEmpty() || pack.id == expectedId) { "更新返回的适配包 ID 与已安装包不一致" }
        mutable.update { it.copy(pendingAdapter = pack, pendingAdapterOrigin = address) }
    }

    fun dismissAdapterPreview() { mutable.update { it.copy(pendingAdapter = null, pendingAdapterOrigin = "") } }

    fun confirmAdapter() = adapterAction {
        val pack = state.value.pendingAdapter ?: return@adapterAction
        withContext(Dispatchers.IO) { store.adapters.install(pack, store.sources(), state.value.pendingAdapterOrigin) }
        adaptersChanged("已启用「${pack.name}」v${pack.version}；适配影响的源需要重新检测图片")
        dismissAdapterPreview()
    }

    fun toggleAdapter(id: String) = adapterAction {
        withContext(Dispatchers.IO) { store.adapters.toggle(id, store.sources()) }
        adaptersChanged("已更改适配包启停状态")
    }

    fun rollbackAdapter(id: String) = adapterAction {
        withContext(Dispatchers.IO) { store.adapters.rollback(id, store.sources()) }
        adaptersChanged("已恢复上一版适配包，已有漫库和下载保持不变")
    }

    fun removeAdapter(id: String) = adapterAction {
        withContext(Dispatchers.IO) { store.adapters.remove(id) }
        adaptersChanged("已移除适配包，恢复原始源规则")
    }

    private suspend fun adaptersChanged(message: String) {
        searchRevision++; searchJob?.cancel(); detailJob?.cancel(); browseJob?.cancel()
        synchronized(detailsCache) { detailsCache.clear() }; synchronized(browseCache) { browseCache.clear() }
        engine.invalidateSources()
        refresh()
        mutable.update { it.copy(adapterPacks = store.adapters.entries(), adapterMessage = message,
            searching = false, detailLoading = false, browseLoading = false, results = emptyList(), hits = emptyList()) }
    }

    fun previewSources(text: String) = safe {
        val parsed = withContext(Dispatchers.IO) { MangaSource.importText(text) }
        val skipped = MangaSource.lastSkipped
        mutable.update { it.copy(pendingSources = parsed, skippedSources = skipped, sourceError = "", sourceImportNotice = "") }
    }
    fun importSources(uri: Uri) = safe {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 8 * 1024 * 1024) { "漫画源文件超过 8 MB" }; output.write(buffer, 0, n) }
                output.toString("UTF-8")
            } ?: error("无法读取漫画源文件")
        }
        val parsed = withContext(Dispatchers.IO) { MangaSource.importText(text) }
        val skipped = MangaSource.lastSkipped
        mutable.update { it.copy(pendingSources = parsed, skippedSources = skipped, sourceError = "", sourceImportNotice = "") }
    }
    fun importSourcesUrl(url: String): Job {
        sourceImportJob?.takeIf { it.isActive }?.let { return it }
        return safe {
            mutable.update { it.copy(importing = true, sourceError = "", sourceImportNotice = "") }
            try {
                val parsed = engine.importUrl(url, onFallback = { notice -> mutable.update { it.copy(sourceImportNotice = notice) } }) { stage ->
                    mutable.update { it.copy(sourceImportStage = stage) }
                }
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(pendingSources = parsed, skippedSources = MangaSource.lastSkipped) }
            } finally { mutable.update { it.copy(importing = false, sourceImportStage = "") } }
        }.also { sourceImportJob = it }
    }
    fun cancelSourceImport() { sourceImportJob?.cancel() }
    fun discardSourcePreview() { mutable.update { it.copy(pendingSources = emptyList(), skippedSources = emptyList(), sourceImportNotice = "") } }
    fun confirmSources() = safe {
        val fresh = state.value.pendingSources
        if (fresh.isEmpty()) return@safe
        withContext(Dispatchers.IO) {
            store.mutateSources { old -> old.filterNot { source -> fresh.any { it.id == source.id } } + fresh }
        }
        engine.invalidateSources()
        val active = fresh.count { it.enabled }
        refresh(); mutable.update { it.copy(pendingSources = emptyList(), editingSourceId = "", message =
            "已完整保存 ${fresh.size} 个漫画源：启用 $active 个，停用 ${fresh.size - active} 个。停用源也已导入，可在源管理查看原因；同 ID 配置已更新。") }
    }
    fun toggleSource(id: String) = safe { withContext(Dispatchers.IO) { store.mutateSources { sources -> sources.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } } }; engine.invalidateSources(); refresh() }
    fun deleteSource(id: String) = safe { withContext(Dispatchers.IO) { store.mutateSources { sources -> sources.filterNot { it.id == id } }; engine.invalidateSources() }; refresh() }

    /**
     * 保存对已有源的编辑。
     *
     * 之前“编辑规则”只是把 JSON 塞回粘贴框再走导入流程：用户改了 id 就会
     * 静默多出一个源，缓存与漫库按 id 关联也跟着分裂。
     * 这里强制沿用原 id，只允许修改 id 以外的规则。
     */
    fun beginEditSource(id: String) {
        val source = state.value.sources.firstOrNull { it.id == id } ?: return
        mutable.update { it.copy(editingSourceId = id, pendingSources = listOf(source), sourceError = "") }
    }
    fun cancelEditSource() { mutable.update { it.copy(editingSourceId = "", pendingSources = emptyList(), sourceError = "") } }

    fun saveSourceEdit(text: String) = safe {
        val editingId = state.value.editingSourceId
        require(editingId.isNotBlank()) { "没有正在编辑的漫画源" }
        val parsed = withContext(Dispatchers.IO) { MangaSource.importText(text) }
        require(parsed.size == 1) { "编辑时只能保存单个漫画源" }
        val edited = parsed.first()
        require(edited.id == editingId) { "漫画源 id 不可修改（与漫库和图片缓存关联）；如需新源，请用导入新建" }
        val normalized = edited.copy(enabled = state.value.sources.firstOrNull { it.id == editingId }?.enabled ?: edited.enabled)
        withContext(Dispatchers.IO) { store.mutateSources { sources -> sources.map { if (it.id == editingId) normalized else it } } }
        engine.invalidateSources()
        refresh()
        mutable.update { it.copy(editingSourceId = "", pendingSources = emptyList(), sourceError = "", message = "已保存「${normalized.name}」的规则") }
    }

    /** 测试源的三段规则，逐段给出结果。 */
    fun testSource(id: String, keyword: String = "") {
        val source = state.value.sources.firstOrNull { it.id == id } ?: return
        if (state.value.testingSourceId.isNotBlank() || state.value.batchTesting) return
        mutable.update { it.copy(testingSourceId = id, sourceError = "") }
        scope.launch {
            try {
                val result = withTimeout(90_000) { isolatedSourceTest(source, keyword) }
                mutable.update { it.copy(tests = it.tests + (id to result)) }
                withContext(Dispatchers.IO) { saveTests() }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(tests = it.tests + (id to MangaSourceTest(false,
                    "检测超过 90 秒，未完成抽样；可重试或使用近期阅读结果，不据此判定整源失效", 0, 0, 0,
                    readability = MangaReadability.FAILED, elapsedMs = 90_000, ruleVersion = engine.ruleVersion(source)))) }
                withContext(Dispatchers.IO) { saveTests() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(tests = it.tests + (id to MangaSourceTest(false, failure.message ?: "测试失败", 0, 0, 0))) }
                withContext(Dispatchers.IO) { saveTests() }
            } finally {
                mutable.update { it.copy(testingSourceId = "") }
            }
        }
    }

    /** Every source is checked in a private cache; disabled sources keep their original state. */
    fun testAllSources(keyword: String = "", failedOnly: Boolean = false) {
        if (batchTestJob?.isActive == true || state.value.testingSourceId.isNotBlank()) return
        val sources = state.value.sources.filter { source ->
            val test = state.value.tests[source.id]
            !failedOnly || test?.readability != MangaReadability.READABLE || test.ruleVersion != engine.ruleVersion(source)
        }
        if (sources.isEmpty()) return
        mutable.update { it.copy(batchTesting = true, batchTestDone = 0, batchTestTotal = sources.size, sourceError = "") }
        batchTestJob = scope.launch {
            val gate = Semaphore(2)
            val hosts = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
            try {
                coroutineScope {
                    sources.map { source -> async {
                        val host = java.net.URI(source.baseUrl).host.orEmpty()
                        hosts.getOrPut(host) { Mutex() }.withLock { gate.withPermit {
                            mutable.update { it.copy(testingSourceIds = it.testingSourceIds + source.id) }
                            try {
                                val result = try { withTimeout(90_000) { isolatedSourceTest(source, keyword) } }
                                catch (timeout: TimeoutCancellationException) { MangaSourceTest(false, "检测超时；已保留此前完成的结果，可单独重试", 0, 0, 0,
                                    readability = MangaReadability.FAILED, elapsedMs = 90_000, ruleVersion = engine.ruleVersion(source)) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { MangaSourceTest(false, failure.message ?: "检测失败", 0, 0, 0,
                                    readability = MangaReadability.FAILED, ruleVersion = engine.ruleVersion(source)) }
                                mutable.update { it.copy(tests = it.tests + (source.id to result), batchTestDone = it.batchTestDone + 1) }
                                withContext(Dispatchers.IO) { saveTests() }
                            } finally { mutable.update { it.copy(testingSourceIds = it.testingSourceIds - source.id) } }
                        } }
                    } }.awaitAll()
                }
            } finally { mutable.update { it.copy(batchTesting = false, testingSourceIds = emptySet()) } }
        }
    }

    fun cancelAllSourceTests() { batchTestJob?.cancel() }

    private suspend fun isolatedSourceTest(source: MangaSource, keyword: String): MangaSourceTest = withContext(Dispatchers.IO) {
        val folder = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "manga-check-").toFile()
        try {
            val isolated = MangaStore(folder)
            isolated.saveSources(listOf(source.copy(enabled = true)))
            store.adapters.entries().filter { it.enabled }.forEach { isolated.adapters.install(it.pack, isolated.sources()) }
            val probe = MangaEngine(isolated)
            val testedRules = probe.ruleVersion(source)
            probe.test(source.copy(enabled = true), keyword.trim().ifBlank { "一" }).copy(ruleVersion = testedRules)
        } finally { folder.deleteRecursively() }
    }

    private fun readTests(): Map<String, MangaSourceTest> = runCatching {
        val file = File(store.root, "source-tests.json"); if (!file.isFile) return@runCatching emptyMap()
        val j = org.json.JSONObject(file.readText())
        j.keys().asSequence().mapNotNull { id -> runCatching { val v = j.getJSONObject(id)
            id to MangaSourceTest(v.getBoolean("ok"), v.getString("message"), v.getInt("results"), v.getInt("chapters"), v.getInt("images"), v.getLong("testedAt"),
                runCatching { MangaReadability.valueOf(v.optString("readability")) }.getOrDefault(if (v.optBoolean("ok")) MangaReadability.READABLE else MangaReadability.FAILED),
                v.optInt("sampledImages"), v.optInt("sampledChapters"), v.optLong("elapsedMs"), v.optString("ruleVersion")) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())
    @Synchronized private fun saveTests() { atomicJson(File(store.root, "source-tests.json"), org.json.JSONObject().also { j ->
        state.value.tests.forEach { (id, t) -> j.put(id, org.json.JSONObject().put("ok", t.ok).put("message", t.message)
            .put("results", t.results).put("chapters", t.chapters).put("images", t.images).put("testedAt", t.testedAt)
            .put("readability", t.readability.name).put("sampledImages", t.sampledImages).put("sampledChapters", t.sampledChapters)
            .put("elapsedMs", t.elapsedMs).put("ruleVersion", t.ruleVersion)) }
    }.toString()) }
    fun toggleTracking(id: String) = safe {
        withContext(Dispatchers.IO) { store.patch(id) { it.copy(tracking = !it.tracking) } }; refreshBooks()
    }

    fun checkUpdates(id: String, onUpdated: (Int) -> Unit = {}) = safe {
        if (id in state.value.updatingIds) return@safe
        mutable.update { it.copy(updatingIds = it.updatingIds + id) }
        try {
        progressFlusher.flush()
        val book = withContext(Dispatchers.IO) { store.books().firstOrNull { it.id == id } } ?: return@safe
        require(!book.local) { "本地漫画无需检查更新" }
        val updated = withTimeout(20_000) { engine.details(source(book.sourceId), book) }
        require(updated.chapters.isNotEmpty()) { "源站目录为空，已保留原目录与进度" }
        val delta = MangaChapterUpdates.reconcile(book.chapters, updated.chapters)
        val fresh = delta.addedIndices.size
        withContext(Dispatchers.IO) { store.patch(id) { current ->
            if (current.sourceId != book.sourceId || current.url != book.url) return@patch current
            val reconciliation = MangaChapterUpdates.reconcile(current.chapters, updated.chapters)
            val chapters = updated.chapters.mapIndexed { index, chapter ->
                val old = current.chapters.getOrNull(reconciliation.previousIndices[index])
                if (old == null) chapter else chapter.copy(pages = old.pages, imageHeaders = old.imageHeaders,
                    imageRuleVersion = old.imageRuleVersion, cacheIdentity = old.cacheIdentity.ifBlank { old.url })
            }
            val unread = chapters.indices.filter { i -> i in reconciliation.addedIndices ||
                current.chapters.getOrNull(reconciliation.previousIndices[i])?.url in current.unreadChapterUrls }.map { chapters[it].url }.toSet()
            val cached = chapters.indices.filter { i -> current.chapters.getOrNull(reconciliation.previousIndices[i])?.url in current.cachedChapters }.map { chapters[it].url }.toSet()
            val anchor = ChapterAnchor.match(current.chapters.map { OnlineChapter(it.title, it.url) }, chapters.map { OnlineChapter(it.title, it.url) }, current.chapterIndex)
            current.copy(chapters = chapters, chapterIndex = anchor.index, intro = updated.intro, updatedAt = System.currentTimeMillis(),
                newChapters = unread.size, unreadChapterUrls = unread, cachedChapters = cached, updateError = "", lastUpdateCheck = System.currentTimeMillis())
        } }
        refreshBooks(); mutable.update { it.copy(message = if (fresh > 0) "「${book.title}」新增 $fresh 章" else "「${book.title}」目录已是最新") }
        onUpdated(delta.addedIndices.minOrNull() ?: -1)

        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            recordUpdateFailure(id, "更新目录超时，旧目录与进度已保留")
        } catch (failure: Exception) { recordUpdateFailure(id, failure.message ?: "更新失败，已保留原目录") }
        finally { mutable.update { it.copy(updatingIds = it.updatingIds - id) } }
    }

    private suspend fun recordUpdateFailure(id: String, error: String) {
        withContext(Dispatchers.IO) { store.patch(id) { it.copy(updateError = error, lastUpdateCheck = System.currentTimeMillis()) } }
        refreshBooks(); mutable.update { it.copy(sourceError = error) }
    }

    fun checkTrackedUpdates() {
        if (trackedUpdateJob?.isActive == true) return
        trackedUpdateJob = scope.launch {
            for (book in state.value.books.filter { !it.local && it.tracking }) checkUpdates(book.id).join()
        }
    }

    fun firstUnread(book: MangaBook): Int = book.chapters.indexOfFirst { it.url in book.unreadChapterUrls }.takeIf { it >= 0 }
        ?: (book.chapterIndex + if (book.lastReadAt > 0) 1 else 0).coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))

    fun searchAlternatives(bookId: String) {
        val book = state.value.books.firstOrNull { it.id == bookId } ?: return
        alternativeJob?.cancel()
        mutable.update { it.copy(switchSearching = true, switchResults = emptyList(), sourceError = "") }
        alternativeJob = scope.launch(Dispatchers.Default) {
            try {
                val permits = Semaphore(4)
                coroutineScope { state.value.sources.filter { it.enabled && it.id != book.sourceId }.map { source -> async {
                    permits.withPermit {
                        val hits = try { withTimeout(20_000) { engine.search(source, book.title, 1) } }
                            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; emptyList() }
                            catch (_: Exception) { emptyList() }
                        currentCoroutineContext().ensureActive()
                        val matches = filterByRelevance(book.title, hits)
                        mutable.update { it.copy(switchResults = (it.switchResults + matches).distinctBy { b -> b.id }.take(200)) }
                    }
                } }.awaitAll() }
            } finally { if (currentCoroutineContext().isActive) mutable.update { it.copy(switchSearching = false) } }
        }
    }
    fun closeAlternatives() { alternativeJob?.cancel(); mutable.update { it.copy(switchSearching = false) } }

    fun switchSource(bookId: String, candidate: MangaBook, oldPageCount: Int, onSaved: () -> Unit) = safe {
        if (state.value.switching) return@safe
        mutable.update { it.copy(switching = true, sourceError = "") }
        try {
            progressFlusher.flush()
            val old = withContext(Dispatchers.IO) { store.books().firstOrNull { it.id == bookId } } ?: error("漫画已移除")
            require(!old.local && candidate.sourceId != old.sourceId) { "请选择另一个在线漫画源" }
            val fresh = loadDetails(candidate)
            require(fresh.chapters.isNotEmpty()) { "新源目录为空，已保留旧源" }
            val anchor = ChapterAnchor.match(old.chapters.map { OnlineChapter(it.title, it.url) }, fresh.chapters.map { OnlineChapter(it.title, it.url) }, old.chapterIndex)
            val chapter = fresh.chapters[anchor.index]
            val images = try { withTimeout(20_000) { engine.pages(source(fresh.sourceId), chapter) } }
                catch (timeout: TimeoutCancellationException) { error("新源图片地址读取超时，原漫画源和进度已保留") }
            require(images.isNotEmpty()) { "新源章节没有图片，已保留旧源" }
            // Resolving addresses is not enough: validate real bytes and the native decoder before committing.
            val validatedChapter = chapter.copy(pages = images, imageHeaders = engine.captureImageHeaders(source(fresh.sourceId), chapter, images), imageRuleVersion = engine.ruleVersion(source(fresh.sourceId)))
            val firstImage = withTimeout(30_000) { engine.imageFile(images.first(), fresh.sourceId,
                context = MangaImageContext.of(fresh, validatedChapter, 0), headers = engine.imageHeaders(source(fresh.sourceId), validatedChapter, images.first())) }
            withContext(Dispatchers.IO) { MangaStore.checkReadableImage(firstImage) }
            val page = if (oldPageCount > 1) (old.pageIndex.toLong() * (images.size - 1) / (oldPageCount - 1)).toInt().coerceIn(images.indices) else 0
            withContext(Dispatchers.IO) { store.patch(bookId) { current ->
                if (current.url != old.url || current.sourceId != old.sourceId) error("作品已发生变化，请重新换源")
                current.copy(sourceId = fresh.sourceId, sourceName = fresh.sourceName, url = fresh.url, cover = fresh.cover,
                    intro = fresh.intro, ruleState = fresh.ruleState, chapters = fresh.chapters.mapIndexed { i, c -> if (i == anchor.index)
                        c.copy(pages = images, imageHeaders = engine.captureImageHeaders(source(fresh.sourceId), chapter, images), imageRuleVersion = engine.ruleVersion(source(fresh.sourceId))) else c },
                    chapterIndex = anchor.index, pageIndex = page, pageReference = images[page], pageFraction = old.pageFraction,
                    cachedChapters = emptySet(), onlineOnly = true, newChapters = 0, unreadChapterUrls = emptySet(),
                    updateError = "", lastUpdateCheck = 0)
            } }
            refreshBooks(); mutable.update { it.copy(message = "已换源并按章节${if (anchor.exact) "名称" else "比例"}及页面比例定位，请核对画面") }
            onSaved()
        } finally { mutable.update { it.copy(switching = false) } }
    }

    fun exportSources(uri: Uri, template: Boolean) = safe {
        val text = if (template) MangaSource.template else org.json.JSONArray().also { a -> state.value.sources.forEach { a.put(it.json()) } }.toString(2)
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("无法写入目标文件") }
        mutable.update { it.copy(message = if (template) "模板已导出，请修改站点地址与规则后导入" else "漫画源已导出") }
    }

    // ---------------- 阅读与缓存 ----------------

    suspend fun ensurePages(bookId: String, chapterIndex: Int, refreshPages: Boolean = false): List<String> {
        val book = withContext(Dispatchers.IO) { store.books().firstOrNull { it.id == bookId } } ?: error("漫画不在漫库中")
        val chapter = book.chapters.getOrNull(chapterIndex) ?: error("漫画章节不存在")
        val stalePlaceholder = !book.local && chapter.pages.any { com.shengzimao.reader.mobile.data.mangaPlaceholder(it) }
        val sourceConfig = state.value.sources.firstOrNull { it.id == book.sourceId }
        val adaptedRulesChanged = !book.local && sourceConfig != null && chapter.imageRuleVersion.isNotBlank() &&
            chapter.imageRuleVersion != engine.ruleVersion(sourceConfig)
        val keepDownloaded = adaptedRulesChanged && withContext(Dispatchers.IO) { store.hasCompleteDownload(book, chapter) }
        val retiredImages = !book.local && sourceConfig != null && withContext(Dispatchers.IO) {
            com.shengzimao.reader.mobile.data.GmhReader.requiresPageRefresh(sourceConfig.baseUrl, chapter) {
                store.cachedImage(book.sourceId, it).isFile
            }
        }
        if (chapter.pages.isNotEmpty() && !stalePlaceholder && (!retiredImages || keepDownloaded) &&
            (!adaptedRulesChanged || keepDownloaded) && !refreshPages) {
            state.value.sources.firstOrNull { it.id == book.sourceId }?.let { engine.restoreImageHeaders(it, chapter) }
            return chapter.pages
        }
        require(!book.local) { "此本地漫画未包含可读图片。若来自不含图片的备份，请重新导入原始 CBZ / ZIP 或图片文件。" }
        val activeSource = source(book.sourceId)
        val pages = try { withTimeout(20_000) { engine.pages(activeSource, chapter) } }
            catch (timeout: TimeoutCancellationException) { throw java.io.IOException("漫画章节读取超时，可重试或更换漫画源", timeout) }
        val headers = engine.captureImageHeaders(activeSource, chapter, pages)
        withContext(Dispatchers.IO) { store.patch(bookId) { current ->
            require(current.sourceId == book.sourceId && current.url == book.url) { "漫画源已变化，已丢弃旧章节响应" }
            current.copy(
            chapters = current.chapters.map { if (it.url == chapter.url) it.copy(pages = pages, imageHeaders = headers, imageRuleVersion = engine.ruleVersion(activeSource)) else it },
            cachedChapters = if (refreshPages || stalePlaceholder || retiredImages || adaptedRulesChanged) current.cachedChapters - chapter.url else current.cachedChapters,
        ) } }
        refreshBooks()
        return pages
    }

    /** Restore expired page URLs once, coalescing simultaneous failures in the same chapter. */
    suspend fun readerImage(bookId: String, chapterIndex: Int, page: Int, force: Boolean = false,
        priority: MangaImagePriority = MangaImagePriority.VISIBLE, onStage: (String) -> Unit = {}): File {
        suspend fun snapshot() = withContext(Dispatchers.IO) { store.books().firstOrNull { it.id == bookId } }
            ?: error("漫画已移出漫库")
        val original = snapshot()
        val chapter = original.chapters.getOrNull(chapterIndex) ?: error("章节已变化，请重新打开目录")
        val reference = chapter.pages.getOrNull(page) ?: error("图片列表已变化，请刷新章节")
        val key = "${original.sourceId}|${original.url}|${chapter.url}"
        val refreshAtStart = lastAddressRefresh[key]
        suspend fun load(book: MangaBook, target: MangaChapter): File {
            val ref = target.pages.getOrNull(page) ?: error("刷新后本章页数发生变化，请刷新章节")
            val file = engine.imageFile(ref, book.sourceId, force = force, context = MangaImageContext.of(book, target, page), priority = priority,
                headers = state.value.sources.firstOrNull { it.id == book.sourceId }?.let { engine.imageHeaders(it, target, ref) }, onStage = onStage)
            if (!book.local && priority <= MangaImagePriority.NEARBY) noteReadable(book.sourceId, file)
            return file
        }
        try { return load(original, chapter) } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (original.local || failure !is MangaImageAddressException || failure.status !in setOf(0, 401, 403, 404, 410)) throw failure
            onStage("图片链接可能已过期，正在重新获取章节地址…")
            pageRefreshLocks[(key.hashCode() and Int.MAX_VALUE) % pageRefreshLocks.size].withLock {
                val latest = snapshot()
                require(latest.sourceId == original.sourceId && latest.url == original.url) { "漫画源已变化，请重新打开阅读" }
                val index = latest.chapters.indexOfFirst { it.url == chapter.url }
                require(index >= 0) { "目录已变化，请刷新章节" }
                val changed = latest.chapters[index].pages.getOrNull(page) != reference ||
                    latest.chapters[index].imageHeaders != chapter.imageHeaders || lastAddressRefresh[key] != refreshAtStart
                if (!changed) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - (lastAddressRefresh[key] ?: -60_000L) < 15_000) throw failure
                    ensurePages(bookId, index, refreshPages = true)
                    lastAddressRefresh[key] = now
                    if (lastAddressRefresh.size > 256) lastAddressRefresh.entries.removeIf { now - it.value > 60_000 }
                }
            }
            val fresh = snapshot()
            require(fresh.sourceId == original.sourceId && fresh.url == original.url) { "漫画源已变化，请重新打开阅读" }
            val freshChapter = fresh.chapters.firstOrNull { it.url == chapter.url } ?: throw failure
            onStage("章节地址已更新，正在重新读取当前图片…")
            return load(fresh, freshChapter)
        }
    }

    /** 只暂存进度并延迟合并写盘，避免每次翻页都重写整个索引。 */
    fun savePosition(bookId: String, chapter: Int, page: Int, fraction: Float = 0f, reference: String = "") {
        progressFlusher.stage(bookId, chapter, page, fraction, reference)
    }

    fun flushReadingProgress() = scope.launch { progressFlusher.flush(); refreshBooks() }

    fun togglePageBookmark(bookId: String, chapter: Int, page: Int, fraction: Float, reference: String) = safe {
        withContext(Dispatchers.IO) { store.patch(bookId) { book ->
            val url = book.chapters.getOrNull(chapter)?.url ?: return@patch book
            val exists = book.bookmarks.any { it.chapterUrl == url && it.page == page }
            book.copy(bookmarks = if (exists) book.bookmarks.filterNot { it.chapterUrl == url && it.page == page }
                else (book.bookmarks + com.shengzimao.reader.mobile.data.MangaBookmark(url, page, reference, fraction,
                    "${book.chapters[chapter].title} · 第 ${page + 1} 页")).takeLast(2000))
        } }
        refresh()
    }

    fun sourceReadabilityScore(id: String): Long {
        val test = state.value.tests[id] ?: return 0
        val config = state.value.sources.firstOrNull { it.id == id } ?: return 0
        if (test.ruleVersion.isNotEmpty() && test.ruleVersion != engine.ruleVersion(config)) return 0
        if (System.currentTimeMillis() - test.testedAt > 7 * 24 * 3600_000L) return 0
        return when(test.readability) { MangaReadability.READABLE -> 100_000L; MangaReadability.PARTIAL -> 50_000L; else -> 0L } - test.elapsedMs.coerceIn(0, 30_000)
    }

    private suspend fun noteReadable(id: String, file: File) {
        val now = System.currentTimeMillis()
        val previous = lastReadable.putIfAbsent(id, now)
        if (previous != null && now - previous < 60_000) return
        lastReadable[id] = now
        val config = state.value.sources.firstOrNull { it.id == id } ?: return
        val test = state.value.tests[id]
        if (test != null && test.sampledImages > 0 && test.ruleVersion == engine.ruleVersion(config) && now - test.testedAt < 3600_000) return
        withContext(Dispatchers.IO) { MangaStore.checkReadableImage(file) }
        mutable.update { it.copy(tests = it.tests + (id to MangaSourceTest(true, "最近阅读图片已下载并解码；仅代表当前样本", 0, 0, 1,
            readability = MangaReadability.READABLE, sampledImages = 1, elapsedMs = engine.imageLoadMillis(id), ruleVersion = engine.ruleVersion(config)))) }
        scope.launch(Dispatchers.IO) { runCatching { saveTests() } }
    }

    fun savePageOverride(bookId: String, chapterIndex: Int, page: Int, value: MangaPageOverride) = safe {
        withContext(Dispatchers.IO) { store.patch(bookId) { book ->
            val chapter = book.chapters.getOrNull(chapterIndex) ?: return@patch book
            var json = MangaPageOverrides.set(book.pageOverrides, chapter, page, value)
            if (value.layout == "join" && page + 1 < chapter.pages.size) {
                val next = MangaPageOverrides.get(json, chapter, page + 1)
                json = MangaPageOverrides.set(json, chapter, page + 1, next.copy(layout = "auto"))
            }
            book.copy(pageOverrides = json)
        } }
        refreshBooks()
    }

    fun saveReaderPreferences(bookId: String, json: String) = safe {
        withContext(Dispatchers.IO) { store.patch(bookId) { it.copy(readerPreferences = json) } }; refresh()
    }

    fun deleteDownloads(bookId: String) = safe {
        val freed = withContext(Dispatchers.IO) { store.deleteDownloads(bookId) }
        refresh(); refreshCache()
        mutable.update { it.copy(message = "已删除该作品的离线下载，释放 ${it.cache.readable(freed)}") }
    }

    suspend fun download(bookId: String, chapterIndex: Int, progress: (Int, Int, String) -> Unit): DownloadOutcome = download(bookId, chapterIndex, progress, null)

    suspend fun download(bookId: String, chapterIndex: Int, progress: (Int, Int, String) -> Unit, selectedUrls: Set<String>?,
        pageProgress: (Int, Int, Int) -> Unit = { _, _, _ -> }): DownloadOutcome {
        val book = withContext(Dispatchers.IO) { store.books().firstOrNull { it.id == bookId } } ?: error("请先把漫画加入漫库")
        val chapters = if (chapterIndex >= 0) listOfNotNull(book.chapters.getOrNull(chapterIndex)) else book.chapters.filter { selectedUrls == null || it.url in selectedUrls }
        require(chapters.isNotEmpty()) { "漫画没有可缓存的目录，或该章节已经移除" }
        var successfulPages = 0; var totalPages = 0; var failures = 0; var completedChapters = 0
        val failuresLock = Any()
        var firstFailure = ""
        for ((done, chapter) in chapters.withIndex()) {
            currentCoroutineContext().ensureActive()
            val current = withContext(Dispatchers.IO) { store.books().firstOrNull { it.id == bookId } } ?: error("漫画已移出漫库，缓存已停止")
            val index = current.chapters.indexOfFirst { it.url == chapter.url }
            require(index >= 0) { "漫画目录已变化，请重新加入缓存任务" }
            require(current.sourceId == book.sourceId && current.url == book.url) { "漫画源已变化，原下载任务已停止" }
            val savedChapter = current.chapters[index]
            val completeCache = withContext(Dispatchers.IO) {
                savedChapter.pages.isNotEmpty() && savedChapter.pages.withIndex().all { (page, ref) ->
                    if (ref.startsWith("file:")) true else store.cachedImage(book.sourceId, ref, context = MangaImageContext.of(current, savedChapter, page)).let { it.isFile && it.length() > 0 }
                }
            }
            // 未完整缓存时刷新图片地址，避免重试永远沿用失效签名 URL 或旧解析结果。
            val pages = ensurePages(book.id, index, refreshPages = !book.local && !completeCache)
            totalPages += pages.size
            var chapterFailed = false
            pageProgress(successfulPages, totalPages, failures)
            coroutineScope {
                var nextPage = 0
                var completed = 0
                val pageLock = failuresLock
                List(minOf(3, pages.size)) {
                    async {
                        while (true) {
                            val page = synchronized(pageLock) { if (nextPage < pages.size) nextPage++ else -1 }
                            if (page < 0) break
                            try {
                                // 主动下载使用引擎的有界图片并发，不进入单路的后台预读队列。
                                readerImage(book.id, index, page, priority = MangaImagePriority.DOWNLOAD)
                                withContext(Dispatchers.IO) {
                                    val latest = store.books().first { it.id == book.id }
                                    require(latest.sourceId == book.sourceId && latest.url == book.url) { "漫画源已变化，原下载任务已停止" }
                                    val c = latest.chapters.first { it.url == chapter.url }
                                    store.protectImage(latest.sourceId, c.pages[page], MangaImageContext.of(latest, c, page))
                                }
                                synchronized(failuresLock) { successfulPages++ }
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                synchronized(failuresLock) {
                                    failures++; chapterFailed = true
                                    if (firstFailure.isBlank()) firstFailure = "「${chapter.title}」第 ${page + 1} 页：${failure.message ?: "网络错误"}"
                                }
                            }
                            synchronized(pageLock) {
                                completed++
                                progress(completedChapters, chapters.size, "第 ${done + 1}/${chapters.size} 章 · $completed/${pages.size} 页 · 失败 $failures 页")
                                pageProgress(successfulPages, totalPages, failures)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (chapterFailed) continue
            completedChapters++
            withContext(Dispatchers.IO) { checkNotNull(store.patch(book.id) { saved ->
                require(saved.sourceId == book.sourceId && saved.url == book.url) { "漫画源已变化，原下载任务已停止" }
                require(saved.chapters.any { it.url == chapter.url }) { "漫画目录已变化，请重新加入缓存任务" }
                saved.copy(cachedChapters = saved.cachedChapters + chapter.url)
            }) { "漫画已移出漫库，缓存已停止" } }
            progress(completedChapters, chapters.size, "已缓存 $completedChapters/${chapters.size} 章")
        }
        if (failures > 0) { refreshBooks(); refreshCache(); throw IllegalStateException("剩余 ${chapters.size - completedChapters} 章、$failures 页未完成。$firstFailure。重试仅补未完成图片。") }
        withContext(Dispatchers.IO) { store.patch(book.id) { saved ->
            if (saved.chapters.all { it.url in saved.cachedChapters }) saved.copy(onlineOnly = false) else saved
        } }
        refresh(); refreshCache()
        return DownloadOutcome(book.id)
    }

    private fun source(id: String) = store.adapters.effective(state.value.sources.firstOrNull { it.id == id && it.enabled }
        ?: error("漫画源不存在或已停用，请在源管理中导入或启用"))

    companion object {
        const val ALL_SOURCES = "*"
        const val SELECTED_SOURCES = "@selected"
        const val MAX_RESULTS = 1000
    }
}

/**
 * 阅读进度的合并写入器：收集短时间内的高频进度更新，
 * 每秒最多落盘一次，退出时立即刷新。
 */
class ProgressFlusher(private val store: MangaStore, private val scope: CoroutineScope) {
    private var dirty = false
    private var job: Job? = null

    fun stage(bookId: String, chapter: Int, page: Int, fraction: Float = 0f, reference: String = "") {
        store.stagePosition(bookId, chapter, page, fraction, reference)
        dirty = true
        if (job?.isActive == true) return
        job = scope.launch {
            kotlinx.coroutines.delay(1200)
            flush()
        }
    }

    suspend fun flush() {
        if (!dirty) return
        dirty = false
        withContext(Dispatchers.IO) { runCatching { store.flushProgress() } }
    }
}
