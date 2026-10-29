package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaAdaptersScreen(controller: MangaController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var showText by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::importAdapterFile)
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("站点适配") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回漫画源管理") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("独立更新规则与图片线路", style = MaterialTheme.typography.titleMedium)
                Text("从可信链接或文件导入适配包。支持搜索、目录、图片规则、请求头和图片线路更新；安装前显示范围，保留上一版供回退。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("新算法或引擎能力仍需更新应用。适配包不会自动将停用源启用，也不改变漫库和已下载内容。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            item {
                OutlinedTextField(link, { link = it.take(2048) }, label = { Text("HTTPS 适配包链接") },
                    singleLine = true, enabled = !state.adapterBusy, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { controller.importAdapterUrl(link) }, enabled = link.isNotBlank() && !state.adapterBusy) { Text("读取链接") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !state.adapterBusy) { Text("选择文件") }
                    TextButton(onClick = { showText = !showText }, enabled = !state.adapterBusy) { Text("粘贴") }
                    TextButton(onClick = controller::previewBundledAdapter, enabled = !state.adapterBusy) { Text("内置修复包") }
                }
                if (showText) {
                    OutlinedTextField(text, { text = it }, label = { Text("适配包 JSON") }, maxLines = 8,
                        modifier = Modifier.fillMaxWidth(), enabled = !state.adapterBusy)
                    TextButton(onClick = { controller.previewAdapter(text) }, enabled = text.isNotBlank() && !state.adapterBusy) { Text("检查内容") }
                }
                if (state.adapterBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("正在读取并校验适配包…", style = MaterialTheme.typography.bodySmall)
                }
                if (state.adapterError.isNotBlank()) Text(state.adapterError, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp))
                if (state.adapterMessage.isNotBlank()) Text(state.adapterMessage, modifier = Modifier.padding(top = 8.dp))
            }
            if (state.adapterPacks.isEmpty()) item {
                HorizontalDivider()
                Text("尚未安装适配包。原有漫画源继续使用原规则；无需为正常的源安装额外适配。")
            }
            items(state.adapterPacks, key = { it.pack.id }) { entry ->
                val pack = entry.pack
                val updateUrl = pack.updateUrl.ifBlank { entry.origin.takeIf { it.startsWith("https://", true) }.orEmpty() }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                        Text("v${pack.version} · ${if (entry.enabled) "已启用" else "已停用"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(entry.enabled, { controller.toggleAdapter(pack.id) }, enabled = !state.adapterBusy)
                }
                Text(pack.adapters.joinToString("；") { "${it.getString("id")}：${it.getJSONArray("hosts").join(", ")}" },
                    style = MaterialTheme.typography.bodySmall)
                if (updateUrl.isNotBlank()) Text(updateUrl, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (updateUrl.isNotBlank()) TextButton(onClick = { controller.importAdapterUrl(updateUrl, pack.id) }, enabled = !state.adapterBusy) { Text("检查更新") }
                    if (entry.previous != null) TextButton(onClick = { controller.rollbackAdapter(pack.id) }, enabled = !state.adapterBusy) { Text("回退至 v${entry.previous.version}") }
                    TextButton(onClick = { controller.removeAdapter(pack.id) }, enabled = !state.adapterBusy) { Text("移除适配") }
                }
            }
        }
    }
    state.pendingAdapter?.let { pack ->
        AlertDialog(onDismissRequest = controller::dismissAdapterPreview, title = { Text("安装 ${pack.name} v${pack.version}？") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("来源：${state.pendingAdapterOrigin}")
                    state.adapterPacks.firstOrNull { it.pack.id == pack.id }?.let { Text("当前版本：v${it.pack.version}") }
                    Text("适配范围", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    pack.adapters.forEach { adapter ->
                        Text("${adapter.getString("id")}：${adapter.getJSONArray("hosts").join(", ")}")
                        val patch = adapter.optJSONObject("patch") ?: JSONObject()
                        if (patch.length() > 0) Text("规则：${patch.keys().asSequence().joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                        adapter.optJSONArray("imageHosts")?.let { Text("图片域名：${it.join(", ")}", style = MaterialTheme.typography.bodySmall) }
                        adapter.optJSONArray("resourceHosts")?.let { Text("资源域名：${it.join(", ")}", style = MaterialTheme.typography.bodySmall) }
                        adapter.optJSONArray("sourceIds")?.let { Text("指定源 ID：${it.join(", ")}", style = MaterialTheme.typography.bodySmall) }
                        listOf("requestRoutes", "imageMirrors").forEach { key ->
                            adapter.optJSONArray(key)?.let { routes ->
                                for (i in 0 until routes.length()) {
                                    val route = routes.getJSONObject(i)
                                    Text("线路：${route.getString("fromHost")}${route.optString("pathPrefix", "/")} → ${route.getString("toBaseUrl")}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Text("只安装你信任的适配包。安装后应重新检测受影响源的图片。", modifier = Modifier.padding(top = 12.dp))
                    if (state.adapterError.isNotBlank()) Text(state.adapterError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = controller::confirmAdapter, enabled = !state.adapterBusy) { Text("安装并应用") } },
            dismissButton = { TextButton(onClick = controller::dismissAdapterPreview, enabled = !state.adapterBusy) { Text("取消") } })
    }
}
