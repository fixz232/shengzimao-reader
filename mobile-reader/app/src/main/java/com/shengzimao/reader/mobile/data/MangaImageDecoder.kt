package com.shengzimao.reader.mobile.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import java.io.File
import java.io.FilterOutputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock

/** 原生图片变换；不执行漫画源中的 Java/Android 脚本。只在临时文件中完成并校验。 */
internal object MangaImageDecoder {
    private const val MAX_PIXELS = 12_000_000L
    private const val MAX_BYTES = 24L * 1024 * 1024
    private val bitmapLock = ReentrantLock()

    fun decode(file: File, url: String, spec: MangaDecoder.Spec, cover: Boolean = false) {
        if (spec.kind == MangaDecoder.Kind.MANWA_AES || spec.kind == MangaDecoder.Kind.BIKA_AES) {
            // 同一个源可以混用普通图片和加密图片；已是有效图片时不要重复 AES 解密。
            val bounds = bounds(file)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) return
            require(file.length() in 1..MAX_BYTES) { "加密图片超过大小上限" }
            val result = MangaDecoder.decrypt(spec, file.readBytes())
            file.writeBytes(result)
            MangaStore.checkImage(file)
            return
        }
        // 封面常常与正文同域同为数字文件名，不能把封面当作果果/JM 拼图。
        if (cover) return
        val dimensions = bounds(file)
        val width = dimensions.outWidth
        val height = dimensions.outHeight
        require(width > 0 && height > 0) { "待还原的图片格式无效" }
        val target = if (spec.kind == MangaDecoder.Kind.BAOZI_SIZE) MangaDecoder.targetSize(url, width, height) else null
        val count = MangaDecoder.stripCount(spec, url)
        if (target == null && (count == null || count == 1)) return
        val outWidth = target?.width ?: width
        val outHeight = target?.height ?: height
        require(width.toLong() * height <= MAX_PIXELS && outWidth.toLong() * outHeight <= MAX_PIXELS) {
            "图片还原超过 1200 万像素内存上限，请使用较小图片源"
        }
        bitmapLock.lockInterruptibly()
        try {
            transform(file, width, height, outWidth, outHeight, count, spec.kind)
        } finally { bitmapLock.unlock() }
    }

    private fun bounds(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
    }

    @Suppress("DEPRECATION")
    private fun transform(file: File, width: Int, height: Int, outWidth: Int, outHeight: Int, count: Int?, kind: MangaDecoder.Kind) {
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val temp = File(file.path + ".decoded")
        try {
            val canvas = Canvas(output)
            if (kind == MangaDecoder.Kind.BAOZI_SIZE) {
                val input = BitmapFactory.decodeFile(file.absolutePath) ?: error("图片解码失败")
                try { canvas.drawBitmap(input, null, Rect(0, 0, outWidth, outHeight), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
                finally { input.recycle() }
            } else {
                val columns = kind == MangaDecoder.Kind.XIANG_COLUMNS
                val slices = if (columns) MangaDecoder.columnSlices(width, requireNotNull(count)) else MangaDecoder.rowSlices(height, requireNotNull(count))
                val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
                try {
                    for (slice in slices) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("图片还原已取消")
                        val region = if (columns) Rect(slice.source, 0, slice.source + slice.length, height)
                            else Rect(0, slice.source, width, slice.source + slice.length)
                        val bitmap = decoder.decodeRegion(region, BitmapFactory.Options()) ?: error("图片条带解码失败")
                        try { canvas.drawBitmap(bitmap, if (columns) slice.target.toFloat() else 0f, if (columns) 0f else slice.target.toFloat(), null) }
                        finally { bitmap.recycle() }
                    }
                } finally { decoder.recycle() }
            }
            temp.outputStream().use { raw ->
                val limited = object : FilterOutputStream(raw) {
                    var size = 0L
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        checkLimit(length); out.write(bytes, offset, length)
                    }
                    override fun write(value: Int) { checkLimit(1); out.write(value) }
                    fun checkLimit(length: Int) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("图片还原已取消")
                        size += length
                        require(size <= MAX_BYTES) { "还原后的图片超过 24 MB" }
                    }
                }
                check(output.compress(Bitmap.CompressFormat.PNG, 100, limited)) { "还原后的图片保存失败" }
            }
            MangaStore.checkImage(temp)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { output.recycle(); temp.delete() }
    }
}
