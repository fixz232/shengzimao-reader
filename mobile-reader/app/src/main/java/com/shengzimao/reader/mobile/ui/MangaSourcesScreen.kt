package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.MangaSource
import com.shengzimao.reader.mobile.data.MangaSourceTest
import com.shengzimao.reader.mobile.data.MangaReadability

/**
 * 漫画源管理。
 *
 * 与旧版的区别：
 * - 导入降级为「＋」弹窗，页面主体留给已有源；
 * - 编辑是真正的编辑态，id 只读锁定（id 与漫库、图片缓存关联，改了会分裂数据）；
 * - 每个源可「测试」三段规则并给出具体失败环节。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaSourcesScreen(controller: MangaController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var importSheet by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<MangaSource?>(null) }
    var testKeyword by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }
    var adapters by rememberSaveable { mutableStateOf(false) }
    if (adapters) {
        MangaAdaptersScreen(controller) { adapters = false }
        return
    }
    BackHandler { if (state.editingSourceId.isNotBlank()) controller.cancelEditSource() else onBack() }

    // 编辑态是独立页面，避免“编辑却看起来像导入”的混淆。
    if (state.editingSourceId.isNotBlank()) {
        MangaSourceEditor(controller, onBack = { controller.cancelEditSource() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("漫画源管理") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回在线搜漫") }
            }, actions = {
                IconButton(onClick = { adapters = true }) { Icon(Icons.Filled.Extension, "站点适配包") }
                val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri != null) controller.exportSources(uri, false)
                }
                IconButton(onClick = { exporter.launch("manga-sources.json") }, enabled = state.sources.isNotEmpty()) {
                    Icon(Icons.Filled.IosShare, "导出全部源")
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importSheet = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("添加源") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "已启用 ${state.sources.count { it.enabled }} / 共 ${state.sources.size} 个源",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("搜索范围只显示已启用源；暂未适配的源保存在此处，不会因停用而丢失。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "支持声明式规则和受限旧式脚本；脚本不能访问手机文件或 Android。只导入信任的源；不支持登录、付费或私有解密绕过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item { MangaFeedback(state.sourceError, state.message, controller) }
            item {
                OutlinedTextField(testKeyword, { testKeyword = it.take(60) }, singleLine = true,
                    label = { Text("源自检关键词（可选）") }, placeholder = { Text("留空使用常用关键词") },
                    enabled = !state.batchTesting && state.testingSourceId.isBlank(), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { adapters = true }) { Text("站点适配") }
                    if (state.batchTesting) {
                        OutlinedButton(onClick = controller::cancelAllSourceTests) { Text("停止检测") }
                    } else {
                        OutlinedButton(onClick = { controller.testAllSources(testKeyword) }, enabled = state.sources.isNotEmpty() && state.testingSourceId.isBlank()) { Text("检测全部源") }
                        TextButton(onClick = { controller.testAllSources(testKeyword, failedOnly = true) }, enabled = state.sources.isNotEmpty() && state.testingSourceId.isBlank()) { Text("重测未通过") }
                    }
                }
                if (state.batchTestTotal > 0) {
                    Text("已检测 ${state.batchTestDone} / ${state.batchTestTotal}；完成一项立即保存结果", style = MaterialTheme.typography.bodySmall)
                    if (state.batchTesting) LinearProgressIndicator(progress = { state.batchTestDone.toFloat() / state.batchTestTotal }, modifier = Modifier.fillMaxWidth())
                }
                Text("实际联网抽查首、中、末章节图片并解码，最多同时检测两个源；停用源只在隔离副本中检测。通过仅代表样本可读。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val readable = state.sources.count { source -> state.tests[source.id]?.let {
                    it.readability == MangaReadability.READABLE && it.ruleVersion == controller.engine.ruleVersion(source)
                } == true }
                Text("当前规则抽样可读 $readable 个；其他源可查看具体失败环节。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = limit.isEmpty(), onClick = { limit = "" }, label = { Text("全部") })
                    FilterChip(selected = limit == "failed", onClick = { limit = "failed" }, label = { Text("未通过 / 待复测") })
                }
            }

            if (state.sources.isEmpty()) {
                item {
                    AppEmptyState(
                        icon = Icons.Filled.ImageSearch,
                        title = "尚未添加漫画源",
                        description = "模板中的 example.invalid 是占位地址，需要替换成你有权访问的真实站点和对应规则。也兼容阅读（Legado）书源。",
                        actionLabel = "添加第一个源",
                        onAction = { importSheet = true },
                    )
                }
            }

            items(state.sources.filter { source -> limit.isEmpty() || state.tests[source.id]?.let {
                it.readability != MangaReadability.READABLE || it.ruleVersion != controller.engine.ruleVersion(source)
            } != false }, key = { it.id }) { source ->
                MangaSourceCard(
                    source = source,
                    test = state.tests[source.id],
                    rulesVersion = controller.engine.ruleVersion(source),
                    testing = state.testingSourceId == source.id || source.id in state.testingSourceIds,
                    onToggle = { controller.toggleSource(source.id) },
                    onEdit = { controller.beginEditSource(source.id) },
                    onTest = { controller.testSource(source.id, testKeyword) },
                    onRemove = { remove = source },
                )
            }

        }
    }

    if (importSheet) {
        MangaSourceImportSheet(controller) { importSheet = false }
    }

    if (state.pendingSources.isNotEmpty() && state.editingSourceId.isBlank()) {
        AlertDialog(
            onDismissRequest = { controller.discardSourcePreview() },
            title = { Text("导入 ${state.pendingSources.size} 个漫画源？") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (state.sourceImportNotice.isNotBlank()) Text(state.sourceImportNotice,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    Text("共识别 ${state.pendingSources.size} 个源，其中启用 ${state.pendingSources.count { it.enabled }} 个、停用 ${state.pendingSources.count { !it.enabled }} 个；两者都会导入。")
                    Text("同 ID 的源将更新配置；已有漫库与阅读进度保留。", modifier = Modifier.padding(top = 8.dp))
                    Column {
                        state.pendingSources.forEach { source ->
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(source.name + if (!source.enabled) " · 暂未启用" else "")
                                Text(source.baseUrl, style = MaterialTheme.typography.bodySmall)
                                if (source.compatibilityNote.isNotBlank()) Text(source.compatibilityNote, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.skippedSources.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            "另有 ${state.skippedSources.size} 项格式无效",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "以下项目无法识别为完整源对象，请检查原始配置。其他源均已保留，兼容状态可在源列表中查看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.padding(top = 4.dp)) {
                            state.skippedSources.forEach { reason ->
                                Text(
                                    "· $reason",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { controller.confirmSources(); importSheet = false }) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = { controller.discardSourcePreview() }) { Text("取消") } },
        )
    }

    remove?.let { source ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("移除 ${source.name}？") },
            text = { Text("漫库与已缓存图片会保留，但图片缓存可能变成无处清理的孤儿数据，可在「漫库 → 缓存管理」清理。该源下尚未缓存的章节将无法加载。") },
            confirmButton = { TextButton(onClick = { controller.deleteSource(source.id); remove = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MangaSourceCard(
    source: MangaSource,
    test: MangaSourceTest?,
    rulesVersion: String,
    testing: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(source.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = source.enabled, onCheckedChange = { onToggle() })
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑规则") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("移除源") }, onClick = { menu = false; onRemove() })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !testing) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("测试中…", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                    } else {
                        Icon(Icons.Filled.NetworkCheck, null, Modifier.size(16.dp))
                        Text("测试", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                TextButton(onClick = onEdit) { Text("编辑", fontSize = 12.sp) }
            }
            when {
                testing -> Unit
                test == null -> Text("尚未测试。建议导入后先测试，确认搜索 / 目录 / 图片三段规则可用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (test.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        null,
                        Modifier.size(16.dp).padding(top = 2.dp),
                        tint = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "${test.readability.label} · ${test.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text("ID：${source.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (source.compatibilityNote.isNotBlank()) Text(source.compatibilityNote, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            test?.let {
                if (it.ruleVersion.isNotBlank() && it.ruleVersion != rulesVersion) Text("适配规则已更新，此结果需要复测", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Text("搜索 ${if (it.results > 0) "✓" else "未通过"} · 目录 ${if (it.chapters > 0) "✓" else "未通过"} · 首图 ${if (it.ok) "✓" else "未通过"}", style = MaterialTheme.typography.labelSmall)
                val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                Text("检测于 " + java.text.SimpleDateFormat("MM-dd HH:mm", locale).format(java.util.Date(it.testedAt)), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 源编辑页：id 只读，规则可改。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaSourceEditor(controller: MangaController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val source = state.sources.firstOrNull { it.id == state.editingSourceId } ?: return
    var text by remember(source.id) { mutableStateOf(source.json().toString(2)) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("编辑漫画源") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = { controller.saveSourceEdit(text) }, enabled = text.isNotBlank()) { Text("保存") }
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("源 ID（不可修改）", style = MaterialTheme.typography.labelMedium)
                        Text(source.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "ID 与漫库条目、图片缓存关联；修改会让已有数据失去关联。如需新源，请用「添加源」新建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item { MangaFeedback(state.sourceError, "", controller) }
            item {
                OutlinedTextField(
                    text, { if (it.length <= 1024 * 1024) text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                    label = { Text("源规则 JSON") },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.saveSourceEdit(text) }, enabled = text.isNotBlank()) { Text("保存并应用") }
                    TextButton(onClick = { text = source.json().toString(2) }) { Text("还原") }
                    TextButton(onClick = { controller.testSource(source.id) }) { Text("测试此源") }
                }
            }
        }
    }
}

/** 导入弹窗：文件 / 链接 / 粘贴三种方式各自独立，切换不丢失输入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaSourceImportSheet(controller: MangaController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) controller.importSources(it) }
    val templateExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) controller.exportSources(uri, true)
    }
    val close = { controller.cancelSourceImport(); onDismiss() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("添加漫画源", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("文件", "链接", "粘贴").forEachIndexed { index, label ->
                    SegmentedButton(selected = mode == index, onClick = { mode = index }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                }
            }
            when (mode) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) { Text("选择 JSON 漫画源文件") }
                    Text("支持单个源、源数组，或 {\"sources\": [...]} 格式；单次最多 500 个源，文件及解压后内容不超过 8 MB。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it.take(2000) }, label = { Text("漫画源 JSON 地址") }, placeholder = { Text("https://…/sources.json") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { controller.importSourcesUrl(url.trim()) }, enabled = url.isNotBlank() && !state.importing, modifier = Modifier.fillMaxWidth()) { Text("读取并预览") }
                    Text(
                        "地址需返回漫画源 JSON，支持正常跳转。连接异常会尝试安全兼容连接；已核验的分享入口还会尝试公开直链，并在预览中注明。也支持文件或粘贴导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { if (it.length <= 1024 * 1024) text = it }, label = { Text("粘贴漫画源 JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.previewSources(text) }, enabled = text.isNotBlank()) { Text("解析并预览") }
                        TextButton(onClick = { text = MangaSource.template }) { Text("填入模板") }
                    }
                }
            }
            if (state.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.sourceImportStage.ifBlank { "正在读取漫画源…" }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = controller::cancelSourceImport) { Text("停止读取") }
            }
            if (state.sourceError.isNotBlank()) {
                // 导入失败的原因是用户最需要看清的信息：用区块承载，并给出可执行的下一步。
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.sourceError, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        if (mode == 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { controller.importSourcesUrl(url.trim()) }, enabled = url.isNotBlank() && !state.importing,
                                contentPadding = PaddingValues(0.dp)) { Text("重试链接") }
                            TextButton(onClick = { mode = 0 }, contentPadding = PaddingValues(0.dp)) { Text("改用「文件」导入") }
                            TextButton(onClick = { mode = 2 }, contentPadding = PaddingValues(0.dp)) { Text("改用「粘贴」导入") }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { templateExporter.launch("manga-source-template.json") }) { Text("导出模板") }
                TextButton(onClick = close) { Text("关闭") }
            }
        }
    }
}
