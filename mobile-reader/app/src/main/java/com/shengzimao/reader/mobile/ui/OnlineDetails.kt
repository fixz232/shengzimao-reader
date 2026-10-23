package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shengzimao.reader.mobile.data.OnlineUiState
import com.shengzimao.reader.mobile.data.TomatoParser
import androidx.compose.ui.platform.LocalUriHandler

/** 书籍详情与在线目录：用于确认章节数量、最新章与简介，再决定下载。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineDetailsSheet(
    state: OnlineUiState,
    format: String,
    onDismiss: () -> Unit,
    onDownload: (Int) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val book = state.details ?: return
    val toc = state.detailsToc
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(book.bookName.ifBlank { "书籍详情" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val facts = remember(book, toc) {
                listOf(
                    "作者" to book.author,
                    "分类" to book.category,
                    "状态" to book.status,
                    "字数" to book.wordCount,
                    "最新章节" to book.latestChapter,
                    "更新时间" to book.lastUpdateTime,
                    "书源" to book.sourceName,
                    "目录" to "${toc.size} 章",
                )
            }
            facts.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    Text(value.ifBlank { "源站未提供" }, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (book.intro.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(book.intro, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            if (TomatoParser.allowedUrl(book.url)) {
                TextButton(onClick = { uriHandler.openUri(book.url) }, modifier = Modifier.fillMaxWidth()) { Text("在番茄官网阅读") }
            }
            Button(
                onClick = { onDownload(state.detailsIndex) },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("下载并入库（${format.uppercase()}）") }
            Spacer(Modifier.height(8.dp))
            Text("在线目录", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                itemsIndexed(toc.take(300)) { index, chapter ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(40.dp))
                        Text(chapter.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (index != toc.take(300).lastIndex) HorizontalDivider()
                }
            }
            if (toc.size > 300) {
                TextButton(onClick = onDismiss) { Text("仅展示前 300 章，下载会获取全部 ${toc.size} 章") }
            }
            Spacer(Modifier.height(12.dp))
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
