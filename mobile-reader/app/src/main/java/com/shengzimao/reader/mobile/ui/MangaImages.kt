package com.shengzimao.reader.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.shengzimao.reader.mobile.data.MangaEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** 长图按 3072 源像素拆块的块高。 */
const val MANGA_TILE_HEIGHT = 3072
private val mangaDecodePermits = Semaphore(2)

/**
 * 漫画图片的整页缩放状态。
 *
 * 关键点：一张长图会被拆成多个 tile，缩放状态必须由**整页共享**，
 * 否则放大时只有手指下的那块放大，相邻块不跟随，图像会撕裂。
 * 同时平移必须约束在可视范围内，否则可以把图拖出屏幕且找不回来。
 */
class MangaZoomState(
    val maxScale: Float = 5f,
    val lockPan: Boolean = false,
    doubleTapScale: Float = 2.5f,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var doubleTapScale by mutableFloatStateOf(doubleTapScale)

    /** 视口尺寸，由外层 onSizeChanged 写入，用于约束平移边界。 */
    var viewport by mutableStateOf(Size.Zero)

    /** 当前页内容的显示高度（原图按视口宽度换算），用于纵向边界。 */
    var contentHeight by mutableFloatStateOf(0f)

    val isZoomed: Boolean get() = scale > 1.001f

    /** 把偏移量约束在可视范围内。 */
    fun clamp(candidate: Offset, scaleValue: Float = scale): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        val scaledWidth = viewport.width * scaleValue
        val visibleHeight = if (contentHeight > 0f) contentHeight else viewport.height
        val scaledHeight = visibleHeight * scaleValue
        val maxX = ((scaledWidth - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledHeight - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun applyZoom(zoom: Float, pan: Offset) {
        val next = (scale * zoom).coerceIn(1f, maxScale)
        if (next <= 1.001f) { reset(); return }
        scale = next
        offset = if (lockPan) clamp(offset, next) else clamp(offset + pan, next)
    }

    /** 双指缩放围绕手指落点，否则会以中心为原点"跑偏"。 */
    fun applyZoomAround(zoom: Float, centroid: Offset, pan: Offset) {
        val next = (scale * zoom).coerceIn(1f, maxScale)
        if (next <= 1.001f) { reset(); return }
        val factor = next / scale
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val focus = centroid - center
        offset = clamp(offset * factor + focus * (1f - factor) + pan, next)
        scale = next
    }

    fun toggleDoubleTap(): Boolean {
        if (isZoomed) { reset(); return false }
        scale = doubleTapScale.coerceIn(1.2f, maxScale)
        offset = clamp(offset, scale)
        return true
    }

    fun reset() { scale = 1f; offset = Offset.Zero }
}

/**
 * 图片解码内存缓存。
 *
 * 之前每个 tile 解出的 Bitmap 没有任何淘汰策略：条漫滑过几十张后，
 * 单块 3072×1600 的 ARGB 位图约 20MB，很容易把内存吃光。
 * 这里用按字节计量的 LRU 限制总占用。
 */
object MangaBitmapCache {
    private val maxBytes = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
    private val entries = LinkedHashMap<String, Bitmap>(32, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: String): Bitmap? {
        val hit = entries[key] ?: return null
        if (hit.isRecycled) { entries.remove(key); bytes -= sizeOf(hit); return null }
        return hit
    }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val previous = entries.put(key, bitmap)
        if (previous != null) bytes -= sizeOf(previous)
        bytes += sizeOf(bitmap)
        trim()
    }

    @Synchronized
    private fun trim() {
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= sizeOf(entry.value)
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() { entries.clear(); bytes = 0 }

    private fun sizeOf(bitmap: Bitmap): Long = bitmap.allocationByteCount.toLong()
}

@Composable
fun MangaCover(engine: MangaEngine, reference: String, sourceId: String, title: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, reference, sourceId) {
        value = null
        if (reference.isNotBlank()) {
            try {
                value = withContext(Dispatchers.IO) {
                    val key = "cover:${sourceId}:$reference"
                    MangaBitmapCache.get(key)?.let { return@withContext it }
                    val file = engine.imageFile(reference, sourceId, cover = true)
                    mangaDecodePermits.withPermit {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 768) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.also { MangaBitmapCache.put(key, it) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                MangaBitmapCache.clear()
            } catch (_: Exception) {
                // 封面失败仍可用标题打开作品。
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(title.take(12), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class MangaImageInfo(val file: File, val width: Int, val height: Int, val detectedCropPercent: Int = 0,
    val modifiedAt: Long = file.lastModified(), val byteCount: Long = file.length()) {
    val wideSpread: Boolean get() = width.toLong() * 100 >= height.toLong() * 125
    fun crop(settings: com.shengzimao.reader.mobile.data.MangaSettings): Int =
        if (settings.autoCrop && !wideSpread) detectedCropPercent else settings.cropPercent
    val tileCount get() = (height + MANGA_TILE_HEIGHT - 1) / MANGA_TILE_HEIGHT
    fun tileTop(tile: Int) = tile * MANGA_TILE_HEIGHT
    fun tileBottom(tile: Int) = minOf(tileTop(tile) + MANGA_TILE_HEIGHT, height)

    /** 整页（按给定宽度等比缩放后）的显示高度，用于条漫整页缩放边界。 */
    fun displayHeight(width: Float) = if (width <= 0f) 0f else width * height / this.width.coerceAtLeast(1)
}

private val imageInfoCache = object : LinkedHashMap<String, MangaImageInfo>(128, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MangaImageInfo>?) = size > 128
}

internal suspend fun loadMangaImageInfo(engine: MangaEngine, reference: String, sourceId: String,
    force: Boolean = false, fileLoader: (suspend ((String) -> Unit) -> File)? = null,
    onStage: (String) -> Unit = {}): MangaImageInfo = withContext(Dispatchers.IO) {
    val file = fileLoader?.invoke(onStage) ?: engine.imageFile(reference, sourceId, force = force, onStage = onStage)
    val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
    if (!force) synchronized(imageInfoCache) { imageInfoCache[key] }?.let { return@withContext it }
    onStage("图片已缓存，正在读取尺寸与识别白边…")
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    check(options.outWidth > 0 && options.outHeight > 0) { "无法解码漫画图片" }
    val crop = mangaDecodePermits.withPermit {
        var sample = 1
        while (options.outWidth / sample > 256 || options.outHeight / sample > 1024) sample *= 2
        val small = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (small == null) 0 else try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            com.shengzimao.reader.mobile.data.ComicPageAnalysis.cropPercent(small.width, small.height, pixels)
        } finally { small.recycle() }
    }
    MangaImageInfo(file, options.outWidth, options.outHeight, crop).also { synchronized(imageInfoCache) { imageInfoCache[key] = it } }
}

@Composable
fun MangaImageLoader(engine: MangaEngine, reference: String, sourceId: String,
    requestKey: String = reference,
    fileLoader: (suspend (Boolean, (String) -> Unit) -> File)? = null,
    onRefreshChapter: (() -> Unit)? = null, onSwitchSource: (() -> Unit)? = null,
    onFailure: (String) -> Unit = {}, onLoaded: (MangaImageInfo) -> Unit) {
    var error by remember(requestKey, reference) { mutableStateOf("") }
    var stage by remember(requestKey, reference) { mutableStateOf("正在读取图片缓存或下载图片…") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(requestKey, reference, sourceId, retry) {
        error = ""
        try {
            val info = loadMangaImageInfo(engine, reference, sourceId, retry > 0,
                fileLoader = fileLoader?.let { loader -> { stageCallback -> loader(retry > 0, stageCallback) } }) { stage = it }
            onLoaded(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            MangaBitmapCache.clear()
            error = "图片过大，内存不足；可重试图片或更换漫画源"
            onFailure(error)
        } catch (failure: Exception) {
            error = failure.message ?: "图片读取失败"
            onFailure(error)
        }
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (error.isBlank()) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stage, color = LocalContentColor.current, modifier = Modifier.padding(top = 8.dp))
        } else {
            Text(error, color = Color(0xFFFFB4AB))
            TextButton(onClick = { error = ""; stage = "正在重试当前图片…"; retry++ }) { Text("重试图片") }
            onRefreshChapter?.let { TextButton(onClick = it) { Text("刷新本章图片链接") } }
            onSwitchSource?.let { TextButton(onClick = it) { Text("更换漫画源") } }
        }
    }
}

/**
 * 单块图片。缩放由整页共享的 [zoom] 驱动，同一张长图的所有块同步变换。
 */
@Composable
fun MangaImageTile(
    info: MangaImageInfo,
    tile: Int,
    page: Int,
    zoom: MangaZoomState? = null,
    contentScale: ContentScale = ContentScale.FillWidth,
    doubleTapEnabled: Boolean = true,
    onTap: () -> Unit = {},
    cropPercent: Int = 0,
    onRetryImage: () -> Unit = {},
    onPageTap: ((Float) -> Unit)? = null,
    onReady: () -> Unit = {},
    onFailure: (String) -> Unit = {},
) {
    val top = info.tileTop(tile)
    val bottom = info.tileBottom(tile)
    var retry by remember(info.file, tile) { mutableIntStateOf(0) }
    var error by remember(info.file, tile) { mutableStateOf("") }
    val latestTap by rememberUpdatedState(onTap)
    val latestPageTap by rememberUpdatedState(onPageTap)
    val latestReady by rememberUpdatedState(onReady)
    val latestFailure by rememberUpdatedState(onFailure)
    val bitmap by produceState<Bitmap?>(null, info, tile, retry, cropPercent) {
        value = null
        error = ""
        try {
            value = withContext(Dispatchers.IO) {
                val cacheKey = "${info.file.absolutePath}:${info.modifiedAt}:${info.byteCount}#$tile:$cropPercent"
                MangaBitmapCache.get(cacheKey)?.let { return@withContext it }
                var sample = 1
                while (info.width / sample > 1600) sample *= 2
                mangaDecodePermits.withPermit {
                    val decoded = decodeMangaTile(info, top, bottom, sample, cropPercent)
                    MangaBitmapCache.put(cacheKey, decoded)
                    decoded
                }
            }
            latestReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            MangaBitmapCache.clear()
            error = "图片解码内存不足，可重试图片或使用较小的漫画源"
            latestFailure(error)
        } catch (failure: Exception) {
            error = failure.message ?: "图片解码失败"
            latestFailure(error)
        }
    }

    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(info.width.toFloat() * (1f - cropPercent.coerceIn(0, 15) / 50f) / (bottom - top).coerceAtLeast(1))
            .clipToBounds()
            .pointerInput(info.file, tile, doubleTapEnabled) {
                detectTapGestures(
                    onTap = { position ->
                        val pageTap = latestPageTap
                        if (pageTap != null && zoom?.isZoomed != true) pageTap(position.x / size.width.coerceAtLeast(1)) else latestTap()
                    },
                    onDoubleTap = { if (doubleTapEnabled && zoom != null) zoom.toggleDoubleTap() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "第 ${page + 1} 页",
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
            error.isNotBlank() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${page + 1} 页图片解码失败：$error", color = LocalContentColor.current, modifier = Modifier.padding(12.dp))
                TextButton(onClick = { retry++; onRetryImage() }) { Text("重试失败图片") }
            }
            else -> CircularProgressIndicator(Modifier.size(22.dp))
        }
    }
}

/** GIF / AVIF 等可由系统显示的图片不一定支持区域解码，回退到有内存上限的整图解码。 */
internal fun decodeMangaTile(info: MangaImageInfo, top: Int, bottom: Int, sample: Int, cropPercent: Int = 0): Bitmap {
    val cropX = info.width * cropPercent.coerceIn(0, 15) / 100
    try {
        val decoder = BitmapRegionDecoder.newInstance(info.file.absolutePath, false)
        try {
            decoder.decodeRegion(Rect(cropX, top, info.width - cropX, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { return it }
        } finally { decoder.recycle() }
    } catch (_: java.io.IOException) {
        // RegionDecoder 的格式支持小于 BitmapFactory / ImageDecoder。
    } catch (_: IllegalArgumentException) {
        // 某些系统解码器用 IllegalArgumentException 报告不支持的格式。
    }
    var safeSample = sample.coerceAtLeast(1)
    while (info.width.toLong() / safeSample * (info.height / safeSample) > 8_000_000L) safeSample *= 2
    val key = "full:${info.file.absolutePath}:${info.file.lastModified()}:$safeSample"
    val full = MangaBitmapCache.get(key) ?: (
        BitmapFactory.decodeFile(info.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = safeSample })
            ?: if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(info.file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize((info.width / safeSample).coerceAtLeast(1), (info.height / safeSample).coerceAtLeast(1))
            } else null
        )?.also { MangaBitmapCache.put(key, it) }
        ?: error("系统无法识别此图片格式，请重试图片或检查漫画源")
    val y = (top.toLong() * full.height / info.height).toInt().coerceIn(0, full.height - 1)
    val end = (bottom.toLong() * full.height / info.height).toInt().coerceIn(y + 1, full.height)
    val x = (full.width * cropPercent.coerceIn(0, 15) / 100).coerceAtMost((full.width - 1) / 2)
    return Bitmap.createBitmap(full, x, y, (full.width - x * 2).coerceAtLeast(1), end - y)
}

/**
 * 单页缩放的容器：负责识别双指缩放，并把同一个 [MangaZoomState] 交给内部的各块。
 * 内容整体通过 graphicsLayer 变换，块之间因此保持连续。
 */
@Composable
fun MangaZoomBox(
    lockPan: Boolean,
    doubleTapScale: Float,
    onZoomChanged: (Boolean) -> Unit,
    /** 外部复位信号：数值变化即把缩放平移回原位，用于「放大状态下翻页」前先解锁。 */
    resetSignal: Int = 0,
    content: @Composable (MangaZoomState) -> Unit,
) {
    val zoom = remember(lockPan) { MangaZoomState(lockPan = lockPan) }
    LaunchedEffect(zoom.isZoomed) { onZoomChanged(zoom.isZoomed) }
    LaunchedEffect(doubleTapScale) { zoom.doubleTapScale = doubleTapScale }
    LaunchedEffect(resetSignal) { if (resetSignal > 0) { zoom.reset(); onZoomChanged(false) } }
    val transform = rememberTransformableState { centroid, zoomChange, pan, _ ->
        // 围绕手指落点缩放，而不是以中心为原点，否则放大时视觉上会“跑偏”。
        zoom.applyZoomAround(zoomChange, centroid, pan)
        onZoomChanged(zoom.isZoomed)
    }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .onSizeChanged { zoom.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .transformable(transform, canPan = { zoom.isZoomed && !lockPan }),
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        ) { content(zoom) }
    }
}
