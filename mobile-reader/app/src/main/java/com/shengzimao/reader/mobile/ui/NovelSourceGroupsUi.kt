package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.OnlineUiState

@Composable
fun NovelSourceGroupPicker(state: OnlineUiState, onSelect: (Int) -> Unit, onImport: () -> Unit,
    onManage: () -> Unit = {}, onScope: (Set<String>?) -> Unit = {}) {
    NovelSearchScope(state, onSelect, onImport, onManage, onScope)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelSourceImportSheet(viewModel: ReaderViewModel, group: Int, state: OnlineUiState, onDismiss: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    // Do not put a potentially multi-MB JSON in the Android saved-instance Bundle.
    var text by remember { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importNovelSources(group, uri)
    }
    AppModalSheet(onDismissRequest = onDismiss, title = "导入书源 · 分组 $group") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("规则只会保存到分组 $group。同站点规则再次导入会更新此组，不会覆盖内置源或其他组。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("文件", "链接", "粘贴").forEachIndexed { index, label ->
                    SegmentedButton(selected = mode == index, onClick = { mode = index }, enabled = !state.importingSources,
                        shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                }
            }
            when (mode) {
                0 -> Button(onClick = {
                    runCatching { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        .onFailure { viewModel.online.report("无法打开文件选择器，可改用链接或粘贴导入。", true) }
                }, enabled = !state.importingSources, modifier = Modifier.fillMaxWidth()) { Text("选择书源 JSON 文件") }
                1 -> {
                    OutlinedTextField(url, { url = it.take(4096) }, singleLine = true, label = { Text("书源 JSON 链接") },
                        enabled = !state.importingSources, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { viewModel.importNovelSourcesUrl(group, url) }, enabled = url.isNotBlank() && !state.importingSources) { Text("读取并导入") }
                }
                else -> {
                    OutlinedTextField(text, { if (it.length <= 5 * 1024 * 1024) text = it }, label = { Text("书源规则 JSON") },
                        enabled = !state.importingSources, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 280.dp))
                    Button(onClick = { viewModel.importNovelSourcesText(group, text) }, enabled = text.isNotBlank() && !state.importingSources) { Text("导入到此组") }
                }
            }
            Text("支持 SoNovel 和阅读/Legado 小说书源，单个对象、数组或 {\"rules\": [...]}；最多 5000 条、32 MB，粘贴有长度限制。大型合集请用文件或 JSON 下载链接导入。漫画源请到在线搜漫导入；需登录、浏览器验证或特殊平台接口的规则不保证可用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.importingSources) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { viewModel.cancelOnline() }) { Text("取消导入") }
            }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (state.message.startsWith("分组 $group：")) Text(state.message, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("本组已保存 ${state.sourceCounts[group - 1]} 条书源", style = MaterialTheme.typography.titleSmall)
            state.sourceEntries.take(50).forEach { entry ->
                Column {
                    Text("${entry.name} · ${entry.format}", style = MaterialTheme.typography.bodyMedium)
                    Text(entry.url + if (entry.searchable) "" else " · 未启用搜索",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.sourceEntries.size > 50) Text("仅预览前 50 条；搜索会使用本组全部已启用的搜索规则。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
