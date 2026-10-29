package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.NovelSourceEntry
import com.shengzimao.reader.mobile.data.OnlineUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun NovelSearchScope(
    state: OnlineUiState,
    onSelect: (Int) -> Unit,
    onImport: () -> Unit,
    onManage: () -> Unit,
    onScope: (Set<String>?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val available = remember(state.sourceEntries) { state.sourceEntries.filter { it.searchable } }
    var pending by remember(state.sourceGroup, state.selectedSourceUrls, available) {
        mutableStateOf(state.selectedSourceUrls?.intersect(available.map { it.url }.toSet()) ?: available.map { it.url }.toSet())
    }
    var detail by remember { mutableStateOf<NovelSourceEntry?>(null) }
    val enabled = state.sourcesReady && !state.running && !state.importingSources
    val count = state.selectedSourceUrls?.let { selected -> available.count { it.url in selected } } ?: available.size
    val selectedName = if (state.selectedSourceUrls?.size == 1) available.firstOrNull { it.url in state.selectedSourceUrls.orEmpty() }?.name else null
    SearchScopeButton(summary = "分组 ${state.sourceGroup} · ${selectedName ?: if (state.selectedSourceUrls == null) "全部 $count 源" else "已选 $count 源"}", enabled = enabled, onClick = {
        pending = state.selectedSourceUrls?.intersect(available.map { it.url }.toSet()) ?: available.map { it.url }.toSet()
        open = true; query = ""
    })
    if (open) AppModalSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().height(modalPageHeight(.88f)).navigationBarsPadding().padding(horizontal = 16.dp)) {
            PanelHeader("搜索范围", { open = false }, "分组 ${state.sourceGroup} · 可搜索 ${available.size} · 待使用 ${pending.size}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { group -> FilterChip(state.sourceGroup == group, {
                    onSelect(group)
                }, enabled = enabled, label = { Text("分组 $group") }) }
            }
            Text(if (state.sourceGroup == 1) "内置书源。选择只改变搜索范围，不修改内置规则。" else "自定义分组。长按或点详情图标可查看书源。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, label = { Text("搜索书源名称或网址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { onScope(null); open = false }, enabled = enabled && available.isNotEmpty()) { Text("全部书源") }
                TextButton(onClick = { pending = available.map { it.url }.toSet() }) { Text("全选") }
                TextButton(onClick = { pending = emptySet() }) { Text("清空") }
            }
            if (!state.sourcesReady) LinearProgressIndicator(Modifier.fillMaxWidth())
            val shown = remember(available, query) { available.filter { query.isBlank() || it.name.contains(query, true) || it.url.contains(query, true) } }
            LazyColumn(Modifier.weight(1f)) {
                if (shown.isEmpty()) item { Text("没有符合条件的可搜索书源。", Modifier.padding(vertical = 20.dp)) }
                items(shown, key = { it.url }) { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(
                        role = Role.Checkbox,
                        onLongClickLabel = "查看${entry.name}详情",
                        onClick = { pending = if (entry.url in pending) pending - entry.url else pending + entry.url },
                        onLongClick = { detail = entry },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(entry.url in pending, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("可搜索 · ${entry.format}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onScope(setOf(entry.url)); open = false }, enabled = enabled) { Text("仅用此源") }
                        IconButton(onClick = { detail = entry }) { Icon(Icons.Default.Info, "查看${entry.name}详情") }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { open = false; onManage() }) { Text("完整书源管理") }
                if (state.sourceGroup in 2..5) TextButton(onClick = { open = false; onImport() }) { Text("导入到此组") }
            }
            Button(onClick = {
                onScope(if (pending.size == available.size) null else pending)
                open = false
            }, enabled = enabled && pending.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("使用所选 ${pending.size} 个书源") }
        }
    }
    detail?.let { entry -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(entry.name) },
        text = { Text("${entry.url}\n\n${entry.format} · 分组 ${state.sourceGroup}\n此处为规则信息，实际目录和正文需联网读取后确认。") },
        confirmButton = { TextButton(onClick = { detail = null }) { Text("完成") } }) }
}
