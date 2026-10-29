package com.shengzimao.reader.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 在独立 /data/local/tmp 目录验证原生 Bitmap 与真实下载代码，不启动应用或读取正式书库。 */
object MangaDecoderNativeProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = File(args.single()).canonicalFile
        require(root.path.startsWith("/data/local/tmp/") && root.isDirectory)
        val originals = JSONArray(File(root, "sources.json").readText())
        fun raw(kind: MangaDecoder.Kind) = (0 until originals.length()).map { originals.getJSONObject(it) }
            .first { MangaDecoder.identify(MangaDecoder.rule(it))?.kind == kind }
        fun spec(kind: MangaDecoder.Kind) = MangaDecoder.identify(MangaDecoder.rule(raw(kind)))!!
        fun bitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { i -> 0xff000000.toInt() or ((i * 619) and 0xffffff) }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        fun png(bitmap: Bitmap) = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        fun encrypt(kind: MangaDecoder.Kind, bytes: ByteArray): ByteArray {
            val (key, iv) = if (kind == MangaDecoder.Kind.BIKA_AES) "f5d965df75336270" to "97b60394abc2fbe1" else "my2ecret782ecret" to "my2ecret782ecret"
            return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            }.doFinal(bytes)
        }
        fun verify(file: File, expected: Bitmap) {
            val actual = BitmapFactory.decodeFile(file.path) ?: error("native decode failed")
            try {
                check(actual.width == expected.width && actual.height == expected.height)
                for (y in 0 until actual.height) for (x in 0 until actual.width) check(actual.getPixel(x, y) == expected.getPixel(x, y)) { "pixel mismatch at $x,$y" }
            } finally { actual.recycle() }
        }
        val original = bitmap(13, 113)
        val originalBytes = png(original)
        try {
            for (kind in listOf(MangaDecoder.Kind.MANWA_AES, MangaDecoder.Kind.BIKA_AES)) {
                val file = File(root, "$kind.bin").apply { writeBytes(encrypt(kind, originalBytes)) }
                MangaImageDecoder.decode(file, "https://images.example/synthetic.bin", spec(kind))
                verify(file, original)
                val bytes = file.readBytes()
                MangaImageDecoder.decode(file, "https://images.example/synthetic.bin", spec(kind))
                check(bytes.contentEquals(file.readBytes()))
                println("DECODER_NATIVE_AES_OK $kind")
            }
            for ((kind, url) in listOf(
                MangaDecoder.Kind.JM_ROWS to "https://images.example/photos/800001/003.jpg",
                MangaDecoder.Kind.GUOGUO_ROWS to "https://images.example/003.png",
                MangaDecoder.Kind.XIANG_COLUMNS to "https://images.example/synthetic.png?w=3",
            )) {
                val decoder = spec(kind)
                val count = MangaDecoder.stripCount(decoder, url)!!
                val columns = kind == MangaDecoder.Kind.XIANG_COLUMNS
                val slices = if (columns) MangaDecoder.columnSlices(original.width, count) else MangaDecoder.rowSlices(original.height, count)
                val scrambled = bitmap(original.width, original.height)
                for (slice in slices) for (n in 0 until slice.length) {
                    if (columns) for (y in 0 until original.height) scrambled.setPixel(slice.source + n, y, original.getPixel(slice.target + n, y))
                    else for (x in 0 until original.width) scrambled.setPixel(x, slice.source + n, original.getPixel(x, slice.target + n))
                }
                val scrambledBytes = png(scrambled); scrambled.recycle()
                val file = File(root, "$kind.png").apply { writeBytes(scrambledBytes) }
                MangaImageDecoder.decode(file, url, decoder, cover = true)
                check(file.readBytes().contentEquals(scrambledBytes))
                MangaImageDecoder.decode(file, url, decoder)
                verify(file, original)
                println("DECODER_NATIVE_STRIPS_OK $kind count=$count")
            }
            val tall = bitmap(100, 800)
            try {
                val file = File(root, "size.png").apply { writeBytes(png(tall)) }
                MangaImageDecoder.decode(file, "https://images.example/synthetic.png#100x400", spec(MangaDecoder.Kind.BAOZI_SIZE))
                val restored = BitmapFactory.decodeFile(file.path)
                check(restored.width == 100 && restored.height == 400); restored.recycle()
                println("DECODER_NATIVE_BAOZI_OK 100x400")
            } finally { tall.recycle() }

            val count = AtomicInteger()
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val worker = Executors.newSingleThreadExecutor()
            worker.submit {
                while (!server.isClosed) runCatching { server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty()
                    while (!input.readLine().isNullOrEmpty()) { }
                    count.incrementAndGet()
                    val body = if (request.contains("/broken")) ByteArray(17) else encrypt(MangaDecoder.Kind.BIKA_AES, originalBytes)
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                    }
                } }
            }
            try {
                val base = "http://127.0.0.1:${server.localPort}"
                val source = LegadoConverter.convert(raw(MangaDecoder.Kind.BIKA_AES).put("bookSourceUrl", base)).source!!
                val store = MangaStore(File(root, "engine")); store.saveSources(listOf(source))
                val engine = MangaEngine(store)
                val url = "$base/encrypted.bin"
                coroutineScope { (0 until 8).map { async(Dispatchers.IO) { verify(engine.imageFile(url, source.id), original) } }.awaitAll() }
                check(count.get() == 1) { "duplicate requests: ${count.get()}" }
                check(runCatching { engine.imageFile("$base/broken.bin", source.id) }.isFailure)
                check(!store.cachedImage(source.id, "$base/broken.bin").exists())
                check(store.imageDir.listFiles()!!.none { it.name.endsWith(".part") || it.name.endsWith(".decoded") })
                store.protectImage(source.id, url)
                val chapter = MangaChapter("原创合成测试", "$base/chapter", listOf(url))
                store.upsert(MangaBook("decoder-probe-book", "原创像素", source.id, source.name, "$base/book", chapters = listOf(chapter), cachedChapters = setOf(chapter.url)))
                val archive = ByteArrayOutputStream()
                ZipOutputStream(archive).use { MangaBackup.write(it, store, MangaSettings(), true) }
                val restored = MangaStore(File(root, "restored"))
                MangaBackup.Restore(restored, root).use { restore ->
                    ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
                        while (true) { val entry = zip.nextEntry ?: break; restore.accept(entry.name, zip.readBytes()); zip.closeEntry() }
                    }
                    check(restore.finish().first == 1)
                }
                check(restored.books().single().cachedChapters == setOf(chapter.url))
                verify(restored.cachedImage(source.id, url), original)
                restored.saveSources(listOf(source.copy(enabled = false)))
                verify(MangaEngine(restored).imageFile(url, source.id), original)
                restored.saveSources(emptyList())
                verify(MangaEngine(restored).imageFile(url, source.id), original)
                println("DECODER_NATIVE_ENGINE_OK concurrent=8 requests=${count.get()} corrupt-rejected=true offline-restore=true")
            } finally { server.close(); worker.shutdownNow() }
        } finally { original.recycle() }
        println("DECODER_NATIVE_ALL_OK")
    }
}
