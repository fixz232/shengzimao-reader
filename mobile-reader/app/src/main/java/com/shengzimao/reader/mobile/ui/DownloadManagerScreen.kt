package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.DownloadStatus
import com.shengzimao.reader.mobile.ui.theme.AppDimens
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadManagerContent(viewModel: ReaderViewModel, navigator: Navigator, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = { navigator.openTool(ToolPage.DOWNLOAD_SETTINGS) }) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permission by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { permission = it }
    val tasks by viewModel.downloads.tasks.collectAsStateWithLifecycle()
    val storageError by viewModel.downloads.storageError.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tasks.any { it.status == DownloadStatus.RUNNING }) {
        while (tasks.any { it.status == DownloadStatus.RUNNING }) { now = System.currentTimeMillis(); delay(1000) }
    }
    val shown = tasks.filter { task -> when (filter) {
        "进行中" -> task.active
        "已完成" -> task.status in setOf(DownloadStatus.DONE, DownloadStatus.SKIPPED)
        "未完成" -> !task.active && task.status !in setOf(DownloadStatus.DONE, DownloadStatus.SKIPPED)
        else -> true
    } }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(AppDimens.ItemSpacing),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("下载任务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${tasks.count { it.active }} 个进行中 · 小说与漫画统一管理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.downloads.clearHistory() }, enabled = tasks.any { !it.active }) { Text("清理记录") }
            }
        }
        item { TextButton(onClick = onOpenSettings) { Text("下载与缓存设置") } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "进行中", "已完成", "未完成").forEach { name ->
                    FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                }
            }
        }
        if (!permission && android.os.Build.VERSION.SDK_INT >= 33) item { TextButton(onClick = { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许显示后台下载通知") } }
        if (storageError.isNotBlank()) item { AppInlineNotice(storageError, isError = true) }
        if (shown.isEmpty()) item {
            AppEmptyState(
                icon = Icons.Filled.Download,
                title = if (tasks.isEmpty()) "还没有下载任务" else "这个分类中没有任务",
                description = "在搜书或漫画详情中选择下载，任务会出现在这里。可暂停、重试，完成后打开作品。",
            )
        }
        items(shown, key = { it.id }) { task ->
            DownloadTaskCard(
                title = task.title,
                kindLabel = if (task.kind == "comic") "漫画" else "小说",
                isComic = task.kind == "comic",
                source = task.source.ifBlank { "在线书源" },
                format = task.format.uppercase(),
                statusLabel = task.status.label,
                failed = task.status == DownloadStatus.FAILED,
                running = task.status == DownloadStatus.RUNNING,
                progress = if (task.total > 0) (task.done.toFloat() / task.total).coerceIn(0f, 1f) else null,
                message = task.message + if (task.kind == "comic") "\n剩余 ${(task.total - task.done).coerceAtLeast(0)} 章 · 已完成 ${task.pagesDone}/${task.pagesTotal} 页 · 失败 ${task.failedPages} 页" else
                    if (task.total > 0) "\n剩余 ${(task.total - task.done).coerceAtLeast(0)} 章" else "",
                timeText = task.startedAt.takeIf { it > 0 }?.let { started ->
                    val seconds = ((if (task.active) now else task.finishedAt.takeIf { it > 0 } ?: now) - started).coerceAtLeast(0) / 1000
                    "耗时 ${seconds / 60}分${seconds % 60}秒" + if (task.total > 0) " · ${task.done}/${task.total}" else ""
                },
                onCancel = if (task.active) { { viewModel.downloads.cancel(task.id) } } else null,
                onPause = if (task.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED)) { { viewModel.downloads.pause(task.id) } } else null,
                onPrioritize = if (task.status == DownloadStatus.QUEUED) { { viewModel.downloads.prioritize(task.id) } } else null,
                onRetry = if (task.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.INTERRUPTED)) {
                    { viewModel.downloads.retry(task.id) }
                } else null,
                onOpen = task.bookId.takeIf { it.isNotBlank() }?.let { id ->
                    { if (task.kind == "comic") navigator.openComic(id) else navigator.openReader(id) }
                },
                onRemove = { viewModel.downloads.remove(task.id) },
            )
        }
    }
}

/** 单个下载任务的卡片：类型图标 + 状态徽标 + 进度条 + 操作。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskCard(
    title: String,
    kindLabel: String,
    isComic: Boolean,
    source: String,
    format: String,
    statusLabel: String,
    failed: Boolean,
    running: Boolean,
    progress: Float?,
    message: String,
    timeText: String?,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onRemove: () -> Unit,
    onPause: (() -> Unit)?,
    onPrioritize: (() -> Unit)?,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 类型图标放在圆角方块里，一眼区分小说与漫画。
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isComic) Icons.Filled.Image else Icons.Filled.MenuBook,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "$kindLabel · $source · $format",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            AppBadge(
                statusLabel,
                container = when {
                    failed -> MaterialTheme.colorScheme.error
                    running -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
            )
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (timeText != null) {
            Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onCancel != null || onRetry != null || onOpen != null) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onPause?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("暂停", fontSize = 12.sp) } }
                onPrioritize?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("提前", fontSize = 12.sp) } }
                onCancel?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("取消", fontSize = 12.sp) } }
                onRetry?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("继续 / 重试", fontSize = 12.sp) } }
                onOpen?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("打开阅读", fontSize = 12.sp) } }
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("移除", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
