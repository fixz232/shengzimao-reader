package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.MangaBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaSourceSwitchSheet(controller: MangaController, book: MangaBook, pageCount: Int,
    onDismiss: () -> Unit, onChanged: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var candidate by remember { mutableStateOf<MangaBook?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!state.switching) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).navigationBarsPadding().padding(16.dp)) {
            Text("切换漫画源", style = MaterialTheme.typography.titleLarge)
            Text("当前：${book.sourceName} · ${book.chapters.getOrNull(book.chapterIndex)?.title.orEmpty()}")
            Text("选择后先验证目录和图片；成功后保留漫库条目，尝试按话数与页面比例恢复。旧源的页面书签不会自动映射。", style = MaterialTheme.typography.bodySmall)
            if (state.switchSearching || state.switching) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { controller.searchAlternatives(book.id) }, enabled = !state.switchSearching && !state.switching) { Text("重新查找") }
                if (state.switchSearching) TextButton(onClick = { controller.closeAlternatives() }) { Text("停止搜索，保留结果") }
            }
            if (state.switching) Text("正在验证新源目录和图片地址，请稍候…")
            if (state.sourceError.isNotBlank()) Text(state.sourceError, color = MaterialTheme.colorScheme.error)
            if (!state.switchSearching && state.switchResults.isEmpty()) Text("暂未找到其他源的对应作品，可检查已启用的漫画源或稍后重试。")
            LazyColumn(Modifier.weight(1f)) { items(state.switchResults, key = { it.id }) { result ->
                TextButton(onClick = { candidate = result }, enabled = !state.switching, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text(result.title); Text("${result.author.ifBlank { "作者未知" }} · ${result.sourceName}", style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
    }
    candidate?.let { target -> AlertDialog(onDismissRequest = { candidate = null }, title = { Text("切换到 ${target.sourceName}？") },
        text = { Text("请核对「${target.title}」及作者。不同源的分话和图片可能不同，无法准确匹配时会按比例定位，切换失败保留原源。") },
        confirmButton = { TextButton(onClick = { candidate = null; controller.closeAlternatives(); controller.switchSource(book.id, target, pageCount, onChanged) }) { Text("验证并切换") } },
        dismissButton = { TextButton(onClick = { candidate = null }) { Text("取消") } }) }
}
