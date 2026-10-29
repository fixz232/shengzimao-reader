package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MangaUpdatesSheet(controller: MangaController, navigator: Navigator, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("有更新") }
    val books = when (filter) {
        "更新失败" -> state.books.filter { it.updateError.isNotBlank() }.sortedByDescending { it.lastUpdateCheck }
        "最近阅读" -> state.books.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt }
        else -> state.books.filter { it.newChapters > 0 }.sortedByDescending { it.updatedAt }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("漫画更新中心", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.checkTrackedUpdates() },
                    enabled = state.updatingIds.isEmpty()) { Text("检查追更") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("有更新", "更新失败", "最近阅读").forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(value) }) }
            }
            if (state.updatingIds.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (books.isEmpty()) Text(when(filter) { "更新失败" -> "没有更新失败记录"; "最近阅读" -> "读过的漫画会出现在这里"; else -> "暂无未读更新，可先在漫库为作品开启追更" }, Modifier.padding(vertical = 24.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(books, key = { it.id }) { book ->
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                        Text("${book.sourceName} · ${book.chapters.size} 章 · ${book.newChapters} 章更新未读", style = MaterialTheme.typography.bodySmall)
                        if (book.updateError.isNotBlank()) Text(book.updateError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        FlowRow {
                            TextButton(onClick = { onDismiss(); navigator.openComic(book.id) }) { Text("继续阅读") }
                            if (book.newChapters > 0) TextButton(onClick = { onDismiss(); navigator.openComic(book.id, controller.firstUnread(book)) }) { Text("读第一章未读") }
                            if (!book.local) TextButton(onClick = { controller.checkUpdates(book.id) }, enabled = book.id !in state.updatingIds) { Text("${if (book.updateError.isBlank()) "检查更新" else "重试更新"}") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
