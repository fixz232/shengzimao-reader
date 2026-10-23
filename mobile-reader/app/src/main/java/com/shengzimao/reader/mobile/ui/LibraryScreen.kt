package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.ReaderApp
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.DocumentImporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书库主页面：三种筛选 + 搜索 + 网格/列表 + 导入。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: ReaderViewModel,
    navigator: Navigator,
    accountLabel: String? = null,
    embedded: Boolean = false,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val settings = state.settings
    var showSort by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var editing by remember { mutableStateOf<Book?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(context, uris)
    }

    val filtered = remember(state.books, state.query, state.statusFilter, settings.sort, settings.gridView) {
        val base = state.books.filter { book ->
            val statusOk = when (state.statusFilter) {
                "reading" -> book.position > 0 && !book.isDone
                "unread" -> book.position <= 0 && !book.isDone
                "done" -> book.isDone
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
                    IconButton(onClick = { navigator.openOnline() }) {
                        Icon(Icons.Filled.Public, contentDescription = "在线搜书")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索书库")
                    }
                    IconButton(onClick = { showSort = true }) {
                        Icon(if (settings.gridView) Icons.Filled.ViewList else Icons.Filled.GridView, contentDescription = "切换视图")
                    }
                    if (!embedded) {
                        IconButton(onClick = { onOpenSettings() }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                    IconButton(onClick = { navigator.openAbout() }) {
                        Icon(Icons.Filled.Info, contentDescription = "关于")
                    }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        listOf("recent" to "最近阅读", "added" to "导入时间", "title" to "书名", "progress" to "阅读进度").forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(if (settings.sort == key) "✓ $label" else label) },
                                onClick = {
                                    viewModel.updateSettings { it.copy(sort = key, gridView = if (key != settings.sort) settings.gridView else !settings.gridView) }
                                    showSort = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (settings.gridView) "列表显示" else "网格显示") },
                            onClick = {
                                viewModel.updateSettings { it.copy(gridView = !settings.gridView) }
                                showSort = false
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showSearch) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("搜索书名或作者") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("all" to "全部", "reading" to "在读", "unread" to "未读", "done" to "读完").forEach { (key, label) ->
                    FilterChip(
                        selected = state.statusFilter == key,
                        onClick = { viewModel.setStatusFilter(key) },
                        label = { Text(label, fontSize = 13.sp) },
                    )
                }
            }
            val tracked = state.books.filter { it.tracking && it.onlineUrl.isNotEmpty() }
            if (tracked.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TrackingSummary(
                            tracked = tracked.size,
                            newChapters = tracked.sumOf { it.newChapterCount },
                            running = state.online.running,
                            onCheckAll = { viewModel.updateAllTracked() },
                        )
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
                EmptyLibrary(
                    hasQuery = state.query.isNotBlank() || state.statusFilter != "all",
                    onImport = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                )
            } else if (settings.gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (embedded) 96.dp else 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            onOpen = { navigator.openReader(book.id) },
                            onFavorite = { viewModel.toggleFavorite(book) },
                            onDelete = { pendingDelete = book },
                            onEdit = { editing = book },
                            onToggleRead = { viewModel.toggleCompleted(book) },
                            onPin = { viewModel.togglePinned(book) },
                            onExport = { exporting = book },
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (embedded) 96.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onOpen = { navigator.openReader(book.id) },
                            onFavorite = { viewModel.toggleFavorite(book) },
                            onDelete = { pendingDelete = book },
                            onEdit = { editing = book },
                            onExport = { exporting = book },
                        )
                    }
                }
            }
        }
    }

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
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleRead: () -> Unit,
    onPin: () -> Unit,
    onExport: () -> Unit = {},
    onTracking: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(coverColor(book.title)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    book.title,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (book.isDone) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)).padding(horizontal = 5.dp, vertical = 2.dp),
                ) { Text("读完", color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp) }
            }
            if (book.pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "已置顶",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(14.dp),
                )
            }
            if (book.missing) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f)).padding(vertical = 3.dp),
                ) { Text("正文缺失", color = MaterialTheme.colorScheme.onError, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            if (book.position > 0) "${(book.progress * 100).toInt()}% · ${book.chapterLabel}" else book.author.ifBlank { book.format },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.position > 0) {
            LinearProgressIndicator(
                progress = { book.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp),
            )
        }
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
            )
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit = {},
    onTracking: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(coverColor(book.title)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.title.take(2),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(book.author.ifBlank { "本地文件" })
                        append(" · ")
                        append(if (book.length > 0) "${book.length / 1000} 千字" else "正文缺失")
                        if (book.lastReadAt > 0) append(" · ").append(stamp(book.lastReadAt))
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    if (book.position > 0) "已读 ${(book.progress * 100).toInt()}% · ${book.chapterLabel}" else book.chapterLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    BookMenuItems(
                        book = book,
                        onFavorite = { menu = false; onFavorite() },
                        onPin = { menu = false },
                        onToggleRead = { menu = false },
                        onEdit = { menu = false; onEdit() },
                        onExport = { menu = false; onExport() },
                        onDelete = { menu = false; onDelete() },
                        onTracking = { menu = false; onTracking() },
                        onCheckUpdate = { menu = false; onCheckUpdate() },
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
    if (book.onlineUrl.isNotEmpty()) {
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

/** 书籍封面色：按标题哈希取一组低饱和底色，避免纯随机导致视觉杂乱。 */
private val COVER_COLORS = listOf(
    0xFF2F4B7C, 0xFF3C6E71, 0xFF7A4E48, 0xFF4A5D8F, 0xFF6B5B95, 0xFF40667A, 0xFF8A6D3B, 0xFF496A5B,
)

private fun coverColor(title: String) = androidx.compose.ui.graphics.Color(COVER_COLORS[(title.hashCode().let { if (it < 0) -it else it }) % COVER_COLORS.size])

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