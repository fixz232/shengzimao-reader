package com.shengzimao.reader.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MangaPageAdjustSheet(controller: MangaController, book: MangaBook, chapterIndex: Int, page: Int,
    settings: MangaSettings, onDismiss: () -> Unit) {
    val chapter = book.chapters.getOrNull(chapterIndex) ?: return
    val initial = remember(book.id, chapter.url, page) { MangaPageOverrides.get(book.pageOverrides, chapter, page) }
    var original by remember { mutableStateOf(initial.original) }
    var layout by remember { mutableStateOf(initial.layout) }
    var preview by remember { mutableStateOf("原图对照") }
    var loading by remember { mutableStateOf(true) }
    var stage by remember { mutableStateOf("正在读取当前页…") }
    var failure by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var next by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableIntStateOf(0) }
    LaunchedEffect(book.id, chapter.url, page, retry) {
        loading = true; failure = ""
        try {
            val info = loadMangaImageInfo(controller.engine, chapter.pages.getOrNull(page).orEmpty(), book.sourceId,
                fileLoader = { callback -> controller.readerImage(book.id, chapterIndex, page, onStage = callback) }) { stage = it }
            crop = info.crop(settings)
            bitmap = withContext(Dispatchers.IO) { mangaPreview(info.file.absolutePath) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { failure = "预览内存不足，可关闭后重试" }
        catch (error: Exception) { failure = error.message ?: "预览读取失败" }
        finally { loading = false }
    }
    LaunchedEffect(preview, page, retry) {
        if (preview != "合并预览" || page + 1 >= chapter.pages.size || next != null) return@LaunchedEffect
        try { next = withContext(Dispatchers.IO) { mangaPreview(controller.readerImage(book.id, chapterIndex, page + 1).absolutePath) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { failure = "下一页预览内存不足" }
        catch (error: Exception) { failure = "下一页：${error.message}" }
    }
    val cropped = remember(bitmap, crop) { bitmap?.let { image ->
        val x = (image.width * crop / 100).coerceAtMost((image.width - 1) / 2)
        if (x == 0) image else Bitmap.createBitmap(image, x, 0, image.width - 2 * x, image.height)
    } }
    val halves = remember(bitmap) { bitmap?.takeIf { it.width >= 2 }?.let { image ->
        val half = image.width / 2
        listOf(Bitmap.createBitmap(image, 0, 0, half, image.height), Bitmap.createBitmap(image, half, 0, image.width - half, image.height))
    }.orEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("第 ${page + 1} 页 · 页面调整", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("原图对照", "合并预览", "拆分预览").forEach { label -> FilterChip(preview == label, { preview = label; failure = "" },
                    enabled = label != "合并预览" || page + 1 < chapter.pages.size, label = { Text(label) }) }
            }
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stage, style = MaterialTheme.typography.bodySmall) }
            if (failure.isNotBlank()) { Text(failure, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重试预览") } }
            Row(Modifier.fillMaxWidth().height(280.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val images = when (preview) { "合并预览" -> listOfNotNull(bitmap, next); "拆分预览" -> halves; else -> listOfNotNull(bitmap, cropped) }
                images.forEachIndexed { index, image ->
                    Column(Modifier.weight(1f)) {
                        Text(when(preview) { "原图对照" -> if(index == 0) "原图" else "当前裁边 ${crop}%"; "拆分预览" -> if(index == 0) "左半页" else "右半页"; else -> "第 ${page + index + 1} 页" }, style = MaterialTheme.typography.labelSmall)
                        Image(image.asImageBitmap(), "页面预览", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("本页显示原图，关闭裁边", Modifier.weight(1f))
                Switch(original, { original = it })
            }
            Text("横向翻页的跨页组合", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "跟随设置", "single" to "本页单独", "join" to "合并下一页").forEach { (value, label) ->
                    FilterChip(layout == value, { layout = value }, enabled = value != "join" || page + 1 < chapter.pages.size, label = { Text(label) })
                }
            }
            Text("拆分预览用于核对跨页左右内容；原图文件保持完整。页面调整仅作用于本作品的这一页。", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { controller.savePageOverride(book.id, chapterIndex, page, MangaPageOverride()); onDismiss() }) { Text("恢复本页默认") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { controller.savePageOverride(book.id, chapterIndex, page, MangaPageOverride(original, layout)); onDismiss() }) { Text("保存本页设置") }
            }
        }
    }
}

private fun mangaPreview(path: String): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 960) sample *= 2
    return checkNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })) { "无法解码预览" }
}
