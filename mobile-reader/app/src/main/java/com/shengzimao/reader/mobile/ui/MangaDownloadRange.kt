package com.shengzimao.reader.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.MangaBook

@Composable
fun MangaDownloadRange(book: MangaBook, onDismiss: () -> Unit, onDownload: (Int, Int, Boolean) -> Unit) {
    var start by remember { mutableStateOf((book.chapterIndex + 1).toString()) }
    var end by remember { mutableStateOf(book.chapters.size.toString()) }
    var missing by remember { mutableStateOf(true) }
    val first = start.toIntOrNull(); val last = end.toIntOrNull()
    val valid = first != null && last != null && first in 1..book.chapters.size && last in first..book.chapters.size
    AlertDialog(onDismissRequest = onDismiss, title = { Text("下载章节范围") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("共 ${book.chapters.size} 章 · 从当前章起下载，跳过已完成章节")
            OutlinedTextField(start, { start = it.filter(Char::isDigit).take(5) }, label = { Text("起始章序号") }, singleLine = true)
            OutlinedTextField(end, { end = it.filter(Char::isDigit).take(5) }, label = { Text("结束章序号") }, singleLine = true)
            Row { Checkbox(missing, { missing = it }); Text("仅下载尚未完整保存的章节") }
            if (!valid) Text("请输入有效的章节范围", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onDownload(first!! - 1, last!! - 1, missing) }) { Text("加入下载队列") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
