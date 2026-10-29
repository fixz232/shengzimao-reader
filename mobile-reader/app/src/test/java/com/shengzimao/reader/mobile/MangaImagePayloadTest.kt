package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaImagePayload
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MangaImagePayloadTest {
    private fun envelope() = ByteArray(80) { 0xfe.toByte() }.apply {
        "http://www.xinmanhua.net".toByteArray().copyInto(this)
        "1.0.0".toByteArray().copyInto(this, 32)
    }
    private fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = Files.createTempFile("image-envelope-", ".jpg").toFile()
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }

    @Test fun knownEnvelopeRestoresARealJpegWithoutReencoding() {
        val bitmap = java.awt.image.BufferedImage(120, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val jpeg = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bitmap, "jpg", it) }.toByteArray()
        withFile(envelope() + jpeg) { file ->
            assertNull(javax.imageio.ImageIO.read(file))
            MangaImagePayload.normalize(file)
            assertArrayEquals(jpeg, file.readBytes())
            assertEquals(160, javax.imageio.ImageIO.read(file).height)
            MangaImagePayload.normalize(file)
            assertArrayEquals(jpeg, file.readBytes())
        }
    }

    @Test fun normalImagesAndArbitraryHtmlAreNeverScannedForJpegMarkers() {
        val ordinary = ByteArray(200) { it.toByte() }
        val html = "<html>arbitrary-response</html>".toByteArray() + envelope() + byteArrayOf(-1, -40, -1, 0)
        for (bytes in listOf(ordinary, html)) withFile(bytes) { file ->
            MangaImagePayload.normalize(file); assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun unsupportedEnvelopeFailsAndLeavesItsInputIntact() {
        val bytes = envelope().apply { this[32] = '2'.code.toByte() } + byteArrayOf(-1, -40, -1, 0)
        withFile(bytes) { file ->
            assertThrows(IllegalArgumentException::class.java) { MangaImagePayload.normalize(file) }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
}
