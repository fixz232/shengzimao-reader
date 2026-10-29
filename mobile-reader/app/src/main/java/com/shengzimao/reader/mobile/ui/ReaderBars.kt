package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.Bookmark
import com.shengzimao.reader.mobile.data.Chapter
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReaderTheme
import com.shengzimao.reader.mobile.ui.theme.readableAccent

/**
 * 阅读页顶栏：返回、书名/章节、更多；工具放在菜单中，给标题和触控留出空间。
 */
@Composable
fun ReaderTopBar(
    title: String,
    chapter: String,
    settings: ReaderSettings,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCatalog: () -> Unit,
    onBookmarks: () -> Unit,
    onExcerpts: () -> Unit,
    onSettings: () -> Unit,
    searchLabel: String = "全文搜索",
) {
    val background = Color(settings.pageBackground)
    val content = readableAccent(Color(settings.pageForeground), background)
    val secondary = readableAccent(lerp(content, background, .25f), background)
    // 悬浮玻璃面板：不贴屏幕边缘，圆角 + 亮描边 + 轻投影。
    // 底色保留较高的不透明度，避免正文透过工具栏影响可读性。
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Surface(
            color = background,
            contentColor = content,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回", content, onBack, size = 44)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        title,
                        color = content,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chapter.isNotBlank()) {
                        Text(
                            chapter,
                            color = secondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 目录是阅读中最高频的操作，直接给一级入口；其余收进「更多」。
                TintIcon(Icons.AutoMirrored.Filled.ListAlt, "章节目录", content, onCatalog)
                var more by remember { mutableStateOf(false) }
                Box {
                    TintIcon(Icons.Filled.MoreVert, "更多阅读工具", content, { more = true })
                    DropdownMenu(more, { more = false }) {
                        listOf(searchLabel to onSearch, "书签" to onBookmarks,
                            "摘录与笔记" to onExcerpts, "阅读设置" to onSettings).forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { more = false; action() })
                        }
                    }
                }
            }
        }
    }
}

/** Compact icon with a minimum 48 dp touch target. */
@Composable
fun TintIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    size: Int = 40,
    iconSize: Int = 21,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(maxOf(size, 48).dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/**
 * 阅读页底部栏：进度滑杆（带章节气泡与成对翻页按钮）+ 功能行（上/下章、朗读、自动翻页开关）。
 * 字号/主题等高频项在阅读设置面板里，这里只留“读的时候会按”的键。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderBottomBar(
    settings: ReaderSettings,
    book: Book?,
    pageIndex: Int,
    pageCount: Int,
    chapterTitle: String,
    percentText: String,
    speaking: Boolean,
    paused: Boolean,
    ttsError: String?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleSpeak: () -> Unit,
    onStopSpeak: () -> Unit,
    onSettings: () -> Unit,
    pageOffset: (Int) -> Int = { 0 },
    onCatalog: (() -> Unit)? = null,
) {
    val background = Color(settings.pageBackground)
    val content = readableAccent(Color(settings.pageForeground), background)
    val secondary = readableAccent(lerp(content, background, .25f), background)
    val accent = readableAccent(MaterialTheme.colorScheme.primary, background)
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember(pageIndex, pageCount) {
        mutableStateOf(if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1))
    }
    if (!dragging) {
        val target = if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1)
        if (sliderValue != target) sliderValue = target
    }
    // 拖动时显示要跳到的章节名，松手才真正跳转。
    val previewTitle = remember(sliderValue, pageCount, book) {
        val target = (sliderValue * (pageCount - 1)).toInt().coerceIn(0, maxOf(0, pageCount - 1))
        book?.chapterTitleAt(pageOffset(target)).orEmpty()
    }
    Surface(color = background, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TintIcon(Icons.AutoMirrored.Filled.ArrowBack, "上一页", content, { onSeek(step(sliderValue, -1, pageCount)) }, size = 36, iconSize = 18)
                Column(modifier = Modifier.weight(1f)) {
                    if (dragging) {
                        Text(
                            "跳到：$previewTitle",
                            fontSize = 11.sp,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            dragging = true
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            onSeek(sliderValue)
                        },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                }
                TintIcon(Icons.AutoMirrored.Filled.ArrowForward, "下一页", content, { onSeek(step(sliderValue, 1, pageCount)) }, size = 36, iconSize = 18)
            }
            Text(
                if (dragging) "${(sliderValue * 100).toInt()}% · $previewTitle" else "$percentText · $chapterTitle",
                fontSize = 11.sp,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            ReaderActionBar(content, onPrevChapter, onCatalog ?: {}, onToggleSpeak,
                if (speaking && !paused) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                "听书", onSettings, onNextChapter)
            if (speaking) TextButton(onClick = onStopSpeak) { Text("停止听书", color = content) }
            if (ttsError != null) {
                Text(
                    ttsError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun step(value: Float, delta: Int, pageCount: Int): Float {
    if (pageCount <= 1) return 0f
    val index = (value * (pageCount - 1)).toInt() + delta
    return (index.coerceIn(0, pageCount - 1).toFloat() / (pageCount - 1))
}

/** 目录抽屉：支持章节名/序号搜索，打开时把当前章节滚到可见处（上方留两行）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSheet(
    chapters: List<Chapter>,
    currentPosition: Int,
    theme: ReaderSettings,
    onDismiss: () -> Unit,
    onSelect: (Chapter) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var reverse by remember { mutableStateOf(false) }
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = remember(chapters, query, reverse) {
        val base = if (tokens.isEmpty()) chapters.withIndex().toList()
        else chapters.withIndex().filter { (index, chapter) ->
            val key = (chapter.title + " " + (index + 1)).lowercase()
            tokens.all { key.contains(it) }
        }
        if (reverse) base.reversed() else base
    }
    val currentIndex = remember(chapters, currentPosition) {
        var found = -1
        chapters.forEachIndexed { index, chapter -> if (chapter.position <= currentPosition) found = index }
        found
    }
    val listState = rememberLazyListState()
    // 打开时定位到当前章：放在上方第 3 行，前面两行作为上下文。
    LaunchedEffect(currentIndex, reverse) {
        val target = filtered.indexOfFirst { it.index == currentIndex }
        if (target >= 0) listState.scrollToItem((target - 2).coerceAtLeast(0))
    }
    AppModalSheet(onDismissRequest = onDismiss, title = "目录", actions = {
        TextButton(onClick = { reverse = !reverse }) { Text(if (reverse) "倒序" else "正序", fontSize = 12.sp) }
    }) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索章节名或序号") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(18.dp)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (tokens.isEmpty()) {
                    if (currentIndex >= 0) "共 ${chapters.size} 章 · 当前第 ${currentIndex + 1} 章" else "共 ${chapters.size} 章"
                } else {
                    "匹配 ${filtered.size} / ${chapters.size} 章"
                },
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                ) {
                    itemsIndexed(filtered, key = { _, item -> item.index }) { _, item ->
                        val selected = item.index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onSelect(item.value) }
                                .padding(vertical = 2.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${item.index + 1}",
                                fontSize = 11.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.width(38.dp).padding(end = 8.dp),
                            )
                            Text(
                                item.value.title,
                                fontSize = 14.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 书签抽屉：显示章节名与进度百分比，支持滑动删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    book: Book?,
    text: String,
    onDismiss: () -> Unit,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onAdd: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bookmarks = book?.bookmarks.orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.ime },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("书签", style = MaterialTheme.typography.titleMedium)
                Text("（${bookmarks.size}）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(bookmarks, key = { _, item -> item.id }) { index, mark ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSelect(mark) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    mark.label.ifBlank { book?.chapterTitleAt(mark.position).orEmpty().ifBlank { "书签" } },
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${book?.chapterTitleAt(mark.position).orEmpty()} · " +
                                        "${percentOf(mark.position, book)}% · ${formatTime(mark.createdAt)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val snippet = Bookmark.snippet(text, mark.position)
                                if (snippet.isNotBlank()) {
                                    Text(
                                        snippet,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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

private fun percentOf(position: Int, book: Book?): Int {
    val length = book?.length ?: 0
    if (length <= 1) return 0
    return ((position.toFloat() / (length - 1)) * 100).toInt().coerceIn(0, 100)
}

private fun formatTime(value: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(value))
