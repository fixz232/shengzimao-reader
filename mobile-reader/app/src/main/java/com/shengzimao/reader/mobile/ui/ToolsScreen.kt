package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.BuildConfig

enum class ToolPage(val title: String) {
    DOWNLOADS("下载管理"), SOURCES("书源与漫源"), NOVEL_SOURCES("小说书源"), MANGA_SOURCES("漫画源管理"),
    UPDATES("更新中心"), APPEARANCE("应用外观"), NOVEL("小说阅读"), COMIC("漫画阅读"),
    SPEECH("听书设置"), DOWNLOAD_SETTINGS("下载与缓存"), DATA("备份与数据"),
}

@Composable
fun MineScreen(viewModel: ReaderViewModel, navigator: Navigator) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val manga by viewModel.manga.state.collectAsStateWithLifecycle()
    val tasks by viewModel.downloads.tasks.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppPageHeader("我的", "阅读工具与偏好")
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { ToolSection("阅读工具") }
            item { ToolEntry("下载管理", "${tasks.count { it.active }} 个进行中 · 小说与漫画", Icons.Default.Download) { navigator.openTool(ToolPage.DOWNLOADS) } }
            item { ToolEntry("更新中心", "${state.books.sumOf { it.newChapterCount } + manga.books.sumOf { it.newChapters }} 章未读更新", Icons.Default.Update) { navigator.openTool(ToolPage.UPDATES) } }
            item { ToolEntry("书源与漫源", "分组、导入、搜索范围与可读性检测", Icons.Default.Source) { navigator.openTool(ToolPage.SOURCES) } }
            item { ToolEntry("备份与恢复", "书库、进度、设置与本地数据", Icons.Default.Backup) { navigator.openTool(ToolPage.DATA) } }
            item { ToolSection("偏好设置") }
            item { ToolEntry("应用外观", "主题、深浅色、配色与动效", Icons.Default.Palette) { navigator.openTool(ToolPage.APPEARANCE) } }
            item { ToolEntry("小说阅读", "排版、阅读主题、翻页与交互", Icons.AutoMirrored.Filled.MenuBook) { navigator.openTool(ToolPage.NOVEL) } }
            item { ToolEntry("漫画阅读", "阅读方向、底色与预取", Icons.Default.Image) { navigator.openTool(ToolPage.COMIC) } }
            item { ToolEntry("听书", "默认语速、音调与阅读联动", Icons.Default.Headphones) { navigator.openTool(ToolPage.SPEECH) } }
            item { ToolEntry("下载与缓存", "网络策略、并发与存储空间", Icons.Default.Storage) { navigator.openTool(ToolPage.DOWNLOAD_SETTINGS) } }
            item { ToolEntry("关于圣子猫阅读", "${BuildConfig.VERSION_NAME} · 协议、声明与开源致谢", Icons.Default.Info) { navigator.openAbout() } }
        }
    }
}

@Composable
private fun ToolSection(title: String) {
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToolEntry(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "打开$title") },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPageScreen(viewModel: ReaderViewModel, navigator: Navigator, page: ToolPage) {
    if (page == ToolPage.MANGA_SOURCES) {
        MangaSourcesScreen(viewModel.manga, navigator::closeTool)
        return
    }
    if (page in setOf(ToolPage.APPEARANCE, ToolPage.NOVEL, ToolPage.COMIC, ToolPage.SPEECH, ToolPage.DOWNLOAD_SETTINGS, ToolPage.DATA)) {
        SettingsScreen(viewModel, onBack = navigator::closeTool, category = page)
        return
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(page.title) }, navigationIcon = {
            IconButton(onClick = navigator::closeTool) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                ToolPage.DOWNLOADS -> DownloadManagerContent(viewModel, navigator,
                    onOpenSettings = { navigator.openTool(ToolPage.DOWNLOAD_SETTINGS) })
                ToolPage.NOVEL_SOURCES -> NovelSourcesContent(viewModel)
                ToolPage.UPDATES -> UpdatesContent(viewModel, navigator)
                ToolPage.SOURCES -> Column {
                    ToolEntry("小说书源", "内置分组 1 · 自定义分组 2–5", Icons.AutoMirrored.Filled.MenuBook) { navigator.openTool(ToolPage.NOVEL_SOURCES) }
                    ToolEntry("漫画源", "导入、启停、适配与可读性检测", Icons.Default.Image) { navigator.openTool(ToolPage.MANGA_SOURCES) }
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NovelSourcesContent(viewModel: ReaderViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val online = state.online
    var importing by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var details by remember { mutableStateOf<com.shengzimao.reader.mobile.data.NovelSourceEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { group ->
                FilterChip(online.sourceGroup == group, { viewModel.selectNovelSourceGroup(group) },
                    enabled = online.sourcesReady && !online.running && !online.importingSources,
                    label = { Text("分组 $group") })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (online.sourceGroup == 1) "内置规则 · 只读" else "自定义规则 · 当前搜索分组",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (online.sourceGroup != 1) TextButton(onClick = { importing = online.sourceGroup },
                enabled = !online.running && !online.importingSources) { Text("导入书源") }
        }
        AppSearchField(query, { query = it }, placeholder = "搜索书源名称或网址")
        if (online.importingSources || !online.sourcesReady) LinearProgressIndicator(Modifier.fillMaxWidth())
        val entries = remember(online.sourceEntries, query) {
            online.sourceEntries.filter { query.isBlank() || it.name.contains(query, true) || it.url.contains(query, true) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (entries.isEmpty()) item { Text("此组没有符合条件的书源，可切换分组或导入 JSON。", Modifier.padding(16.dp)) }
            items(entries, key = { it.url }) { entry ->
                ListItem(headlineContent = { Text(entry.name) }, supportingContent = {
                    Text("${entry.format} · ${if (entry.searchable) "可搜索 · 尚未验证正文" else "规则未启用搜索"}\n${entry.url}",
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }, trailingContent = { Icon(Icons.Default.Info, "书源详情") }, modifier = Modifier.clickable { details = entry })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    if (importing in 2..5) NovelSourceImportSheet(viewModel, importing, online) { importing = 0 }
    details?.let { entry ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(entry.name) }, text = {
            Text("${entry.url}\n\n格式：${entry.format}\n分组：${online.sourceGroup}\n${if (entry.searchable) "规则支持搜索；实际目录和正文取决于源站。" else "此规则未启用搜索。"}")
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("完成") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdatesContent(viewModel: ReaderViewModel, navigator: Navigator) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val manga by viewModel.manga.state.collectAsStateWithLifecycle()
    var comic by rememberSaveable { mutableStateOf(navigator.librarySection == LibrarySection.COMIC) }
    var filter by rememberSaveable { mutableStateOf("有更新") }
    val failedNovelIds = state.online.batch?.items?.filter { it.state == "failed" }?.map { it.id }?.toSet().orEmpty()
    Column(Modifier.fillMaxSize()) {
        AppSectionTabs(listOf("小说", "漫画"), if (comic) 1 else 0) { comic = it == 1 }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("有更新", "更新失败", "最近阅读").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("追更仅在应用运行时检查", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (comic) viewModel.manga.checkTrackedUpdates() else viewModel.updateAllTracked() },
                enabled = if (comic) manga.updatingIds.isEmpty() else !state.online.running) { Text("检查追更") }
        }
        if (if (comic) manga.updatingIds.isNotEmpty() else state.online.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (comic) {
                val books = manga.books.filter { when (filter) {
                    "更新失败" -> it.updateError.isNotBlank(); "最近阅读" -> it.lastReadAt > 0; else -> it.newChapters > 0
                } }.sortedByDescending { if (filter == "最近阅读") it.lastReadAt else it.updatedAt }
                if (books.isEmpty()) item { Text("暂无${filter}记录。在书架为在线作品开启追更后，可在这里检查。") }
                items(books, key = { it.id }) { book ->
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Text("${book.sourceName} · ${book.newChapters} 章未读更新", style = MaterialTheme.typography.bodySmall)
                    if (book.updateError.isNotBlank()) Text(book.updateError, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    FlowRow {
                        TextButton(onClick = { navigator.openComic(book.id) }) { Text("继续阅读") }
                        if (book.newChapters > 0) TextButton(onClick = { navigator.openComic(book.id, viewModel.manga.firstUnread(book)) }) { Text("读第一章未读") }
                        if (!book.local) TextButton(onClick = { viewModel.manga.checkUpdates(book.id) }, enabled = book.id !in manga.updatingIds) { Text("检查更新") }
                    }
                    HorizontalDivider()
                }
            } else {
                val books = state.books.filter { when (filter) {
                    "更新失败" -> state.online.batch?.kind == "update" && it.id in failedNovelIds
                    "最近阅读" -> it.lastReadAt > 0; else -> it.newChapterCount > 0
                } }.sortedByDescending { if (filter == "最近阅读") it.lastReadAt else it.lastUpdatedAt }
                if (books.isEmpty()) item { Text(if (filter == "更新失败") "最近一次检查没有失败记录。" else "暂无${filter}记录。可在书架为在线作品开启追更。") }
                items(books, key = { it.id }) { book ->
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Text("${book.newChapterCount} 章更新 · ${book.chapterLabel}", style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = { navigator.openReader(book.id) }) { Text("继续阅读") }
                        com.shengzimao.reader.mobile.data.NovelChapterNavigation.firstUnread(book)?.let { first ->
                            TextButton(onClick = { viewModel.openNovelChapter(book.id, first) { navigator.openReader(book.id) } }) { Text("读第一章未读") }
                        }
                        if (book.onlineUrl.isNotBlank()) TextButton(onClick = { viewModel.updateTrackedBooks(listOf(book.id)) }, enabled = !state.online.running) { Text("检查更新") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
