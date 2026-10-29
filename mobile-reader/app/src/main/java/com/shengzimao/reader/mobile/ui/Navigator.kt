package com.shengzimao.reader.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** 单 Activity 页面栈，主页面与底部选中项共用同一入口。 */
sealed interface Screen {
    data object Library : Screen
    data class Reading(val bookId: String) : Screen
    data object Online : Screen
    data object Comics : Screen
    data object MangaOnline : Screen
    data class ComicReading(val bookId: String, val chapter: Int = -1) : Screen
}

@Composable
fun rememberNavigator(): Navigator {
    return rememberSaveable(saver = listSaver(save = { it.snapshot() }, restore = { Navigator.restore(it) })) { Navigator() }
}

/**
 * 页面导航。
 *
 * 底部只有 3 个 tab（书架/发现/我的），但 [Screen] 保留更细的页面粒度：
 * 书架下可以打开「在线搜书」「在线搜漫」等子页面，它们都归属「发现」tab 的高亮。
 */
class Navigator {
    var selectedTab by mutableStateOf(MainTab.LIBRARY)
    var screen by mutableStateOf<Screen>(Screen.Library)
        internal set
    var showSettings by mutableStateOf(false)
        internal set
    var showAbout by mutableStateOf(false)
        internal set

    /** 书架内的小说/漫画分区；「发现」页也用它决定打开在线搜书还是在线搜漫。 */
    var librarySection by mutableStateOf(LibrarySection.NOVEL)

    var discoverManga by mutableStateOf(false)
        private set
    var discoverTomato by mutableStateOf(false)
        private set
    var toolPage by mutableStateOf<ToolPage?>(null)
        private set
    private val toolHistory = ArrayDeque<ToolPage>()
    private var readerOrigin: Screen? = null
    private var readerToolOrigin: ToolPage? = null
    private var readerToolHistory = emptyList<ToolPage>()

    fun openTool(page: ToolPage) {
        if (toolPage == page) return
        toolPage?.let { toolHistory.addLast(it) }
        if (toolHistory.size > 8) toolHistory.removeFirst()
        toolPage = page
    }

    fun closeTool() {
        toolPage = toolHistory.removeLastOrNull()
    }

    fun returnFromReader() {
        screen = readerOrigin ?: if (screen is Screen.ComicReading) Screen.Comics else Screen.Library
        toolPage = readerToolOrigin
        toolHistory.clear()
        toolHistory.addAll(readerToolHistory)
        readerOrigin = null
        readerToolOrigin = null
        readerToolHistory = emptyList()
    }

    /** 记录 tab 历史，返回键优先回退到上一个 tab 而不是直接回书架。 */
    private val tabHistory = ArrayDeque<MainTab>()

    fun openReader(bookId: String) {
        rememberReaderOrigin()
        screen = Screen.Reading(bookId)
    }

    private fun rememberReaderOrigin() {
        if (screen !is Screen.Reading && screen !is Screen.ComicReading) {
            readerOrigin = screen
            readerToolOrigin = toolPage
            readerToolHistory = toolHistory.toList()
            toolHistory.clear()
        }
        toolPage = null
    }

    private fun switchTab(tab: MainTab) {
        if (selectedTab != tab) {
            tabHistory.remove(tab)
            tabHistory.addLast(selectedTab)
            if (tabHistory.size > 8) tabHistory.removeFirst()
        }
        selectedTab = tab
    }

    fun openLibrary() {
        switchTab(MainTab.LIBRARY)
        screen = Screen.Library
    }

    /** 在线搜书：归属「发现」，但不再是一个独立 tab。 */
    fun openOnline() {
        switchTab(MainTab.DISCOVER)
        discoverManga = false
        discoverTomato = false
        screen = Screen.Online
    }

    fun openTomato() {
        switchTab(MainTab.DISCOVER)
        discoverManga = false
        discoverTomato = true
        screen = Screen.Online
    }

    /** 漫库：现在属于「书架」的漫画分区。 */
    fun openComics() {
        switchTab(MainTab.LIBRARY)
        librarySection = LibrarySection.COMIC
        screen = Screen.Comics
    }

    fun openMangaOnline() {
        switchTab(MainTab.DISCOVER)
        discoverManga = true
        discoverTomato = false
        screen = Screen.MangaOnline
    }

    fun openComic(bookId: String, chapter: Int = -1) {
        rememberReaderOrigin()
        screen = Screen.ComicReading(bookId, chapter)
    }

    /** 打开「发现」的默认页（聚合搜书）。 */
    fun openDiscover() {
        switchTab(MainTab.DISCOVER)
        discoverManga = false
        discoverTomato = false
        screen = Screen.Online
    }

    fun openMine() {
        switchTab(MainTab.MINE)
        showSettings = false
        screen = Screen.Library
    }

    fun selectTab(tab: MainTab) {
        if (selectedTab == tab) return
        showAbout = false
        when (tab) {
            MainTab.LIBRARY -> {
                showSettings = false
                switchTab(tab)
                screen = Screen.Library
            }
            MainTab.DISCOVER -> when {
                discoverManga -> openMangaOnline()
                discoverTomato -> openTomato()
                else -> openDiscover()
            }
            MainTab.MINE -> openMine()
        }
    }

    fun openSettings() = openTool(when (screen) {
        is Screen.Reading -> ToolPage.NOVEL
        is Screen.ComicReading -> ToolPage.COMIC
        else -> ToolPage.APPEARANCE
    })

    fun closeSettings() {
        showSettings = false
        openLibrary()
    }

    fun openAbout() {
        showAbout = true
    }

    fun closeAbout() {
        showAbout = false
    }

    /** 返回键：返回 true 表示已消费。 */
    fun onBack(): Boolean {
        if (showAbout) {
            showAbout = false
            return true
        }
        if (toolPage != null) {
            closeTool()
            return true
        }
        if (showSettings) {
            closeSettings()
            return true
        }
        if (screen is Screen.ComicReading) {
            returnFromReader()
            return true
        }
        if (screen is Screen.Reading) {
            returnFromReader()
            return true
        }
        // 从「发现」的子页面返回：回到发现首页，而不是跳去书架。
        if (screen is Screen.MangaOnline) {
            return backToPreviousTab()
        }
        if (screen is Screen.Online && selectedTab == MainTab.DISCOVER) {
            // 发现页本身就是聚合搜书，再返回就回上一个 tab。
            return backToPreviousTab()
        }
        if (selectedTab != MainTab.LIBRARY) {
            return backToPreviousTab()
        }
        return false
    }

    /** 回退到上一个 tab；没有历史时回书架。 */
    private fun backToPreviousTab(): Boolean {
        val previous = tabHistory.removeLastOrNull() ?: return false
        selectedTab = previous
        screen = when (previous) {
            MainTab.LIBRARY -> Screen.Library
            MainTab.DISCOVER -> if (discoverManga) Screen.MangaOnline else Screen.Online
            MainTab.MINE -> Screen.Library
        }
        showSettings = false
        return true
    }

    /** Only navigation identifiers are saved; books and reader data stay in their existing stores. */
    internal fun snapshot(): List<String> = listOf(
        "1", selectedTab.name, librarySection.name, discoverManga.toString(),
        showSettings.toString(), showAbout.toString(), toolPage?.name.orEmpty(),
    ) + encodeScreen(screen) + encodeScreen(readerOrigin) + listOf(
        tabHistory.joinToString(",") { it.name }, toolHistory.joinToString(",") { it.name },
        readerToolOrigin?.name.orEmpty(),
        readerToolHistory.joinToString(",") { it.name },
        discoverTomato.toString(),
    )

    companion object {
        private fun encodeScreen(screen: Screen?): List<String> = when (screen) {
            Screen.Library -> listOf("library", "", "")
            Screen.Online -> listOf("online", "", "")
            Screen.Comics -> listOf("comics", "", "")
            Screen.MangaOnline -> listOf("manga", "", "")
            is Screen.Reading -> listOf("reading", screen.bookId, "")
            is Screen.ComicReading -> listOf("comicReading", screen.bookId, screen.chapter.toString())
            null -> listOf("", "", "")
        }

        private fun decodeScreen(values: List<String>, offset: Int): Screen? = when (values.getOrNull(offset)) {
            "library" -> Screen.Library
            "online" -> Screen.Online
            "comics" -> Screen.Comics
            "manga" -> Screen.MangaOnline
            "reading" -> values.getOrNull(offset + 1)?.takeIf { it.isNotBlank() }?.let { Screen.Reading(it) }
            "comicReading" -> values.getOrNull(offset + 1)?.takeIf { it.isNotBlank() }?.let {
                Screen.ComicReading(it, values.getOrNull(offset + 2)?.toIntOrNull()?.coerceAtLeast(-1) ?: -1)
            }
            else -> null
        }

        internal fun restore(values: List<String>): Navigator = Navigator().apply {
            if (values.firstOrNull() != "1") return@apply
            selectedTab = MainTab.entries.firstOrNull { it.name == values.getOrNull(1) } ?: MainTab.LIBRARY
            librarySection = LibrarySection.entries.firstOrNull { it.name == values.getOrNull(2) } ?: LibrarySection.NOVEL
            discoverManga = values.getOrNull(3) == "true"
            discoverTomato = !discoverManga && values.getOrNull(17) == "true"
            showSettings = values.getOrNull(4) == "true"
            showAbout = values.getOrNull(5) == "true"
            toolPage = ToolPage.entries.firstOrNull { it.name == values.getOrNull(6) }
            screen = decodeScreen(values, 7) ?: Screen.Library
            readerOrigin = decodeScreen(values, 10)
            values.getOrNull(13).orEmpty().split(',').takeLast(8).forEach { name ->
                MainTab.entries.firstOrNull { it.name == name }?.let { tabHistory.addLast(it) }
            }
            values.getOrNull(14).orEmpty().split(',').takeLast(8).forEach { name ->
                ToolPage.entries.firstOrNull { it.name == name }?.let { toolHistory.addLast(it) }
            }
            readerToolOrigin = ToolPage.entries.firstOrNull { it.name == values.getOrNull(15) }
            readerToolHistory = values.getOrNull(16).orEmpty().split(',').takeLast(8).mapNotNull { name ->
                ToolPage.entries.firstOrNull { it.name == name }
            }
        }
    }
}
