package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.Consent
import com.shengzimao.reader.mobile.data.OnlineMatcher
import com.shengzimao.reader.mobile.data.OnlineResult

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
fun OnlineScreen(viewModel: ReaderViewModel, navigator: Navigator, embedded: Boolean = false) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var tomatoPage by rememberSaveable { mutableStateOf(false) }
    val online = if (tomatoPage) state.tomato else state.online
    var aggregateKeyword by rememberSaveable { mutableStateOf(state.online.keyword) }
    var tomatoKeyword by rememberSaveable { mutableStateOf(state.tomato.keyword) }
    val keyword = if (tomatoPage) tomatoKeyword else aggregateKeyword
    val setKeyword: (String) -> Unit = { if (tomatoPage) tomatoKeyword = it else aggregateKeyword = it }
    var site by remember(tomatoPage) { mutableStateOf("") }
    var sort by remember(tomatoPage) { mutableStateOf("match") }
    var format by remember(tomatoPage) { mutableStateOf("txt") }
    var selected by remember(tomatoPage, online.revision) { mutableStateOf(setOf<Int>()) }
    var detailsOpen by remember(tomatoPage) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val search = {
        if (!online.running && keyword.isNotBlank()) {
            keyboard?.hide()
            viewModel.searchOnline(keyword, tomatoPage)
        }
    }

    if (!state.consentAccepted) {
        ConsentScreen(accepted = { viewModel.acceptConsent() }, onBack = { navigator.openLibrary() })
        return
    }

    val groups = remember(online.results, site, sort) { groupResults(online.results, site, sort) }
    val sources = remember(online.results) {
        online.results.groupBy { it.sourceId to it.sourceName }
            .map { (key, list) -> Triple(key.first, key.second, list.size) }
            .sortedByDescending { it.third }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("在线搜书") },
                    actions = { TextButton(onClick = { viewModel.probeEngine(tomatoPage) }, enabled = !online.running) { Text("检查服务") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                SecondaryTabRow(selectedTabIndex = if (tomatoPage) 1 else 0) {
                    Tab(selected = !tomatoPage, onClick = { tomatoPage = false }, text = { Text("聚合搜书") })
                    Tab(selected = tomatoPage, onClick = { tomatoPage = true }, text = { Text("番茄小说") })
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = setKeyword,
                        placeholder = { Text(if (tomatoPage) "番茄书名、ID 或官网链接" else "书名或「书名 作者」") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { search() }, enabled = !online.running && keyword.isNotBlank()) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("搜索", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (online.searchHistory.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        online.searchHistory.take(5).forEach { word ->
                            AssistChip(onClick = { setKeyword(word); viewModel.searchOnline(word, tomatoPage) }, enabled = !online.running, label = { Text(word, fontSize = 12.sp) })
                        }
                    }
                }
            }
            if (online.running) {
                item {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(online.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { viewModel.cancelOnline(tomatoPage) }) { Text("取消任务") }
                    }
                }
            } else if (online.message.isNotEmpty()) {
                item {
                    Text(
                        buildString {
                            append(online.message)
                            if (online.elapsedMs > 0) append(" · 耗时 ${"%.1f".format(online.elapsedMs / 1000.0)}s")
                        },
                        fontSize = 12.sp,
                        color = if (online.error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourcePicker(sources, site) { site = it }
                        SortPicker(sort) { sort = it }
                        if (!tomatoPage) FormatPicker(format) { format = it }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 本", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                val indexes = groups.mapNotNull { it.variants.firstOrNull()?.first }.filter { it in selected }
                                viewModel.downloadOnlineMany(indexes, format, tomatoPage)
                            },
                            enabled = selected.isNotEmpty() && !online.running,
                        ) { Text("批量下载", fontSize = 13.sp) }
                        TextButton(onClick = { selected = emptySet() }, enabled = selected.isNotEmpty()) { Text("取消选择", fontSize = 13.sp) }
                    }
                }
            }
            items(groups, key = { it.book.bookName + "\u0000" + it.book.author }) { group ->
                OnlineResultCard(
                    book = group.book,
                    variants = group.variants,
                    checked = group.variants.first().first in selected,
                    enabled = !online.running,
                    onCheck = { index -> selected = if (index in selected) selected - index else selected + index },
                    onDetails = {
                        viewModel.openOnlineDetails(group.variants.first().first, tomatoPage)
                        detailsOpen = true
                    },
                    onDownload = { index -> viewModel.downloadOnline(index, format, tomatoPage) },
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("书源与网络", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (tomatoPage) "直接访问番茄公开搜索、书籍详情和目录，不依赖聚合引擎。正文仅在官网公开且可读时入库；遇到登录、付费、验证或字体加密会停止，请前往官网阅读。"
                            else "搜索会把关键词发到启用的第三方书源站点，需要联网。若长时间没有结果，可先点右上角「检查服务」确认内置引擎状态。部分书源使用 HTTP，传输不加密。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (!online.running && online.results.isEmpty() && online.keyword.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("怎么用", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(if (tomatoPage) "1 · 输入番茄书名或作者，也可粘贴书籍 ID、官网书籍链接。" else "1 · 输入书名或「书名 作者」，多个关键词用空格分隔。", fontSize = 13.sp)
                            Text(if (tomatoPage) "2 · 打开详情核对作者、简介及目录；也可在番茄官网阅读。" else "2 · 同一本书会聚合多个书源，可展开对比来源与最新章节。", fontSize = 13.sp)
                            Text("3 · 点「下载入库」加入书库，并自动加入追更。", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
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
private fun SortPicker(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = linkedMapOf("match" to "匹配度", "title" to "书名", "source" to "书源数")
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

@Composable
private fun OnlineResultCard(
    book: OnlineResult,
    variants: List<Pair<Int, OnlineResult>>,
    checked: Boolean,
    enabled: Boolean,
    onCheck: (Int) -> Unit,
    onDetails: () -> Unit,
    onDownload: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val index = variants.first().first
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onCheck(index) }, enabled = enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(book.bookName, modifier = Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (book.matchScore > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${book.matchType} ${book.matchScore}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        buildString {
                            append(book.author.ifBlank { "作者未知" })
                            if (book.category.isNotBlank()) append(" · ").append(book.category)
                            if (book.status.isNotBlank()) append(" · ").append(book.status)
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            if (book.latestChapter.isNotBlank()) append("最新：").append(book.latestChapter).append(" · ")
                            append(book.sourceName.ifBlank { "未知书源" })
                            append(" · ").append(variants.size).append(" 个书源")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetails, enabled = enabled) { Text("详情 / 目录", fontSize = 12.sp) }
                Button(onClick = { onDownload(index) }, enabled = enabled) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("下载入库", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
                if (variants.size > 1) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起书源" else "切换书源", fontSize = 12.sp) }
                }
            }
            if (book.intro.isNotBlank()) {
                Text(
                    book.intro,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (expanded) {
                variants.forEach { (vi, item) ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.sourceName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(item.latestChapter.ifBlank { "源站未提供最新章节" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onDownload(vi) }, enabled = enabled) { Text("从该书源入库", fontSize = 12.sp) }
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
    androidx.compose.runtime.LaunchedEffect(scroll.value, scroll.maxValue) {
        if (scroll.maxValue >= 0 && scroll.value >= scroll.maxValue - 8) readToEnd = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("在线功能使用声明") },
                actions = { TextButton(onClick = onBack) { Text("返回书库") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
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
                Button(onClick = accepted, enabled = readToEnd && checked) { Text("同意并进入在线搜书") }
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
                    "书库、阅读进度与确认记录保存在本机，不会上传。"
                ),
            "可用性与风险说明" to (
                "第三方内容的准确性、完整性、时效性与持续可用性无法保证。网页规则改变或网络故障可能导致目录缺失或下载失败。" +
                    "追更只在旧目录可核对时追加新章节，不负责替换源站对旧章节的修订。重要书库请定期导出备份。"
                ),
            "番茄小说说明" to (
                "本项目与番茄小说及其运营方无隶属、合作或授权关系，相关名称与标识归各权利人所有。" +
                    "番茄页仅访问公开接口与网页，不绕过登录、付费、验证码及其他访问限制。公开可访问不等于已获得转载或再传播授权，请优先支持正版平台。" +
                    "公开协议研究参考 Tomato-Novel-Downloader（MIT），保留来源与致谢；本软件不承诺目录中每章均可下载。"
                ),
            "责任与退出" to (
                "请根据您的用途自行判断来源和使用权限。若不同意，可返回书库继续使用本地阅读、导入与导出功能。"
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
