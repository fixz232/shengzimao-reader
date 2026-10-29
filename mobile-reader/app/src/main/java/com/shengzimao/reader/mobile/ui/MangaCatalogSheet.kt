package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.MangaBook
import com.shengzimao.reader.mobile.data.mangaCatalogEntries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaCatalogSheet(book: MangaBook, chapterIndex: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit,
    onUpdate: () -> Unit, updating: Boolean, onTracking: () -> Unit) {
    var query by rememberSaveable(book.id) { mutableStateOf("") }
    var reversed by rememberSaveable(book.id) { mutableStateOf(false) }
    var cachedOnly by rememberSaveable(book.id) { mutableStateOf(false) }
    var jump by rememberSaveable(book.id) { mutableStateOf("") }
    var locate by remember { mutableIntStateOf(0) }
    val chapters = remember(book.chapters, book.cachedChapters, query, reversed, cachedOnly) { mangaCatalogEntries(book, query, reversed, cachedOnly) }
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val height = modalPageHeight(.88f)
    val number = jump.toIntOrNull()?.takeIf { it in 1..book.chapters.size }
    fun openNumber() { number?.let { keyboard?.hide(); onSelect(it - 1) } }
    LaunchedEffect(book.id, reversed, cachedOnly, locate) {
        val current = chapters.indexOfFirst { it.index == chapterIndex }
        if (current >= 0) list.scrollToItem(current)
    }
    AppModalSheet(onDismissRequest = onDismiss, title = "目录 · ${book.chapters.size} 章", actions = {
        TextButton(onClick = { query = ""; cachedOnly = false; locate++ }) { Text("定位当前章") }
    }) {
        Column(Modifier.fillMaxWidth().height(height).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                if (!book.local) FlowRow {
                    TextButton(onClick = onUpdate, enabled = !updating) { Text(if (updating) "更新目录中…" else "更新目录") }
                    TextButton(onClick = onTracking) { Text(if (book.tracking) "取消追更" else "加入追更") }
                    if (book.newChapters > 0) TextButton(onClick = { onSelect((book.chapters.size-book.newChapters).coerceAtLeast(0)) }) { Text("阅读新增 ${book.newChapters} 章") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = reversed, onClick = { reversed = !reversed }, label = { Text(if (reversed) "倒序" else "正序") })
                    FilterChip(selected = cachedOnly, onClick = { cachedOnly = !cachedOnly }, label = { Text("仅已缓存") })
                    Text("${chapters.size} / ${book.chapters.size}", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索章节名称") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "清空章节筛选") } }) else null)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(jump, { jump = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), singleLine = true,
                        label = { Text("跳转章节序号（1–${book.chapters.size}）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { openNumber() }))
                    TextButton(onClick = { openNumber() }, enabled = number != null) { Text("跳转") }
                }
                Text("当前：${book.chapters.getOrNull(chapterIndex)?.title.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            if (chapters.isEmpty()) Text("没有符合筛选的章节，可清空搜索或关闭「仅已缓存」。", Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                items(chapters, key = { "${it.index}:${it.value.url}" }) { (index, chapter) ->
                    val current = index == chapterIndex
                    Row(Modifier.fillMaxWidth().clickable { onSelect(index) }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index+1}", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
                        Text(chapter.title, Modifier.weight(1f), maxLines = 2, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                        if (current) Icon(Icons.Filled.Check, "当前章节", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        if (book.local || chapter.url in book.cachedChapters) Icon(Icons.Filled.OfflinePin, "已缓存", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
