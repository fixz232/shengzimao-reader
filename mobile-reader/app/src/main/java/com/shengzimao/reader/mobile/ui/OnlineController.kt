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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线搜书、下载入库与追更。
 *
 * 与桌面版一致的行为约定：
 * - 全局单任务：同一时刻只跑一个在线任务；
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
) {
    private val _state = MutableStateFlow(OnlineUiState())
    val state: StateFlow<OnlineUiState> = _state.asStateFlow()

    private var engine: SonovelEngine? = null
    private val tomato by lazy { TomatoEngine() }

    /**
     * 引擎有全局可变状态（工作目录、AppConfig、HttpClientContext），
     * 而 ReaderViewModel 的各个入口是彼此独立的协程，仅靠 UI 上的 running 标记无法阻止并发。
     * 所有在线任务都必须在这把锁内执行。
     */
    private val taskLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!taskLock.tryLock()) throw IllegalStateException("已有在线任务正在运行，请等待完成。")
        try {
            return block()
        } finally {
            if (currentCoroutineContext().isActive.not()) {
                _state.value = _state.value.copy(running = false, message = "任务已取消。", error = "")
            }
            taskLock.unlock()
        }
    }

    private val engineOrThrow: SonovelEngine
        get() {
            val existing = engine
            if (existing != null && existing.available) return existing
            val created = SonovelEngine(context)
            engine = created
            if (!created.available) {
                _state.value = _state.value.copy(engineReady = false)
                throw IllegalStateException("内置在线引擎未能加载：${created.loadError ?: "未知初始化错误"}")
            }
            _state.value = _state.value.copy(engineReady = true)
            return created
        }

    fun report(text: String, error: Boolean = false) {
        _state.value = _state.value.copy(message = text, error = if (error) text else "")
        notify(text, error)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = "")
    }

    // ---------------- 搜索 ----------------

    suspend fun search(keyword: String, history: List<String>) = exclusive { searchLocked(keyword, history) }

    private suspend fun searchLocked(keyword: String, history: List<String>) = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        if (query.isEmpty() || query.length > 120) throw IllegalArgumentException("请输入 1–120 个字符的书名或作者。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        val started = System.currentTimeMillis()
        _state.value = _state.value.copy(
            running = true,
            keyword = query,
            results = emptyList(),
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
            val raw = if (tomatoPage) tomato.search(query) else runInterruptible { OnlineResult.listFrom(engineOrThrow.search(query)) }
            currentCoroutineContext().ensureActive()
            val filtered = (if (tomatoPage) raw else OnlineMatcher.filter(query, raw)).take(500)
            _state.value = _state.value.copy(
                running = false,
                results = filtered,
                message = if (filtered.isEmpty()) {
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
                error = if (hint.isBlank()) detail else "$detail（$hint）",
                message = "任务未完成。",
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }

    // ---------------- 详情 ----------------

    suspend fun details(index: Int) = exclusive { detailsLocked(index) }

    private suspend fun detailsLocked(index: Int) = withContext(Dispatchers.IO) {
        val book = _state.value.results.getOrNull(index) ?: throw IllegalArgumentException("请从当前结果选择书籍。")
        if (_state.value.running) throw IllegalStateException("请等待当前任务结束或先取消。")
        _state.value = _state.value.copy(running = true, message = "正在读取书籍详情和目录…", error = "", details = null, detailsToc = emptyList(), detailsIndex = -1)
        try {
            val details = detailsFor(book.url)
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

    private suspend fun detailsFor(url: String): Updates.Details =
        if (TomatoParser.bookId(url).isNotEmpty()) tomato.details(url)
        else runInterruptible { Updates.validateDetails(engineOrThrow.details(url)) }

    private suspend fun importDownloaded(book: OnlineResult, format: String): Boolean {
        if (book.sourceId == TomatoParser.SOURCE_ID) {
            val details = tomato.details(book.url)
            val text = tomato.chapters(details.toc) { done, total ->
                _state.value = _state.value.copy(message = "正在读取公开正文：$done / $total 章…")
            }
            currentCoroutineContext().ensureActive()
            return saveDownloaded(backfill(book, details.book), text, details.toc)
        }
        val result = runInterruptible { engineOrThrow.download(book.url, format) }
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
        if (!store.exists(book)) throw IllegalStateException("正文缺失，请先重新关联或恢复备份。")
        val details = detailsFor(book.onlineUrl)
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
        val addition = if (TomatoParser.bookId(book.onlineUrl).isNotEmpty()) {
            tomato.chapters(plan.chapters)
        } else {
            val data = runInterruptible { engineOrThrow.update(book.onlineUrl, plan.start, array) }
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
        val engine = engineOrThrow
        val info = runInterruptible { engine.probe() }
        val warning = info.optString("warning").ifBlank { engine.diagnostics() }
        buildString {
            append("内置引擎 ${info.optString("version")} 可用，加载 ${info.optInt("sources")} 条书源规则。")
            if (warning.isNotBlank()) append(" 配置自检：" + warning)
        }
    } }
}
