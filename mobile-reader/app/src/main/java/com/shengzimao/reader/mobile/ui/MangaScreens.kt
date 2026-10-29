package com.shengzimao.reader.mobile.ui

import kotlinx.coroutines.launch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.MangaBook
import com.shengzimao.reader.mobile.data.MangaCacheUsage
import com.shengzimao.reader.mobile.data.MangaSource
import com.shengzimao.reader.mobile.ui.theme.AppDimens
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MangaLibraryScreen(viewModel: ReaderViewModel, navigator: Navigator, embedded: Boolean = false) {
    val controller = viewModel.manga
    val state by controller.state.collectAsStateWithLifecycle()
    val librarySettings by viewModel.mangaSettings.collectAsStateWithLifecycle()
    var updatesCenter by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var actions by remember { mutableStateOf<MangaBook?>(null) }
    var remove by remember { mutableStateOf<MangaBook?>(null) }
    var deleteDownloads by remember { mutableStateOf<MangaBook?>(null) }
    var rename by remember { mutableStateOf<MangaBook?>(null) }
    var downloadManager by remember { mutableStateOf(false) }
    var cacheSheet by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) controller.importLocal(it) }

    val books = state.books.filter { book ->
        (query.isBlank() || book.title.contains(query, true) || book.author.contains(query, true)) && when (filter) {
            "在读" -> book.lastReadAt > 0
            "未读" -> book.lastReadAt <= 0
            "收藏" -> book.favorite
            "追更" -> book.tracking
            "有更新" -> book.newChapters > 0
            "在线" -> book.onlineOnly
            "离线" -> !book.onlineOnly
            "已缓存" -> !book.local && book.cachedChapters.size >= book.chapters.size && book.chapters.isNotEmpty()
            else -> true
        }
    }.let { filtered ->
        val pinned = compareByDescending<MangaBook> { it.favorite }
        filtered.sortedWith(when (librarySettings.librarySort) {
            "title" -> pinned.thenBy { it.title }
            "added" -> pinned.thenByDescending { it.createdAt }
            "progress" -> pinned.thenByDescending { it.chapterIndex.toFloat() / it.chapters.size.coerceAtLeast(1) }
            else -> pinned.thenByDescending { it.lastReadAt }.thenByDescending { it.createdAt }
        })
    }

    // 继续阅读：最近在读的几本，横向大卡片。
    val continueReading = remember(state.books) {
        state.books.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt }.take(6)
    }

    Scaffold(topBar = {
        // embedded 时标题由外层 BookshelfScreen 的 ShelfHeader 提供，这里只留操作按钮。
        if (embedded) {
            if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size} 部", Modifier.weight(1f))
                TextButton(onClick = { selected = emptySet(); selecting = false }) { Text("取消") }
            } else Box {
                ShelfTools("${books.size} 部漫画", !librarySettings.libraryCompact,
                    onSearch = { showSearch = !showSearch }, onSort = { showSort = true },
                    onView = { viewModel.updateMangaSettings { it.copy(libraryCompact = !it.libraryCompact) } },
                    onImport = { if (!state.importing) picker.launch(arrayOf("*/*")) })
                DropdownMenu(showSort, { showSort = false }) {
                    listOf("recent" to "最近阅读", "added" to "导入时间", "title" to "作品名称", "progress" to "阅读进度").forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.updateMangaSettings { it.copy(librarySort = key) }; showSort = false },
                            trailingIcon = { if (librarySettings.librarySort == key) Icon(Icons.Default.Check, "当前排序") })
                    }
                    DropdownMenuItem(text = { Text("更新中心") }, onClick = { showSort = false; navigator.openTool(ToolPage.UPDATES) })
                    DropdownMenuItem(text = { Text("缓存管理") }, onClick = { showSort = false; cacheSheet = true })
                }
            }
        } else {
            TopAppBar(title = { Text(if (selecting) "已选 ${selected.size} 部" else "漫库") }, actions = {
                if (selecting) {
                    TextButton(onClick = { selected = emptySet(); selecting = false }) { Text("取消") }
                } else {
                    IconButton(onClick = { viewModel.updateMangaSettings { it.copy(libraryCompact = !it.libraryCompact) } }) {
                        Icon(if (librarySettings.libraryCompact) Icons.Filled.GridView else Icons.Filled.ViewList,
                            if (librarySettings.libraryCompact) "切换封面网格" else "切换紧凑列表")
                    }
                    IconButton(onClick = { updatesCenter = true }) { Icon(Icons.Filled.Update, "漫画更新中心") }
                    IconButton(onClick = { cacheSheet = true }) { Icon(Icons.Filled.Storage, "缓存管理") }
                    IconButton(onClick = { navigator.openTool(ToolPage.DOWNLOADS) }) { Icon(Icons.Filled.Download, "下载管理") }
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing) { Text("导入") }
                }
            })
        }
    }, containerColor = Color.Transparent, contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 搜索框常驻保留（高频），统计头与筛选在 embedded 时收掉。
            if (showSearch || query.isNotBlank() || !embedded) AppSearchField(
                query, { query = it },
                placeholder = "搜索漫画名或作者",
                modifier = Modifier.padding(bottom = 2.dp),
            )
            if (embedded) {
                // 紧凑筛选：默认一行，展开才显示全部。
                var filtersOpen by remember { mutableStateOf(false) }
                ShelfFilterRow(
                    options = listOf("全部", "在线", "离线", "在读", "未读", "收藏", "已缓存", "追更", "有更新"),
                    current = filter,
                    expanded = filtersOpen,
                    onToggle = { filtersOpen = !filtersOpen },
                    onSelect = { filter = it },
                )
            } else {
                if (state.books.isNotEmpty()) {
                    AppStatsHeader(
                        title = "漫架",
                        stats = listOf(
                            "${state.books.size}" to "共收",
                            "${state.books.count { it.favorite }}" to "收藏",
                            "${state.books.count { !it.local && it.cachedChapters.isNotEmpty() }}" to "已缓存",
                        ),
                    )
                }
                FlowRow(Modifier.padding(horizontal = AppDimens.PagePadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "在线", "离线", "在读", "未读", "收藏", "已缓存", "追更", "有更新").forEach { name ->
                        FilterChip(filter == name, { filter = name }, label = { Text(name) })
                    }
                }
            }
            MangaFeedback(state.sourceError, state.message, controller)
            if (state.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在导入 CBZ / ZIP / 图片…", Modifier.padding(16.dp))
            }
            if (books.isEmpty()) {
                AppEmptyState(
                    icon = Icons.Filled.CollectionsBookmark,
                    title = if (state.books.isEmpty()) "把喜欢的漫画收进来" else "没有符合条件的漫画",
                    description = if (state.books.isEmpty())
                        "导入 CBZ、ZIP 或多张图片；也可以从在线搜漫加入作品。阅读进度自动保存在本机。"
                    else "换个筛选条件试试。",
                    actionLabel = if (state.books.isEmpty()) "导入本地漫画" else null,
                    onAction = if (state.books.isEmpty()) {
                        { picker.launch(arrayOf("*/*")) }
                    } else null,
                )
            } else {
                LazyVerticalGrid(
                    columns = if (librarySettings.libraryCompact) GridCells.Fixed(1) else GridCells.Adaptive(130.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (continueReading.isNotEmpty() && filter == "全部" && query.isBlank() && !selecting) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            val book = continueReading.first()
                            ContinueReadingBanner(book.title, book.chapters.getOrNull(book.chapterIndex)?.title ?: "第 ${book.chapterIndex + 1} 章") {
                                navigator.openComic(book.id)
                            }
                        }
                    }
                    items(books, key = { it.id }) { book ->
                        MangaGridCard(
                            controller = controller,
                            book = book,
                            selecting = selecting,
                            checked = book.id in selected,
                            onClick = {
                                if (selecting) selected = if (book.id in selected) selected - book.id else selected + book.id
                                else navigator.openComic(book.id)
                            },
                            onLongClick = { selecting = true; selected = selected + book.id },
                            onMenu = { actions = book },
                            compact = librarySettings.libraryCompact,
                        )
                    }
                }
                if (selecting) {
                    Surface(tonalElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextButton(
                                onClick = {
                                    selected.forEach { id ->
                                        val target = books.firstOrNull { it.id == id }
                                        if (target != null && !target.local) viewModel.downloadComic(id)
                                    }
                                    selected = emptySet(); selecting = false; navigator.openTool(ToolPage.DOWNLOADS)
                                },
                                enabled = selected.isNotEmpty(),
                            ) { Text("批量缓存") }
                            TextButton(onClick = { selected = books.map { it.id }.toSet() }) { Text("全选") }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { selected.forEach { controller.favorite(it) }; selected = emptySet(); selecting = false },
                                enabled = selected.isNotEmpty(),
                            ) { Text("收藏") }
                        }
                    }
                }
            }
        }
    }

    if (updatesCenter) MangaUpdatesSheet(controller, navigator) { updatesCenter = false }

    actions?.let { book ->
        ModalBottomSheet(onDismissRequest = { actions = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(if (book.local) "本地漫画" else book.sourceName)
                        append(" · ${book.chapters.size} 章")
                        if (book.cachedChapters.isNotEmpty()) append(" · 已缓存 ${book.cachedChapters.size} 章")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                TextButton(onClick = { controller.favorite(book.id); actions = null }) { Text(if (book.favorite) "取消收藏" else "收藏漫画") }
                if (!book.local) {
                    TextButton(onClick = { controller.toggleTracking(book.id); actions = null }) { Text(if (book.tracking) "取消追更" else "加入追更") }
                    TextButton(onClick = { controller.checkUpdates(book.id); actions = null }, enabled = book.id !in state.updatingIds) { Text("检查更新") }
                }
                TextButton(onClick = { rename = book; actions = null }) { Text("修改名称") }
                TextButton(onClick = { navigator.openComic(book.id); actions = null }) { Text("继续阅读") }
                if (!book.local) {
                    TextButton(onClick = { controller.checkUpdates(book.id); actions = null }) { Text("检查漫画更新") }
                    TextButton(onClick = { viewModel.downloadComic(book.id); actions = null; navigator.openTool(ToolPage.DOWNLOADS) }) { Text("离线缓存全部章节") }
                }
                if (!book.local && book.cachedChapters.isNotEmpty()) {
                    TextButton(onClick = { deleteDownloads = book; actions = null }) { Text("删除已下载图片", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { remove = book; actions = null }) { Text("移出漫库", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    deleteDownloads?.let { book -> AlertDialog(onDismissRequest = { deleteDownloads = null }, title = { Text("删除已下载图片？") },
        text = { Text("「${book.title}」将恢复为在线作品，目录、阅读进度和页面书签保留。") },
        confirmButton = { TextButton(onClick = { controller.deleteDownloads(book.id); deleteDownloads = null }) { Text("删除下载") } },
        dismissButton = { TextButton(onClick = { deleteDownloads = null }) { Text("取消") } }) }
    remove?.let { book ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("移除《${book.title}》？") },
            text = { Text(if (book.local) "导入到应用中的本地图片会一起移除，手机上的原始文件不受影响。" else "将移除漫库记录、阅读进度及该作品独占的下载图片。共享临时缓存可在缓存管理中清理。") },
            confirmButton = { TextButton(onClick = { controller.remove(book.id); remove = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } },
        )
    }
    rename?.let { book ->
        var title by remember(book.id) { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("修改漫画名称") },
            text = { OutlinedTextField(title, { title = it.take(250) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { controller.rename(book.id, title); rename = null }, enabled = title.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } },
        )
    }
    if (cacheSheet) MangaCacheSheet(controller, state.cache) { cacheSheet = false }
    if (downloadManager) DownloadManagerSheet(viewModel, navigator) { downloadManager = false }
    MangaDetailsSheet(viewModel, navigator)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MangaGridCard(
    controller: MangaController,
    book: MangaBook,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    compact: Boolean = false,
) {
    val coverRadius = com.shengzimao.reader.mobile.ui.theme.LocalAppTokens.current.radii.cover
    if (compact) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MangaCover(controller.engine, book.cover, book.sourceId, book.title,
                Modifier.width(52.dp).height(72.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f)) {
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(if (book.local) "本地 · ${book.chapters.sumOf { it.pages.size }} 页" else
                    "${if (book.onlineOnly) "缓存优先" else "可离线"} · 已下载 ${book.cachedChapters.size}/${book.chapters.size} 章",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MangaUpdateLabel(book)
                if (book.lastReadAt > 0) Text("读至 ${book.chapterIndex + 1} 章 · ${book.pageIndex + 1} 页",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (selecting) Checkbox(checked, { onClick() })
            else IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "管理${book.title}") }
        }
        return
    }
    Column {
        Box {
            MangaCover(
                controller.engine, book.cover, book.sourceId, book.title,
                Modifier.fillMaxWidth().aspectRatio(ShelfCoverAspectRatio).clip(RoundedCornerShape(coverRadius))
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            )
            // 漫画封面同样跟随氛围：下雨挂水珠、下雪积霜、春天落瓣……
            CoverDecoration(seed = book.title.hashCode(), cornerRadius = 10)
            if (selecting) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                )
            }
            if (book.favorite) {
                Icon(
                    Icons.Filled.Star, "已收藏",
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    tint = Color(0xFFFFD54F),
                )
            }
            if (book.lastReadAt > 0 && book.chapters.isNotEmpty()) {
                ShelfCoverProgress(book.chapterIndex.toFloat() / book.chapters.size.coerceAtLeast(1),
                    Modifier.align(Alignment.BottomCenter))
                // 进度角标：比文字行更省空间。
                Surface(
                    color = Color(0xB3000000),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "${book.chapterIndex + 1}/${book.chapters.size}",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShelfBookTitle(book.title, Modifier.weight(1f).padding(top = 7.dp))
            if (!selecting) {
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, "管理${book.title}", Modifier.size(20.dp))
                }
            }
        }
        ShelfCaption(
            buildString {
                if (book.local) append("本地 · ${book.chapters.sumOf { it.pages.size }} 页")
                else {
                    append(if (book.onlineOnly) "在线 · 缓存优先" else "已下载 · 可离线")
                    if (book.cachedChapters.isNotEmpty()) append(" · 缓存 ${book.cachedChapters.size}/${book.chapters.size}")
                }
            },
        )
        MangaUpdateLabel(book)
    }
}

@Composable
private fun ContinueReadingCard(controller: MangaController, book: MangaBook, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.width(240.dp),
        shape = RoundedCornerShape(18.dp),
        spec = GlassDefaults.card(),
        onClick = onClick,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover(controller.engine, book.cover, book.sourceId, book.title, Modifier.width(52.dp).height(72.dp).clip(RoundedCornerShape(10.dp)))
            CoverDecoration(seed = book.title.hashCode(), cornerRadius = 10, modifier = Modifier.width(52.dp).height(72.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "读到第 ${book.chapterIndex + 1} 章 · 第 ${book.pageIndex + 1} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 缓存管理：显示占用并提供清理与容量上限。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaCacheSheet(controller: MangaController, usage: MangaCacheUsage, onDismiss: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var limitMb by remember { mutableIntStateOf(1024) }
    LaunchedEffect(Unit) { controller.refreshCache() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("漫画缓存", style = MaterialTheme.typography.titleMedium)
            Text("临时阅读缓存可以清理；主动下载的章节受保护，本地导入文件单独保存。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("临时阅读缓存", style = MaterialTheme.typography.bodyMedium)
                    Text("${usage.onlineCount} 个文件 · ${usage.readable()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmClear = true }, enabled = usage.onlineCount > 0) { Text("全部清理", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("主动下载 ${usage.readable(usage.downloadedBytes)}（清理临时缓存不影响）", style = MaterialTheme.typography.bodySmall)
            Text("本地导入漫画 ${usage.readable(usage.localBytes)}（随漫画移除一起删除）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("按上限淘汰 ${limitMb} MB", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = limitMb.toFloat(),
                onValueChange = { limitMb = it.toInt() },
                valueRange = 128f..4096f,
                steps = 30,
            )
            TextButton(onClick = { controller.trimCache(limitMb.toLong() * 1024 * 1024) }) { Text("立即按上限淘汰最久未用的图片") }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理临时阅读缓存？") },
            text = { Text("将删除 ${usage.readable()} 临时图片。主动下载章节及本地漫画不受影响，在线作品需要联网重新加载临时图片。") },
            confirmButton = { TextButton(onClick = { controller.clearCache(); confirmClear = false }) { Text("清理") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 统一的反馈条：错误与消息分开传入，避免不同页面的错误互相串。 */
@Composable
internal fun MangaFeedback(error: String, message: String, controller: MangaController) {
    if (error.isBlank() && message.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            error.ifBlank { message },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { controller.dismissMessage() }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "关闭提示", Modifier.size(18.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MangaOnlineScreen(viewModel: ReaderViewModel, navigator: Navigator) {
    val library by viewModel.ui.collectAsStateWithLifecycle()
    val controller = viewModel.manga
    val state by controller.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var selectingResults by rememberSaveable { mutableStateOf(false) }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var selected by remember(state.query) { mutableStateOf(setOf<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    // 未同意声明时给同意页；取消回到「发现」的在线搜书，而不是跳到书架漫画分区
    // （从「发现」入口进来的用户不该被莫名其妙带到另一个 tab）。
    if (!library.consentAccepted) { ConsentScreen({ viewModel.acceptConsent() }, { navigator.openDiscover() }); return }

    val enabledSources = remember(state.sources) { state.sources.filter { it.enabled } }
    val sourceReadability = remember(state.sources, state.tests) { enabledSources.associate { source -> source.id to (state.tests[source.id]?.let { checked ->
        if (checked.ruleVersion.isNotBlank() && checked.ruleVersion != source.ruleVersion) "规则已更新 · 请重新检测"
        else "上次抽样：${checked.readability.label} · " + java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date(checked.testedAt))
    } ?: "已启用 · 尚未检测目录和图片") } }
    val visible = remember(state.results, sourceFilter) { state.results.filter { sourceFilter.isBlank() || it.sourceId == sourceFilter } }
    val grouped = remember(visible) { com.shengzimao.reader.mobile.data.MangaSearchGrouping.groups(visible) }
    val known = remember(state.books) { state.books.map { it.id }.toSet() }

    Scaffold(topBar = {
        Column {
            DiscoverySearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索漫画名或作者",
                searchEnabled = !state.searching && query.isNotBlank() && enabledSources.isNotEmpty() && state.selectedSource.isNotBlank(),
                searching = state.searching,
                onSearch = { keyboard?.hide(); controller.rememberQuery(query); controller.search(query) },
            )
            if (enabledSources.isNotEmpty()) MangaSearchSourcePicker(
                    enabledSources, state.selectedSource, state.sources.size, state.sources.count { !it.enabled },
                onManage = { navigator.openTool(ToolPage.MANGA_SOURCES) }, onChange = { controller.selectSource(it) },
                onOpenSource = { controller.openBrowse(it) },
                selectedIds = state.selectedSourceIds, onSelectMany = controller::selectSources,
                readability = sourceReadability,
            )
        }
    }, containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.history.isNotEmpty() && state.results.isEmpty() && !state.searching) {
                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("最近搜索", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { controller.clearHistory() }) { Text("清空", fontSize = 12.sp) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.history.take(8).forEach { word ->
                                AssistChip(
                                    onClick = { query = word; controller.search(word) },
                                    enabled = !state.searching,
                                    label = { Text(word, fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                }
            }
            if (enabledSources.isEmpty()) {
                item {
                    DiscoveryEmptyState(
                        icon = Icons.Filled.ImageSearch,
                        title = "先添加你的漫画源",
                        description = "导入你使用的漫画源，再搜索想看的作品。支持 JSON 文件、链接和粘贴导入。",
                        actionLabel = "导入漫画源",
                        onAction = { navigator.openTool(ToolPage.MANGA_SOURCES) },
                    )
                }
            } else if (!state.searching && state.results.isEmpty() && state.query.isBlank()) {
                item {
                    DiscoveryEmptyState(
                        icon = Icons.Filled.ImageSearch,
                        title = "下一部漫画，从这里开始",
                        description = "搜索漫画名或作者，多个来源一起找。结果返回就能打开阅读，也可以加入书架或下载。",
                    )
                }
            }
            if (state.searching) {
                item {
                    DiscoveryProgress("${state.hits.size} 个源响应 · ${grouped.size} 部作品，可立即阅读", "取消搜索") {
                        controller.cancelSearch()
                    }
                }
            }
            item { MangaFeedback(state.searchError, state.message, controller) }
            if (state.searchError.isNotBlank()) {
                item {
                    FlowRow {
                        TextButton(onClick = { controller.search(query) }) { Text("重新搜索") }
                        TextButton(onClick = { navigator.openTool(ToolPage.MANGA_SOURCES) }) { Text("检查漫画源") }
                    }
                }
            }
            if (!state.searching && visible.isEmpty() && state.query.isNotEmpty() && enabledSources.isNotEmpty()) {
                item {
                    Text("没有找到相关漫画。可以换一个源，或检查该源的搜索规则。", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (visible.isNotEmpty()) {
                item {
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("第 ${state.page} 页 · ${grouped.size} 部 / ${visible.size} 个来源", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { selectingResults = !selectingResults; selected = emptySet() }) { Text(if (selectingResults) "取消多选" else "多选") }
                        if (state.hits.size > 1) {
                            SourceFilterChip(state, sourceFilter) { sourceFilter = it }
                        }
                    }
                }
                if (selectingResults) item {
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已选 ${selected.size} 部", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = {
                                viewModel.importComics(visible.filter { it.id in selected })
                                selected = emptySet(); navigator.openTool(ToolPage.DOWNLOADS)
                            },
                            enabled = selected.isNotEmpty() && !state.searching,
                        ) { Text("下载导入漫库", fontSize = 13.sp) }
                        TextButton(onClick = { selected = emptySet(); selectingResults = false }) { Text("取消选择", fontSize = 13.sp) }
                    }
                }
            }
            items(grouped, key = { com.shengzimao.reader.mobile.data.MangaSearchGrouping.identity(it.first()) }) { choices ->
                val ordered = choices.sortedByDescending { controller.sourceReadabilityScore(it.sourceId) }
                var chosen by remember(state.query, choices.first().title, choices.first().author) { mutableStateOf("") }
                var sourceChoices by remember { mutableStateOf(false) }
                val book = ordered.firstOrNull { it.sourceId == chosen } ?: ordered.first()
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (choices.size > 1) {
                            TextButton(onClick = { sourceChoices = !sourceChoices }) { Text("${choices.size} 个来源 · 当前 ${book.sourceName} · 点击切换") }
                            if (sourceChoices) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ordered.forEach { candidate -> FilterChip(candidate.id == book.id, { chosen = candidate.sourceId },
                                    label = { Text(candidate.sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                            }
                        }
                        MangaSearchCard(
                            controller = controller,
                            book = book,
                            inLibrary = book.id in known,
                            checked = book.id in selected,
                            selecting = selectingResults,
                            onCheck = { selectingResults = true; selected = if (book.id in selected) selected - book.id else selected + book.id },
                            onDetails = { controller.openDetails(book) },
                            onAdd = { controller.keep(book) },
                            onRead = { controller.keep(book, openFailureDetails = true) { navigator.openComic(it) } },
                        )
                    }
                }
            }
            if (visible.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { controller.search(state.query, more = true) },
                        enabled = !state.searching,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("读取下一页") }
                }
            }
        }
    }
    MangaDetailsSheet(viewModel, navigator)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MangaSearchSourcePicker(sources: List<MangaSource>, selected: String, savedCount: Int, disabledCount: Int,
    onManage: () -> Unit, onChange: (String) -> Unit, onOpenSource: (String) -> Unit,
    selectedIds: Set<String> = emptySet(), onSelectMany: (Set<String>) -> Unit, readability: Map<String, String> = emptyMap()) {
    MangaSearchScope(sources, selected, savedCount, disabledCount, onManage, onChange, onOpenSource, selectedIds, onSelectMany, readability)
}

@Composable
private fun SourceFilterChip(state: MangaUiState, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(state.hits.firstOrNull { it.sourceId == value }?.sourceName ?: "全部来源", fontSize = 12.sp) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("全部来源") }, onClick = { onChange(""); open = false })
            state.hits.forEach { hit ->
                DropdownMenuItem(
                    text = { Text("${hit.sourceName}（${hit.results.size}${if (hit.error.isNotBlank()) " · 失败" else ""}）") },
                    onClick = { onChange(hit.sourceId); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MangaSearchCard(
    controller: MangaController,
    book: MangaBook,
    inLibrary: Boolean,
    checked: Boolean,
    selecting: Boolean,
    onCheck: () -> Unit,
    onDetails: () -> Unit,
    onAdd: () -> Unit,
    onRead: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var expanded by remember(book.id) { mutableStateOf(false) }
    var more by remember(book.id) { mutableStateOf(false) }
    val checkedSource = state.tests[book.sourceId]
    val directory = book.chapters.takeIf { it.isNotEmpty() }
        ?: state.books.firstOrNull { it.id == book.id }?.chapters?.takeIf { it.isNotEmpty() }
        ?: state.detail?.takeIf { it.id == book.id }?.chapters.orEmpty()
    val sourceRules = state.sources.firstOrNull { it.id == book.sourceId }?.ruleVersion
    val stale = checkedSource?.let { it.ruleVersion.isNotBlank() && it.ruleVersion != sourceRules ||
        System.currentTimeMillis() - it.testedAt > 7 * 24 * 3600_000L } ?: false
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onCheck() else onDetails() }, onLongClick = onCheck).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onCheck() })
        MangaCover(controller.engine, book.cover, book.sourceId, book.title, Modifier.width(64.dp)
            .aspectRatio(ShelfCoverAspectRatio).clip(RoundedCornerShape(com.shengzimao.reader.mobile.ui.theme.LocalAppTokens.current.radii.cover)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ShelfCaption(book.author.ifBlank { "作者未提供" })
            ShelfCaption((if (directory.isEmpty()) "目录未读取" else "目录 ${directory.size} 章") + " · ${book.sourceName}")
            ShelfCaption(checkedSource?.let { "该源抽样：${it.readability.label}" + if (stale) " · 建议复测" else "" }
                ?: "该源尚未检测图片")
        }
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                Button(onClick = onRead, enabled = !state.saving) { Text("阅读") }
                TextButton(onClick = onAdd, enabled = !inLibrary && !state.saving) { Text(if (inLibrary) "已在漫库" else "加入书架") }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "漫画更多操作") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("目录与作品详情") }, onClick = { more = false; onDetails() })
                        DropdownMenuItem(text = { Text(if (expanded) "收起附加信息" else "简介与检测记录") }, onClick = { more = false; expanded = !expanded })
                        DropdownMenuItem(text = { Text("查看漫源详情") }, onClick = { more = false; controller.openBrowse(book.sourceId) })
                        DropdownMenuItem(text = { Text("多选作品") }, onClick = { more = false; onCheck() })
                    }
                }
      }
      if (expanded) {
          if (book.intro.isNotBlank()) Text(book.intro, style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
          checkedSource?.let { Text("源抽样检测时间：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
              .format(java.util.Date(it.testedAt)) + "；仅代表抽样作品，当前作品需实际读图确认。",
              style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerSheet(viewModel: ReaderViewModel, navigator: Navigator, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DownloadManagerContent(viewModel, navigator, Modifier.fillMaxHeight(0.90f).navigationBarsPadding())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaDetailsSheet(viewModel: ReaderViewModel, navigator: Navigator) {
    val controller = viewModel.manga
    val state by controller.state.collectAsStateWithLifecycle()
    val book = state.detail ?: return
    var query by remember(book.id) { mutableStateOf("") }
    var introExpanded by remember(book.id) { mutableStateOf(false) }
    var errorExpanded by remember(book.id) { mutableStateOf(false) }
    var rangeDownload by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val known = state.books.firstOrNull { it.id == book.id }
    var reverse by rememberSaveable(book.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val filtered = remember(book.chapters, query, reverse) {
        book.chapters.withIndex().filter { query.isBlank() || it.value.title.contains(query, true) || (it.index + 1).toString() == query.trim() }
            .let { if (reverse) it.reversed() else it }
    }
    if (introExpanded) AlertDialog(onDismissRequest = { introExpanded = false }, title = { Text(book.title) },
        text = { Text(book.intro, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { introExpanded = false }) { Text("完成") } })
    val detailsHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    if (errorExpanded) AlertDialog(
        onDismissRequest = { errorExpanded = false },
        title = { Text("目录加载说明") },
        text = { Text(state.detailError, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { errorExpanded = false }) { Text("知道了") } },
    )
    ModalBottomSheet(
        onDismissRequest = { controller.closeDetails() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The default top inset follows the sheet offset. Combined with a fractional
        // content height it repeatedly moves the expanded anchor at the catalog edge.
        contentWindowInsets = { WindowInsets.ime },
    ) {
        Column(Modifier.fillMaxWidth().height(detailsHeight).navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().heightIn(max = detailsHeight * .3f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                if (rangeDownload) MangaDownloadRange(book, { rangeDownload = false }) { start, end, unread ->
                    controller.keep(book) { id ->
                        viewModel.downloadComicRange(id, start, end, unread)
                        controller.closeDetails(); navigator.openTool(ToolPage.DOWNLOADS)
                    }
                    rangeDownload = false
                }
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${book.author.ifBlank { "作者未提供" }} · ${book.sourceName} · " +
                        if (book.chapters.isNotEmpty()) "${book.chapters.size} 章" else if (state.detailLoading) "正在加载目录" else "目录不可用",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                if (book.intro.isNotBlank()) {
                    Text(
                        book.intro,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { introExpanded = !introExpanded },
                    )
                    if (book.intro.length > 120) {
                        TextButton(onClick = { introExpanded = true }, contentPadding = PaddingValues(0.dp)) { Text("查看完整简介") }
                    }
                }
                if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                if (state.detailError.isNotBlank()) {
                    Text(state.detailError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (state.detailError.length > 100) TextButton(onClick = { errorExpanded = true }) {
                        Text("查看完整说明")
                    }
                }
                if (known != null && known.cachedChapters.isNotEmpty()) {
                    Text("已缓存 ${known.cachedChapters.size}/${book.chapters.size} 章", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (book.chapters.isEmpty()) FlowRow(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.detailLoading) {
                    Button(onClick = { controller.findDetailAlternatives() }, enabled = !state.detailFinding) { Text("查找其他版本 / 漫源") }
                    OutlinedButton(onClick = { controller.openDetails(book, refresh = true) }) { Text("重试目录") }
                }
                if (book.url.isNotBlank()) TextButton(onClick = { uriHandler.openUri(book.url) }) { Text("原站") }
            }
            if (book.chapters.isEmpty() && !state.detailLoading) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    Text("当前条目未能获取可读目录，已自动查找同源可读版本。版本与作者可能不同，请核对后选择阅读；原条目与进度保留。", style = MaterialTheme.typography.bodySmall)
                    if (state.detailFindStarted) {
                        Text("已查找 ${state.detailFindCompleted}/${state.detailFindTotal} 个源 · ${state.detailAlternatives.size} 个候选", style = MaterialTheme.typography.labelSmall)
                        if (state.detailFinding) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            TextButton(onClick = { controller.stopDetailAlternatives() }) { Text("停止查找，保留结果") }
                        } else if (state.detailAlternatives.isEmpty()) Text("暂未找到其他版本，可启用更多漫源后重试。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.detailAlternatives, key = { it.id }) { candidate ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { controller.openDetails(candidate) }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp)) {
                                Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${candidate.author.ifBlank { "作者未提供" }} · ${candidate.sourceName}", style = MaterialTheme.typography.bodySmall)
                                Text(if (candidate.chapters.isNotEmpty()) "已确认 ${candidate.chapters.size} 章 · 点击查看目录" else "打开并检查目录", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (candidate.chapters.isNotEmpty()) TextButton(onClick = {
                            controller.keep(candidate) { id -> controller.closeDetails(); navigator.openComic(id) }
                        }, enabled = !state.saving) { Text("阅读此版本") }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
            Column(Modifier.fillMaxWidth().heightIn(max = detailsHeight * .25f).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("搜索章节名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                FlowRow(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { reverse = !reverse; scope.launch { listState.scrollToItem(0) } }) { Text(if (reverse) "倒序" else "正序") }
                    TextButton(onClick = {
                        query = ""
                        val current = known?.chapterIndex ?: 0
                        val position = if (reverse) book.chapters.lastIndex - current else current
                        scope.launch { listState.scrollToItem(position.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))) }
                    }) { Text("定位在读") }
                    TextButton(onClick = { controller.openDetails(book, refresh = true) }, enabled = !state.detailLoading) { Text("刷新目录") }
                }
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (filtered.isEmpty()) item { Text("没有匹配章节。", Modifier.padding(18.dp)) }
                items(filtered.size, key = { "${filtered[it].index}:${filtered[it].value.url}" }) { position ->
                    val (index, chapter) = filtered[position]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { controller.keep(book) { id -> controller.closeDetails(); navigator.openComic(id, index) } }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chapter.title, Modifier.weight(1f), maxLines = 2)
                        if (chapter.url in (known?.cachedChapters ?: book.cachedChapters)) {
                            Icon(Icons.Filled.OfflinePin, "已缓存", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
            }
            if (book.chapters.isNotEmpty()) {
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = !state.detailLoading && !state.saving
                    Button(onClick = { controller.keep(book) { id -> controller.closeDetails(); navigator.openComic(id) } }, enabled = enabled) { Text("在线阅读") }
                    OutlinedButton(onClick = { controller.keep(book) }, enabled = enabled) { Text("加入书架") }
                    Box {
                        TextButton(onClick = { more = true }, enabled = enabled) { Text("更多") }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text("下载全部章节") }, onClick = {
                                more = false
                                controller.keep(book) { id -> viewModel.downloadComic(id); controller.closeDetails(); navigator.openTool(ToolPage.DOWNLOADS) }
                            })
                            DropdownMenuItem(text = { Text("选择章节下载") }, onClick = { more = false; rangeDownload = true })
                            if (book.url.isNotBlank()) DropdownMenuItem(text = { Text("查看原站") }, onClick = { more = false; uriHandler.openUri(book.url) })
                        }
                    }
                }
            }
        }
    }
}
