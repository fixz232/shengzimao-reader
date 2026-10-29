package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.shengzimao.reader.mobile.reader.SearchHit
import kotlinx.coroutines.delay

/**
 * 全文搜索面板。搜索在 IO 线程执行（由调用方传入 suspend 函数），面板只负责输入与结果。
 * 选中结果后关闭面板并跳到命中位置，命中词会在正文中保留浅色标记。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSearchSheet(
    initialQuery: String,
    onSearch: suspend (String) -> List<SearchHit>,
    onDismiss: () -> Unit,
    onSelect: (SearchHit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 输入防抖：300ms 内没有新输入才真正搜。
    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            results = emptyList()
            searched = false
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        results = onSearch(keyword)
        searching = false
        searched = true
        if (results.isNotEmpty()) listState.scrollToItem(0)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.ime },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("全文搜索", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("输入正文关键词") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "清除") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    when {
                        searching -> "搜索中…"
                        !searched -> "输入关键词后会扫描全书正文"
                        results.isEmpty() -> "没有找到「${query.trim()}」"
                        else -> if (results.size >= SearchLimit) {
                            "命中超过 $SearchLimit 处，已截断显示"
                        } else {
                            "共 ${results.size} 处命中"
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (results.isNotEmpty()) {
                    TextButton(onClick = { onSelect(results.first()) }) { Text("跳到第一处", fontSize = 12.sp) }
                }
            }
            if (results.isNotEmpty()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(if (results.size > 6) 420.dp else (results.size * 62).dp.coerceAtLeast(72.dp))) {
                    itemsIndexed(results, key = { index, hit -> "$index-${hit.position}" }) { index, hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(hit) }
                                .padding(vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${index + 1}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.widthIn(min = 28.dp),
                                )
                                Text(
                                    hit.chapterTitle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                hit.snippet,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
                            )
                        }
                        if (index != results.lastIndex) HorizontalDivider()
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (!searched) "支持书名外任意正文关键词，不区分大小写" else "换个更短的词试试",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 面板外限制：与 TextSearch.MAX_TOTAL 保持一致。 */
const val SearchLimit = 2000

/** 结果列表的圆角容器（供其它页面复用）。 */
val SearchCardShape = RoundedCornerShape(10.dp)
