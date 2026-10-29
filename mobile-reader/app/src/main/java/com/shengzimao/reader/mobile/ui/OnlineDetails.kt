package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalUriHandler
import com.shengzimao.reader.mobile.data.OnlineChapter
import com.shengzimao.reader.mobile.data.OnlineUiState
import com.shengzimao.reader.mobile.data.TomatoParser

/** 章节选择的下载范围。 */
sealed interface ChapterRange {
    /** 全部章节。 */
    data object All : ChapterRange
    /** 按序号勾选的章节（0 基索引）。 */
    data class Picked(val indexes: Set<Int>) : ChapterRange
    /** 闭区间，1 基、包含两端。 */
    data class Interval(val from: Int, val to: Int) : ChapterRange
}

/** 把选择范围解析成最终的章节列表，保持目录顺序。 */
fun ChapterRange.resolve(toc: List<OnlineChapter>): List<OnlineChapter> = when (this) {
    is ChapterRange.All -> toc
    is ChapterRange.Picked -> indexes.sorted().mapNotNull { toc.getOrNull(it) }
    is ChapterRange.Interval -> {
        // 目录为空时必须直接返回空表：coerceIn 会把区间压成 1..1，
        // 再对空列表取 subList(0, 1) 会抛 IndexOutOfBounds 并导致闪退。
        if (toc.isEmpty()) emptyList()
        else {
            val start = from.coerceIn(1, toc.size)
            val end = to.coerceIn(start, toc.size)
            toc.subList(start - 1, end)
        }
    }
}

/** 供界面显示的选择摘要。 */
fun ChapterRange.summary(toc: List<OnlineChapter>): String = when (this) {
    is ChapterRange.All -> "全部 ${toc.size} 章"
    is ChapterRange.Picked -> if (indexes.isEmpty()) "未选择章节" else "已选 ${indexes.size} 章"
    is ChapterRange.Interval -> "第 $from–$to 章（共 ${(to - from + 1).coerceAtLeast(0)} 章）"
}

/** The catalog owns the available height; optional metadata and download forms stay out of its way. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineDetailsSheet(
    state: OnlineUiState,
    format: String,
    onDismiss: () -> Unit,
    onDownload: (Int) -> Unit,
    onDownloadChapters: (List<OnlineChapter>) -> Unit = {},
    onRead: () -> Unit = {},
    onReadChapter: (Int) -> Unit = {},
    onRefresh: () -> Unit = {},
    onShelf: () -> Unit = {},
) {
    val book = state.details ?: return
    val toc = state.detailsToc
    val uriHandler = LocalUriHandler.current
    var downloading by rememberSaveable(book.url) { mutableStateOf(false) }
    var info by rememberSaveable(book.url) { mutableStateOf(false) }
    var mode by rememberSaveable(book.url) { mutableIntStateOf(0) }
    var picked by remember(book.url) { mutableStateOf(setOf<Int>()) }
    var fromText by rememberSaveable(book.url) { mutableStateOf("1") }
    var toText by rememberSaveable(book.url) { mutableStateOf("") }
    var query by rememberSaveable(book.url) { mutableStateOf("") }
    var reverse by rememberSaveable(book.url) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val detailsHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .9f }
    val range: ChapterRange = when (mode) {
        1 -> ChapterRange.Interval(fromText.toIntOrNull() ?: 1, toText.toIntOrNull() ?: toc.size)
        2 -> ChapterRange.Picked(picked)
        else -> ChapterRange.All
    }
    val resolved = range.resolve(toc)
    val validRange = mode != 1 || ((fromText.toIntOrNull() ?: 1) in 1..toc.size &&
        (toText.toIntOrNull() ?: toc.size) in (fromText.toIntOrNull() ?: 1)..toc.size)
    val filtered = remember(toc, query, reverse) {
        toc.withIndex().filter { (index, chapter) ->
            query.isBlank() || chapter.title.contains(query, true) || (index + 1).toString() == query.trim()
        }.let { if (reverse) it.reversed() else it }
    }
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text(book.bookName) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("作者" to book.author, "状态" to book.status, "字数" to book.wordCount,
                "最新章节" to book.latestChapter, "书源" to book.sourceName).forEach { (key, value) ->
                if (value.isNotBlank()) Text("$key：$value")
            }
            Text(book.intro.ifBlank { "源站未提供简介。" })
            if (TomatoParser.allowedUrl(book.url)) TextButton(onClick = { uriHandler.openUri(book.url) }) { Text("在番茄官网阅读") }
        } }, confirmButton = { TextButton(onClick = { info = false }) { Text("完成") } })

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.ime }) {
        Column(Modifier.fillMaxWidth().height(detailsHeight).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Column(Modifier.heightIn(max = detailsHeight * .55f).verticalScroll(rememberScrollState())) {
                Text(book.bookName.ifBlank { "书籍详情" }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${book.author.ifBlank { "作者未提供" }} · ${book.sourceName} · ${toc.size} 章",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { info = true }) { Text("简介与书籍信息") }
                    TextButton(onClick = { downloading = !downloading }) { Text(if (downloading) "返回阅读目录" else "选择下载章节") }
                }
                if (downloading) Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                    Text("下载为 $format，导入书库后可离线阅读。", style = MaterialTheme.typography.bodySmall)
                    SingleChoiceSegmentedRow(mode, listOf("全部", "区间", "勾选")) { mode = it }
                    when (mode) {
                        1 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(fromText, { fromText = it.filter(Char::isDigit).take(6) }, label = { Text("起始章") },
                                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(toText, { toText = it.filter(Char::isDigit).take(6) }, label = { Text("结束章") }, placeholder = { Text("${toc.size}") },
                                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        2 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { picked = toc.indices.toSet() }) { Text("全选") }
                            TextButton(onClick = { picked = emptySet() }) { Text("清空") }
                            TextButton(onClick = { picked = toc.indices.filterNot { it in picked }.toSet() }) { Text("反选") }
                        }
                        else -> Unit
                    }
                    if (!validRange) Text("起止章节应在目录范围内，结束章不能早于起始章。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(query, { query = it.take(60) }, placeholder = { Text("搜索章节名或序号") },
                    singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Text("目录 · ${filtered.size}/${toc.size}", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { reverse = !reverse; scope.launch { listState.scrollToItem(0) } }) { Text(if (reverse) "倒序" else "正序") }
                    TextButton(onClick = { scope.launch { listState.scrollToItem(0) } }) { Text("回到开头") }
                    TextButton(onClick = onRefresh, enabled = !state.running) { Text("刷新") }
                }
            }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                if (filtered.isEmpty()) item { Text(if (toc.isEmpty()) "目录暂不可用，请点击刷新重试。" else "没有匹配章节。", Modifier.padding(vertical = 24.dp)) }
                itemsIndexed(filtered, key = { _, item -> "${item.index}:${item.value.url}" }) { _, item ->
                    val index = item.index
                    val checked = when (mode) {
                        2 -> index in picked
                        1 -> item.value in resolved
                        else -> true
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !state.running) {
                        if (downloading) {
                            val before = if (mode == 2) picked else toc.indices.filter { toc[it] in resolved }.toSet()
                            picked = if (index in before) before - index else before + index
                            mode = 2
                        }
                        else onReadChapter(index)
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (downloading) Checkbox(checked, onCheckedChange = null)
                        Text("${index + 1}", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.value.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
            HorizontalDivider()
            if (downloading) {
                Text(range.summary(toc), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (resolved.size == toc.size) onDownload(state.detailsIndex) else onDownloadChapters(resolved) },
                    enabled = !state.running && validRange && resolved.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Icons.Filled.Download, null); Text(" 下载并导入 ${resolved.size} 章")
                }
            } else FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRead, enabled = toc.isNotEmpty() && !state.running) { Text("在线阅读") }
                OutlinedButton(onClick = onShelf, enabled = toc.isNotEmpty() && !state.running) { Text("加入书架") }
                TextButton(onClick = { downloading = true }, enabled = toc.isNotEmpty()) { Text("下载") }
            }
        }
    }
}

/** 轻量的单选分段控件。 */
@Composable
private fun SingleChoiceSegmentedRow(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(label, fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 追更面板：列出追踪中的书籍与进度。 */
@Composable
fun TrackingSummary(
    tracked: Int,
    newChapters: Int,
    onCheckAll: () -> Unit,
    running: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("追更书架", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                buildString {
                    append("$tracked 本追更中")
                    if (newChapters > 0) append(" · 共新增 $newChapters 章")
                    append(" · 仅追加新增章节")
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCheckAll, enabled = !running && tracked > 0) { Text("全部检查", fontSize = 13.sp) }
    }
}

@Composable
fun SpacerHeight(value: Int) {
    Spacer(Modifier.height(value.dp))
}

@Composable
fun RowScopeWeight() = Arrangement.Start
