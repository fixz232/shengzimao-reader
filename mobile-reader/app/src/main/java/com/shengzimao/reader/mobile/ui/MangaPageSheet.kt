package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.MangaBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaPageSheet(book: MangaBook, pages: List<String>, currentPage: Int, fraction: Float, controller: MangaController,
    onAdjust: () -> Unit = {}, onDismiss: () -> Unit, onSelect: (Int, Float) -> Unit, onChapter: (Int, Int, Float) -> Unit) {
    var bookmarks by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text("页面导航", style = MaterialTheme.typography.titleLarge)
            Row {
                FilterChip(!bookmarks, { bookmarks = false }, label = { Text("本章缩略图") })
                Spacer(Modifier.width(8.dp))
                FilterChip(bookmarks, { bookmarks = true }, label = { Text("页面书签 ${book.bookmarks.size}") })
            }
            TextButton(onClick = {
                controller.togglePageBookmark(book.id, book.chapterIndex, currentPage, fraction, pages.getOrNull(currentPage).orEmpty())
            }) { Text("添加 / 移除当前页书签") }
            TextButton(onClick = onAdjust) { Text("调整当前页 · 原图对照 / 跨页预览") }
            if (bookmarks && book.bookmarks.isEmpty()) Text("在喜欢的页面添加书签，下次直接回到该位置。", Modifier.padding(vertical = 16.dp))
            LazyVerticalGrid(GridCells.Adaptive(96.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (bookmarks) items(book.bookmarks.size) { index ->
                    val mark = book.bookmarks[index]
                    val chapter = book.chapters.indexOfFirst { it.url == mark.chapterUrl }
                    Column(Modifier.clickable(enabled = chapter >= 0) { onChapter(chapter, mark.page, mark.fraction) }) {
                        MangaCover(controller.engine, mark.reference, book.sourceId, mark.label, Modifier.fillMaxWidth().aspectRatio(0.7f))
                        Text(mark.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                } else items(pages.size, key = { it }) { page ->
                    Column(Modifier.clickable { onSelect(page, 0f) }) {
                        MangaCover(controller.engine, pages[page], book.sourceId, "第 ${page + 1} 页", Modifier.fillMaxWidth().aspectRatio(0.7f))
                        Text("${if (page == currentPage) "正在阅读 · " else ""}第 ${page + 1} 页", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
