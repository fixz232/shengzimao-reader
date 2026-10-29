package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.Highlight
import com.shengzimao.reader.mobile.data.HighlightColors
import com.shengzimao.reader.mobile.data.ReaderSettings

/**
 * 选中文本后的操作条：复制 / 划线 / 笔记 / 简译（占位） / 取消。
 * 用悬浮在底部的小条而不是系统 ActionMode，避免打断自绘正文的手势。
 */
@Composable
fun SelectionBar(
    theme: ReaderSettings,
    length: Int,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit,
    onClear: () -> Unit,
) {
    val background = Color(theme.pageBackground)
    val content = Color(theme.pageForeground)
    Surface(
        color = background,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(content.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text("已选 $length 字", fontSize = 11.sp, color = content.copy(alpha = 0.7f)) }
            Spacer(Modifier.size(4.dp))
            BarAction("复制", content, onCopy)
            BarAction("划线", content, onHighlight)
            BarAction("笔记", content, onNote)
            BarAction("取消", content, onClear)
        }
    }
}

@Composable
private fun BarAction(label: String, content: Color, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** 新建/编辑笔记时的弹窗。 */
@Composable
fun NoteDialog(
    initial: String,
    quote: String,
    onDismiss: () -> Unit,
    initialTags: List<String> = emptyList(),
    onSave: (String, List<String>) -> Unit,
) {
    var note by remember { mutableStateOf(initial) }
    var tags by remember { mutableStateOf(initialTags.joinToString(" ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isBlank()) "写下想法" else "编辑笔记") },
        text = {
            Column {
                Text(
                    quote.take(120),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2000) },
                    label = { Text("笔记") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(tags, { tags = it.take(200) }, label = { Text("标签 · 空格分隔") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note, tags.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct().take(20)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 摘录页：本书所有划线/笔记，按位置排序；点击跳转、可编辑笔记、可删除、可导出分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcerptSheet(
    book: Book,
    text: String,
    onDismiss: () -> Unit,
    onSelect: (Highlight) -> Unit,
    onEditNote: (Highlight) -> Unit,
    onDelete: (Highlight) -> Unit,
    onShareAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) } // 0=全部 1=仅笔记
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("摘录与笔记", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (book.highlights.isNotEmpty()) {
                    OutlinedButton(onClick = onShareAll) { Text("导出", fontSize = 12.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    label = { Text("全部 ${book.highlights.size}", fontSize = 12.sp) },
                )
                androidx.compose.material3.FilterChip(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    label = { Text("笔记 ${book.highlights.count { it.isNote }}", fontSize = 12.sp) },
                )
            }
            OutlinedTextField(filter, { filter = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("搜索摘录、笔记或标签") }, singleLine = true)
            val items = remember(book.highlights, mode, filter) {
                val base = if (mode == 1) book.highlights.filter { it.isNote } else book.highlights
                base.filter { filter.isBlank() || (it.snippet + " " + it.note + " " + it.tags.joinToString(" ")).contains(filter, true) }.sortedBy { it.start }
            }
            if (items.isEmpty()) {
                Text(
                    if (mode == 1) "还没有笔记。选中文字后点「笔记」即可添加。" else "还没有摘录。长按正文选字，点「划线」即可保存。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 28.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    items(items, key = { it.id }) { mark ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(mark) }
                                .padding(vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(HighlightColors.palette[mark.colorIndex.coerceIn(0, HighlightColors.palette.lastIndex)])),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    mark.chapterTitle.ifBlank { book.chapterTitleAt(mark.start) },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { onEditNote(mark) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.Edit, contentDescription = "写笔记", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { onDelete(mark) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                mark.snippet.ifBlank { com.shengzimao.reader.mobile.data.Bookmark.snippet(text, mark.start, mark.length.coerceAtLeast(40)) },
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                            )
                            if (mark.tags.isNotEmpty()) Text(mark.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall)
                            if (mark.isNote) {
                                Text(
                                    "笔记：${mark.note}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 朗读悬浮条：正文页顶部显示，不挡住翻页热区（高度很小，且可点关闭）。
 * 进度条显示“第 n 段 / 共 m 段”。
 */
@Composable
fun TtsBar(
    settings: ReaderSettings,
    speaking: Boolean,
    paused: Boolean,
    segment: Int,
    total: Int,
    error: String?,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val background = Color(settings.pageBackground).copy(alpha = 0.96f)
    val content = Color(settings.pageForeground)
    Surface(color = background, shadowElevation = 6.dp, shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePause, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) "继续朗读" else "暂停朗读",
                        tint = content,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    when {
                        error != null -> "朗读出错"
                        paused -> "已暂停"
                        else -> "朗读中"
                    },
                    fontSize = 12.sp,
                    color = if (error != null) MaterialTheme.colorScheme.error else content,
                )
                if (total > 0 && segment >= 0) {
                    Text(
                        " · ${segment + 1}/$total 段",
                        fontSize = 11.sp,
                        color = content.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "朗读设置",
                        tint = content.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止朗读", tint = content, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
