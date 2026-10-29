package com.shengzimao.reader.mobile.data

import java.io.File

/** Recognize the published Xinmanhua 1.0.0 envelope, never scan arbitrary responses for image bytes. */
internal object MangaImagePayload {
    private val signature = "http://www.xinmanhua.net".toByteArray(Charsets.US_ASCII)
    private val version = "1.0.0".toByteArray(Charsets.US_ASCII)

    fun normalize(file: File) {
        if (file.length() < 84) return
        val header = ByteArray(84)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val n = input.read(header, offset, header.size - offset)
                if (n < 0) return
                offset += n
            }
        }
        if (!signature.indices.all { header[it] == signature[it] }) return
        require((24..31).all { header[it] == 0xfe.toByte() } &&
            version.indices.all { header[32 + it] == version[it] } &&
            (37..39).all { header[it] == 0xfe.toByte() } &&
            (64..79).all { header[it] == 0xfe.toByte() } &&
            header[80] == 0xff.toByte() && header[81] == 0xd8.toByte() && header[82] == 0xff.toByte()) {
            "新漫画图片封装版本或格式已变化，请更新站点适配"
        }
        require(file.length() <= 24L * 1024 * 1024) { "漫画图片超过 24 MB" }
        file.writeBytes(file.readBytes().copyOfRange(80, file.length().toInt()))
    }
}
