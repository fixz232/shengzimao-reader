package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.*

private data class TextSearchHit(val chapter: Int, val offset: Int, val snippet: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelCleanupSheet(settings: ReaderSettings, rawText: String, onSave: (Boolean, String) -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(settings.cleanupEnabled) }
    var rules by remember { mutableStateOf(settings.cleanupRules) }
    val preview = remember(rules, enabled, rawText) { runCatching {
        val parsed = ContentCleanup.parse(rules)
        if (enabled) ContentCleanup.apply(rawText.take(5000), parsed).text else rawText.take(5000)
    } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f).navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("正文净化 · 当前书籍", style = MaterialTheme.typography.titleLarge) }
            item { Row { Text("启用净化规则", Modifier.weight(1f)); Switch(enabled, { enabled = it }) } }
            item { Text("每行一条：-包含文字 删除整段；原文字=>新文字 替换文字。使用字面匹配，不执行脚本或正则。原始正文不会被修改。") }
            item { OutlinedTextField(rules, { rules = it.take(12_000) }, Modifier.fillMaxWidth().heightIn(min = 130.dp), label = { Text("净化规则") }, placeholder = { Text("-请收藏本站\n错别字=>正确文字") }) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(enabled, rules); onDismiss() }, enabled = preview.isSuccess) { Text("保存并应用") }
                TextButton(onClick = { enabled = false; rules = ""; onSave(false, ""); onDismiss() }) { Text("恢复原文并清空规则") }
            } }
            item { Text("效果预览（当前章前 5000 字）", style = MaterialTheme.typography.titleMedium) }
            item { Text(preview.getOrElse { it.message ?: "规则无效" }, color = if (preview.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NovelTextSearchSheet(viewModel: ReaderViewModel, book: Book, currentChapter: Int, rawText: String,
    settings: ReaderSettings, onDismiss: () -> Unit, onHit: (Int, Int, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("当前章") }
    var results by remember { mutableStateOf<List<TextSearchHit>>(emptyList()) }
    var progress by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun start() {
        job?.cancel(); results = emptyList(); running = true; progress = "准备搜索…"
        val token = ++revision
        val needle = query.trim(); val selectedRange = range
        job = scope.launch {
            try {
                val toc = book.onlineChapters
                val saved = when (selectedRange) {
                    "当前章" -> mapOf(toc.getOrNull(currentChapter)?.url.orEmpty() to rawText)
                    "阅读缓存" -> emptyMap()
                    else -> emptyMap()
                }
                val cachedUrls = if (selectedRange == "阅读缓存") viewModel.cachedOnlineUrls(book) else emptySet()
                val targets = toc.withIndex().filter { selectedRange in listOf("全书在线", "已下载章节") || it.value.url in saved || it.value.url in cachedUrls }
                var failures = 0
                val found = ArrayList<TextSearchHit>()
                for ((done, target) in targets.withIndex()) {
                    ensureActive(); progress = "已搜索 $done/${targets.size} 章 · ${found.size} 处匹配" + if (failures > 0) " · $failures 章失败" else ""
                    val text = try { saved[target.value.url] ?: when (selectedRange) {
                        "阅读缓存" -> viewModel.readCachedOnline(book, target.value) ?: continue
                        "已下载章节" -> viewModel.readDownloadedOnline(book, target.value) ?: continue
                        else -> withTimeout(25_000) { viewModel.readOnlineChapter(book, target.value) }
                    } }
                        catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; failures++; continue }
                        catch (_: Exception) { failures++; continue }
                    val matches = withContext(Dispatchers.Default) {
                        val cleaned = ContentCleanup.apply(text, if (settings.cleanupEnabled) ContentCleanup.parse(settings.cleanupRules) else emptyList())
                        val hits = ArrayList<TextSearchHit>(); var offset = 0
                        while (hits.size < 300 - found.size) {
                            val at = cleaned.text.indexOf(needle, offset, ignoreCase = true); if (at < 0) break
                            hits += TextSearchHit(target.index, cleaned.rawOffset(at), cleaned.text.substring((at - 24).coerceAtLeast(0), (at + needle.length + 60).coerceAtMost(cleaned.text.length)))
                            offset = at + needle.length.coerceAtLeast(1)
                        }; hits
                    }
                    found.addAll(matches); results = found.toList()
                    if (found.size >= 300) break
                }
                progress = if (targets.isEmpty()) "此范围没有可搜索的正文；阅读缓存包含已读或预取章节，下载章节需要先主动下载。"
                    else "搜索结束 · ${found.size} 处匹配" + if (found.size >= 300) "（最多显示 300 处，请缩小范围）" else if (failures > 0) " · $failures 章读取失败，可重试" else ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { progress = failure.message ?: "搜索未完成" }
            finally { if (token == revision) running = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).navigationBarsPadding().padding(16.dp)) {
            Text("搜索正文", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { revision++; job?.cancel(); running = false; query = it.take(100); results = emptyList() }, Modifier.fillMaxWidth(), label = { Text("关键词") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("当前章", "阅读缓存", "已下载章节", "全书在线").forEach { label ->
                FilterChip(range == label, { revision++; job?.cancel(); running = false; range = label; results = emptyList(); progress = "" }, label = { Text(label) })
            } }
            if (range == "全书在线") Text("点击后将联网逐章读取全书，可能产生流量；结果会陆续显示，随时可停止。", color = MaterialTheme.colorScheme.primary)
            Row { Button(onClick = { start() }, enabled = query.isNotBlank() && !running) { Text(if (range == "全书在线") "开始联网搜索" else "开始搜索") }
                if (running) TextButton(onClick = { job?.cancel(); progress = "搜索已停止，已找到的结果保留" }) { Text("停止搜索") } }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(progress, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) { items(results) { hit ->
                TextButton(onClick = { job?.cancel(); onHit(hit.chapter, hit.offset, query); onDismiss() }, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text(book.onlineChapters.getOrNull(hit.chapter)?.title.orEmpty(), style = MaterialTheme.typography.labelLarge); Text(hit.snippet) }
                }
            } }
        }
    }
}
