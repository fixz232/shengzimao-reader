package com.shengzimao.reader.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengzimao.reader.mobile.data.MangaBook
import com.shengzimao.reader.mobile.data.MangaDiscover
import com.shengzimao.reader.mobile.ui.theme.GlassDefaults
import com.shengzimao.reader.mobile.ui.theme.GlassSurface

/**
 * 漫源详情页：长按「搜索范围」里的某个源进入。
 *
 * 分两层：
 * 1. 源信息卡（名称、地址、启用状态、规则能力）；
 * 2. 浏览区——源声明了 discover 规则就展示真实榜单/分类，
 *    否则退回分类关键词搜索，并在顶部明确标注二者区别。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaSourceBrowserScreen(viewModel: ReaderViewModel, navigator: Navigator) {
    val controller = viewModel.manga
    val state by controller.state.collectAsStateWithLifecycle()
    val source = state.sources.firstOrNull { it.id == state.browseSourceId } ?: return
    BackHandler { controller.closeBrowse() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (source.enabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { controller.closeBrowse() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回在线搜漫")
                    }
                },
                actions = {
                    IconButton(onClick = { state.browseEntry.takeIf { it.isNotBlank() }?.let { controller.browse(it, force = true) } },
                        enabled = state.browseEntry.isNotBlank() && !state.browseLoading) {
                        Icon(Icons.Filled.Refresh, "刷新当前分类")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SourceInfoCard(source.name, source.baseUrl, source.enabled, state.browseHasDiscover, state.browseIsLegacy) }

            // 榜单 / 分类入口
            state.browseGroups.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(group.entries, key = { index, entry -> "$index:${entry.name}:${entry.url}" }) { _, entry ->
                                val selected = entry.name == state.browseEntry && entry.url == state.browseEntryUrl
                                Surface(
                                    onClick = { controller.browse(entry.name, entryUrl = entry.url) },
                                    shape = CircleShape,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 结果区
            when {
                state.browseLoading && state.browseResults.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("正在读取「${state.browseEntry}」…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.browseError.isNotBlank() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("读取失败", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(state.browseError, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { controller.browse(state.browseEntry, force = true) }) { Text("重试") }
                        }
                    }
                }
                state.browseEntry.isBlank() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.ImageSearch, null, Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("选一个榜单或分类开始浏览", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (state.browseHasDiscover) "该源提供了真实榜单入口" else "该源未提供榜单规则，下面是分类关键词搜索",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.browseResults.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CloudOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("「${state.browseEntry}」没有返回结果", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("换个分类试试，或检查该源站点的分类地址是否仍然有效。",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                }
                else -> {
                        items(state.browseResults, key = { it.id }) { book ->
                            BrowseBookCard(controller, book) {
                                // 先入详情，再让用户决定在线阅读或加入漫库。
                                controller.openDetails(book)
                                controller.closeBrowse()
                            }
                        }
                }
            }

            if (state.browseResults.isNotEmpty() && state.browseHasDiscover) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { controller.browse(state.browseEntry, more = true) },
                            enabled = !state.browseLoading,
                        ) { Text(if (state.browseLoading) "正在加载…" else "加载第 ${state.browsePage + 1} 页") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceInfoCard(name: String, baseUrl: String, enabled: Boolean, hasDiscover: Boolean, isLegacy: Boolean) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        spec = GlassDefaults.card(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    com.shengzimao.reader.mobile.ui.theme.LocalAurora.current.accent,
                                    com.shengzimao.reader.mobile.ui.theme.LocalAurora.current.accentAlt,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(baseUrl, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoPill(if (enabled) "已启用" else "已停用",
                    if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                InfoPill(if (isLegacy) "旧式规则" else "声明式", MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant)
                InfoPill(if (hasDiscover) "有榜单" else "无榜单", MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!hasDiscover) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Extension, null, Modifier.size(15.dp).padding(top = 1.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "该源没有配置 discover 榜单规则，下面的分类是「关键词搜索」，不是源站官方排行榜。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun BrowseBookCard(controller: MangaController, book: MangaBook, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            // 复用漫库同一套封面加载（含占位与失败回退），不在浏览页另造一套。
            MangaCover(
                engine = controller.engine,
                reference = book.cover,
                sourceId = book.sourceId,
                title = book.title,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(book.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium)
        if (book.author.isNotBlank()) {
            Text(book.author, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
