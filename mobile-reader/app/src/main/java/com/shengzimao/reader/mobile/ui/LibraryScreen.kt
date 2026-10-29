package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.ReaderApp
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.DocumentImporter
import com.shengzimao.reader.mobile.ui.theme.AppDimens
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书库主页面：三种筛选 + 搜索 + 网格/列表 + 导入。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    viewModel: ReaderViewModel,
    navigator: Navigator,
    accountLabel: String? = null,
    embedded: Boolean = false,
    onOpenSettings: () -> Unit = {},
    /** 由 MainScaffold 传入的书架分区切换条，渲染在标题栏下方。 */
    sectionBar: (@Composable () -> Unit)? = null,
    /** 为 false 时不渲染自带标题栏（由外层 BookshelfScreen 的 ShelfHeader 提供）。 */
    showSectionChrome: Boolean = true,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val settings = state.settings
    var showSort by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var editing by remember { mutableStateOf<Book?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Book?>(null) }
    var showTracking by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmBatchRemove by rememberSaveable { mutableStateOf(false) }
    val selectedBooks = state.books.filter { it.id in selectedIds }
    val toggleSelection: (Book) -> Unit = { book ->
        selectedIds = if (book.id in selectedIds) selectedIds - book.id else selectedIds + book.id
    }
    BackHandler(selecting) { selecting = false; selectedIds = emptyList() }
    LaunchedEffect(state.books) {
        selectedIds = selectedIds.filter { id -> state.books.any { it.id == id } }
    }
    val continuing = remember(state.books, state.query, state.statusFilter) {
        state.books.filter { it.lastReadAt > 0 && !it.isDone }.maxByOrNull { it.lastReadAt }
            ?.takeIf { state.query.isBlank() && state.statusFilter == "all" }
    }


    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(context, uris)
    }

    val filtered = remember(state.books, state.query, state.statusFilter, settings.sort, settings.gridView) {
        val base = state.books.filter { book ->
            val statusOk = when (state.statusFilter) {
                "reading" -> book.position > 0 && !book.isDone
                "unread" -> book.position <= 0 && !book.isDone
                "done" -> book.isDone
                "favorite" -> book.favorite
                "online" -> book.onlineOnly
                "local" -> !book.onlineOnly
                else -> true
            }
            statusOk && (state.query.isBlank() || "${book.title} ${book.author}".lowercase().contains(state.query.lowercase()))
        }
        when (settings.sort) {
            "recent" -> base.sortedWith(compareByDescending<Book> { it.pinned }.thenByDescending { it.lastReadAt }.thenByDescending { it.createdAt })
            "title" -> base.sortedWith(compareByDescending<Book> { it.pinned }.thenBy { it.title })
            "progress" -> base.sortedWith(compareByDescending<Book> { it.pinned }.thenByDescending { it.progress })
            "added" -> base.sortedWith(compareByDescending<Book> { it.pinned }.thenByDescending { it.createdAt })
            else -> base
        }
    }

    Scaffold(
        topBar = {
            if (!showSectionChrome) return@Scaffold
            TopAppBar(
                title = {
                    Column {
                        Text("圣子猫阅读", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.books.size} 本 · ${state.reading.size} 本在读",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索书库")
                    }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(
                                text = { Text(if (settings.gridView) "切换为列表" else "切换为网格") },
                                onClick = {
                                    viewModel.updateSettings { it.copy(gridView = !settings.gridView) }
                                    more = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("排序：${sortLabel(settings.sort)}") },
                                onClick = { more = false; showSort = true },
                            )
                            DropdownMenuItem(
                                text = { Text("在线搜书") },
                                onClick = { more = false; navigator.openOnline() },
                            )
                            DropdownMenuItem(
                                text = { Text("查看追更") },
                                onClick = { more = false; showTracking = true },
                            )
                            DropdownMenuItem(
                                text = { Text("关于") },
                                onClick = { more = false; navigator.openAbout() },
                            )
                        }
                    }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        listOf("recent" to "最近阅读", "added" to "导入时间", "title" to "书名", "progress" to "阅读进度").forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.updateSettings { it.copy(sort = key) }
                                    showSort = false
                                },
                                trailingIcon = {
                                    if (settings.sort == key) Icon(Icons.Filled.Check, contentDescription = "当前排序")
                                },
                            )
                        }
                    }
                },
                // 状态栏留白由外层 MainScaffold 的分区条统一负责，
                // 这里关掉 TopAppBar 自带的 windowInsets，避免出现双倍留白。
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            if (selecting) Surface(tonalElevation = 2.dp) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        selectedBooks.filterNot { it.favorite }.forEach(viewModel::toggleFavorite)
                        selectedIds = emptyList(); selecting = false
                    }, enabled = selectedBooks.isNotEmpty()) { Text("收藏") }
                    TextButton(onClick = {
                        selectedBooks.filter { !it.tracking && it.onlineUrl.isNotBlank() }.forEach(viewModel::toggleTracking)
                        selectedIds = emptyList(); selecting = false
                    }, enabled = selectedBooks.any { it.onlineUrl.isNotBlank() }) { Text("追更") }
                    TextButton(onClick = {
                        selectedBooks.filter { it.onlineUrl.isNotBlank() }.forEach(viewModel::downloadShelfBook)
                        selectedIds = emptyList(); selecting = false; navigator.openTool(ToolPage.DOWNLOADS)
                    }, enabled = selectedBooks.any { it.onlineUrl.isNotBlank() }) { Text("下载") }
                    TextButton(onClick = { confirmBatchRemove = true }, enabled = selectedBooks.isNotEmpty()) {
                        Text("移除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        floatingActionButton = {
            if (showSectionChrome) ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            )
        },
        containerColor = Color.Transparent,
        // 外层 BookShelf/MainScaffold 已经处理过系统栏，这里再套一层会多出一条空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 分区条紧贴标题栏下方（视觉顺序：标题 → 分区 → 内容）。
            sectionBar?.invoke()
            if (!showSectionChrome) {
                Box {
                    ShelfTools(
                        summary = "${filtered.size} 本小说",
                        grid = settings.gridView,
                        onSearch = { showSearch = !showSearch },
                        onSort = { showSort = true },
                        onView = { viewModel.updateSettings { it.copy(gridView = !it.gridView) } },
                        onImport = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                    )
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        listOf("recent" to "最近阅读", "added" to "导入时间", "title" to "书名", "progress" to "阅读进度").forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { viewModel.updateSettings { it.copy(sort = key) }; showSort = false },
                                trailingIcon = { if (settings.sort == key) Icon(Icons.Default.Check, "当前排序") },
                            )
                        }
                        DropdownMenuItem(text = { Text("筛选作品") }, onClick = { showSort = false; showFilters = !showFilters })
                        DropdownMenuItem(text = { Text("查看追更") }, onClick = { showSort = false; showTracking = true })
                    }
                }
            }
            if (selecting) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已选 ${selectedBooks.size} 本", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { selectedIds = filtered.map { it.id } }) { Text("全选当前结果") }
                    TextButton(onClick = { selecting = false; selectedIds = emptyList() }) { Text("完成") }
                }
            }
            if (showSearch || state.query.isNotBlank()) {
                AppSearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "搜索书名或作者",
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            val filters = listOf("all" to "全部", "online" to "在线", "local" to "本地", "favorite" to "收藏", "reading" to "在读", "unread" to "未读", "done" to "读完")
            ShelfFilterRow(filters.map { it.second }, filters.firstOrNull { it.first == state.statusFilter }?.second ?: "全部",
                showFilters, { showFilters = !showFilters }, { label -> filters.firstOrNull { it.second == label }?.let { viewModel.setStatusFilter(it.first) } })
            val tracked = state.books.filter { it.tracking && it.onlineUrl.isNotEmpty() }
            if (tracked.isNotEmpty() && showTracking) {
                // 追更不再常驻占多行：只留一行可点摘要，展开才看批量进度。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { showTracking = !showTracking },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append("追更 ${tracked.size} 本")
                                    val newChapters = tracked.sumOf { it.newChapterCount }
                                    if (newChapters > 0) append(" · 新增 $newChapters 章")
                                    if (state.online.running) append(" · 检查中")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (showTracking) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (showTracking) {
                            TrackingSummary(
                                tracked = tracked.size,
                                newChapters = tracked.sumOf { it.newChapterCount },
                                running = state.online.running,
                                onCheckAll = { viewModel.updateAllTracked() },
                            )
                        }
                        if (state.online.batch?.kind == "update") {
                            BatchProgress(state.online.batch!!)
                        }
                    }
                }
            }
            if (state.importProgress.active) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "正在导入 ${state.importProgress.current + 1}/${state.importProgress.total}：${state.importProgress.fileName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { if (state.importProgress.total == 0) 0f else state.importProgress.current.toFloat() / state.importProgress.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在加载书库…") }
            } else if (filtered.isEmpty()) {
                AppEmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = if (state.query.isNotBlank() || state.statusFilter != "all") "没有符合条件的书" else "书架还是空的",
                    description = if (state.query.isNotBlank() || state.statusFilter != "all")
                        "换个筛选条件或搜索词试试。"
                    else
                        "导入 TXT 或 EPUB 开始阅读；也可以从在线搜书把书带回来。",
                    actionLabel = if (state.query.isNotBlank() || state.statusFilter != "all") null else "导入第一本书",
                    onAction = if (state.query.isNotBlank() || state.statusFilter != "all") null
                    else { { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) } },
                )
            } else if (settings.gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (embedded) 24.dp else 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (continuing != null && !selecting) item(span = { GridItemSpan(maxLineSpan) }, key = "continue-reading") {
                        ContinueReadingBanner(continuing.title, continuing.chapterLabel) { navigator.openReader(continuing.id) }
                    }
                    items(filtered, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            selecting = selecting,
                            checked = book.id in selectedIds,
                            onLongClick = { selecting = true; selectedIds = (selectedIds + book.id).distinct() },
                            onOpen = { if (selecting) toggleSelection(book) else navigator.openReader(book.id) },
                            onFavorite = { viewModel.toggleFavorite(book) },
                            onDelete = { pendingDelete = book },
                            onEdit = { editing = book },
                            onToggleRead = { viewModel.toggleCompleted(book) },
                            onPin = { viewModel.togglePinned(book) },
                            onExport = { exporting = book },
                            onTracking = { viewModel.toggleTracking(book) },
                            onCheckUpdate = { viewModel.updateTrackedBooks(listOf(book.id)) },
                            onDownload = { viewModel.downloadShelfBook(book) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (embedded) 24.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (continuing != null && !selecting) item(key = "continue-reading") {
                        ContinueReadingBanner(continuing.title, continuing.chapterLabel) { navigator.openReader(continuing.id) }
                    }
                    items(filtered, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            selecting = selecting,
                            checked = book.id in selectedIds,
                            onLongClick = { selecting = true; selectedIds = (selectedIds + book.id).distinct() },
                            onOpen = { if (selecting) toggleSelection(book) else navigator.openReader(book.id) },
                            onFavorite = { viewModel.toggleFavorite(book) },
                            onDelete = { pendingDelete = book },
                            onEdit = { editing = book },
                            onExport = { exporting = book },
                            onPin = { viewModel.togglePinned(book) },
                            onToggleRead = { viewModel.toggleCompleted(book) },
                            onTracking = { viewModel.toggleTracking(book) },
                            onCheckUpdate = { viewModel.updateTrackedBooks(listOf(book.id)) },
                            onDownload = { viewModel.downloadShelfBook(book) },
                        )
                    }
                }
            }
        }
    }

    if (confirmBatchRemove) AlertDialog(
        onDismissRequest = { confirmBatchRemove = false },
        title = { Text("移除所选 ${selectedBooks.size} 本小说？") },
        text = { Text("将移除书库条目、本机正文副本、进度和书签。原始文件不受影响。") },
        confirmButton = { TextButton(onClick = {
            viewModel.deleteBooks(selectedBooks)
            confirmBatchRemove = false; selectedIds = emptyList(); selecting = false
        }) { Text("确认移除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmBatchRemove = false }) { Text("取消") } },
    )

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除《${book.title}》？") },
            text = { Text("只从书库移除条目并删除本地正文副本，原始文件不受影响。阅读进度与书签会一起删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book)
                    pendingDelete = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    editing?.let { book ->
        EditBookDialog(
            book = book,
            onDismiss = { editing = null },
            onConfirm = { title, author, intro ->
                viewModel.editBook(book, title, author, intro)
                editing = null
            },
        )
    }

    exporting?.let { book ->
        ExportDialog(
            book = book,
            store = app.container.store,
            onDismiss = { exporting = null },
            onResult = { text, error -> viewModel.message(text, error) },
        )
    }

    state.message?.let { message ->
        MessageDialog(message.text, message.error) { viewModel.consumeMessage() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    selecting: Boolean,
    checked: Boolean,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleRead: () -> Unit,
    onPin: () -> Unit,
    onExport: () -> Unit = {},
    onTracking: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    val radii = com.shengzimao.reader.mobile.ui.theme.LocalAppTokens.current.radii
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(bottom = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ShelfCoverAspectRatio)
                .clip(RoundedCornerShape(radii.cover))
                .background(coverBrush(book.title)),
            contentAlignment = Alignment.Center,
        ) {
            // 封面右下角的柔光，模拟纸张反光，让纯色封面不那么呆。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = 320f,
                        ),
                    ),
            )
            // 氛围装饰：雨挂水珠、雪积霜、春落瓣、秋夹叶……
            // 叠在封面之上、文字之下，所以不会挡住书名。
            CoverDecoration(seed = book.title.hashCode(), cornerRadius = 16)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    book.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (book.isDone && !book.missing) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(7.dp))
                        .background(Color.White.copy(alpha = 0.28f)).padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("读完", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (book.pinned) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.30f)).padding(4.dp),
                ) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "已置顶",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            if (book.missing) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.88f)).padding(vertical = 3.dp),
                ) { Text("正文缺失", color = MaterialTheme.colorScheme.onError, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            if (book.progress > 0f) {
                ShelfCoverProgress(book.progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShelfBookTitle(book.title, Modifier.weight(1f))
            if (selecting) Checkbox(checked, onCheckedChange = { onOpen() })
            else IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${book.title}的更多操作") }
        }
        ShelfCaption(if (book.onlineOnly) "在线 · 缓存优先" else "本地 · 可离线")
        BookUpdateLabel(book)
        ShelfCaption(
            if (book.position > 0) "${(book.progress * 100).toInt()}% · ${book.chapterLabel}" else book.author.ifBlank { book.format },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            BookMenuItems(
                book = book,
                onFavorite = { menu = false; onFavorite() },
                onPin = { menu = false; onPin() },
                onToggleRead = { menu = false; onToggleRead() },
                onEdit = { menu = false; onEdit() },
                onDelete = { menu = false; onDelete() },
                onExport = { menu = false; onExport() },
                onTracking = { menu = false; onTracking() },
                onCheckUpdate = { menu = false; onCheckUpdate() },
                onDownload = { menu = false; onDownload() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    selecting: Boolean,
    checked: Boolean,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onToggleRead: () -> Unit,
    onExport: () -> Unit = {},
    onTracking: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(coverBrush(book.title)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.title.take(2),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(book.author.ifBlank { "本地文件" })
                        append(" · ")
                        append(if (book.onlineOnly) "在线书架 · ${book.chapters.size} 章" else if (book.length > 0) "${book.length / 1000} 千字 · 可离线" else "正文缺失")
                        if (book.lastReadAt > 0) append(" · ").append(stamp(book.lastReadAt))
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BookUpdateLabel(book)
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(book.progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                                ),
                            ),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    if (book.position > 0) "已读 ${(book.progress * 100).toInt()}% · ${book.chapterLabel}" else book.chapterLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                if (selecting) Checkbox(checked, onCheckedChange = { onOpen() })
                else IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    BookMenuItems(
                        book = book,
                        onFavorite = { menu = false; onFavorite() },
                        onPin = { menu = false; onPin() },
                        onToggleRead = { menu = false; onToggleRead() },
                        onEdit = { menu = false; onEdit() },
                        onExport = { menu = false; onExport() },
                        onDelete = { menu = false; onDelete() },
                        onTracking = { menu = false; onTracking() },
                        onCheckUpdate = { menu = false; onCheckUpdate() },
                        onDownload = { menu = false; onDownload() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookMenuItems(
    book: Book,
    onFavorite: () -> Unit,
    onPin: () -> Unit,
    onToggleRead: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit = {},
    onDelete: () -> Unit,
    onTracking: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    DropdownMenuItem(
        text = { Text(if (book.favorite) "取消收藏" else "收藏") },
        leadingIcon = { Icon(if (book.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = null) },
        onClick = onFavorite,
    )
    DropdownMenuItem(
        text = { Text(if (book.pinned) "取消置顶" else "置顶") },
        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
        onClick = onPin,
    )
    DropdownMenuItem(
        text = { Text(if (book.completed) "标记为未读完" else "标记为读完") },
        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
        onClick = onToggleRead,
    )
    DropdownMenuItem(
        text = { Text("编辑书名与简介") },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        onClick = onEdit,
    )
    if (book.onlineOnly) {
        DropdownMenuItem(text = { Text("下载导入书库") }, onClick = onDownload)
    }
    if (book.onlineUrl.isNotEmpty() && !book.onlineOnly) {
        DropdownMenuItem(
            text = { Text(if (book.tracking) "取消追更" else "加入追更") },
            onClick = onTracking,
        )
        DropdownMenuItem(
            text = { Text("检查更新") },
            onClick = onCheckUpdate,
        )
    }
    DropdownMenuItem(
        text = { Text("导出为 TXT / EPUB") },
        enabled = !book.onlineOnly,
        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
        onClick = onExport,
    )
    DropdownMenuItem(
        text = { Text("从书库移除", color = MaterialTheme.colorScheme.error) },
        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        onClick = onDelete,
    )
}

@Composable
private fun EmptyLibrary(hasQuery: Boolean, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(if (hasQuery) "没有符合条件的书" else "书库还是空的", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasQuery) "换个关键词，或把筛选切回「全部」。" else "导入 TXT 或 EPUB 文件即可开始阅读。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!hasQuery) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onImport) { Text("导入文件") }
        }
    }
}

@Composable
private fun EditBookDialog(book: Book, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    var intro by remember { mutableStateOf(book.intro) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书籍信息") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("书名") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("作者") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = intro, onValueChange = { intro = it }, label = { Text("简介") }, minLines = 3, maxLines = 6)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, author, intro) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 书籍封面色：按标题哈希取一组渐变，比纯色更有成品感，也不至于杂乱。 */
private val COVER_GRADIENTS = listOf(
    0xFF5B6CFF to 0xFF8F5BFF,
    0xFF2AA9A1 to 0xFF4FD1C5,
    0xFFE0609F to 0xFFFF9BC8,
    0xFF3F6BD6 to 0xFF6FA8FF,
    0xFF8A5BD6 to 0xFFB47BFF,
    0xFFD97742 to 0xFFF2A96B,
    0xFF2F8F6E to 0xFF5BC49A,
    0xFF4A5D8F to 0xFF7A8FD4,
)

private fun coverBrush(title: String): Brush {
    val index = (title.hashCode().let { if (it < 0) -it else it }) % COVER_GRADIENTS.size
    val (from, to) = COVER_GRADIENTS[index]
    return Brush.linearGradient(listOf(Color(from), Color(to)))
}

/** 排序键的展示名（与设置页同义，这里本地化一份避免跨文件依赖 private）。 */
private fun sortLabel(key: String): String = when (key) {
    "recent" -> "最近阅读"
    "added" -> "导入时间"
    "title" -> "书名"
    "progress" -> "阅读进度"
    else -> "最近阅读"
}

/** 书架顶部「继续阅读」横向卡片：一屏就能回到上次在读的书。 */
@Composable
private fun ContinueReadingCard(book: Book, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(98.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(16.dp))
                .background(coverBrush(book.title)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = 260f,
                        ),
                    ),
            )
            Text(
                book.title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(6.dp),
            )
            if (book.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.2f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(book.progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.95f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(book.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            "${(book.progress * 100).toInt()}% · ${book.chapterLabel}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun stamp(value: Long): String = SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(value))

@Composable
private fun BatchProgress(batch: com.shengzimao.reader.mobile.data.OnlineBatch) {
    val labels = mapOf("pending" to "排队", "downloading" to "处理中", "done" to "完成", "failed" to "失败", "skipped" to "跳过")
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("${batch.finished} / ${batch.total} 已完成", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { if (batch.total == 0) 0f else batch.finished.toFloat() / batch.total },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
        )
        batch.items.take(4).forEach { item ->
            Text(
                "${labels[item.state] ?: item.state} · ${item.bookName}${if (item.message.isBlank()) "" else " · " + item.message}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
