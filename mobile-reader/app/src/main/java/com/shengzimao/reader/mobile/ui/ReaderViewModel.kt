package com.shengzimao.reader.mobile.ui

import com.shengzimao.reader.mobile.data.onlineChapters

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shengzimao.reader.mobile.AppContainer
import com.shengzimao.reader.mobile.ReaderApp
import com.shengzimao.reader.mobile.WelcomeBook
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.Bookmark
import com.shengzimao.reader.mobile.data.Chapter
import com.shengzimao.reader.mobile.data.ChapterParser
import com.shengzimao.reader.mobile.data.DocumentImporter
import com.shengzimao.reader.mobile.data.Library
import com.shengzimao.reader.mobile.data.LibraryStore
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.SourceDocument
import com.shengzimao.reader.mobile.data.UriDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.UUID

data class UiMessage(val text: String, val error: Boolean = false, val id: Long = System.nanoTime())

data class ImportProgress(
    val active: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val fileName: String = "",
)

data class LibraryUiState(
    val loaded: Boolean = false,
    val books: List<Book> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
    val importProgress: ImportProgress = ImportProgress(),
    val message: UiMessage? = null,
    val query: String = "",
    val statusFilter: String = "all",
    val online: com.shengzimao.reader.mobile.data.OnlineUiState = com.shengzimao.reader.mobile.data.OnlineUiState(),
    val tomato: com.shengzimao.reader.mobile.data.OnlineUiState = com.shengzimao.reader.mobile.data.OnlineUiState(),
    val consentVersion: String = com.shengzimao.reader.mobile.data.Consent.VERSION,
    val consentAccepted: Boolean = false,
    val autoUpdate: Boolean = false,
) {
    val reading: List<Book> get() = books.filter { it.position > 0 && !it.isDone }
    val unread: List<Book> get() = books.filter { it.position <= 0 && !it.isDone }
    val finished: List<Book> get() = books.filter { it.isDone }
}

data class ReaderUiState(
    val loaded: Boolean = false,
    val book: Book = Book(title = ""),
    val chapters: List<Chapter> = emptyList(),
    val text: String = "",
    val settings: ReaderSettings = ReaderSettings(),
    val message: UiMessage? = null,
)

class ReaderViewModel(private val container: AppContainer) : ViewModel() {
    private val store: LibraryStore = container.store
    private val settingsRepository = container.settings

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    private var library: Library = Library()

    /**
     * 书库内存态与磁盘写入共用一把锁：阅读进度（每 20 秒）、书签、
     * 在线下载与追更都会改 library，非原子读改写会互相覆盖。
     */
    private val libraryLock = Any()

    private fun mutateLibrary(transform: (Library) -> Library, persist: Boolean = true): Library = synchronized(libraryLock) {
        val next = transform(library)
        library = next
        if (persist) store.save(next)
        next
    }

    /** 在线搜书 / 追更；引擎加载与网络请求都在 IO 线程串行执行。 */
    val online = OnlineController(
        context = container.context,
        store = store,
        readLibrary = { synchronized(libraryLock) { library } },
        writeLibrary = { updated ->
            val next = mutateLibrary({ updated })
            _ui.value = _ui.value.copy(books = next.books)
        },
        saveDownloaded = { result, text, chapters -> saveDownloadedBook(result, text, chapters) },
        notify = { text, error -> _ui.value = _ui.value.copy(message = UiMessage(text, error)) },
        concurrencyProvider = { _ui.value.settings.downloadConcurrency },
    )

    val tomato = OnlineController(
        context = container.context,
        store = store,
        readLibrary = { synchronized(libraryLock) { library } },
        writeLibrary = { updated ->
            val next = mutateLibrary({ updated })
            _ui.update { it.copy(books = next.books) }
        },
        saveDownloaded = { result, text, chapters -> saveDownloadedBook(result, text, chapters) },
        notify = { text, error -> _ui.update { it.copy(message = UiMessage(text, error)) } },
        tomatoPage = true,
        concurrencyProvider = { _ui.value.settings.downloadConcurrency },
    )
    private val onlineJobs = mutableMapOf<Boolean, Job>()

    val manga = MangaController(container.context, viewModelScope)

    /** 漫画专属阅读设置，与小说 ReaderSettings 分开存储。 */
    private val mangaSettingsRepository = com.shengzimao.reader.mobile.data.MangaSettingsRepository(container.context)
    private val _mangaSettings = MutableStateFlow(com.shengzimao.reader.mobile.data.MangaSettings())
    val mangaSettings: StateFlow<com.shengzimao.reader.mobile.data.MangaSettings> = _mangaSettings.asStateFlow()

    init {
        viewModelScope.launch {
            _mangaSettings.value = runCatching { mangaSettingsRepository.load() }
                .getOrDefault(com.shengzimao.reader.mobile.data.MangaSettings())
        }
    }

    fun updateMangaSettings(transform: (com.shengzimao.reader.mobile.data.MangaSettings) -> com.shengzimao.reader.mobile.data.MangaSettings) {
        val next = transform(_mangaSettings.value)
        _mangaSettings.value = next
        viewModelScope.launch { runCatching { mangaSettingsRepository.save(next) } }
    }

    val downloads = com.shengzimao.reader.mobile.data.DownloadQueue(
        java.io.File(container.context.filesDir, "downloads/tasks.json"), viewModelScope,
        canRun = { com.shengzimao.reader.mobile.data.NetworkPolicy.connected(container.context) && (!it.wifiOnly || com.shengzimao.reader.mobile.data.NetworkPolicy.wifi(container.context)) },
        defaultWifiOnly = { _ui.value.settings.downloadWifiOnly },
        onEnqueued = {
            if (container.context is ReaderApp) runCatching { com.shengzimao.reader.mobile.DownloadService.start(container.context) }
                .onFailure { message("后台下载服务启动失败，当前页面仍可下载：${it.message}", true) }
        },
    ) { task, progress ->
        require(container.context.filesDir.usableSpace >= 16L * 1024 * 1024) { "存储空间不足 16 MB，请释放空间后继续下载" }
        if (task.kind == "comic") {
            val payload = org.json.JSONObject(task.payload)
            manga.download(payload.getString("bookId"), payload.optInt("chapter", -1), progress, payload.optJSONArray("chapterUrls")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
                pageProgress = { done, total, failed -> reportComicDownloadPages(task.id, done, total, failed) })
        } else {
            val payload = org.json.JSONObject(task.payload)
            val book = com.shengzimao.reader.mobile.data.OnlineResult.fromJson(payload)
            val controller = onlineController(book.sourceId == com.shengzimao.reader.mobile.data.TomatoParser.SOURCE_ID)
            val selected = payload.optJSONArray("selectedChapters")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    com.shengzimao.reader.mobile.data.OnlineChapter(item.optString("title"), item.optString("url"))
                }
            }
            val added = if (!selected.isNullOrEmpty()) {
                // 选章下载：只读指定章节。
                controller.downloadChapters(book, selected, task.format)
            } else {
                controller.downloadQueued(book, task.format, progress)
            }
            val id = synchronized(libraryLock) { library }.books.firstOrNull { it.onlineUrl == book.url && it.onlineSourceGroup == book.sourceGroup && it.onlineSourceKey == book.sourceKey }?.id.orEmpty()
            com.shengzimao.reader.mobile.data.DownloadOutcome(id, added)
        }
    }

    private fun reportComicDownloadPages(id: String, done: Int, total: Int, failed: Int): Unit { downloads.reportPages(id, done, total, failed) }

    private fun onlineController(tomatoPage: Boolean) = if (tomatoPage) tomato else online

    private fun launchOnline(tomatoPage: Boolean, block: suspend (OnlineController) -> Unit) {
        if (onlineJobs[tomatoPage]?.isCompleted == false) return
        onlineJobs[tomatoPage] = viewModelScope.launch {
            try {
                block(onlineController(tomatoPage))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onlineController(tomatoPage).report(error.message ?: "在线操作失败", true)
            }
        }
    }

    fun cancelOnline(tomatoPage: Boolean = false) {
        onlineJobs[tomatoPage]?.cancel()
    }

    init {
        viewModelScope.launch { online.state.collect { value -> _ui.update { it.copy(online = value) } } }
        viewModelScope.launch { tomato.state.collect { value -> _ui.update { it.copy(tomato = value) } } }
        launchSafe { online.loadSourceGroups() }
        viewModelScope.launch {
            val settings = settingsRepository.load()
            val loaded = withContext(Dispatchers.IO) {
                val base = store.load()
                ensureWelcomeBook(base)
                // 欢迎书写入后重新读取，避免与 store.save 的状态副本不一致。
                val next = store.load()
                refreshMissing(next)
                store.load()
            }
            library = loaded
            _ui.value = _ui.value.copy(
                loaded = true,
                books = loaded.books,
                settings = settings,
                consentAccepted = com.shengzimao.reader.mobile.data.Consent.accepted(loaded.consentVersion, loaded.consentAccepted),
                consentVersion = loaded.consentVersion,
                autoUpdate = loaded.autoUpdate,
            )
            if (settings.downloadAutoResume) downloads.resumeInterrupted()
        }
        // 追更定时器：每 30 分钟检查一次（仅应用在前台运行且开关开启时）。
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30 * 60 * 1000L)
                val enabled = synchronized(libraryLock) { library.autoUpdate }
                if (!enabled) continue
                val ids = synchronized(libraryLock) { library }.books
                    .filter { it.tracking && it.onlineUrl.isNotEmpty() }
                    .take(200)
                    .map { it.id }
                if (ids.isNotEmpty()) runCatching { online.updateBooks(ids) }
                for (comic in manga.state.value.books.filter { it.tracking && !it.local }.take(200)) { manga.checkUpdates(comic.id).join() }
                _ui.value = _ui.value.copy(books = synchronized(libraryLock) { library }.books)
            }
        }
    }

    private fun ensureWelcomeBook(source: Library) {
        if (source.books.any { it.id == WelcomeBook.ID }) return
        val text = WelcomeBook.text
        val book = Book(
            id = WelcomeBook.ID,
            title = "欢迎使用圣子猫阅读",
            author = "使用说明",
            format = "TXT",
            length = text.length,
            position = 0,
            createdAt = System.currentTimeMillis(),
            lastReadAt = 0L,
            intro = "六章读完，就知道这台手机上的阅读器怎么用。",
            sourceName = "内置",
            chapters = ChapterParser.find(text),
            digest = LibraryStore.digest(text),
        )
        val file = store.writeText(book.id, text)
        val updated = source.copy(books = listOf(book.copy(file = file)) + source.books)
        store.save(updated)
        library = updated
    }

    private fun refreshMissing(source: Library) {
        val patched = source.books.map { book ->
            val missing = !book.onlineOnly && !store.exists(book)
            if (book.missing == missing) book else book.copy(missing = missing)
        }
        if (patched == source.books) return
        library = source.copy(books = patched)
        store.save(library)
    }

    fun setQuery(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    fun setStatusFilter(filter: String) {
        _ui.value = _ui.value.copy(statusFilter = filter)
    }

    fun consumeMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    fun message(text: String, error: Boolean = false) {
        _ui.value = _ui.value.copy(message = UiMessage(text, error))
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_ui.value.settings)
        _ui.value = _ui.value.copy(settings = next)
        viewModelScope.launch { settingsRepository.save(next) }
    }

    fun importDocuments(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(importProgress = ImportProgress(true, 0, uris.size, ""))
            var imported = 0
            var duplicates = 0
            val errors = ArrayList<String>()
            for ((index, uri) in uris.withIndex()) {
                val document = runCatching { UriDocument.from(context, uri) }.getOrNull()
                if (document == null) {
                    errors.add("无法读取所选文件。")
                    continue
                }
                _ui.value = _ui.value.copy(importProgress = ImportProgress(true, index, uris.size, document.displayName))
                try {
                    val result = withContext(Dispatchers.IO) { importDocument(document) }
                    if (result.duplicate) duplicates++ else imported++
                } catch (error: Throwable) {
                    errors.add("${document.displayName}：${error.message ?: "导入失败"}")
                }
            }
            _ui.value = _ui.value.copy(
                books = library.books,
                importProgress = ImportProgress(false),
                message = UiMessage(
                    buildString {
                        append("导入 $imported 本")
                        if (duplicates > 0) append("，重复 $duplicates 本")
                        if (errors.isNotEmpty()) append("；${errors.first()}${if (errors.size > 1) " 等 ${errors.size} 项失败" else ""}")
                    },
                    error = errors.isNotEmpty(),
                ),
            )
        }
    }

    private fun importDocument(document: SourceDocument): com.shengzimao.reader.mobile.data.ImportResult {
        val parsed = DocumentImporter.parse(document)
        val text = parsed.text
        val digest = LibraryStore.digest(text)
        val existing = library.books.firstOrNull { it.digest == digest && it.digest.isNotEmpty() }
        if (existing != null) return com.shengzimao.reader.mobile.data.ImportResult(existing, text, duplicate = true)
        val id = LibraryStore.newId()
        val file = store.writeText(id, text)
        parsed.cover?.let { store.saveCover(id, it) }
        val book = Book(
            id = id,
            title = parsed.title.ifBlank { ChapterParser.titleFromFileName(document.displayName) }.take(250),
            author = parsed.author.take(120),
            file = file,
            format = document.extension.uppercase().ifEmpty { "TXT" },
            length = text.length,
            position = 0,
            createdAt = System.currentTimeMillis(),
            intro = parsed.intro,
            sourceName = "本地",
            chapters = ChapterParser.find(text),
            digest = digest,
        )
        val next = synchronized(libraryLock) {
            library = library.copy(books = listOf(book) + library.books)
            library
        }
        persist(next)
        return com.shengzimao.reader.mobile.data.ImportResult(book, text)
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.removeText(book) }
            val next = synchronized(libraryLock) {
                library = library.copy(
                    books = library.books.filterNot { it.id == book.id },
                    activeId = if (library.activeId == book.id) "" else library.activeId,
                )
                library
            }
            _ui.value = _ui.value.copy(books = next.books, message = UiMessage("已从书库移除《${book.title}》"))
            persist(next)
        }
    }

    fun deleteBooks(books: List<Book>) {
        if (books.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { books.forEach { store.removeText(it) } }
            val ids = books.map { it.id }.toSet()
            val next = synchronized(libraryLock) {
                library = library.copy(
                    books = library.books.filterNot { it.id in ids },
                    activeId = if (library.activeId in ids) "" else library.activeId,
                )
                library
            }
            _ui.value = _ui.value.copy(books = next.books, message = UiMessage("已移除 ${books.size} 本"))
            persist(next)
        }
    }

    fun toggleFavorite(book: Book) = patch(book.id) { it.copy(favorite = !it.favorite) }
    fun togglePinned(book: Book) = patch(book.id) { it.copy(pinned = !it.pinned) }
    fun toggleCompleted(book: Book) = patch(book.id) { it.copy(completed = !it.completed) }

    fun editBook(book: Book, title: String, author: String, intro: String) = patch(book.id) {
        it.copy(title = title.trim().ifEmpty { it.title }.take(250), author = author.trim().take(120), intro = intro.take(4000))
    }

    private fun patch(id: String, transform: (Book) -> Book) {
        val next = synchronized(libraryLock) {
            library = library.copy(books = library.books.map { if (it.id == id) transform(it) else it })
            library
        }
        _ui.value = _ui.value.copy(books = next.books)
        persist(next)
    }

    fun clearReadingHistory() {
        val next = synchronized(libraryLock) {
            library = library.copy(books = library.books.map { it.copy(lastReadAt = 0L) })
            library
        }
        _ui.value = _ui.value.copy(books = next.books)
        persist(next)
    }

    /** 阅读器打开书籍：补齐长度/章节，返回完整正文。 */
    suspend fun openBook(id: String): Pair<Book, String>? = withContext(Dispatchers.IO) {
        val book = synchronized(libraryLock) { library }.books.firstOrNull { it.id == id } ?: return@withContext null
        if (!store.exists(book)) {
            // 只在内存里标记并落盘一次，不在 IO 线程里再起协程。
            mutateLibrary(transform = { state: Library -> state.copy(books = state.books.map { if (it.id == id) it.copy(missing = true) else it }) })
            _ui.value = _ui.value.copy(books = synchronized(libraryLock) { library }.books)
            return@withContext null
        }
        val text = runCatching { store.readText(book) }.getOrNull() ?: return@withContext null
        val chapters = if (book.chapters.isEmpty()) ChapterParser.find(text) else book.chapters
        val updated = book.copy(
            length = text.length,
            position = book.position.coerceIn(0, maxOf(0, text.length - 1)),
            chapters = chapters,
            missing = false,
            lastReadAt = if (book.lastReadAt == 0L) System.currentTimeMillis() else book.lastReadAt,
        )
        val next = mutateLibrary(transform = { state: Library ->
            state.copy(books = state.books.map { if (it.id == id) updated else it }, activeId = id)
        })
        _ui.value = _ui.value.copy(books = next.books)
        updated to text
    }

    fun savePosition(bookId: String, position: Int) {
        val next = synchronized(libraryLock) {
            val index = library.books.indexOfFirst { it.id == bookId }
            if (index < 0) return
            val book = library.books[index]
            val clamped = position.coerceIn(0, maxOf(0, book.length - 1))
            if (clamped == book.position) return
            val updated = book.copy(position = clamped, updatedAt = System.currentTimeMillis())
            library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
            library
        }
        // 落盘用本次的不可变快照，避免异步执行时读到更晚的状态。
        persist(next)
    }

    /**
     * 串行落盘：阅读计时每 20 秒写一次，与进度/书签/追更的写入交叉时，
     * 直接用旧快照覆盖会把这期间的修改冲掉（表现为进度倒退）。
     * 这里用单线程串行执行器按提交顺序写盘，前面的写完成才轮到后面。
     */
    private val writeDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "szm-library-writer").apply { isDaemon = true }
    }

    private fun persist(snapshot: Library) {
        writeDispatcher.execute {
            runCatching { store.save(snapshot) }
            val books = synchronized(libraryLock) { library }.books
            _ui.update { it.copy(books = books) }
        }
    }

    fun recordReading(bookId: String, seconds: Long) {
        if (seconds <= 0) return
        val next = synchronized(libraryLock) {
            val index = library.books.indexOfFirst { it.id == bookId }
            if (index < 0) return
            val book = library.books[index]
            val today = LocalDate.now().toString()
            val daily = book.readingDailySeconds.toMutableMap()
            daily[today] = (daily[today] ?: 0L) + seconds
            val trimmed = if (daily.size > 400) {
                daily.entries.sortedByDescending { it.key }.take(400).associate { it.key to it.value }
            } else {
                daily
            }
            val updated = book.copy(
                readingSeconds = book.readingSeconds + seconds,
                readingDailySeconds = trimmed,
                lastReadAt = System.currentTimeMillis(),
            )
            library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
            library
        }
        persist(next)
    }

    fun addBookmark(bookId: String, position: Int, label: String) {
        var added = false
        val next = synchronized(libraryLock) {
            val index = library.books.indexOfFirst { it.id == bookId }
            if (index < 0) return
            val book = library.books[index]
            if (book.bookmarks.any { it.position == position }) return
            val chapter = book.chapters.lastOrNull { it.position <= position }?.title ?: book.title
            val mark = Bookmark(position = position, label = label.ifBlank { chapter.take(60) }, createdAt = System.currentTimeMillis())
            val updated = book.copy(bookmarks = (book.bookmarks + mark).sortedBy { it.position })
            library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
            added = true
            library
        }
        if (!added) {
            message("这一页已经有书签了")
            return
        }
        _ui.value = _ui.value.copy(books = synchronized(libraryLock) { library }.books)
        persist(next)
        message("书签已保存")
    }

    fun removeBookmark(bookId: String, bookmarkId: String) {
        val next = synchronized(libraryLock) {
            val index = library.books.indexOfFirst { it.id == bookId }
            if (index < 0) return
            val book = library.books[index]
            val updated = book.copy(bookmarks = book.bookmarks.filterNot { it.id == bookmarkId })
            library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
            library
        }
        _ui.value = _ui.value.copy(books = synchronized(libraryLock) { library }.books)
        persist(next)
    }

    /** 新增或更新一条划线/笔记：[selection] 是全文偏移区间。 */
    fun addHighlight(bookId: String, selection: com.shengzimao.reader.mobile.reader.TextSelection, text: String, note: String = "", tags: List<String> = emptyList()) {
        val ordered = selection.ordered()
        val start = ordered.start.coerceIn(0, maxOf(0, text.length - 1))
        val end = ordered.end.coerceIn(start + 1, text.length)
        if (end <= start) {
            message("请先选中一段文字")
            return
        }
        val snippet = text.substring(start, end).replace('\n', ' ').replace("\u3000", " ").trim()
        if (snippet.isEmpty()) {
            message("这段内容为空")
            return
        }
        val next = synchronized(libraryLock) {
            val index = library.books.indexOfFirst { it.id == bookId }
            if (index < 0) return
            val book = library.books[index]
            // 同一起点同长度视为重复，更新笔记而不是再叠一条。
            val existing = book.highlights.firstOrNull { it.start == start && it.end == end }
            val marks = if (existing != null) {
                book.highlights.map {
                    if (it.id == existing.id) it.copy(note = note.take(2000), snippet = snippet, tags = tags.take(20).map { tag -> tag.take(40) }) else it
                }
            } else {
                book.highlights + com.shengzimao.reader.mobile.data.Highlight(
                    start = start,
                    end = end,
                    snippet = snippet.take(400),
                    note = note.take(2000),
                    tags = tags.take(20).map { it.take(40) },
                    chapterTitle = book.chapters.lastOrNull { it.position <= start }?.title.orEmpty(),
                )
            }
            val updated = book.copy(highlights = marks.sortedBy { it.start })
            library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
            library
        }
        _ui.value = _ui.value.copy(books = synchronized(libraryLock) { library }.books)
        persist(next)
        message(if (note.isBlank()) "已划线" else "笔记已保存")
    }

    fun removeHighlight(bookId: String, highlightId: String) {
        val next = synchronized(libraryLock) {
            val index = library.books.indexOfFirst { it.id == bookId }
            if (index < 0) return
            val book = library.books[index]
            val updated = book.copy(highlights = book.highlights.filterNot { it.id == highlightId })
            library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
            library
        }
        _ui.value = _ui.value.copy(books = synchronized(libraryLock) { library }.books)
        persist(next)
    }

    fun currentBook(bookId: String): Book? = _ui.value.books.firstOrNull { it.id == bookId }

    // ---------------- 在线搜书 / 追更 / 备份 ----------------

    private fun launchSafe(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _ui.value = _ui.value.copy(message = UiMessage(error.message ?: "操作失败", true))
        }
    }

    /** 引擎返回的正文落库：按在线 URL 去重，重复则跳过。 */
    private fun saveDownloadedBook(
        result: com.shengzimao.reader.mobile.data.OnlineResult,
        text: String,
        chapters: List<com.shengzimao.reader.mobile.data.OnlineChapter>,
    ): Boolean = synchronized(libraryLock) {
        val existing = library.books.firstOrNull { it.onlineUrl.isNotEmpty() && it.onlineUrl == result.url && it.onlineSourceGroup == result.sourceGroup && it.onlineSourceKey == result.sourceKey }
        if (existing != null && store.exists(existing)) return@synchronized false
        val id = existing?.id ?: LibraryStore.newId()
        val file = store.writeText(id, text)
        val localChapters = runCatching {
            com.shengzimao.reader.mobile.data.Updates.mapOnlineChapters(text, chapters)
        }.getOrElse { ChapterParser.find(text) }
        val base = existing ?: Book(
            id = id,
            title = result.bookName.ifBlank { "未命名书籍" }.take(250),
            author = result.author.take(120),
            format = "TXT",
            createdAt = System.currentTimeMillis(),
            sourceName = result.sourceName.ifBlank { "在线" },
        )
        val reading = com.shengzimao.reader.mobile.data.OnlineReadingMigration.apply(base, text, chapters, localChapters)
        val merged = reading.copy(
            onlineOnly = false,
            onlineOffset = 0,
            format = "TXT",
            file = file,
            length = text.length,
            position = reading.position.coerceIn(0, maxOf(0, text.length - 1)),
            chapters = localChapters,
            digest = LibraryStore.digest(text),
            onlineUrl = result.url,
            onlineSourceGroup = result.sourceGroup,
            onlineSourceKey = result.sourceKey,
            onlineManifest = com.shengzimao.reader.mobile.data.OnlineChapterCodec.encode(chapters),
            tracking = true,
            category = result.category.ifBlank { base.category },
            intro = result.intro.ifBlank { base.intro },
            latestChapter = result.latestChapter.ifBlank { base.latestChapter },
            lastUpdateTime = result.lastUpdateTime.ifBlank { base.lastUpdateTime },
            status = result.status.ifBlank { base.status },
            sourceName = result.sourceName.ifBlank { base.sourceName },
            missing = false,
        )
        library = if (existing == null) {
            library.copy(books = listOf(merged) + library.books)
        } else {
            library.copy(books = library.books.map { if (it.id == id) merged else it })
        }
        store.save(library)
        _ui.value = _ui.value.copy(books = library.books)
        true
    }

    fun addOnlineShelf(
        result: com.shengzimao.reader.mobile.data.OnlineResult,
        toc: List<com.shengzimao.reader.mobile.data.OnlineChapter> = emptyList(),
        onSaved: (String) -> Unit = {},
    ) = launchSafe {
        require(ui.value.consentAccepted) { "请先阅读并确认在线使用声明" }
        val tomatoPage = com.shengzimao.reader.mobile.data.TomatoParser.bookId(result.url).isNotEmpty()
        if (onlineController(tomatoPage).state.value.searching) onlineJobs[tomatoPage]?.cancelAndJoin()
        message("正在读取在线目录，请稍候…")
        val chapters = toc.ifEmpty { onlineController(com.shengzimao.reader.mobile.data.TomatoParser.bookId(result.url).isNotEmpty()).readingDetails(result.url, result.sourceGroup, result.sourceKey).toc }
        require(chapters.isNotEmpty()) { "源站没有返回有效目录" }
        val next = withContext(Dispatchers.IO) { mutateLibrary(transform = { current ->
            val existing = current.books.firstOrNull { it.onlineUrl == result.url && it.onlineSourceGroup == result.sourceGroup && it.onlineSourceKey == result.sourceKey }
            if (existing != null) current else {
                val book = Book(title = result.bookName.take(250), author = result.author.take(120), onlineUrl = result.url,
                    onlineSourceGroup = result.sourceGroup,
            onlineSourceKey = result.sourceKey,
                    onlineOnly = true, onlineManifest = com.shengzimao.reader.mobile.data.OnlineChapterCodec.encode(chapters),
                    chapters = chapters.mapIndexed { i, chapter -> Chapter(chapter.title, i) }, length = chapters.size,
                    sourceName = result.sourceName, intro = result.intro, format = "在线", tracking = false)
                current.copy(books = listOf(book) + current.books)
            }
        }) }
        _ui.update { it.copy(books = next.books) }
        val saved = next.books.first { it.onlineUrl == result.url && it.onlineSourceGroup == result.sourceGroup && it.onlineSourceKey == result.sourceKey }
        message(if (saved.onlineOnly) "已加入在线书架，优先读取本机缓存；未缓存章节需联网，完整离线阅读可下载导入" else "本书已在本地书库")
        onSaved(saved.id)
    }

    fun switchOnlineSource(id: String, result: com.shengzimao.reader.mobile.data.OnlineResult, onSaved: () -> Unit) =
        switchOnlineSourceAt(id, result, "", -1, onSaved = onSaved)

    fun switchOnlineSourceAt(id: String, result: com.shengzimao.reader.mobile.data.OnlineResult,
        previousText: String, previousOffset: Int, previousChapter: Int = -1, onSaved: () -> Unit) = launchSafe {
        val previous = library.books.firstOrNull { it.id == id && it.onlineOnly } ?: error("只支持为在线作品换源")
        val targetController = onlineController(com.shengzimao.reader.mobile.data.TomatoParser.bookId(result.url).isNotBlank())
        val targetTomato = com.shengzimao.reader.mobile.data.TomatoParser.bookId(result.url).isNotBlank()
        if (targetController.state.value.searching) onlineJobs[targetTomato]?.cancelAndJoin()
        val details = withTimeoutOrNull(25_000) { targetController.readingDetails(result.url, result.sourceGroup, result.sourceKey) } ?: error("新源目录读取超时，已保留原书源，可重试")
        require(details.toc.isNotEmpty()) { "新书源目录为空，已保留原书源" }
        val currentChapter = previousChapter.takeIf { it in previous.onlineChapters.indices } ?: previous.position
        val anchor = com.shengzimao.reader.mobile.data.ChapterAnchor.match(previous.onlineChapters, details.toc, currentChapter)
        val oldBody = previousText.ifBlank { previous.onlineChapters.getOrNull(currentChapter)?.let { readCachedOnline(previous, it) }.orEmpty() }
        val nextText = withTimeoutOrNull(25_000) { targetController.readChapter(result.url, details.toc[anchor.index], result.sourceGroup, result.sourceKey) } ?: error("新源正文读取超时，已保留原书源和进度，可重试")
        require(nextText.isNotBlank()) { "新书源正文为空，已保留原书源和进度" }
        val textAnchor = withContext(Dispatchers.Default) { com.shengzimao.reader.mobile.data.TextPositionAnchor.match(oldBody,
            nextText, previousOffset.takeIf { it >= 0 } ?: previous.onlineCharacterOffset.coerceAtLeast(0)) }
        val cacheKey = com.shengzimao.reader.mobile.data.NovelSourceGroups.identity(result.url, result.sourceGroup, result.sourceKey)
        onlineSession.put(cacheKey, details.toc[anchor.index].url, nextText)
        withContext(Dispatchers.IO) { runCatching { readingCache.write(cacheKey, details.toc[anchor.index].url, nextText) } }
        val next = withContext(Dispatchers.IO) { mutateLibrary(transform = { current -> current.copy(books = current.books.map { book ->
            if (book.id != id || book.onlineUrl != previous.onlineUrl || book.onlineSourceGroup != previous.onlineSourceGroup || book.onlineSourceKey != previous.onlineSourceKey) book else book.copy(onlineUrl = result.url, sourceName = result.sourceName,
                onlineSourceGroup = result.sourceGroup,
            onlineSourceKey = result.sourceKey,
                onlineManifest = com.shengzimao.reader.mobile.data.OnlineChapterCodec.encode(details.toc),
                chapters = details.toc.mapIndexed { i, chapter -> Chapter(chapter.title, i) }, length = details.toc.size,
                position = anchor.index, onlineOffset = 0, onlineCharacterOffset = textAnchor.offset, onlineParagraphFraction = 0f)
        }) }) }
        _ui.update { it.copy(books = next.books) }
        message(if (anchor.exact && textAnchor.exact) "已换源，章节与附近正文位置已匹配" else "已换源，未能完全匹配的部分按进度比例定位，请核对正文")
        onSaved()
    }

    val onlineSession = com.shengzimao.reader.mobile.data.OnlineSessionCache()
    private val readingCache = com.shengzimao.reader.mobile.data.OnlineReadingCache(java.io.File(container.context.filesDir, "reading-cache/novels"))
    private var cacheWarningShown = false
    private fun onlineCacheKey(book: Book): String = com.shengzimao.reader.mobile.data.NovelSourceGroups.identity(book.onlineUrl, book.onlineSourceGroup, book.onlineSourceKey)
    suspend fun readOnlineChapter(book: Book, chapter: com.shengzimao.reader.mobile.data.OnlineChapter, force: Boolean = false): String {
        val cacheKey = onlineCacheKey(book)
        if (!force) onlineSession.get(cacheKey, chapter.url)?.let { return it }
        if (!force) withContext(Dispatchers.IO) { readingCache.read(cacheKey, chapter.url) }?.let {
            onlineSession.put(cacheKey, chapter.url, it); return it
        }
        val text = onlineController(com.shengzimao.reader.mobile.data.TomatoParser.bookId(book.onlineUrl).isNotEmpty()).readChapter(book.onlineUrl, chapter, book.onlineSourceGroup, book.onlineSourceKey)
        onlineSession.put(cacheKey, chapter.url, text)
        val saved = withContext(Dispatchers.IO) { runCatching { readingCache.write(cacheKey, chapter.url, text) }.isSuccess }
        if (!saved && !cacheWarningShown) { cacheWarningShown = true; message("正文可继续阅读，但本机缓存写入失败；请检查存储空间，退出后可能需要重新加载", true) }
        return text
    }

    suspend fun cachedOnlineUrls(book: Book): Set<String> = withContext(Dispatchers.IO) {
        book.onlineChapters.map { it.url }.filter { readingCache.contains(onlineCacheKey(book), it) || onlineSession.get(onlineCacheKey(book), it) != null }.toSet()
    }
    suspend fun readCachedOnline(book: Book, chapter: com.shengzimao.reader.mobile.data.OnlineChapter): String? =
        onlineSession.get(onlineCacheKey(book), chapter.url) ?: withContext(Dispatchers.IO) { readingCache.read(onlineCacheKey(book), chapter.url) }

    suspend fun readDownloadedOnline(book: Book, chapter: com.shengzimao.reader.mobile.data.OnlineChapter): String? = withContext(Dispatchers.IO) {
        com.shengzimao.reader.mobile.data.ChapterDownloadCache(java.io.File(container.context.filesDir,
            "downloads/tomato-parts/" + com.shengzimao.reader.mobile.data.mangaHash(book.onlineUrl))).read(chapter)?.let { return@withContext it }
        val downloaded = ui.value.books.firstOrNull { !it.onlineOnly && it.onlineUrl == book.onlineUrl && it.onlineSourceGroup == book.onlineSourceGroup && it.onlineSourceKey == book.onlineSourceKey && container.store.exists(it) } ?: return@withContext null
        com.shengzimao.reader.mobile.data.DownloadedChapterText.read(container.store, downloaded, chapter)
    }

    fun saveOnlinePosition(id: String, chapter: Int, offset: Int, characterOffset: Int = -1, fraction: Float = 0f, expectedUrl: String = "", expectedGroup: Int = 0, expectedSourceKey: String? = null) = launchSafe {
        val next = withContext(Dispatchers.IO) { mutateLibrary(transform = { current -> current.copy(books = current.books.map {
            if (it.id == id && it.onlineOnly && (expectedUrl.isBlank() || it.onlineUrl == expectedUrl) && (expectedGroup == 0 || it.onlineSourceGroup == expectedGroup) && (expectedSourceKey == null || it.onlineSourceKey == expectedSourceKey)) it.copy(position = chapter.coerceIn(0, maxOf(0, it.length - 1)),
                onlineOffset = offset.coerceAtLeast(0), onlineCharacterOffset = characterOffset,
                onlineParagraphFraction = fraction.coerceIn(0f, 1f), lastReadAt = System.currentTimeMillis(),
                newChapterCount = minOf(it.newChapterCount, (it.length - chapter - 1).coerceAtLeast(0))) else it
        }) }) }
        _ui.update { it.copy(books = next.books) }
    }

    fun saveBookPreferences(id: String, json: String) = launchSafe {
        val next = withContext(Dispatchers.IO) { mutateLibrary(transform = { current ->
            current.copy(books = current.books.map { if (it.id == id) it.copy(readerPreferences = json) else it })
        }) }
        _ui.update { it.copy(books = next.books) }
    }

    fun saveOnlineAnnotations(id: String, json: String) = launchSafe {
        require(json.length <= 2_000_000) { "书签笔记内容过大" }
        val next = withContext(Dispatchers.IO) { mutateLibrary(transform = { current ->
            current.copy(books = current.books.map { if (it.id == id) it.copy(onlineAnnotations = json) else it })
        }) }
        _ui.update { it.copy(books = next.books) }
    }

    fun openNovelChapter(id: String, index: Int, expectedTitle: String? = null, onReady: () -> Unit) = launchSafe {
        val next = withContext(Dispatchers.IO) { mutateLibrary(transform = { current ->
            require(current.books.any { it.id == id }) { "作品已从书架移除。" }
            current.copy(books = current.books.map { book ->
                if (book.id == id) com.shengzimao.reader.mobile.data.NovelChapterNavigation.moveTo(book, index, expectedTitle, System.currentTimeMillis()) else book
            })
        }) }
        _ui.update { it.copy(books = next.books) }
        onReady()
    }

    fun downloadShelfBook(book: Book) {
        if (!ui.value.consentAccepted) { message("请先确认在线使用声明", true); return }
        val result = com.shengzimao.reader.mobile.data.OnlineResult(bookName = book.title, author = book.author,
            url = book.onlineUrl, sourceName = book.sourceName,
            sourceGroup = book.onlineSourceGroup, sourceKey = book.onlineSourceKey,
            sourceId = if (book.onlineSourceKey.isBlank() && com.shengzimao.reader.mobile.data.TomatoParser.bookId(book.onlineUrl).isNotEmpty()) com.shengzimao.reader.mobile.data.TomatoParser.SOURCE_ID else -1)
        try { downloads.enqueue(listOf(com.shengzimao.reader.mobile.data.DownloadTask(title = book.title,
            source = book.sourceName, key = onlineCacheKey(book), payload = result.toJson().toString(), format = "txt")))
            message("已加入下载管理，导入完成后可离线阅读")
        } catch (failure: Exception) { message(failure.message ?: "无法加入下载队列", true) }
    }

    fun importComics(books: List<com.shengzimao.reader.mobile.data.MangaBook>) = launchSafe {
        for (book in books.distinctBy { it.id }.take(30)) {
            manga.keep(book) { id -> downloadComic(id) }?.join()
        }
    }

    fun searchOnline(keyword: String, tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        val history = _ui.value.online.searchHistory.ifEmpty { library.searchHistory }
        controller.search(keyword, history)
        val trimmed = onelineKeyword(keyword)
        if (trimmed.isNotEmpty()) {
            val next = synchronized(libraryLock) {
                if (library.searchHistory.firstOrNull() == trimmed) {
                    null
                } else {
                    library = library.copy(searchHistory = (listOf(trimmed) + library.searchHistory.filter { it != trimmed }).take(12))
                    library
                }
            }
            if (next != null) persist(next)
        }
    }

    fun selectNovelSourceGroup(group: Int) = launchOnline(false) { it.selectSourceGroup(group) }

    fun retryFailedNovelSources() = launchOnline(false) { it.retryFailedSources() }

    fun selectNovelSearchSources(urls: Set<String>?) = launchOnline(false) { it.selectSearchSources(urls) }

    fun importNovelSources(group: Int, uri: Uri) = launchOnline(false) { controller ->
        controller.importSources(group) {
            val input = container.context.contentResolver.openInputStream(uri) ?: error("无法打开书源文件。")
            input.use { com.shengzimao.reader.mobile.data.NovelSourceGroups.readImport(it) }
        }
    }

    fun importNovelSourcesText(group: Int, text: String) = launchOnline(false) { controller ->
        controller.importSources(group) { text }
    }

    private val novelSourceImportClient by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).callTimeout(35, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    fun importNovelSourcesUrl(group: Int, url: String) = launchOnline(false) { controller ->
        controller.importSources(group) {
            val uri = java.net.URI(url.trim())
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入有效的 HTTP(S) 书源 JSON 链接。" }
            val request = okhttp3.Request.Builder().url(uri.toASCIIString()).build()
            novelSourceImportClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "读取书源链接失败：HTTP ${response.code}，可改用文件或粘贴导入。" }
                val body = response.body
                require(body.contentLength() <= com.shengzimao.reader.mobile.data.NovelSourceGroups.MAX_BYTES) { "书源文件不能超过 32 MB。" }
                body.byteStream().use { com.shengzimao.reader.mobile.data.NovelSourceGroups.readImport(it) }
            }
        }
    }

    private fun onelineKeyword(value: String): String = value.trim().take(120)

    fun clearSearchHistory() {
        val next = synchronized(libraryLock) {
            library = library.copy(searchHistory = emptyList())
            library
        }
        _ui.value = _ui.value.copy(online = _ui.value.online.copy(searchHistory = emptyList()))
        persist(next)
    }

    fun openOnlineDetails(index: Int, tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        controller.details(index)
    }

    fun closeOnlineDetails(tomatoPage: Boolean = false) {
        onlineController(tomatoPage).closeDetails()
    }

    fun downloadOnline(index: Int, format: String, tomatoPage: Boolean = false) {
        downloadOnlineMany(listOf(index), format, tomatoPage)
    }

    /**
     * 只下载所选章节。
     *
     * 选章下载不进入"整本"队列去重键，而是用 `url#N` 作为任务 key，
     * 这样同一本书可以同时存在"全部下载"和"部分章节"两个任务而不会互相顶掉。
     */
    fun downloadOnlineChapters(index: Int, chapters: List<com.shengzimao.reader.mobile.data.OnlineChapter>, format: String, tomatoPage: Boolean = false) {
        if (!ui.value.consentAccepted) return
        if (chapters.isEmpty()) { message("请至少选择一个章节", true); return }
        try {
            val book = onlineController(tomatoPage).state.value.results.getOrNull(index)
                ?: run {
                    // 详情页打开时可能已重新搜索，退回用详情里的书。
                    onlineController(tomatoPage).state.value.details
                }
                ?: return
            val payload = book.toJson().put(
                "selectedChapters",
                org.json.JSONArray().also { array ->
                    chapters.forEach { array.put(org.json.JSONObject().put("title", it.title).put("url", it.url)) }
                },
            )
            val task = com.shengzimao.reader.mobile.data.DownloadTask(
                title = "${book.bookName}（${chapters.size} 章）",
                source = book.sourceName,
                key = com.shengzimao.reader.mobile.data.NovelSourceGroups.identity(book.url, book.sourceGroup, book.sourceKey) + "#" + chapters.size + "@" + (chapters.firstOrNull()?.url?.hashCode() ?: 0),
                payload = payload.toString(),
                format = format,
            )
            val added = downloads.enqueue(listOf(task))
            message(if (added > 0) "已加入 ${chapters.size} 章的下载任务，进度见下载管理" else "该章节任务已在下载队列中")
        } catch (failure: Exception) { message(failure.message ?: "加入下载队列失败", true) }
    }

    fun downloadOnlineMany(indexes: List<Int>, format: String, tomatoPage: Boolean = false) {
        if (!ui.value.consentAccepted) return
        try {
            val snapshot = onlineController(tomatoPage).state.value.results
            val tasks = indexes.distinct().take(30).mapNotNull { index -> snapshot.getOrNull(index)?.let { book ->
                com.shengzimao.reader.mobile.data.DownloadTask(title = book.bookName, source = book.sourceName,
                    key = com.shengzimao.reader.mobile.data.NovelSourceGroups.identity(book.url, book.sourceGroup, book.sourceKey), payload = book.toJson().toString(), format = format)
            } }
            val added = downloads.enqueue(tasks)
            message(if (added > 0) "已加入 $added 个下载任务，进度见下载管理" else "任务已在下载队列中")
        } catch (failure: Exception) { message(failure.message ?: "加入下载队列失败", true) }
    }

    fun downloadComic(bookId: String, chapter: Int = -1) {
        val book = manga.state.value.books.firstOrNull { it.id == bookId } ?: return
        if (book.local) { message("本地漫画已可离线阅读"); return }
        try {
            val label = if (chapter >= 0) "${book.title} · ${book.chapters.getOrNull(chapter)?.title.orEmpty()}" else book.title
            val payload = org.json.JSONObject().put("bookId", bookId).put("chapter", chapter)
            val added = downloads.enqueue(listOf(com.shengzimao.reader.mobile.data.DownloadTask(kind = "comic", title = label,
                source = book.sourceName, key = "$bookId:$chapter", payload = payload.toString(), format = "图片")))
            message(if (added > 0) "已加入漫画缓存队列" else "漫画缓存任务已在队列中")
        } catch (failure: Exception) { message(failure.message ?: "加入缓存队列失败", true) }
    }

    fun downloadComicRange(bookId: String, start: Int, end: Int, unread: Boolean = false) {
        val book = manga.state.value.books.firstOrNull { it.id == bookId } ?: return
        if (book.local) return
        val from = start.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0)); val to = end.coerceIn(from, book.chapters.lastIndex.coerceAtLeast(from))
        val urls = book.chapters.slice(from..to).filter { !unread || it.url !in book.cachedChapters }.map { it.url }
        if (urls.isEmpty()) { message("所选范围已全部下载"); return }
        try {
            val payload = org.json.JSONObject().put("bookId", bookId).put("chapter", -1).put("chapterUrls", org.json.JSONArray(urls))
            downloads.enqueue(listOf(com.shengzimao.reader.mobile.data.DownloadTask(kind = "comic", title = "${book.title} · ${from + 1}–${to + 1}章",
                source = book.sourceName, key = "$bookId:${com.shengzimao.reader.mobile.data.mangaHash(urls.joinToString())}", payload = payload.toString(), format = "图片")))
            message("已加入章节下载队列")
        } catch (failure: Exception) { message(failure.message ?: "加入队列失败", true) }
    }

    fun acceptConsent() {
        val next = synchronized(libraryLock) {
            library = library.copy(consentVersion = com.shengzimao.reader.mobile.data.Consent.VERSION, consentAccepted = true, consentAt = System.currentTimeMillis())
            library
        }
        _ui.value = _ui.value.copy(consentAccepted = true)
        persist(next)
    }

    fun probeEngine(tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        val text = controller.probe()
        _ui.value = _ui.value.copy(message = UiMessage(text))
    }

    fun toggleTracking(book: Book) = launchSafe {
        online.toggleTracking(book.id, !book.tracking)
        _ui.value = _ui.value.copy(books = library.books)
    }

    fun updateTrackedBooks(ids: List<String>) = launchSafe {
        online.updateBooks(ids)
        _ui.value = _ui.value.copy(books = library.books)
    }

    fun updateAllTracked() = launchSafe {
        val ids = synchronized(libraryLock) { library }.books.filter { it.tracking && it.onlineUrl.isNotEmpty() }.map { it.id }
        if (ids.isEmpty()) {
            _ui.value = _ui.value.copy(message = UiMessage("还没有加入追更的在线书籍。"))
            return@launchSafe
        }
        online.updateBooks(ids)
        _ui.value = _ui.value.copy(books = library.books)
    }

    fun setAutoUpdate(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoUpdate = enabled)
        val next = synchronized(libraryLock) {
            library = library.copy(autoUpdate = enabled)
            library
        }
        persist(next)
    }

    private fun syncOnline() {
        _ui.value = _ui.value.copy(online = online.state.value)
    }

    fun exportBackup(uri: Uri, includeMangaImages: Boolean = false) = launchSafe {
        val context = container.context
        val report = withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入所选位置。")
            com.shengzimao.reader.mobile.data.Backup.export(store, library, com.shengzimao.reader.mobile.data.ReadingPreferences.encode(_ui.value.settings), stream) { zip ->
                com.shengzimao.reader.mobile.data.MangaBackup.write(zip, manga.store, mangaSettings.value, includeMangaImages)
            }
        }
        _ui.value = _ui.value.copy(message = UiMessage("已导出 ${report.included} 本正文（共 ${report.books} 本，缺失 ${report.missing} 本）。"))
    }

    fun importBackup(uri: Uri) = launchSafe {
        val context = container.context
        val report = withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件。")
            com.shengzimao.reader.mobile.data.MangaBackup.Restore(manga.store, context.cacheDir).use { extra ->
                val result = com.shengzimao.reader.mobile.data.Backup.import(store, library, stream, extra::accept)
                val (_, preferences) = extra.finish()
                preferences?.let { json -> val restored = com.shengzimao.reader.mobile.data.ReadingPreferences.manga(json, mangaSettings.value)
                    mangaSettingsRepository.save(restored); _mangaSettings.value = restored }
                result
            }
        }
        library = withContext(Dispatchers.IO) { store.load() }
        report.settingsJson?.let { json -> updateSettings { com.shengzimao.reader.mobile.data.ReadingPreferences.novel(json, it) } }
        manga.reloadLibrary()
        val missing = if (report.missingText > 0) "，正文缺失 ${report.missingText} 本" else ""
        _ui.value = _ui.value.copy(
            books = library.books,
            message = UiMessage("已导入 ${report.imported} 本，跳过 ${report.skipped} 本$missing。"),
        )
    }
    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(ReaderApp.instance.container) as T
        }
    }
}
