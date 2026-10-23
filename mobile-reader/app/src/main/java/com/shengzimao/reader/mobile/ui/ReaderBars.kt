package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.Bookmark
import com.shengzimao.reader.mobile.data.Chapter
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReaderTheme

/** 阅读页顶部栏：返回、书名/章节、目录、书签、设置。 */
@Composable
fun ReaderTopBar(
    title: String,
    chapter: String,
    theme: ReaderTheme,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = Color(theme.background), tonalElevation = 0.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书库", tint = Color(theme.onBackground))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color(theme.onBackground), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chapter, color = Color(theme.onBackground).copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCatalog) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "目录", tint = Color(theme.onBackground))
            }
            IconButton(onClick = onBookmarks) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "书签", tint = Color(theme.onBackground))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Tune, contentDescription = "阅读设置", tint = Color(theme.onBackground))
            }
        }
    }
}

/** 阅读页底部栏：进度滑杆、上/下章、字号、主题底色。 */
@Composable
fun ReaderBottomBar(
    settings: ReaderSettings,
    pageIndex: Int,
    pageCount: Int,
    theme: ReaderTheme,
    onFontSize: (Int) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onSettings: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    speaking: Boolean = false,
    ttsError: String? = null,
    onToggleSpeak: () -> Unit = {},
) {
    val content = Color(theme.onBackground)
    var sliderValue by remember(pageIndex, pageCount) {
        mutableStateOf(if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1))
    }
    Surface(color = Color(theme.background), tonalElevation = 0.dp, shadowElevation = 12.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevChapter) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
                    Text("上章", color = content, fontSize = 13.sp, modifier = Modifier.padding(start = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggleSpeak) {
                    Icon(
                        if (speaking) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
                        contentDescription = if (speaking) "停止朗读" else "朗读本页",
                        tint = if (speaking) MaterialTheme.colorScheme.primary else content,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (speaking) "停止" else "朗读",
                        color = if (speaking) MaterialTheme.colorScheme.primary else content,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${pageIndex + 1} / $pageCount", color = content.copy(alpha = 0.72f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNextChapter) {
                    Text("下章", color = content, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSeek(sliderValue) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ttsError != null) {
                Text(ttsError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", color = content.copy(alpha = 0.7f), fontSize = 12.sp)
                IconButton(onClick = { onFontSize((settings.fontSize - 1).coerceAtLeast(12)) }) {
                    Text("A-", color = content, fontSize = 13.sp)
                }
                Text(settings.fontSize.toString(), color = content, fontSize = 13.sp)
                IconButton(onClick = { onFontSize((settings.fontSize + 1).coerceAtMost(40)) }) {
                    Text("A+", color = content, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                ReaderTheme.entries.forEach { item ->
                    val selected = settings.theme == item
                    val ring = if (selected) MaterialTheme.colorScheme.primary else Color(item.onBackground).copy(alpha = 0.6f)
                    IconButton(onClick = { onTheme(item) }, modifier = Modifier.size(34.dp)) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            drawCircle(color = Color(item.background))
                            drawCircle(color = ring, style = Stroke(width = if (selected) 5f else 2f))
                        }
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Tune, contentDescription = "全部设置", tint = content, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** 目录抽屉：支持章节名/序号搜索，打开时定位到当前章节。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSheet(
    chapters: List<Chapter>,
    currentPosition: Int,
    theme: ReaderTheme,
    onDismiss: () -> Unit,
    onSelect: (Chapter) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = remember(chapters, query) {
        if (tokens.isEmpty()) chapters.withIndex().toList()
        else chapters.withIndex().filter { (index, chapter) ->
            val key = (chapter.title + " " + (index + 1)).lowercase()
            tokens.all { key.contains(it) }
        }
    }
    val currentIndex = remember(chapters, currentPosition) {
        var found = -1
        chapters.forEachIndexed { index, chapter -> if (chapter.position <= currentPosition) found = index }
        found
    }
    val listState = rememberLazyListState()
    LaunchedEffect(filtered, currentIndex) {
        val target = filtered.indexOfFirst { it.index == currentIndex }
        if (target > 0) listState.scrollToItem(target)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("目录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索章节名或序号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (tokens.isEmpty()) "共 ${chapters.size} 章" else "匹配 ${filtered.size} / ${chapters.size} 章",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (filtered.isEmpty()) {
                Text(
                    if (chapters.isEmpty()) "这本书没有识别出章节，可以按页码阅读。" else "没有匹配的章节。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    itemsIndexed(filtered, key = { _, item -> item.index }) { _, item ->
                        val selected = item.index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(item.value) }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${item.index + 1}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(44.dp),
                            )
                            Text(
                                item.value.title,
                                fontSize = 14.sp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .padding(vertical = 2.dp),
                            )
                        }
                        if (selected) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 书签抽屉：跳转、删除、添加当前页书签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    bookmarks: List<Bookmark>,
    theme: ReaderTheme,
    onDismiss: () -> Unit,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onAdd: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("书签", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAdd) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("添加当前页", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (bookmarks.isEmpty()) {
                Text(
                    "还没有书签。阅读时长按屏幕，或点右上角「添加当前页」。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                    itemsIndexed(bookmarks, key = { _, item -> item.id }) { index, mark ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSelect(mark) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    mark.label.ifBlank { "书签" },
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .padding(vertical = 2.dp),
                                )
                                Text(
                                    "位置 ${mark.position} · " + formatTime(mark.createdAt),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDelete(mark) }) { Text("删除", fontSize = 12.sp) }
                        }
                        if (index != bookmarks.lastIndex) HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun formatTime(value: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(value))
