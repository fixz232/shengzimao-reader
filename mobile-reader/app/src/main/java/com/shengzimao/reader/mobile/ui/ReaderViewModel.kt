package com.shengzimao.reader.mobile.ui

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    )
    private val onlineJobs = mutableMapOf<Boolean, Job>()

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
                consentAccepted = loaded.consentAccepted,
                consentVersion = loaded.consentVersion,
                autoUpdate = loaded.autoUpdate,
            )
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
                if (ids.isEmpty()) continue
                runCatching { online.updateBooks(ids) }
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
            val exists = store.exists(book)
            if (book.missing == !exists) book else book.copy(missing = !exists)
        }
        if (patched != source.books) {
            library = source.copy(books = patched)
            store.save(library)
        }
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
        library = library.copy(books = listOf(book) + library.books)
        store.save(library)
        return com.shengzimao.reader.mobile.data.ImportResult(book, text)
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.removeText(book) }
            library = library.copy(
                books = library.books.filterNot { it.id == book.id },
                activeId = if (library.activeId == book.id) "" else library.activeId,
            )
            withContext(Dispatchers.IO) { store.save(library) }
            _ui.value = _ui.value.copy(books = library.books, message = UiMessage("已从书库移除《${book.title}》"))
        }
    }

    fun deleteBooks(books: List<Book>) {
        if (books.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { books.forEach { store.removeText(it) } }
            val ids = books.map { it.id }.toSet()
            library = library.copy(
                books = library.books.filterNot { it.id in ids },
                activeId = if (library.activeId in ids) "" else library.activeId,
            )
            withContext(Dispatchers.IO) { store.save(library) }
            _ui.value = _ui.value.copy(books = library.books, message = UiMessage("已移除 ${books.size} 本"))
        }
    }

    fun toggleFavorite(book: Book) = patch(book.id) { it.copy(favorite = !it.favorite) }
    fun togglePinned(book: Book) = patch(book.id) { it.copy(pinned = !it.pinned) }
    fun toggleCompleted(book: Book) = patch(book.id) { it.copy(completed = !it.completed) }

    fun editBook(book: Book, title: String, author: String, intro: String) = patch(book.id) {
        it.copy(title = title.trim().ifEmpty { it.title }.take(250), author = author.trim().take(120), intro = intro.take(4000))
    }

    private fun patch(id: String, transform: (Book) -> Book) {
        library = library.copy(books = library.books.map { if (it.id == id) transform(it) else it })
        _ui.value = _ui.value.copy(books = library.books)
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
    }

    fun clearReadingHistory() {
        library = library.copy(books = library.books.map { it.copy(lastReadAt = 0L) })
        _ui.value = _ui.value.copy(books = library.books)
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.save(next) }
            _ui.value = _ui.value.copy(books = next.books)
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
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(next) } }
    }

    fun addBookmark(bookId: String, position: Int, label: String) {
        val index = library.books.indexOfFirst { it.id == bookId }
        if (index < 0) return
        val book = library.books[index]
        if (book.bookmarks.any { it.position == position }) {
            message("这一页已经有书签了")
            return
        }
        val chapter = book.chapters.lastOrNull { it.position <= position }?.title ?: book.title
        val mark = Bookmark(position = position, label = label.ifBlank { chapter.take(60) }, createdAt = System.currentTimeMillis())
        val updated = book.copy(bookmarks = (book.bookmarks + mark).sortedBy { it.position })
        library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
        _ui.value = _ui.value.copy(books = library.books)
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
        message("书签已保存")
    }

    fun removeBookmark(bookId: String, bookmarkId: String) {
        val index = library.books.indexOfFirst { it.id == bookId }
        if (index < 0) return
        val book = library.books[index]
        val updated = book.copy(bookmarks = book.bookmarks.filterNot { it.id == bookmarkId })
        library = library.copy(books = library.books.toMutableList().also { it[index] = updated })
        _ui.value = _ui.value.copy(books = library.books)
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
    }

    fun currentBook(bookId: String): Book? = _ui.value.books.firstOrNull { it.id == bookId }

    // ---------------- 在线搜书 / 追更 / 备份 ----------------

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _ui.value = _ui.value.copy(message = UiMessage(error.message ?: "操作失败", true))
            }
        }
    }

    /** 引擎返回的正文落库：按在线 URL 去重，重复则跳过。 */
    private fun saveDownloadedBook(
        result: com.shengzimao.reader.mobile.data.OnlineResult,
        text: String,
        chapters: List<com.shengzimao.reader.mobile.data.OnlineChapter>,
    ): Boolean = synchronized(libraryLock) {
        val existing = library.books.firstOrNull { it.onlineUrl.isNotEmpty() && it.onlineUrl == result.url }
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
        val merged = base.copy(
            file = file,
            length = text.length,
            position = base.position.coerceIn(0, maxOf(0, text.length - 1)),
            chapters = localChapters,
            digest = LibraryStore.digest(text),
            onlineUrl = result.url,
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

    fun searchOnline(keyword: String, tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        val history = _ui.value.online.searchHistory.ifEmpty { library.searchHistory }
        controller.search(keyword, history)
        val trimmed = onelineKeyword(keyword)
        if (trimmed.isNotEmpty() && library.searchHistory.firstOrNull() != trimmed) {
            library = library.copy(searchHistory = (listOf(trimmed) + library.searchHistory.filter { it != trimmed }).take(12))
            withContext(Dispatchers.IO) { store.save(library) }
        }
    }

    private fun onelineKeyword(value: String): String = value.trim().take(120)

    fun clearSearchHistory() {
        library = library.copy(searchHistory = emptyList())
        _ui.value = _ui.value.copy(online = _ui.value.online.copy(searchHistory = emptyList()))
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
    }

    fun openOnlineDetails(index: Int, tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        controller.details(index)
    }

    fun closeOnlineDetails(tomatoPage: Boolean = false) {
        onlineController(tomatoPage).closeDetails()
    }

    fun downloadOnline(index: Int, format: String, tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        controller.download(index, format)
    }

    fun downloadOnlineMany(indexes: List<Int>, format: String, tomatoPage: Boolean = false) = launchOnline(tomatoPage) { controller ->
        controller.downloadMany(indexes, format)
    }

    fun acceptConsent() {
        library = library.copy(consentVersion = com.shengzimao.reader.mobile.data.Consent.VERSION, consentAccepted = true, consentAt = System.currentTimeMillis())
        _ui.value = _ui.value.copy(consentAccepted = true)
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
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
        library = library.copy(autoUpdate = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { store.save(library) } }
    }

    private fun syncOnline() {
        _ui.value = _ui.value.copy(online = online.state.value)
    }

    fun exportBackup(uri: Uri) = launchSafe {
        val context = container.context
        val report = withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入所选位置。")
            com.shengzimao.reader.mobile.data.Backup.export(store, library, null, stream)
        }
        _ui.value = _ui.value.copy(message = UiMessage("已导出 ${report.included} 本正文（共 ${report.books} 本，缺失 ${report.missing} 本）。"))
    }

    fun importBackup(uri: Uri) = launchSafe {
        val context = container.context
        val report = withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件。")
            com.shengzimao.reader.mobile.data.Backup.import(store, library, stream)
        }
        library = withContext(Dispatchers.IO) { store.load() }
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
