package com.shengzimao.reader.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.Book
import com.shengzimao.reader.mobile.data.DocumentExporter
import com.shengzimao.reader.mobile.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出对话框：把一本书另存为 TXT 或 EPUB。
 * 走系统文件选择器（SAF），不需要任何存储权限。
 */
@Composable
fun ExportDialog(
    book: Book,
    store: LibraryStore,
    onDismiss: () -> Unit,
    onResult: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf<String?>(null) }

    val target = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(format?.let { if (it == "epub") "application/epub+zip" else "text/plain" } ?: "text/plain"),
    ) { uri: Uri? ->
        val chosen = format
        if (uri == null || chosen == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = store.readText(book)
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入所选位置。")
                    if (chosen == "epub") {
                        DocumentExporter.exportEpub(book.title, book.author, text, book.chapters, stream)
                    } else {
                        DocumentExporter.exportTxt(text, stream)
                    }
                }
                onResult("已导出为 ${chosen.uppercase()}：${book.title}", false)
            } catch (error: Throwable) {
                onResult("导出失败：${error.message ?: "未知错误"}", true)
            } finally {
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出《${book.title}》") },
        text = {
            Column {
                Text("导出后可以用其它阅读器打开。EPUB 会按识别出的章节分文件，并生成目录。")
                Spacer(Modifier.height(10.dp))
                Text("文本长度：${book.length} 字 · 章节：${book.chapters.size}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                format = "txt"
                target.launch(safeFileName(book.title) + ".txt")
            }) { Text("导出 TXT") }
        },
        dismissButton = {
            TextButton(onClick = {
                format = "epub"
                target.launch(safeFileName(book.title) + ".epub")
            }) { Text("导出 EPUB") }
        },
    )
}

/** 文件名过滤：去掉文件系统与 SAF 不接受的字符。 */
fun safeFileName(title: String): String {
    val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(60)
    return cleaned.ifEmpty { "book" }
}
