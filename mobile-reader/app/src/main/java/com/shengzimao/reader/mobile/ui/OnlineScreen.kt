package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.Consent
import com.shengzimao.reader.mobile.data.OnlineMatcher
import com.shengzimao.reader.mobile.data.OnlineResult
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface

/** 搜索结果聚合：同一本书的多个书源合成一组，默认按匹配度排序。 */
private data class ResultGroup(
    val book: OnlineResult,
    val variants: List<Pair<Int, OnlineResult>>,
)

/**
 * 在线搜书页。首次使用需滚动读完声明并勾选确认，与桌面版门槛一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineScreen(viewModel: ReaderViewModel, navigator: Navigator, tomatoPage: Boolean = false) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var selecting by rememberSaveable(tomatoPage) { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var searchMenu by remember(tomatoPage) { mutableStateOf(false) }
    val online = if (tomatoPage) state.tomato else state.online
    var aggregateKeyword by rememberSaveable { mutableStateOf(state.online.keyword) }
    var tomatoKeyword by rememberSaveable { mutableStateOf(state.tomato.keyword) }
    val keyword = if (tomatoPage) tomatoKeyword else aggregateKeyword
    val setKeyword: (String) -> Unit = { if (tomatoPage) tomatoKeyword = it else aggregateKeyword = it }
    var site by rememberSaveable(tomatoPage, online.sourceGroup, online.revision) { mutableStateOf("") }
    var expandedFailures by remember(online.revision) { mutableStateOf(false) }
    var sort by rememberSaveable(tomatoPage) { mutableStateOf("arrival") }
    var format by rememberSaveable(tomatoPage) { mutableStateOf("txt") }
    var selected by remember(tomatoPage, online.revision) { mutableStateOf(setOf<Int>()) }
    var detailsOpen by remember(tomatoPage, online.sourceGroup) { mutableStateOf(false) }
    var importSourceGroup by rememberSaveable(tomatoPage) { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val sourceScopeReady = tomatoPage || online.sourcesReady && online.sourceEntries.any {
        it.searchable && (online.selectedSourceUrls == null || it.url in online.selectedSourceUrls.orEmpty())
    }
    val searchEnabled = !online.running && !online.importingSources && sourceScopeReady && keyword.isNotBlank()
    val search = {
        if (searchEnabled) {
            keyboard?.hide()
            selecting = false
            viewModel.searchOnline(keyword, tomatoPage)
        }
    }

    if (!state.consentAccepted) {
        ConsentScreen(accepted = { viewModel.acceptConsent() }, onBack = { navigator.openLibrary() })
        return
    }

    val arrival = remember(tomatoPage, online.revision) { com.shengzimao.reader.mobile.data.SearchArrivalOrder<String>() }
    val groups = remember(online.results, site, sort, online.revision) {
        val all = groupResults(online.results, "", sort)
        arrival.record(all.map { OnlineMatcher.groupKey(it.book) })
        val shown = if (site.isEmpty()) all else groupResults(online.results, site, sort)
        if (sort == "arrival") arrival.sort(shown) { OnlineMatcher.groupKey(it.book) } else shown
    }
    val sources = remember(online.results) {
        online.results.groupBy { it.sourceId to it.sourceName }
            .map { (key, list) -> Triple(key.first, key.second, list.size) }
            .sortedByDescending { it.third }
    }

    Scaffold(
        topBar = {
            Column {
                DiscoverySearchBar(
                    value = keyword,
                    onValueChange = setKeyword,
                    placeholder = if (tomatoPage) "搜索番茄书名、作者或粘贴链接" else "搜索书名或作者",
                    searchEnabled = searchEnabled,
                    searching = online.searching,
                    onSearch = { search() },
                )
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (tomatoPage) Text("默认番茄服务 · 无需导入书源",
                            Modifier.padding(start = 20.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else NovelSourceGroupPicker(online, viewModel::selectNovelSourceGroup,
                            onImport = { viewModel.online.clearError(); importSourceGroup = online.sourceGroup },
                            onManage = { navigator.openTool(ToolPage.NOVEL_SOURCES) }, onScope = viewModel::selectNovelSearchSources)
                    }
                    Box {
                        IconButton(onClick = { searchMenu = true }) { Icon(Icons.Default.MoreVert, "搜索选项") }
                        DropdownMenu(searchMenu, { searchMenu = false }) {
                            DropdownMenuItem(text = { Text("批量选择") }, enabled = online.results.isNotEmpty() && !online.running,
                                onClick = { selecting = true; searchMenu = false })
                            if (!tomatoPage) DropdownMenuItem(text = { Text("书源管理") },
                                onClick = { searchMenu = false; navigator.openTool(ToolPage.NOVEL_SOURCES) })
                            DropdownMenuItem(text = { Text("检查服务") }, enabled = !online.running && !online.importingSources,
                                onClick = { searchMenu = false; viewModel.probeEngine(tomatoPage) })
                            DropdownMenuItem(text = { Text("使用说明与网络声明") }, onClick = { searchMenu = false; showHelp = true })
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (online.searchHistory.isNotEmpty() && online.results.isEmpty() && !online.running) {
                item {
                    Column {
                        Text("最近搜索", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            online.searchHistory.take(5).forEach { word ->
                                AssistChip(onClick = { setKeyword(word); viewModel.searchOnline(word, tomatoPage) }, enabled = !online.running && !online.importingSources &&
                                    sourceScopeReady,
                                    label = { Text(word, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                            }
                        }
                    }
                }
            }
            if (online.running) {
                item {
                    DiscoveryProgress(online.message, if (online.queuedDownload) "下载管理" else "取消任务") {
                        if (online.queuedDownload) navigator.openTool(ToolPage.DOWNLOADS) else viewModel.cancelOnline(tomatoPage)
                    }
                }
            } else if (online.message.isNotEmpty() && (online.keyword.isNotBlank() || online.results.isNotEmpty())) {
                item {
                    Text(
                        buildString {
                            append(online.message)
                            if (online.elapsedMs > 0) append(" · 耗时 ${"%.1f".format(online.elapsedMs / 1000.0)}s")
                        },
                        fontSize = 14.sp,
                        color = if (online.error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (online.searchFailures.isNotEmpty() && !tomatoPage) item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${online.searchFailures.size} 个书源请求失败", fontWeight = FontWeight.Medium)
                        Text("已返回的书仍可阅读；失败可能来自网络、站点验证或书源规则。", style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (if (expandedFailures) online.searchFailures else online.searchFailures.take(3)).forEach { failure ->
                                Text("${failure.sourceName}：${failure.message}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.retryFailedNovelSources() }, enabled = !online.running && !online.importingSources) { Text("重试失败书源") }
                            if (online.searchFailures.size > 3) TextButton(onClick = { expandedFailures = !expandedFailures }) { Text(if (expandedFailures) "收起原因" else "查看全部原因") }
                        }
                    }
                }
            }
            if (online.error.isNotBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(online.error, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                            FlowRow {
                                TextButton(onClick = { search() }, enabled = !online.running && keyword.isNotBlank()) { Text("重新搜索") }
                                if (tomatoPage) TextButton(onClick = { uriHandler.openUri("https://fanqienovel.com") }) { Text("打开番茄官网") }
                            }
                        }
                    }
                }
            }
            if (online.results.isNotEmpty()) {
                item {
                    FlowRow(verticalArrangement = Arrangement.spacedBy(0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!tomatoPage) SourcePicker(sources, site) { site = it }
                        SortPicker(sort, showSources = !tomatoPage) { sort = it }
                        if (!tomatoPage) FormatPicker(format) { format = it }
                    }
                }
                if (selecting) item {
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已选 ${selected.size} 本", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = {
                                val indexes = groups.mapNotNull { it.variants.firstOrNull()?.first }.filter { it in selected }
                                viewModel.downloadOnlineMany(indexes, format, tomatoPage)
                            },
                            enabled = selected.isNotEmpty() && !online.running && !online.importingSources,
                        ) { Text("批量下载", fontSize = 13.sp) }
                        TextButton(onClick = { selected = emptySet(); selecting = false }) { Text("取消选择", fontSize = 13.sp) }
                    }
                }
            }
            items(groups, key = { it.book.bookName + "\u0000" + it.book.author }) { group ->
                OnlineResultCard(
                    book = group.book,
                    variants = group.variants,
                    checked = group.variants.first().first in selected,
                    selecting = selecting,
                    showSources = !tomatoPage,
                    enabled = !online.running && !online.importingSources,
                    readEnabled = (!online.running || online.searching || online.queuedDownload) && !online.importingSources,
                    onCheck = { index -> selecting = true; selected = if (index in selected) selected - index else selected + index },
                    onDetails = {
                        viewModel.openOnlineDetails(group.variants.first().first, tomatoPage)
                        detailsOpen = true
                    },
                    onDownload = { index -> viewModel.downloadOnline(index, format, tomatoPage) },
                    onRead = { index -> group.variants.firstOrNull { it.first == index }?.second?.let { result ->
                        viewModel.addOnlineShelf(result) { navigator.openReader(it) }
                    } },
                    onShelf = { index -> group.variants.firstOrNull { it.first == index }?.second?.let { viewModel.addOnlineShelf(it) } },
                )
            }
            if (online.results.isNotEmpty() && groups.isEmpty()) item {
                DiscoveryEmptyState(icon = Icons.Default.AutoStories, title = "当前来源没有匹配结果",
                    description = "其他书源已有结果，清除筛选后即可查看。", actionLabel = "查看全部书源", onAction = { site = "" })
            }
            if (!online.running && online.results.isEmpty() && online.error.isBlank() && online.searchFailures.isEmpty()) {
                item {
                    val missingSources = !tomatoPage && online.sourcesReady && online.sourceEntries.none { it.searchable }
                    DiscoveryEmptyState(
                        icon = Icons.Default.AutoStories,
                        title = when {
                            missingSources -> "选好书源，再找好书"
                            online.keyword.isNotBlank() -> "换个关键词再找找"
                            tomatoPage -> "想读什么，搜一下"
                            else -> "找到下一本想读的书"
                        },
                        description = when {
                            missingSources -> "当前分组没有可搜索书源。可切回内置分组，或在书源管理中导入。"
                            online.keyword.isNotBlank() -> "试试缩短书名，或使用作者名字搜索。"
                            tomatoPage -> "输入书名或作者，也可粘贴书籍 ID、官网链接。喜欢的书可以加入书架，下载后可离线阅读。"
                            else -> "输入书名或作者，同时搜索多个书源。结果返回就能打开阅读，喜欢的书可以加入书架。"
                        },
                        actionLabel = if (missingSources) "管理书源" else null,
                        onAction = { navigator.openTool(ToolPage.NOVEL_SOURCES) },
                    )
                }
            }
        }
    }

    if (showHelp) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text(if (tomatoPage) "番茄小说" else "书源与网络") },
        text = { Text(if (tomatoPage)
            "使用默认番茄服务，无需导入书源。直接搜索书名、作者、书籍 ID 或官网链接；可在线阅读、加入书架或下载入库。遇到未知字体、登录、付费或验证要求时，请前往官网阅读。"
            else "仅搜索当前分组中选中的书源。分组 1 使用内置规则；分组 2–5 可导入用户规则。先返回的结果会立即展示。关键词会发送到第三方书源站点，部分站点使用未加密的 HTTP 连接。") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
    )

    if (importSourceGroup in 2..5 && !tomatoPage) {
        NovelSourceImportSheet(viewModel, importSourceGroup, state.online) { importSourceGroup = 0 }
    }
    if (detailsOpen && online.details != null) {
        OnlineDetailsSheet(
            state = online,
            format = format,
            onDismiss = {
                detailsOpen = false
                viewModel.closeOnlineDetails(tomatoPage)
            },
            onDownload = { index -> viewModel.downloadOnline(index, format, tomatoPage) },
            onDownloadChapters = { chapters -> viewModel.downloadOnlineChapters(online.detailsIndex, chapters, format, tomatoPage) },
            onRead = { online.details?.let { result -> viewModel.addOnlineShelf(result, online.detailsToc) { id ->
                detailsOpen = false; viewModel.closeOnlineDetails(tomatoPage); navigator.openReader(id)
            } } },
            onReadChapter = { index -> online.details?.let { result ->
                val chapter = online.detailsToc.getOrNull(index)
                if (chapter != null) viewModel.addOnlineShelf(result, online.detailsToc) { id ->
                    viewModel.openNovelChapter(id, index, chapter.title) {
                        detailsOpen = false; viewModel.closeOnlineDetails(tomatoPage); navigator.openReader(id)
                    }
                }
            } },
            onRefresh = { viewModel.openOnlineDetails(online.detailsIndex, tomatoPage) },
            onShelf = { online.details?.let { viewModel.addOnlineShelf(it, online.detailsToc) } },
        )
    }
    state.message?.let { message ->
        MessageToast(message.text, message.error) { viewModel.consumeMessage() }
    }
}

private fun groupResults(results: List<OnlineResult>, site: String, sort: String): List<ResultGroup> {
    val filtered = results.withIndex().filter { (_, item) -> site.isEmpty() || item.sourceId.toString() == site }
    val map = LinkedHashMap<String, MutableList<Pair<Int, OnlineResult>>>()
    for ((index, item) in filtered) {
        map.getOrPut(OnlineMatcher.groupKey(item)) { mutableListOf() }.add(index to item)
    }
    val groups = map.values.map { ResultGroup(it.first().second, it) }
    return when (sort) {
        "arrival" -> groups
        "source" -> groups.sortedByDescending { it.variants.size }
        "title" -> groups.sortedBy { it.book.bookName }
        else -> groups.sortedByDescending { it.book.matchScore }
    }
}

@Composable
private fun SourcePicker(sources: List<Triple<Int, String, Int>>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    BoxWithMenu(
        open = open,
        onOpen = { open = true },
        onDismiss = { open = false },
        label = if (value.isEmpty()) "全部书源" else sources.firstOrNull { it.first.toString() == value }?.second ?: "书源",
    ) {
        DropdownMenuItem(text = { Text("全部书源") }, onClick = { onChange(""); open = false })
        sources.forEach { (id, name, count) ->
            DropdownMenuItem(text = { Text("$name（$count）") }, onClick = { onChange(id.toString()); open = false })
        }
    }
}

@Composable
private fun SortPicker(value: String, showSources: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = linkedMapOf("arrival" to "返回顺序", "match" to "匹配度", "title" to "书名").apply {
        if (showSources) put("source", "书源数")
    }
    BoxWithMenu(open, { open = true }, { open = false }, labels[value] ?: "排序") {
        labels.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(key); open = false }) }
    }
}

@Composable
private fun FormatPicker(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    BoxWithMenu(open, { open = true }, { open = false }, "入库 ${value.uppercase()}") {
        listOf("txt", "epub").forEach { key ->
            DropdownMenuItem(text = { Text(key.uppercase()) }, onClick = { onChange(key); open = false })
        }
    }
}

@Composable
private fun BoxWithMenu(
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    label: String,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        FilterChip(selected = false, onClick = onOpen, label = { Text(label, fontSize = 12.sp) })
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnlineResultCard(
    book: OnlineResult,
    variants: List<Pair<Int, OnlineResult>>,
    checked: Boolean,
    selecting: Boolean,
    showSources: Boolean,
    enabled: Boolean,
    readEnabled: Boolean,
    onCheck: (Int) -> Unit,
    onDetails: () -> Unit,
    onDownload: (Int) -> Unit,
    onRead: (Int) -> Unit,
    onShelf: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val index = variants.first().first
    var more by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selecting) onCheck(index) else if (enabled) onDetails() },
            onLongClick = { if (enabled) onCheck(index) },
        ),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(checked = checked, onCheckedChange = { onCheck(index) }, enabled = enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(book.bookName, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)

                    }
                    Text(
                        buildString {
                            append(book.author.ifBlank { "作者未知" })
                            if (book.category.isNotBlank()) append(" · ").append(book.category)
                            if (book.status.isNotBlank()) append(" · ").append(book.status)
                        },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            if (book.latestChapter.isNotBlank()) append("最新：").append(book.latestChapter).append(" · ")
                            append(book.sourceName.ifBlank { "未知书源" })
                            if (showSources && variants.size > 1) append(" · ").append(variants.size).append(" 个来源")
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onRead(index) }, enabled = readEnabled) { Text("阅读") }
                TextButton(onClick = { onShelf(index) }, enabled = readEnabled) { Text("加入书架") }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "作品更多操作") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("详情与目录") }, enabled = enabled, onClick = { more = false; onDetails() })
                        DropdownMenuItem(text = { Text("下载入库") }, enabled = enabled, onClick = { more = false; onDownload(index) })
                        if (book.intro.isNotBlank() || showSources && variants.size > 1) DropdownMenuItem(text = { Text(if (expanded) "收起附加信息" else if (showSources && variants.size > 1) "简介与 ${variants.size} 个来源" else "查看作品简介") },
                            onClick = { more = false; expanded = !expanded })
                        DropdownMenuItem(text = { Text("多选作品") }, enabled = enabled, onClick = { more = false; onCheck(index) })
                    }
                }
            }
            if (expanded && book.intro.isNotBlank()) {
                Text(
                    book.intro,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (expanded && showSources && variants.size > 1) {
                variants.forEach { (vi, item) ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Column {
                        Column {
                            Text(item.sourceName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(item.latestChapter.ifBlank { "源站未提供最新章节" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRead(vi) }, enabled = readEnabled) { Text("从该书源在线读", fontSize = 12.sp) }
                            TextButton(onClick = { onDownload(vi) }, enabled = enabled) { Text("从该书源入库", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

/** 首次使用声明：滚动到底才允许勾选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentScreen(accepted: () -> Unit, onBack: () -> Unit) {
    var readToEnd by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(scroll) {
        snapshotFlow { scroll.value to scroll.maxValue }.first { (value, maximum) ->
            maximum != Int.MAX_VALUE && value >= maximum - 8
        }
        readToEnd = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("在线功能使用声明") },
                actions = { TextButton(onClick = onBack) { Text("返回书库") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(scroll)) { ConsentText() }
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text(
                if (readToEnd) "已阅读到底，请勾选确认。" else "请滚动阅读到底，再勾选确认。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { checked = it }, enabled = readToEnd)
                Text("我已阅读并理解以上说明，同意遵守使用条件。", fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) { Text("返回书库") }
                Button(onClick = accepted, enabled = readToEnd && checked) { Text("同意并进入在线功能") }
            }
        }
    }
}

@Composable
private fun ConsentText() {
    Column {
        val sections = listOf(
            "来源与开源许可" to (
                "聚合搜书使用开源项目 SoNovel；番茄小说页由本项目按桌面版逻辑独立适配公开接口，界面与适配层由本项目独立实现。" +
                    "SoNovel 项目地址：${Consent.LINKS["project"]}。本软件保留 AGPL-3.0 许可与来源说明。"
                ),
            "内容与授权" to (
                "在线内容由第三方网站提供，版权归原作者及其他权利人所有。本软件不提供作品授权，也不保证第三方网站有权传播内容。" +
                    "请仅检索、下载和转换您有权访问和使用的作品，尊重作者、网站服务条款及适用法律，不得用于未经授权的传播或商业用途。建议优先选择作者与正版平台。"
                ),
            "网络访问与数据" to (
                "搜索会把关键词发往启用的第三方书源；读取详情、检查更新及下载会访问相应网站。第三方可能记录网络地址和访问行为。" +
                    "漫画搜索、封面与图片也会直接访问用户导入的源站。书库、漫库、阅读进度与确认记录保存在本机，不由本项目上传。"
                ),
            "可用性与风险说明" to (
                "第三方内容的准确性、完整性、时效性与持续可用性无法保证。网页规则改变或网络故障可能导致目录缺失或下载失败。" +
                    "追更只在旧目录可核对时追加新章节，不负责替换源站对旧章节的修订。重要书库请定期导出备份。"
                ),
            "番茄小说说明" to (
                "本项目与番茄小说及其运营方无隶属、合作或授权关系，相关名称与标识归各权利人所有。" +
                    "番茄页仅访问公开接口与网页，不绕过登录、付费、验证码及其他访问限制。公开可访问不等于已获得转载或再传播授权，请优先支持正版平台。" +
                    "公开协议研究参考 Tomato-Novel-Downloader（MIT）；已知公开网页字体字符数据来自 ying-ck/fanqienovel-downloader（AGPL-3.0），保留来源与许可。不支持的字体或缺字会停止；本软件不承诺目录中每章均可下载。"
                ),
            "责任与退出" to (
                "请根据您的用途自行判断来源和使用权限。若不同意，可返回书库继续使用本地阅读、导入与导出功能。"
                ),
            "漫画源与下载管理" to (
                "漫画由您自行导入可信且有权使用的 HTML/CSS 或 JSON 漫画源，不内置漫画内容，不执行漫画源脚本，不绕过登录、付费或验证。源规则不代表作品授权。" +
                    "小说与漫画下载仅在应用运行期间处理，进程退出可能中断；可在下载管理重试，小说从头下载，漫画复用已缓存图片。小说 .szmbak 备份不含漫库、漫画源、图片缓存或下载记录，请另行导出源并保留本地漫画原件。"
                ),
        )
        sections.forEach { (title, body) ->
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Text(body, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "声明版本：${Consent.VERSION} · 已阅读至此",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
