package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.MangaSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MangaSearchScope(
    sources: List<MangaSource>, selected: String, savedCount: Int, disabledCount: Int,
    onManage: () -> Unit, onChange: (String) -> Unit, onOpenSource: (String) -> Unit,
    selectedIds: Set<String>, onSelectMany: (Set<String>) -> Unit, readability: Map<String, String> = emptyMap(),
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(emptySet<String>()) }
    val name = when {
        selected == MangaController.ALL_SOURCES -> "全部启用源（${sources.size}）"
        selectedIds.size > 1 -> "已选 ${selectedIds.size} 个源"
        else -> sources.firstOrNull { it.id == selected }?.name ?: "请选择漫画源"
    }
    SearchScopeButton(summary = name, onClick = {
        pending = if (selected == MangaController.ALL_SOURCES) sources.map { it.id }.toSet()
            else selectedIds.ifEmpty { setOfNotNull(selected.takeIf { id -> sources.any { it.id == id } }) }
        query = ""; open = true
    })
    if (open) AppModalSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().height(modalPageHeight(.88f)).navigationBarsPadding().padding(horizontal = 16.dp)) {
            PanelHeader("漫画搜索范围", { open = false }, "已导入 $savedCount · 启用 ${sources.size} · 停用 $disabledCount")
            Text("启用不代表已经验证可读；目录和图片检测结果见源详情。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, label = { Text("搜索漫画源") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pending = sources.map { it.id }.toSet() }) { Text("全选") }
                TextButton(onClick = { pending = emptySet() }) { Text("清空") }
                Spacer(Modifier.weight(1f))
                Text("已选 ${pending.size}", style = MaterialTheme.typography.bodySmall)
            }
            val shown = remember(sources, query) { sources.filter { query.isBlank() || it.name.contains(query, true) } }
            LazyColumn(Modifier.weight(1f)) {
                if (shown.isEmpty()) item { Text("没有符合条件的漫画源。", Modifier.padding(vertical = 20.dp)) }
                items(shown, key = { it.id }) { source ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(
                        role = Role.Checkbox,
                        onLongClickLabel = "查看${source.name}详情",
                        onClick = { pending = if (source.id in pending) pending - source.id else pending + source.id },
                        onLongClick = { open = false; onOpenSource(source.id) },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(source.id in pending, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(readability[source.id] ?: "已启用 · 尚未检测目录和图片", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { open = false; onOpenSource(source.id) }) {
                            Icon(Icons.Default.ChevronRight, "查看${source.name}详情")
                        }
                    }
                }
            }
            TextButton(onClick = { open = false; onManage() }) { Text("源管理与停用原因") }
            Button(onClick = {
                if (pending.size == sources.size) onChange(MangaController.ALL_SOURCES) else onSelectMany(pending)
                open = false
            }, enabled = pending.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text("完成（${pending.size}）")
            }
        }
    }
}
